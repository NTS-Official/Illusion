# MCDR 的 FFM ABI 层

本文只讲一件事：**JVM 侧怎么安全地调用 MCDR 这个 C++ DLL**，以及调用时必须遵守的用法与边界约束。

三个参与者：

| 层 | 文件 | 角色 |
| --- | --- | --- |
| 契约 | `MCDR\include\mcdr_abi.h` | 唯一真相：`extern "C"` 导出、结构体定义、错误码约定 |
| 布局 | `Illusion\src\main\kotlin\io\github\dxegp\d3d12\extern\MCDR_Layout.kt` | 结构体的 `MemoryLayout` 镜像 + 自检 |
| 句柄 | `Illusion\src\main\kotlin\io\github\dxegp\d3d12\extern\MCDR_ABI.kt` | 252 个 `downcallHandle` + 每个导出的调用封装 |

适用范围：Java 25 / Minecraft release-26.1 起 FFM 已退出预览。**本项目禁止 JNI**（`System.loadLibrary`、`external fun`、`RegisterNatives` 一律不许出现），native 侧也不许再提供 `JNIEXPORT` 入口。

核心 JVM 命名空间为 `io.github.dxegp`；RT、SR 与帧生成分别位于 `experimental.raytracing`、
`experimental.superresolution` 与 `experimental.framegen`。完整类名、反射查找、Mixin 清单和早期窗口服务必须使用当前包名。
源码包名不决定外部标识：保留 `dxr_mc` mod id、`config/dxr_mc-client.toml` 的 `[rt]` / `[sr]`、
光影包的 `rt:<key>` 与工作目录 `dxrmc/`。`MCDR.dll` 和 SDK 运行库仍解压到同一 `dxrmc/` 目录。
早期窗口服务只进入独立 companion JAR；主 JAR 内嵌路径为 `META-INF/dxrmc/earlywindow.jar`。

当前头文件、绑定声明和布局表的规模：

| 项 | 数值 |
| --- | --- |
| native 导出 | 257 |
| Kotlin 绑定 | 252 |
| 其中"允许缺失"的可选导出 | 75 |
| 头文件有、Kotlin 未绑定 | 5（回读 / 落盘 / 诊断辅助） |
| 签名不匹配 | 0 |
| 双方镜像的结构体 | 40 个检查大小，其中 38 个逐字段检查，共 425 字段 |

## 1 三层结构

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
MCDR_ABI.kt                        句柄：252 个 downcallHandle + 每导出的封装
        │
        ▼
MCDR_PipelineDesc.kt               打包器：把 Kotlin 对象铺进 arena
MCDR_MeshPipelineDesc.kt
```

### 1.1 契约层：`mcdr_abi.h`

头文件开头把设计规则写成了七条，逐条对应到实现：

| 规则 | 含义 | 落地位置 |
| --- | --- | --- |
| 只有 C 链接 | `extern "C"`，无名字修饰，无生成的头文件 | 全部 257 个导出 |
| 每个聚合体只有一份定义 | Java 侧 1:1 `MemoryLayout`，不手写偏移、不 packed blob | `MCDR_Layout.kt` |
| 句柄是不透明指针 | `mcdr_object*` 等，不是"让 Java 猜含义的整数" | §2.1 |
| 字符串由调用方分配 | UTF-8 缓冲，被调方返回所需字节数（含 NUL） | §2.3 |
| 异常不跨界 | 布尔入口返回 `int32_t` 1/0；错误在 `mcdrLastError()` | §2.5 |
| 数组是 (pointer, count) | 从不用 JNI 数组 | §2.4 |
| 单一 upcall 类型 | 只带整数句柄，native 不持 JVM 对象 | §3.4 |

两个容易忽略的细节：

- **调用约定是 `__cdecl`**。`MCDR_CALL` 在 Windows 上展开成 `__cdecl`；`MCDR_API` 在 native 编译时是 `__declspec(dllexport)`，在消费方是 `dllimport`。x64 上只有一种调用约定，但这个宏让头文件在 32 位下也不会错。
- **MSVC 的 `long` 是 32 位**。头文件为此专门写了一条注释：凡是写成 `int32_t` 的参数，Java 侧必须是 `JAVA_INT`；只有 `int64_t` / `size_t` / 指针才是 `JAVA_LONG` / `JAVA_ADDRESS`。这一条是 §5 里"类型不逐字匹配"坑的根源。

版本号是双份的：宏 `MCDR_ABI_VERSION`（当前 `44u`），以及运行时函数 `mcdrABIVersion()`。两者相等才算握手成功，防止"Java 常量被编译器内联、DLL 却是旧的"。

RT 几何注册使用 120 B `mcdr_rt_geometry_desc` 与 56 B `mcdr_rt_geometry_output`，追踪使用 128 B `mcdr_rt_frame_desc`。
FFM 在 confined Arena 中复制 CPU payload，native 在返回前完成 CPU 数据复制并记录上传；Arena 关闭不影响 GPU 在途寿命。
复用组传 NULL/0，trace 仅传 snapshotId；native 拥有五个缓冲，输出句柄只可借用。详见 [RT 帧资源边界](../rt/README.md#1610-native-帧资源与录制边界)。

### 1.2 布局层：`MCDR_Layout.kt`

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

### 1.3 句柄层：`MCDR_ABI.kt`

一个 Kotlin `object`，内部 252 个方法句柄字段：

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

目前 177 个必需、75 个可选。可选绑定允许能力探测处理缺失的功能入口；ABI 版本握手仍必须通过。

当前几何生产入口为 `D3D12CreateRtGeometryDelta`：112 B 描述、每条 72 B 内容引用与 72 B 输出。
调用方保留完整 64 B record table，只有变化的 heap 内容才复制到 FFM arena；未变内容以 identity/长度引用。
内容列表为空时传零记录表，records/contents 均写空指针、长度与数量均为 0，禁止将零长度外部地址当作几何数据。
native 返回合法非零快照及最小 raw 缓冲；材质仍须提供或合法复用，复用材质的空请求没有 staging 上传。
生产配合独立所需目标构造带有限代理或无界 unknown 标记的 scene；没有已观测目标时撤销场景，不以空载荷推断空空间。
`D3D12GetRtGeometryMemory` 的 16 B 输出分别为在用 DEFAULT/UPLOAD committed 容量，不将多个快照共用的 arena 重复相加。
几何布局、背压、取消与退休约束见 [native 几何快照](../../../MCDR/docs/advanced/rt-frame.md#11-不可变几何快照)。

时间戳快照的 `D3D12QueueQueryValues` / `D3D12TryReadQueryValues` 是 ABI 38 的兼容追加入口。
缺少它们时分别返回 `false` / `-1`，不回退到同步读回。轮询的 `1` / `0` / `-1` 分别表示
已复制并消费、尚未完成、错误；仅 `1` 复制到 JVM 输出数组，pending 或错误均保持数组原值。
采样窗口与缺失槽的口径见 [RT 逐阶段耗时](../rt/README.md#96-逐阶段耗时)。

描述符助手两个：

```kotlin
private fun fd(ret: MemoryLayout, vararg args: MemoryLayout) = FunctionDescriptor.of(ret, *args)
private fun fdv(vararg args: MemoryLayout) = FunctionDescriptor.ofVoid(*args)
```

### 1.4 封装层：每个导出的四种形态

`MCDR_ABI` 的公开函数采用**四种失败语义**：

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

### 1.5 描述符打包器

`MCDR_PipelineDesc` / `MCDRMeshPipelineDesc` 是布局层的延伸：把 Kotlin 侧的 builder 对象铺进一块 arena，得到一个 `mcdr_pipeline_desc*` / `mcdr_mesh_pipeline_desc*`。

```kotlin
MCDRPipelineDesc().apply { /* 填字段 */ }.pack(arena)   // 返回的段只在 arena 生命周期内有效
```

设计要点：

- **builder 是单次的**：填完字段直接交给 `D3D12CreatePipeline`，不缓存、不复用。
- **嵌套类是 static nested**（Kotlin 默认），不能写 `inner class` —— Java 侧要能用 `McdrPipelineDesc.ColorTarget(...)` 构造。
- **字段偏移在类初始化时解析一次**（`off(MCDRLayout.COLOR_TARGET, "srcColor")`），从不写字面量。
- **`semanticName` 跟着元素走**：它是 `InputElement` 自己的字段，不得另设一个按位置关联的名字数组（"第 i 个名字给第 i 个元素"），两边数量不等会静默错配。
- **未使用的颜色槽用 `format = -1`**（`DXGI_FORMAT_UNKNOWN`），不用额外的 sentinel 计数。
- **空 shader 也分配 1 字节**，让指针永远不为 `NULL`：native 校验器拒绝"`NULL` 指针 + 非零长度"，但接受"有效指针 + 长度 0"。
- mesh 版只有一处语义差别：三个 blob 是 **DXIL** 字节码而不是 HLSL 源码，且没有 input layout / 没有 topology（mesh 管线没有输入装配阶段）。

### 1.6 新增一个导出函数：要改哪几处

按顺序做，缺一处就是"能编译、能启动、运行到才炸"。

1. **`MCDR\include\mcdr_abi.h`**：加声明。签名里只用 `int32_t` / `int64_t` / `void*` / 已有结构体指针，不要出现别的模块拥有的 C++ 类型（那会让修饰名变成模块私有名）。放对段落（`abi_device.cpp` / `abi_surface.cpp` / … 对应的功能段）。
2. **结构体有变就升版本**：宏 `MCDR_ABI_VERSION` 加一，并在注释里写清"加了什么、哪些旧入口被降级成转发"。
3. **native 实现**：在对应 `src\c_abi\abi_*.cpp` 里定义，导出函数必须与声明处在同一模块单元内。
4. **`MCDR_Layout.kt`**：新结构体写 `structLayout`（pad 全部显式），加一行 `EXPECTED_C_SIZES`，若是新类型还要登记进 `STRUCTS`（漏登记 = 该结构体完全不被校验）。
5. **`MCDR_ABI.kt` 加字段**：`private var MH_xxx: MethodHandle? = null`，命名统一小写。
6. **`bindAll()` 的对应 `bindXxx()`** 里加一行：必需用 `skip`，功能级用 `skipOptional`，描述符用 `fd` / `fdv` 逐字写对每个参数宽度。
7. **写公开封装**：按 §1.4 选一种失败语义；文档注释里写清返回值的哨兵含义。
8. **跑自检**：

   ```
   python _port\abi_check.py
   ```

   期望看到 `native exports` 与 `kotlin bindings` 同步 +1、`mismatched symbols: (none)`、`struct layout problems: (none)`。它**不需要 DLL、不需要 GPU、不用启动游戏**：符号表是正则解析头文件与 Kotlin 源码得到的，结构体布局是按 MSVC x64 的自然对齐规则静态模拟出来的。
9. **编译两边**：`cd Illusion; .\gradlew.bat compileKotlin`，native 侧 `MSBuild MCDR\MCDR.vcxproj /p:Configuration=Release /p:Platform=x64`。

## 2 调用约定与内存

### 2.1 类型映射

| C | ValueLayout | Kotlin 侧读 | 备注 |
| --- | --- | --- | --- |
| `int32_t` / `bool` / MSVC `long` / `float` | `I32` / `F32` | `as Int` / `as Float` | 布尔入口一律 `int32_t` 1/0 |
| `int64_t` / `size_t` / `uint64_t` | `I64` | `as Long` | 需要按无符号解释时自己转 |
| `void*` / `mcdr_xxx*` | `PTR` | `as MemorySegment` → `.address()` | 句柄在 Kotlin 里存 `Long` |
| `uint8_t` | `I8` | `as Byte` | 结构体字段用，不作为参数 |
| `char*`（UTF-8 出参） | `PTR` | 二次调用取串 | §2.3 |
| `void` | — | — | 用 `fdv(...)` 而不是 `fd(VOID, ...)` |

**句柄约定**：所有句柄在 Kotlin 里都是 `Long`，只在调用边界用 `MemorySegment.ofAddress(handle)` 转成段。反向用 `addr(seg) = seg?.address() ?: 0L`。这样上层代码（渲染后端、录制器）完全不碰 `MemorySegment`，也不必 import `java.lang.foreign`。

### 2.2 Arena：谁分配、谁释放

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

### 2.3 字符串

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

### 2.4 数组与结构体数组

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

**索引必须按"实际起始偏移"算**：索引"每槽一份"的类型数组时用 `startDraw` 之后的绝对下标，不要用 slot 下标 —— 后者只在 `startDraw == 0` 的 worker 上正确，其余 worker 抛 `ArrayIndexOutOfBoundsException`，整条并行录制退化成串行。

### 2.5 返回码与错误串

| 出口 | 约定 |
| --- | --- |
| 布尔语义 | `int32_t` 1 = 成功/真，0 = 失败/假 |
| 三态 | 1 = 成功，0 = 未启用/已关闭，-1 = 参数或加载失败（如 PIX 加载、日志镜像） |
| 长度查询 | 返回所需字节数（含 NUL），0 表示"无内容" |
| 指针 | 非 `NULL` 表示有效，`NULL` 表示失败（失败原因去 `mcdrLastError()`） |
| 错误串 | `mcdrLastError()` 取**调用线程**的最近一条错误 |

`mcdrLastError()` 是线程局部的 —— 这也是它不用全局变量的原因：渲染线程与 worker 各自报自己的错，不会互相覆盖。

### 2.6 对齐与 padding

- 头文件**没有** `#pragma pack`，所以布局就是 MSVC x64 的自然对齐：`int64_t`/指针 8 字节对齐，`int32_t`/`float` 4 字节，`uint8_t` 1 字节，结构体整体对齐到其成员的最大对齐。
- 每个"编译器会插"的位置在 C 侧都写了一个具名 `_padN`，Java 侧必须有对应成员；C 侧没命名、只能靠编译器补的位置（如 `INPUT_ELEMENT` 里 28→32 的 4 字节），Java 侧必须补 `paddingLayout(n)`。
- **arena 分配的地址**：`scratch()` 用 16 字节对齐（`allocate(need, 16L)`），比结构体最大对齐（8）更严，避免 SIMD 拷贝路径上的意外。

## 3 生命周期与线程

### 3.1 加载顺序

`MCDR_ABI` 的 `init { }` 是唯一的加载入口，顺序不可换：

1. `extractLibrary()`：目录 `Path.of(System.getProperty("user.dir"), "dxrmc")` → `createDirectories` → **先** `extractVendorResources(dir)`（第三方运行库必须比 DLL 更早落位）→ 从 JAR 资源 `/MCDR.dll` 读字节、**逐字节比较**决定是否覆盖本地副本 → 返回 DLL 绝对路径。
2. `SymbolLookup.libraryLookup(dllPath, ARENA)` —— 失败就把异常记进 `LOAD_FAILURE`，**不抛**。
3. `bindAll()`：`bindFenceStub()` → `bindMetaAndDevice()` → `bindCommand()` → `bindPipelineSurfaceDraw()` → `bindAsync()`，然后推两个启动期开关（日志详细度、关机期进度行）。
4. `verifyABI()`：版本握手 + 布局逐字段比对。
5. 全部成功才打三行 INFO（库版本、各结构体 `byteSize`、启动日志级别）。

任何一步失败都只写 `LOAD_FAILURE`，让类**继续可用**（只是 `isAvailable() == false`）。

### 3.2 `dxrmc\` 目录与"必须同目录"

- DLL 与所有第三方运行库都解到 `<user.dir>\dxrmc\`。`user.dir` 是游戏进程的工作目录，也就是整合包/实例目录。
- 解压是**内容比较**而不是时间戳比较：JAR 条目的时间戳固定在 1980 纪元，按 mtime 判断会让陈旧的本地副本遮住新构建。
- vendor 解压**从不抛异常**：这些 SDK 是可选的，缺一个必须降级成"该 SDK 报不可用"，而不是让模组起不来。日志只报一行 `SDK runtime DLLs: N extracted, M already current`。
- 清单由 `vendor/manifest.txt` 驱动（构建期生成）。不硬编码名单的理由：JAR 目录不可枚举，硬编码会漂移。
- 随发行包发布的是 XeSS 与 Microsoft 的 D3D12Core / DXC；DLSS 与 FidelityFX 按许可证要求不进发行包，只在开发目录里出现。发行版因此会如实报告 DLSS / FSR 不可用。

**为什么必须和第三方运行库同目录**：Windows 的 DLL 搜索顺序里，PATH 排在很后面，把目录追加到 PATH 对"被隐式加载的依赖"并不可靠；而同一目录是加载器与 native 侧都能预期的唯一定位点。具体到本项目，这些运行库都是 native 在运行时按名字去找的，所以必须落在 `MCDR.dll` 旁边。代码里能确认的只有"解压到同一目录、且在 `libraryLookup` 之前完成"这个顺序保证；更细的搜索路径语义代码中未明确。

### 3.3 版本握手与 `isAvailable()`

```kotlin
fun isAvailable(): Boolean = LOAD_FAILURE == null
fun unavailableReason(): String? = LOAD_FAILURE?.toString()
```

`LOAD_FAILURE == null` 意味着**三件事都成立**：DLL 找到了、必需句柄全绑上了、`mcdrABIVersion()` 与 `MCDR_ABI_VERSION` 相等且布局逐字段一致。它不等价于"显卡能用" —— 设备创建失败是另一层的事。

版本不匹配直接抛 `MCDR ABI version mismatch: java=4, native=3`。这是**故意做成硬失败**的：版本不同意味着结构体可能不同，继续调用就是按错误布局读写内存。

### 3.4 线程约束

| 场景 | 约束 |
| --- | --- |
| 绑定（`bindAll`） | 只在类初始化时、单线程跑一次 |
| 主渲染线程 | 独占一条命令列表；`SCRATCH` 槽只它用 |
| bundle 录制 worker | 各线程自己的 `SCRATCH`；共享的只有描述符分配器的原子游标 |
| upcall（围栏回调） | 从 native 自己的 `std::thread` 进来，**不是** JVM 线程 |
| confined arena | 绝不跨线程传递段 |

upcall 这条要单独说：`onFenceComplete` 在 native 线程上执行，异常一旦逃出去会**直接终止 JVM**（不是抛到某处可以捕获）。所以它自己 `catch (Throwable)`，只打一条 WARN，不依赖任何 `catchException` 包装。

### 3.5 NRD 显式偏移入口

当前 ABI 版本为 44。`MCDR_ABI.D3D12NrdDenoiseTemporal` 绑定九个参数：
encoder、七纹理句柄数组、纹理数、64-float 矩阵地址、四 float 偏移地址、帧号、reset、诊断地址与容量。
FFM descriptor 依次为 `PTR/PTR/I32/PTR/PTR/I32/I32/PTR/I32`，返回 `I32`。
偏移个数使用 `MCDRLayout.NRD_JITTER_FLOAT_COUNT`，矩阵段至少 256 B，偏移段至少 16 B。
四个偏移的顺序、单位与数值范围见 [光追接口](../rt/README.md#234-引擎侧的接口s4-4-c)。
参数段与纹理句柄数组必须在调用返回前有效；调用返回不表示 GPU 已完成，纹理仍受提交 fence 约束。
实际 RT 帧由 native 帧上下文直接调用 NRD，偏移与光栅/PT 同源，NRD 取 `(-Jx,+Jy)`。
RR/RT SR 的 104-float 相机载荷 68–71 为当前/上一帧屏幕内容偏移 xy，
不再作为保留位。SDK 矩阵仍无 jitter；四分量须有限且位于 `[-0.5,0.5]`。

### 3.6 RT 显式曝光入口

`MCDR_ABI.D3D12DlssdEvalExposure` / `D3D12RtSrEvalExposure` 绑定同名 native 导出，
参数依次为 encoder、纹理句柄数组、纹理数、输入宽/高、输出宽/高、帧号、reset、
104-float 相机段、独立曝光段、诊断段与容量，共十三项。
FFM descriptor 为 `PTR/PTR/I32/I32/I32/I32/I32/I32/I32/PTR/PTR/PTR/I32`，返回 `I32`。
wrapper 检查相机段至少 416 B，曝光数组个数为 `MCDRLayout.RT_EXPOSURE_FLOAT_COUNT=2`，
且所有分量有限；Arena 分配两 float 参数段及纹理指针数组，生命周期覆盖调用。
曝光分量依次为 `preExposure / exposureScale`，native 另检查闭区间 `[2^-16,2^16]`。

RR 允许八/九纹理、RT SR 允许四/五纹理；可选最后一张为 1×1 `R32_FLOAT` 最终曝光倍率。
缺少资源时 SR 显式启用 `useAutoExposure`；RR 没有同名开关，缺资源的内部行为代码中未明确。
资源模式切换会等待上次求值 fence、释放 feature 并请求 reset；同一未提交列表内切换返回 0。
旧入口默认两个曝光分量为 1。玩家 RR 与独立 DLSS-SR 由 native 帧直接求值，两个分量仍为 1，
附带由引擎测光生成的同帧曝光纹理；手动/AUTO 均保留纹理，切换时不改变 SDK 资源模式。
资源与 GPU 生命周期见 [native ABI](../../../MCDR/docs/abi/README.md#4-版本握手)。

### 3.7 native RT 帧入口

native RT 帧通过 `D3D12CreateRtFrame` / `D3D12RtFrame` / `D3D12ReleaseRtFrame` 管理；
上下文使用 I64 编号，录制签名为 `PTR/I64/PTR/PTR -> I32`。128 / 128 / 128 B 三个结构体登记在
`MCDRLayout`，CPU 常量使用 confined arena，仅在调用内借用；返回的 GPU 资源不得由 JVM 销毁。
描述末尾携带帧时间戳、曝光模式/EV/速率和 reset；native 持有曝光纹理及适应状态，输出附带同帧曝光句柄。
还包括信号编码（0=RAW、1=REBLUR、2=RR）、当前减上一帧的屏幕 jitter 和 denoisingRange；输出附带同帧 motion、NRD packed-normal、viewZ、diffuse/specular 信号及反射距离借用句柄。
描述中的 gbufferRevision 位于 56，CPU 常量指针位于 64；contentJitterX/Y 位于 120/124，
与当前 SL 68/69 同源，供 RAW、失败回退与重建后的合光使用。
`D3D12CreateRtGeometrySnapshot` 使用 120 B 描述和 56 B 输出，调用内复制 CPU 数据并在当前主列表上传。
`D3D12CreateRtAcceleration` 为 `I64/I64 -> I64`，以几何编号与可选复用 AS 编号创建 native AS 上下文。
`D3D12PrepareRtAcceleration` 为 `PTR/I64/PTR -> I32`；`D3D12FilterRtAcceleration` 为 `PTR/I64/PTR/I32/PTR -> I32`。
两者返回 80 B 输出，必须另查 ready；GPU 资源均为借用句柄。过滤数组通过 confined arena 在调用内传递。
`D3D12ReleaseRtAcceleration` 为 `I64 -> I32`，撤销编号；`D3D12RtAccelerationMemory` 为 `I64/I64 -> I64`，按实际资源去重计费。
追踪描述的 snapshotId 位于 32，accelerationId 位于 48；两者均为 I64 注册令牌，不能把 TLAS view 地址当作 AS 令牌。
构建、过滤和追踪资源保留至实际列表槽 fence 完成；未知、撤销或不匹配的令牌在 native 入口拒绝。
生产场景通过 `D3D12CreateRtScene`（`-> I64`）、`D3D12PrepareRtSceneTargets`（`PTR/I64/PTR/PTR/I32/PTR -> I32`）
和 `D3D12ReleaseRtScene`（`I64 -> I32`）管理。64 B 描述携带 worldEpoch、候选几何令牌、liveRevision 和 16 B section 版本数组，
偏移 40 为真实 `frameId`，同一真实帧共享 scene 构建额度；偏移 48、56、60 为 outputs 指针、数量与完整模式。
版本数组、独立 8 B node 目标数组与 48 B 当前输出支持记录由同一 CPU 锁内的快照提供；无可信有限界的要求不按来源段盒排除。
输出支持记录含 node/layer/version、flags/reserved 与局部界六个 float；flags=1 为当前有限界，flags=0 为无界且坐标全零。
layer=-1 表示等待整段当前编译；具备当前冻结来源证明时可导出包含段盒的有限候选界，否则保持无界。当前输出界不签发空间状态 1/2。
目标指针和数量仍位于入口参数中，签名登记于 `MCDRLayout.RT_SCENE_TARGETS_CALL`。
FFM 复制输出 ByteBuffer 当前剩余切片到 confined arena，不修改调用方位置；native 在返回前复制并校验完整清单。
同世界同 liveRevision 的版本与输出必须逐字节相同；完整模式不得降级，错误输入不修改原场景。
confined arena 只在调用内有效；native 完成候选构建、发布与局部失效，内容不变时复用目标查找表。
旧 `D3D12PrepareRtScene`（`PTR/I64/PTR/PTR -> I32`）供独立低层与自检；显式目标 scene 拒绝退回旧入口，生产不以其兜底。
232 B 输出包括两份配对令牌、版本、去重容量，以及 active/candidate 两份 80 B AS 输出；偏移 224 的 coverageFlags bit 0 表示无界 unknown。
成功返回后仍须检查 active.ready；ready 不表示查询空间完整。
scene 自有 AS 令牌拒绝直接 Prepare/Filter/Release；释放 scene 撤销其候选和已发布令牌，资源由实际列表槽退休。
`D3D12GetRtSceneStats` 使用 `MCDRLayout.RT_SCENE_STATS`（`I64/PTR/I32 -> I32`）。
confined arena 分配 112 B 数组并传入数量 14，返回后立即解包为 `RtSceneStats`；字段顺序见
[native RT 文档](../../../MCDR/docs/advanced/rt-frame.md)的 scene 统计表。读取不等待 GPU，也不推进录制或预算。
可选绑定缺失返回 `null`，统计 JSON 写 `scene:null`；不能写成零积压或退回同步 GPU 查询。
ABI 版本握手必须与 native 的 44 匹配，不能将可选绑定规则解释为允许混用旧 ABI DLL。
RT 有限失效体积使用程序化 AABB，并按当前输出界变换；射线库必须提供 `RtInvalidIntersection` 并处理程序化候选。
无界 unknown 使用追踪表 flags bit 1。调用方在来源全集尚无有限输出范围时置此位，native 在 scene 另有无界要求时也置此位，
不得清除调用方已冻结的标记；shader 在非空原查询的 TMin 返回 unknown，不制造有限替代盒或缩短生产射线。
`D3D12RtFrameSetSpatial` 为 `PTR/I64/I64/I64/I64/PTR/I64 -> I32`，签名登记在
`MCDRLayout.RT_FRAME_SET_SPATIAL`；依次传 encoder、frame context、frameId、worldEpoch、liveRevision、空间载荷与字节数。
FFM arena 复制 ByteBuffer 当前剩余切片，native 在返回前再次复制；调用方的 buffer 位置与只读性保持不变。
空间网格和 live versions 必须来自同一池锁快照，冻结发生在当前 G-Buffer 重放之后、追踪之前。
该入口为必需绑定；与缺少此入口的旧 DLL 混用会导致加载失败，不允许静默跳过空间证明。
64 B 表头的 flags 仅允许 0–3：bit 0 是可靠物理界，bit 1 是尚未闭合来源的全局 unknown；reserved 必须为 0。
即使每个格子标为已知，bit 1 仍阻止它们充当完整空间证明。来源的支持、内容、层与生命周期保守清单由
同一 `Coverage.sources` 提供；它不授予空空间、内容依赖或本帧实际可查询资格。
受认证的静态模型/普通 fluid 资源全集及空附加监听器列表可把未观测来源限制到逐格范围，
此时调用方 bit 1 为 0，尚未取得内容或仍有未观测来源的格子继续保持 unknown。
资源注册表或附加监听器变化须推进同一池 revision，不能把不同证明代次的 versions 和网格混合提交。
`D3D12RtFrameBeginGBuffer` 签名为 `PTR/I64/I64/I32/I32/PTR -> I32`，传入 frameNanos 与尺寸，
返回 88 B `mcdr_rt_gbuffer_output`：重放编号、资源代际、时间戳、尺寸、六张借用附件与字节数。
`D3D12CreateRtGBufferProgram` 为 `PTR -> I64`，提交 56 B 配方：MS/PS DXIL 指针/长度、bindings 指针/数量、剔除与多边形模式。
native 复制配方、创建并缓存 PSO/根签名；JVM 持有注册令牌，关闭/替换时经 `D3D12ReleaseRtGBufferProgram`（`I64 -> I32`）撤销。
96 B 批次的首字段是 I64 程序令牌，禁止传入通用 pipeline 指针；在途程序由 native 列表槽保留，释放令牌后仍能重放已经捕获的批次。
源 pass 的成功绘制立即调用 `D3D12RtCaptureGBuffer`，签名 `PTR/I64/I64/PTR -> I64`，
传入旧令牌、frameNanos 和 96 B 批次，返回非零令牌；40 B binding 数组复用 `BINDING_SLOT`。
native 复制常量/表、保留程序并复制精确 view，JVM arena 只在调用内有效。JVM 不再保留批次和绑定 map。
native 分配/清屏并打开 pass 后，`D3D12RtFrameReplayCapturedGBuffer` 以 `PTR/I64/I64/PTR -> I32`
提交捕获令牌、48 B 描述中的重放编号、16-float 投影与相机偏移，批次数和数组指针须为零。
`D3D12RtReleaseGBufferCapture` 为 `PTR/I64 -> I32`，finally 撤销令牌；捕获资源由列表槽 fence 保活。
独立诊断保留 `D3D12RtFrameReplayGBuffer`（`PTR/I64/PTR -> I32`），在调用内借用整组批次。
重放后调用 `D3D12RtFrameEndGBuffer`，签名 `PTR/I64/I64/I32 -> I32`，
传入重放编号和成功标志。只有成功 End 的同列表、同时间戳、同尺寸输入能进入 trace。
trace 描述不携带 guide 指针；五张颜色与独立深度全部归 native，JVM 仅持借用元数据。
整组预检失败可重试，录制中失败必须取消；成功批次不得在同一回放编号上重复提交。
`D3D12RtFrameDenoise` 的签名为 `PTR/I64/PTR/PTR -> I32`，提交 64 B 降噪描述，返回 48 B 输出。
描述携带当前 frameId、历史代际/序号、Plan 代际、显示尺寸与 NRD/SL 相机常量。
native 持有双通道 NRD 输出和显示尺寸 RR 输出，执行 SDK、同帧回退与独立历史确认；
JVM 只发布借用 Long 句柄。独立 SR 通过 `D3D12RtFrameUpscale`，签名为 `PTR/I64/I64/I32/PTR -> I32`，
传入当前 frameId 和后端，返回同样的 48 B 输出。native 持有重调制 PSO、三张 SR 纹理与历史，
复用降噪时复制的相机和历史快照。失败保留本帧 NRD 输出，成功后 native 合光线性重建结果。
`D3D12RtFrameComposite` 签名为 `PTR/I64/I64/PTR/I32/PTR -> I32`，提交当前 frameId、原版颜色和 debugMode；
native 自动选择本帧输入，拥有合光 PSO/输出。48 B 返回结构的 diffuse 为借用纹理，JVM 仅用于上屏或调试覆盖。
`D3D12RtFrameReblur` 保留为独立诊断入口，签名 `PTR/I64/I64 -> I32`。
这些入口都要求同一 encoder 尚未提交的当前帧；本帧重建与合光结束前不得重新 Begin G-Buffer。
完整输入、输出与在途寿命见 [native RT 帧接口](../../../MCDR/docs/advanced/rt-frame.md)。

## 4 失败处理

### 4.1 五类失败，五种处理

| 失败 | 检测点 | 处理 | 用户可见后果 |
| --- | --- | --- | --- |
| 库缺失 / 加载失败 | `extractLibrary()` / `libraryLookup` | 记 `LOAD_FAILURE`，不抛 | 后端整体不可用，报原因 |
| 必需导出缺失 | `skip()` | 抛 → 记 `LOAD_FAILURE` | 同上（新 JVM 配老 DLL 的典型形态） |
| 可选导出缺失 | `skipOptional()` 返回 `null` | 该函数返回哨兵值 | 单个功能报不可用 |
| 布局/版本不匹配 | `verifyABI()` | 抛 → 记 `LOAD_FAILURE` | 同上，日志里有逐字段明细 |
| 调用抛异常 | 各封装的 `catch` | 硬失败接口包 `unexpected`；查询接口返回哨兵 | 一条 WARN + 降级 |

### 4.2 显式失败 vs 静默降级

分界线是"这个调用的失败会不会让后续状态不可信"：

- **硬失败**（抛 `IllegalStateException`）：创建资源/管线/命令列表、销毁、present、`D3D12LastError()` —— 继续跑下去只会积累不一致状态。
- **静默降级**（哨兵值）：所有开关类（DLSS 质量、Reflex 模式、VRS、PIX、HDR）、所有能力查询。理由很实际：这些函数大多在"配置被读取"的时刻调用，那时 DLL 是可选的，抛异常等于让玩家改不了配置。

### 4.3 错误信息长什么样

- 绑定失败：`MCDR export not found: mcdr_dx12CreateSurface`
- 库缺失：`MCDR.dll is neither packaged in the JAR nor present at ... — build the MCDR native project first`
- 版本：`MCDR ABI version mismatch: java=4, native=3`
- 布局：`MCDR ABI layout mismatch (N field(s)); ...` 后面逐行列出
- 尺寸：`MCDR ABI struct size mismatch for mcdr_fg_dispatch: java=92 c=96 — fix the mirror ...`
- 调用：`MCDR native call failed: <原始异常>`
- 业务：`<what> failed: <mcdrLastError()>`

### 4.4 跨边界硬约束

- native 侧**不抛 Java 异常**，C++ 异常也不得穿过 FFM 边界。
- 布尔 / 三态入口一律返回 `int32_t`（1 = 成功，0 = 失败或未启用，-1 = 参数或加载失败），失败详情只从 `mcdrLastError()` 读，不要另开错误通道。
- 需要硬失败时由 JVM 侧封装包成 `IllegalStateException`；native 侧不得感知 JVM 的存在。
- `FunctionDescriptor` 与 `invokeExact` 的类型必须**逐字匹配**：`int32_t` 用 `I32` 并 `as Int`，`int64_t` / `size_t` / 指针用 `I64` / `PTR` 并 `as Long`；写错是运行期 `WrongMethodTypeException`，不是编译错误。

## 5 常见坑（用法约束）

1. **编码**：一律 UTF-8 加显式 NUL；`String.toByteArray()` 不带字符集取的是平台默认编码（中文 Windows 上是 GBK），native 收到的是乱码路径。
2. **字节序**：`MemorySegment.asByteBuffer()` 默认是 **BIG_ENDIAN**，而 x86 是 little；拿到 `ByteBuffer` 立刻 `.order(ByteOrder.nativeOrder())`，否则所有 UBO 里的矩阵都被静默转置。
3. **对齐**：`structLayout` 不插 padding（§1.2）。宁可多写一个具名 `_padN` 并在 C 侧也命名它，也不要指望"反正编译器会补"。
4. **尾部 padding 决定 stride**：拿 `byteSize()` 当 stride 之前，先确认 `EXPECTED_C_SIZES` 那一行和 C 的 `sizeof` 一致。
5. **跨线程 Arena**：`Arena.ofConfined()` 的段只能在本线程用；要跨线程就传值 / 传句柄，不要传段，逐帧缓冲用 `ThreadLocal` 槽。
6. **`invokeExact` 必须逐字匹配**：`int32_t` 参数写 `I32`、调用点写 `as Int`；写成 `I64` 或 `as Long` 会在运行时抛 `WrongMethodTypeException`，而且可能在**第一次调用**才暴露 —— 新封装必须真的调一次做冒烟。
7. **`MemorySegment.NULL` 与 `ofAddress(0)`**：两者等价，比较时用 `.address() == 0L`。
8. **数组索引按绝对位置**：并行录制里"每槽一份"和"每 worker 一份"的数组不是同一种索引，混用只在部分 worker 上炸（§2.4）。
9. **upcall 里不能抛**：逃出去的异常直接杀 JVM（§3.4）。
10. **`reinterpret` 要写长度**：native 返回的裸地址没有可见长度，`getString` / `get` 之前必须给出准确长度，否则是越界读。
11. **不要凭名字猜语义**：`mcdr_dx12CreateDevice(char* buf, int32_t cap)` 名字里带 Create，实际返回 `int32_t` 并只往 `buf` 里写设备摘要（两阶段字符串），设备的创建是这个调用的副作用 —— 看签名，不要看名字。
12. **导出名不统一**：绝大多数是 `mcdr_dx12*`，但命令编码器那条是 `MCDR_D3D12_create_command_encoder`（大写 + 下划线），抄别的绑定行时别照抄命名。

## 6 文件导航

相对 `Illusion\src\main\kotlin\io\github\dxegp\`：

| 文件 | 职责 |
| --- | --- |
| `d3d12\extern\MCDR_ABI.kt` | 唯一入口：解包 DLL、绑定 252 个方法句柄、封装每个导出、维护 `isAvailable()` |
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

## 7 文档维护

- **本文的数字来自 `_port\abi_check.py` 的实际输出**。改了导出数量、结构体字段或版本号，先跑一遍脚本，再同步开头的表格与 §1.6 的步骤。
- **判定标准是"代码里能读到的"**。凡是头文件注释没写、Kotlin 侧也没实现的，写"代码中未明确"，不要补合理推测。
- **不要在这里复制源码**。要引代码就引签名与一行关键逻辑，实现细节以源码注释为准 —— 两处都写必然会漂移，而漂移的注释比没有注释更贵。
- **只写当下**。不写设计史与方案对比；必须遵守的写法写成祈使句，已经发现的缺陷单独列成警告清单。
- **新增坑位就加进 §5**，并尽量给出"触发条件 + 失败形式"，因为这一节的价值在于让人一眼认出自己正踩的是哪一条。
