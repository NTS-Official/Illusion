# MCDR 的 FFM ABI 层

本文只讲一件事：**JVM 侧怎么安全地调用 MCDR 这个 C++ DLL**，以及这套约定为什么长成这样。

三个参与者：

| 层 | 文件 | 角色 |
| --- | --- | --- |
| 契约 | `MCDR\include\mcdr_abi.h` | 唯一真相：`extern "C"` 导出、结构体定义、错误码约定 |
| 布局 | `Illusion\src\main\kotlin\dev\nts\expdxr\d3d12\extern\MCDR_Layout.kt` | 结构体的 `MemoryLayout` 镜像 + 自检 |
| 句柄 | `Illusion\src\main\kotlin\dev\nts\expdxr\d3d12\extern\MCDR_ABI.kt` | 195 个 `downcallHandle` + 每个导出的调用封装 |

适用范围：Java 25 / Minecraft release-26.1 起 FFM 已退出预览。**本项目禁止 JNI**（`System.loadLibrary`、`external fun`、`RegisterNatives` 一律不许出现），native 侧也不许再提供 `JNIEXPORT` 入口。

先把当前的体量摆出来（`_port\abi_check.py` 的实际输出）：

| 项 | 数值 |
| --- | --- |
| native 导出 | 200 |
| Kotlin 绑定 | 195 |
| 其中"允许缺失"的可选导出 | 61 |
| 头文件有、Kotlin 未绑定 | 5（回读 / 落盘 / 诊断辅助） |
| 签名不匹配 | 0 |
| 双方镜像的结构体 | 16，尺寸与字段偏移全部一致 |

## 1 为什么是 FFM 而不是 JNI

### 1.1 先看被替掉的东西有多大

JNI 桥是 **6 个文件、2158 行、115 个 `Java_com_..._Dx12Native_*` 导出**（数字来自头文件开头的历史记录）。现在是 200 个平铺的 `extern "C"` 导出，JVM 侧的调用方只认 `MCDR_ABI` 一个类。

替掉的不只是行数，而是四条会静默失败的链路。

### 1.2 头文件生成这条链被整个删掉

JNI 的 native 符号名由 `javac -h` 从**包名 + 类名 + 方法名**合成。这意味着：任何一次重命名（包、类、方法）都会让 native 侧的符号名漂移；而失败形式极差 —— `UnsatisfiedLinkError`，或者更糟，`RegisterNatives` 表里少一项、运行到那条路径才炸。

FFM 下 native 侧的符号就是一个纯 C 名字（`mcdr_dx12CreateSurface`），Kotlin 侧绑定时写的也是同一个字符串字面量：

```kotlin
MH_asyncexecutorcreate = skip("mcdr_dx12AsyncExecutorCreate", fd(MCDRLayout.PTR))
```

名字写错立刻在**绑定期**抛 `IllegalStateException("MCDR export not found: ...")`，而不是等玩家走某条分支时才发现。

### 1.3 Arena 取代了 JNI 的手工生命周期

JNI 里每传一次数组/字符串都要配一对：`Get*ArrayElements` ↔ `Release*ArrayElements`、`NewDirectByteBuffer` ↔ 自己管释放、`GetStringUTFChars` ↔ `ReleaseStringUTFChars`，跨线程还要 `NewGlobalRef` / `DeleteGlobalRef`。漏一处就是泄漏，写早一处就是 use-after-free。

FFM 把生命周期变成**词法作用域**：谁开 `Arena` 谁负责关，关掉之后所有从它分配出去的 `MemorySegment` 一起失效。逐帧路径上则用线程局部的复用槽（`SCRATCH`，4 个 slot，内部 `Arena.ofConfined()`），首次几帧分配够之后就完全不分配 —— 这条在 JNI 里要自己写一个 per-thread 缓存池才能做到。

### 1.4 不再需要 `JavaVM*` 和线程附着

JNI 里 native 想回调 JVM，必须先缓存 `JavaVM*`，在工作线程上 `GetEnv` / `AttachCurrentThread`，再 `GetMethodID(cls, "onFenceComplete", "(JZ)V")` 做一次字符串查找。

现在整个库里**只有一个 upcall**（围栏回调），签名固定为三个整数：

```c
typedef void(MCDR_CALL* mcdr_fence_callback_fn)(int64_t userHandle, uint64_t value, int32_t completed);
```

`userHandle` 是 JVM 侧注册表的一个整数句柄，native 侧**从不持有任何 JVM 对象引用**。这个 upcall stub 建一次、存在共享 Arena 里、活到进程结束。

### 1.5 结构体从"手写字节"变成"声明的布局 + 运行时自检"

旧做法是把管线描述符塞进一个 `LITTLE_ENDIAN` 的 `ByteBuffer`，Java 侧 `putInt`/`putU8` 一个个写，native 侧按相同顺序读回来。这条路上任何一次字节序滑落、任何一次少写一个 pad 字节，结果都是**整屏全黑**而不是异常。

现在是「一份 `structLayout` + 一份 `EXPECTED_C_SIZES` + 一次和运行中 DLL 的逐字段比对」，三层都失败得很快（详见 §2.2）。

### 1.6 类型错误在句柄层就失败

`FunctionDescriptor` 把返回值与每个参数的类型钉死；`invokeExact` 要求调用点的静态类型**逐字匹配**描述符。把 `int32_t` 声明成 `I64`、或者用 `as Long` 去接 `I32` 的返回值，抛的是 `WrongMethodTypeException`，而不是把一个 64 位值当 32 位用、写坏栈。

### 1.7 错误不跨边界抛异常

native 从不抛 Java 异常（C++ 异常也不可能安全穿过 FFM 边界）。布尔入口返回 `int32_t` 1/0，失败详情挂在 `mcdrLastError()` 上；需要硬失败的封装再把它包成 `IllegalStateException`。这条规则让 native 侧完全不必知道 JVM 的存在。

## 2 三层结构

```
MCDR\include\mcdr_abi.h            契约：extern "C" 平铺导出 / 结构体 / 错误码
        │
        │  SymbolLookup.libraryLookup(dllPath, ARENA)
        │  mcdrABIVersion()  ── 版本握手
        │  mcdrABILayout()   ── 运行中 DLL 的字段偏移表
        ▼
MCDR_Layout.kt                     布局：MemoryLayout 镜像 + byteSize 断言 + 偏移比对
        │
        ▼
MCDR_ABI.kt                        句柄：195 个 downcallHandle + 每导出的封装
        │
        ▼
MCDR_PipelineDesc.kt               打包器：把 Kotlin 对象铺进 arena
MCDR_MeshPipelineDesc.kt
```

### 2.1 契约层：`mcdr_abi.h`

头文件开头把设计规则写成了七条，逐条对应到实现：

| 规则 | 含义 | 落地位置 |
| --- | --- | --- |
| 只有 C 链接 | `extern "C"`，无名字修饰，无生成的头文件 | 全部 200 个导出 |
| 每个聚合体只有一份定义 | Java 侧 1:1 `MemoryLayout`，不手写偏移、不 packed blob | `MCDR_Layout.kt` |
| 句柄是不透明指针 | `mcdr_object*` 等，不是"让 Java 猜含义的整数" | §3.1 |
| 字符串由调用方分配 | UTF-8 缓冲，被调方返回所需字节数（含 NUL） | §3.3 |
| 异常不跨界 | 布尔入口返回 `int32_t` 1/0；错误在 `mcdrLastError()` | §3.5 |
| 数组是 (pointer, count) | 从不用 JNI 数组 | §3.4 |
| 单一 upcall 类型 | 只带整数句柄，native 不持 JVM 对象 | §1.4 |

两个容易忽略的细节：

- **调用约定是 `__cdecl`**。`MCDR_CALL` 在 Windows 上展开成 `__cdecl`；`MCDR_API` 在 native 编译时是 `__declspec(dllexport)`，在消费方是 `dllimport`。x64 上只有一种调用约定，但这个宏让头文件在 32 位下也不会错。
- **MSVC 的 `long` 是 32 位**。头文件为此专门写了一条注释：凡是写成 `int32_t` 的参数，Java 侧必须是 `JAVA_INT`；只有 `int64_t` / `size_t` / 指针才是 `JAVA_LONG` / `JAVA_ADDRESS`。这一条是 §6 里"类型不逐字匹配"坑的根源。

版本号是双份的：宏 `MCDR_ABI_VERSION 4u`，以及运行时函数 `mcdrABIVersion()`。两者相等才算握手成功，防止"Java 常量被编译器内联、DLL 却是旧的"。

### 2.2 布局层：`MCDR_Layout.kt`

这一层的三条硬规则：

1. **`MemoryLayout.structLayout` 完全不插入 padding**。它只是按顺序拼接成员，C 编译器会插的每一个 pad 字节都必须显式写出来 —— 要么命名成 `_padN`，要么用 `MemoryLayout.paddingLayout(n)`。
2. **尾部 padding 决定 `byteSize()` 能不能当数组 stride**。C 的 `sizeof` 会把结构体补到对齐的整数倍；漏掉尾部 pad，`byteSize()` 就比 `sizeof` 小，按它算 stride 写数组时每一项都会错位。
3. **不写字面量偏移**。所有偏移都经 `offset(layout, field)`（`layout.byteOffset(PathElement.groupElement(field))`）解析一次，存成 `@JvmField val XXX_YYY: Long`。

几个具体的"坑位"已经写在代码注释里，值得记住它们的形状：

| 结构体 | 现象 | 原因 |
| --- | --- | --- |
| `mcdr_input_element_desc` | 指针落在 32 而不是 28 | C 侧 24 处一个 `_pad0`，编译器再补 4；Kotlin 必须**多写一个** `paddingLayout(4)` |
| `mcdr_depth_state_desc` | 尾部 2 字节是"承重"的 | 成员和 6，C 补齐到 8；这 2 字节同时保证 `PIPELINE_DESC.topology` 落在 56 |
| `mcdr_fg_dispatch` | 末尾是 `_pad2[2]` 而不是一个 int32 | 两个位置都要显式填，只留一个会多出 4 B 尾填充，`sizeof` 变成 92 而不是 96 |
| `mcdr_sr_caps` / `mcdr_fg_caps` | `char note[28]` 要写成**一个**带名字的 `sequenceLayout(28, I8)` | 拆成 28 个成员后 `describe()` 会枚举出 28 个键，和 native 的字段表对不上 |

**互操作要求**：标量布局（`I32`/`I64`/`F32`/`F64`/`I8`/`PTR`）与所有偏移常量必须是 `@JvmField`。普通 `val` 会生成 `MCDRLayout.INSTANCE.getI32()` 这样的访问器，Java 侧的 static import 解析不到（混编工程里仍有 Java 调用方）。

**两级自检，分工明确**：

| 检查 | 触发时机 | 需要 DLL | 覆盖内容 |
| --- | --- | --- | --- |
| `EXPECTED_C_SIZES` 断言 | `MCDRLayout` 类初始化 | 否 | 每个结构体的 `byteSize()` 与手写常量 |
| `describe()` vs `mcdrABILayout()` | `MCDR_ABI` 初始化、`verifyABI()` | 是 | 每个字段的名字、offset、size |

第一级在**任何 native 调用之前**就能发现漂移，连 DLL 没编出来都能查：

```kotlin
arrayOf<Any>("mcdr_binding_slot", BINDING_SLOT, 40L),
```

第二级是 `mcdrABILayout(&fields, &count)` 返回一张 `mcdr_abi_field{ name, offset, size }` 表，名字形如 `"mcdr_binding_slot.buffer"`。`verify()` 做**双向**比对，三类问题分别报：

- `xxx: offset java=A native=B` / `size java=A native=B` —— 字段错位；
- `xxx: missing from the native layout report` —— Java 有、native 没报（native 少登记）；
- `xxx: reported by native but absent from the Java mirror` —— native 有、Java 没镜像（Kotlin 少一个字段，或者新结构体忘了登记进 `STRUCTS`）。

任何一条都抛 `IllegalStateException`，后面列出全部问题，**不是只报第一个**。

`STRUCTS` 这张表存在的意义是"枚举一致性"：`describe()` 和 `verify()` 都从它出发，所以新结构体只要登记进去就自动被两个方向检查，不会出现"加了结构体但没人校验"。

**当前的缺口**：`mcdr_dlss_constants` 只出现在 `EXPECTED_C_SIZES` 里（尺寸 312 被断言），但没有登记进 `STRUCTS`，因此它的字段偏移不参与 `verify()` 比对 —— 总大小对、内部字段错位仍然检测不到。

### 2.3 句柄层：`MCDR_ABI.kt`

一个 Kotlin `object`，内部 195 个方法句柄字段：

```kotlin
private var MH_createdevice: MethodHandle? = null
```

三个刻意的选择：

- **可空**：可选导出缺了就是 `null`，调用方靠 `?:` 降级。
- **非 `final`**：初始化失败不能变成 `ExceptionInInitializerError` —— 那会让整个类变成 `NoClassDefFoundError`，后端连"报告不可用"都做不到。
- **只链接一次**：`downcallHandle` 在 `bindAll()` 里做完，逐帧路径只 `invokeExact`。

两种绑定的语义差别是本层的核心设计：

| 绑定 | 缺失时 | 用于 |
| --- | --- | --- |
| `skip(name, desc)` | 抛 `IllegalStateException("MCDR export not found: ...")` | 没有它整个库没有意义：版本、布局报告、错误串、日志开关 |
| `skipOptional(name, desc)` | 返回 `null`，连 `Throwable` 也吞 | 单个功能（SR / FG / DLSS / PIX / VRS…），旧 DLL 上只是那个功能不可用 |

目前 134 个必需、61 个可选。**可选不等于不重要**：帧生成那批入口是 ABI v4 契约的一部分，之所以仍按可选绑定，只是为了让"JVM 新、DLL 旧"退化成"帧生成面板显示不可用"，而不是整个后端起不来。

描述符助手两个：

```kotlin
private fun fd(ret: MemoryLayout, vararg args: MemoryLayout) = FunctionDescriptor.of(ret, *args)
private fun fdv(vararg args: MemoryLayout) = FunctionDescriptor.ofVoid(*args)
```

### 2.4 封装层：每个导出的四种形态

`MCDR_ABI` 的 200 个公开函数不是风格统一的 API，而是**四种刻意区分的失败语义**：

| 形态 | 返回 | 缺句柄时 | 抛异常时 | 例 |
| --- | --- | --- | --- | --- |
| 状态码 | `Int` | `Int.MIN_VALUE` | `Int.MIN_VALUE` + WARN | `D3D12SetDlssQuality`、`D3D12SetHdrMode` |
| 谓词 | `Boolean` | `false` | `false` + WARN | `D3D12SetPipeline`、`AsyncFenceSignal` |
| 句柄 | `Long` | `0L` | 包成 `IllegalStateException` | `D3D12CreateSurface`、`D3D12AsyncExecutorCreate` |
| 命令 | `Unit` | 静默返回 | 包成 `IllegalStateException` | `D3D12PresentSurface`、`ID3D12DestroyResource` |

`Int.MIN_VALUE` 是一个**哨兵**，含义是"本地 DLL 没有这个导出"，与"调用失败"（其它负值）区分开。少数查询函数有自己更合适的默认值：`D3D12DlssAvailable` 缺导出返回 **1**（"未知 = 可用"，避免旧 DLL 上把 DLSS 面板藏掉）；`D3D12ReflexAvailable` 缺导出返回 **0**；`D3D12HdrAppliedMode` 缺导出返回 **-1**。

创建类函数的统一形状：

```kotlin
fun D3D12CreateSurface(hwnd: Long): Long {
    requireLoaded()
    return try {
        addr(MH_createsurface!!.invokeExact(hwnd) as MemorySegment)
    } catch (t: Throwable) {
        throw unexpected(t)
    }
}
```

`unexpected(t)` 一律是 `IllegalStateException("MCDR native call failed: $t", t)`。需要带 native 错误串的地方用 `check(value, what)`：它调 `D3D12LastError()`，拼成 `"$what failed: $detail"` 再抛。

### 2.5 描述符打包器

`MCDR_PipelineDesc` / `MCDRMeshPipelineDesc` 是布局层的延伸：把 Kotlin 侧的 builder 对象铺进一块 arena，得到一个 `mcdr_pipeline_desc*` / `mcdr_mesh_pipeline_desc*`。

```kotlin
MCDRPipelineDesc().apply { /* 填字段 */ }.pack(arena)   // 返回的段只在 arena 生命周期内有效
```

设计要点：

- **builder 是单次的**：填完字段直接交给 `D3D12CreatePipeline`，不缓存、不复用。
- **嵌套类是 static nested**（Kotlin 默认），不能写 `inner class` —— Java 侧要能用 `McdrPipelineDesc.ColorTarget(...)` 构造。
- **字段偏移在类初始化时解析一次**（`off(MCDRLayout.COLOR_TARGET, "srcColor")`），从不写字面量。
- **`semanticName` 跟着元素走**。旧实现里它是单独一个数组、按位置重新关联（"第 i 个名字给第 i 个元素"），两边数量不等时会静默错配；现在它是 `InputElement` 自己的字段。
- **未使用的颜色槽用 `format = -1`**（`DXGI_FORMAT_UNKNOWN`），不再需要额外的 sentinel 计数。
- **空 shader 也分配 1 字节**，让指针永远不为 `NULL`：native 校验器拒绝"`NULL` 指针 + 非零长度"，但接受"有效指针 + 长度 0"。
- mesh 版只有一处语义差别：三个 blob 是 **DXIL** 字节码而不是 HLSL 源码，且没有 input layout / 没有 topology（mesh 管线没有输入装配阶段）。

### 2.6 新增一个导出函数：要改哪几处

按顺序做，缺一处就是"能编译、能启动、运行到才炸"。

1. **`MCDR\include\mcdr_abi.h`**：加声明。签名里只用 `int32_t` / `int64_t` / `void*` / 已有结构体指针，不要出现别的模块拥有的 C++ 类型（那会让修饰名变成模块私有名）。放对段落（`abi_device.cpp` / `abi_surface.cpp` / … 对应的功能段）。
2. **结构体有变就升版本**：宏 `MCDR_ABI_VERSION` 加一，并在注释里写清"加了什么、哪些旧入口被降级成转发"。
3. **native 实现**：在对应 `src\c_abi\abi_*.cpp` 里定义，导出函数必须与声明处在同一模块单元内。
4. **`MCDR_Layout.kt`**：新结构体写 `structLayout`（pad 全部显式），加一行 `EXPECTED_C_SIZES`，若是新类型还要登记进 `STRUCTS`（漏登记 = 该结构体完全不被校验）。
5. **`MCDR_ABI.kt` 加字段**：`private var MH_xxx: MethodHandle? = null`，命名统一小写。
6. **`bindAll()` 的对应 `bindXxx()`** 里加一行：必需用 `skip`，功能级用 `skipOptional`，描述符用 `fd` / `fdv` 逐字写对每个参数宽度。
7. **写公开封装**：按 §2.4 选一种失败语义；文档注释里写清返回值的哨兵含义。
8. **跑自检**：

   ```
   python _port\abi_check.py
   ```

   期望看到 `native exports` 与 `kotlin bindings` 同步 +1、`mismatched symbols: (none)`、`struct layout problems: (none)`。它**不需要 DLL、不需要 GPU、不用启动游戏**：符号表是正则解析头文件与 Kotlin 源码得到的，结构体布局是按 MSVC x64 的自然对齐规则静态模拟出来的。
9. **编译两边**：`cd Illusion; .\gradlew.bat compileKotlin`，native 侧 `MSBuild MCDR\MCDR.vcxproj /p:Configuration=Release /p:Platform=x64`。

## 3 调用约定与内存

### 3.1 类型映射

| C | ValueLayout | Kotlin 侧读 | 备注 |
| --- | --- | --- | --- |
| `int32_t` / `bool` / MSVC `long` / `float` | `I32` / `F32` | `as Int` / `as Float` | 布尔入口一律 `int32_t` 1/0 |
| `int64_t` / `size_t` / `uint64_t` | `I64` | `as Long` | 需要按无符号解释时自己转 |
| `void*` / `mcdr_xxx*` | `PTR` | `as MemorySegment` → `.address()` | 句柄在 Kotlin 里存 `Long` |
| `uint8_t` | `I8` | `as Byte` | 结构体字段用，不作为参数 |
| `char*`（UTF-8 出参） | `PTR` | 二次调用取串 | §3.3 |
| `void` | — | — | 用 `fdv(...)` 而不是 `fd(VOID, ...)` |

**句柄约定**：所有句柄在 Kotlin 里都是 `Long`，只在调用边界用 `MemorySegment.ofAddress(handle)` 转成段。反向用 `addr(seg) = seg?.address() ?: 0L`。这样上层代码（渲染后端、录制器）完全不碰 `MemorySegment`，也不必 import `java.lang.foreign`。

### 3.2 Arena：谁分配、谁释放

| Arena | 位置 | 生命周期 | 装什么 |
| --- | --- | --- | --- |
| `ARENA = Arena.ofShared()` | `MCDR_ABI` 字段 | 进程 | `libraryLookup` 的库句柄、upcall stub（**永不关闭**） |
| `Arena.ofConfined().use { }` | 单个函数体内 | 本次调用 | 一次性的路径串、长度出参、临时结构体 |
| `SCRATCH`（ThreadLocal，4 个 confined slot） | 每线程 | 首次分配后复用 | 逐帧的数组与结构体打包 |
| 调用方传入的 arena（`pack(arena)`） | 描述符 | 跟随调用方 | 管线描述符 + 嵌套 shader blob |

两条纪律：

- **共享 Arena 只装"活到进程结束"的东西**。库句柄和 upcall stub 都必须如此 —— stub 被回收就是悬空函数指针，库句柄被关就是 DLL 被卸载。
- **confined Arena 绝不跨线程**。这是 API 语义：`Arena.ofConfined()` 的段只能在分配它的线程上访问，跨线程是运行时异常。所以逐帧的复用槽必须是 `ThreadLocal`，每个渲染线程（主线程与 bundle 录制 worker）各有一套。

指针参数的有效期：头文件没有逐条声明，但代码的用法是**只在本次调用期间有效** —— `pack()` 的返回值在同一段 `use { }` 里直接传给 downcall，随后 arena 关闭。native 侧负责把需要留存的数据拷进 D3D12 对象。

### 3.3 字符串

所有跨边界的字符串都是 **UTF-8 + NUL**，由调用方分配：

```kotlin
private fun cstringUtf8(arena: Arena, s: String): MemorySegment {
    val bytes = s.toByteArray(StandardCharsets.UTF_8)
    val seg = arena.allocate(bytes.size + 1L)          // +1 给 NUL
    MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.size)
    seg.set(ValueLayout.JAVA_BYTE, bytes.size, 0)
    return seg
}
```

**出参字符串是两阶段协议**，`mcdrLastError` 是最好的样板：

```c
// 返回所需字节数（含 NUL）；buf == NULL / cap == 0 时只做长度查询
int32_t mcdrLastError(char* buf, int32_t cap);
```

Kotlin 侧因此永远是"先问长度、再分配、再读"：

```kotlin
val need = MH_lasterror!!.invokeExact(MemorySegment.NULL, 0) as Int
if (need <= 0) return ""
val buf = arena.allocate(need.toLong())
val written = MH_lasterror!!.invokeExact(buf, need) as Int
return if (written > 0) buf.getString(0) else ""
```

同样的形状用在设备摘要、适配器列表、各 SDK 的能力查询上（`mcdr_dx12QueryDlss`、`mcdr_dx12QueryMeshSupport` 等）。**不要假设一次调用就够**：native 返回的是"必需长度"，不是"写入了多少"。

`getString(0)` 默认按 UTF-8 解码并以 NUL 结尾，正合约定；但它要求段的可见长度足够，所以对 native 返回的裸地址必须先 `reinterpret` 一个明确长度（不能一路用 `Long.MAX_VALUE`，除非确实要读到 NUL 为止，像 `mcdrABILayout` 里的字段名那样）。

### 3.4 数组与结构体数组

数组参数一律是 **(pointer, count)**：

```kotlin
val arr = scratch(0, count * 8L)                    // count 个指针
arr.setAtIndex(MCDRLayout.PTR, i, ptr(objects[i]))
MH_prepareobjects!!.invokeExact(ptr(ctx), arr, count)
```

结构体数组用 `arena.allocate(layout, count)`，然后按 `layout.byteSize()` 逐项写：

```kotlin
val array = arena.allocate(MCDRLayout.COLOR_TARGET, targets.size.toLong())
val stride = MCDRLayout.COLOR_TARGET.byteSize()      // 这就是尾部 padding 必须算对的原因
```

**索引必须按"实际起始偏移"算**。曾经有一处按 slot 下标而不是 `startDraw` 之后的绝对下标去索引"每槽一份"的类型数组，只有 `startDraw == 0` 的 worker 不炸，其余 worker 抛 `ArrayIndexOutOfBoundsException`，整条并行录制 100% 失败并静默退化成串行。

### 3.5 返回码与错误串

| 出口 | 约定 |
| --- | --- |
| 布尔语义 | `int32_t` 1 = 成功/真，0 = 失败/假 |
| 三态 | 1 = 成功，0 = 未启用/已关闭，-1 = 参数或加载失败（如 PIX 加载、日志镜像） |
| 长度查询 | 返回所需字节数（含 NUL），0 表示"无内容" |
| 指针 | 非 `NULL` 表示有效，`NULL` 表示失败（失败原因去 `mcdrLastError()`） |
| 错误串 | `mcdrLastError()` 取**调用线程**的最近一条错误 |

`mcdrLastError()` 是线程局部的 —— 这也是它不用全局变量的原因：渲染线程与 worker 各自报自己的错，不会互相覆盖。

### 3.6 对齐与 padding

- 头文件**没有** `#pragma pack`，所以布局就是 MSVC x64 的自然对齐：`int64_t`/指针 8 字节对齐，`int32_t`/`float` 4 字节，`uint8_t` 1 字节，结构体整体对齐到其成员的最大对齐。
- 每个"编译器会插"的位置在 C 侧都写了一个具名 `_padN`，Java 侧必须有对应成员；C 侧没命名、只能靠编译器补的位置（如 `INPUT_ELEMENT` 里 28→32 的 4 字节），Java 侧必须补 `paddingLayout(n)`。
- **arena 分配的地址**：`scratch()` 用 16 字节对齐（`allocate(need, 16L)`），比结构体最大对齐（8）更严，避免 SIMD 拷贝路径上的意外。

## 4 生命周期与线程

### 4.1 加载顺序

`MCDR_ABI` 的 `init { }` 是唯一的加载入口，顺序不可换：

1. `extractLibrary()`：目录 `Path.of(System.getProperty("user.dir"), "dxrmc")` → `createDirectories` → **先** `extractVendorResources(dir)`（第三方运行库必须比 DLL 更早落位）→ 从 JAR 资源 `/MCDR.dll` 读字节、**逐字节比较**决定是否覆盖本地副本 → 返回 DLL 绝对路径。
2. `SymbolLookup.libraryLookup(dllPath, ARENA)` —— 失败就把异常记进 `LOAD_FAILURE`，**不抛**。
3. `bindAll()`：`bindFenceStub()` → `bindMetaAndDevice()` → `bindCommand()` → `bindPipelineSurfaceDraw()` → `bindAsync()`，然后推两个启动期开关（日志详细度、关机期进度行）。
4. `verifyABI()`：版本握手 + 布局逐字段比对。
5. 全部成功才打三行 INFO（库版本、各结构体 `byteSize`、启动日志级别）。

任何一步失败都只写 `LOAD_FAILURE`，让类**继续可用**（只是 `isAvailable() == false`）。

### 4.2 `dxrmc\` 目录与"必须同目录"

- DLL 与所有第三方运行库都解到 `<user.dir>\dxrmc\`。`user.dir` 是游戏进程的工作目录，也就是整合包/实例目录。
- 解压是**内容比较**而不是时间戳比较。JAR 条目的时间戳固定在 1980 纪元，旧的"本地 mtime 更新且大小相同就不覆盖"判断曾让一个陈旧的本地副本长期遮住新构建。
- vendor 解压**从不抛异常**：这些 SDK 是可选的，缺一个必须降级成"该 SDK 报不可用"，而不是让模组起不来。日志只报一行 `SDK runtime DLLs: N extracted, M already current`。
- 清单由 `vendor/manifest.txt` 驱动（构建期生成）。不硬编码名单的理由：JAR 目录不可枚举，硬编码会漂移。
- 随发行包发布的是 XeSS 与 Microsoft 的 D3D12Core / DXC；DLSS 与 FidelityFX 按许可证要求不进发行包，只在开发目录里出现。发行版因此会如实报告 DLSS / FSR 不可用。

**为什么必须和第三方运行库同目录**：Windows 的 DLL 搜索顺序里，PATH 排在很后面，把目录追加到 PATH 对"被隐式加载的依赖"并不可靠；而同一目录是加载器与 native 侧都能预期的唯一定位点。具体到本项目，这些运行库都是 native 在运行时按名字去找的，所以必须落在 `MCDR.dll` 旁边。代码里能确认的只有"解压到同一目录、且在 `libraryLookup` 之前完成"这个顺序保证；更细的搜索路径语义代码中未明确。

### 4.3 版本握手与 `isAvailable()`

```kotlin
fun isAvailable(): Boolean = LOAD_FAILURE == null
fun unavailableReason(): String? = LOAD_FAILURE?.toString()
```

`LOAD_FAILURE == null` 意味着**三件事都成立**：DLL 找到了、必需句柄全绑上了、`mcdrABIVersion()` 与 `MCDR_ABI_VERSION` 相等且布局逐字段一致。它不等价于"显卡能用" —— 设备创建失败是另一层的事。

版本不匹配直接抛 `MCDR ABI version mismatch: java=4, native=3`。这是**故意做成硬失败**的：版本不同意味着结构体可能不同，继续调用就是按错误布局读写内存。

### 4.4 线程约束

| 场景 | 约束 |
| --- | --- |
| 绑定（`bindAll`） | 只在类初始化时、单线程跑一次 |
| 主渲染线程 | 独占一条命令列表；`SCRATCH` 槽只它用 |
| bundle 录制 worker | 各线程自己的 `SCRATCH`；共享的只有描述符分配器的原子游标 |
| upcall（围栏回调） | 从 native 自己的 `std::thread` 进来，**不是** JVM 线程 |
| confined arena | 绝不跨线程传递段 |

upcall 这条要单独说：`onFenceComplete` 在 native 线程上执行，异常一旦逃出去会**直接终止 JVM**（不是抛到某处可以捕获）。所以它自己 `catch (Throwable)`，只打一条 WARN，不依赖任何 `catchException` 包装。

## 5 失败处理

### 5.1 五类失败，五种处理

| 失败 | 检测点 | 处理 | 用户可见后果 |
| --- | --- | --- | --- |
| 库缺失 / 加载失败 | `extractLibrary()` / `libraryLookup` | 记 `LOAD_FAILURE`，不抛 | 后端整体不可用，报原因 |
| 必需导出缺失 | `skip()` | 抛 → 记 `LOAD_FAILURE` | 同上（新 JVM 配老 DLL 的典型形态） |
| 可选导出缺失 | `skipOptional()` 返回 `null` | 该函数返回哨兵值 | 单个功能报不可用 |
| 布局/版本不匹配 | `verifyABI()` | 抛 → 记 `LOAD_FAILURE` | 同上，日志里有逐字段明细 |
| 调用抛异常 | 各封装的 `catch` | 硬失败接口包 `unexpected`；查询接口返回哨兵 | 一条 WARN + 降级 |

### 5.2 显式失败 vs 静默降级

分界线是"这个调用的失败会不会让后续状态不可信"：

- **硬失败**（抛 `IllegalStateException`）：创建资源/管线/命令列表、销毁、present、`D3D12LastError()` —— 继续跑下去只会积累不一致状态。
- **静默降级**（哨兵值）：所有开关类（DLSS 质量、Reflex 模式、VRS、PIX、HDR）、所有能力查询。理由很实际：这些函数大多在"配置被读取"的时刻调用，那时 DLL 是可选的，抛异常等于让玩家改不了配置。

### 5.3 错误信息长什么样

- 绑定失败：`MCDR export not found: mcdr_dx12CreateSurface`
- 库缺失：`MCDR.dll is neither packaged in the JAR nor present at ... — build the MCDR native project first`
- 版本：`MCDR ABI version mismatch: java=4, native=3`
- 布局：`MCDR ABI layout mismatch (N field(s)); ...` 后面逐行列出
- 尺寸：`MCDR ABI struct size mismatch for mcdr_fg_dispatch: java=92 c=96 — fix the mirror ...`
- 调用：`MCDR native call failed: <原始异常>`
- 业务：`<what> failed: <mcdrLastError()>`

## 6 常见坑

1. **编码**：一律 UTF-8，且必须显式写 NUL。用 `String.toByteArray()` 不带字符集就是平台默认编码，在中文 Windows 上是 GBK —— native 收到的是乱码路径。
2. **字节序**：`MemorySegment.asByteBuffer()` 默认是 **BIG_ENDIAN**，而 x86 是 little。旧 JNI 路径用 `NewDirectByteBuffer` 交出去的缓冲正是这个默认值，结果所有 UBO 里的矩阵都被静默转置。现在的规矩是：拿到 `ByteBuffer` 立刻 `.order(ByteOrder.nativeOrder())`。
3. **对齐**：`structLayout` 不插 padding（§2.2）。宁可多写一个具名 `_padN` 并在 C 侧也命名它，也不要指望"反正编译器会补"。
4. **尾部 padding 决定 stride**：算 `byteSize()` 当 stride 之前，先确认 `EXPECTED_C_SIZES` 那一行和 C 的 `sizeof` 一致。
5. **跨线程 Arena**：`Arena.ofConfined()` 的段只能在本线程用。要跨线程就传值/传句柄，不要传段；逐帧缓冲用 `ThreadLocal` 槽。
6. **`invokeExact` 必须逐字匹配**：`int32_t` 参数写 `I32`、调用点写 `as Int`。写成 `I64` 或 `as Long` 会在运行时抛 `WrongMethodTypeException`，而且错的可能是**第一次调用**，不是绑定期 —— 所以新封装的冒烟测试一定要真的调一次。
7. **`MemorySegment.NULL` 与 `ofAddress(0)`**：两者等价；比较时用 `.address() == 0L` 最省事。
8. **数组索引按绝对位置**：并行录制里"每槽一份"和"每 worker 一份"的数组不是同一种索引，混用只在部分 worker 上炸（§3.4）。
9. **upcall 里不能抛**：逃出去的异常直接杀 JVM（§4.4）。
10. **`reinterpret` 要写长度**：native 返回的裸地址没有可见长度，`getString`/`get` 之前必须给出准确长度，否则是越界读。
11. **不要凭名字猜语义**。`mcdr_dx12CreateDevice(char* buf, int32_t cap)` 名字里带 Create，实际返回 `int32_t` 并只往 `buf` 里写设备摘要（两阶段字符串）；设备的创建是这个调用的副作用。看签名，不要看名字。
12. **导出名不统一**。绝大多数是 `mcdr_dx12*`，但命令编码器那条是 `MCDR_D3D12_create_command_encoder`（大写 + 下划线）。抄别的绑定行时别照抄命名。

## 7 文件导航

相对 `Illusion\src\main\kotlin\dev\nts\expdxr\`：

| 文件 | 职责 |
| --- | --- |
| `d3d12\extern\MCDR_ABI.kt` | 唯一入口：解包 DLL、绑定 195 个方法句柄、封装每个导出、维护 `isAvailable()` |
| `d3d12\extern\MCDR_Layout.kt` | 结构体的 `MemoryLayout` 镜像、尺寸断言、与运行中 DLL 的字段偏移比对 |
| `d3d12\extern\MCDR_PipelineDesc.kt` | 图形管线描述符 → `mcdr_pipeline_desc` 的 arena 打包 |
| `d3d12\extern\MCDR_MeshPipelineDesc.kt` | mesh 管线描述符（DXIL）→ `mcdr_mesh_pipeline_desc` 的 arena 打包 |
| `misc\IllusionLogger.kt` | FFM 调用失败时的唯一日志出口（`infoMessage` / `warnMessage`） |
| `misc\settings\D3D12Settings.kt` | 配置 → 启动期快照，再经 `MCDR_ABI` 的 `Set*` 入口推给 native |
| `IllusionConfig.kt` | 所有开关的定义处（本层不读环境变量，只读这里） |

本层之外但必须一起看：

| 路径 | 职责 |
| --- | --- |
| `MCDR\include\mcdr_abi.h` | 契约本体：导出声明、结构体、错误码、版本宏 |
| `_port\abi_check.py` | 静态自检：符号/签名宽度比对 + 结构体布局镜像比对 |

## 8 文档维护

- **本文的数字来自 `_port\abi_check.py` 的实际输出**。改了导出数量、结构体字段或版本号，先跑一遍脚本，再同步开头的表格与 §2.6 的步骤。
- **判定标准是"代码里能读到的"**。凡是头文件注释没写、Kotlin 侧也没实现的，写"代码中未明确"，不要补合理推测。
- **不要在这里复制源码**。要引代码就引签名与一行关键逻辑，实现细节以源码注释为准 —— 两处都写必然会漂移，而漂移的注释比没有注释更贵。
- **历史事故只留结论**。本文提到"曾经"的地方，保留的是"什么形状会坏、为什么"，不复述当时的排查过程。
- **新增坑位就加进 §6**，并尽量给出"触发条件 + 失败形式"，因为这一节的价值在于让人一眼认出自己正踩的是哪一条。
