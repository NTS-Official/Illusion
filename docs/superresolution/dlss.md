# DLSS / DLAA 专项

DLSS 是本工程里唯一「不是我们自己实现」的超分后端：它由 NVIDIA 的 **NGX** 运行时（`nvngx_dlss.dll`）
做推理，我们只负责**把每帧的输入按契约摆好、把输出接回渲染管线**。DLAA 是同一套 NGX feature 的
另一个档位——渲染比例 100%、只做时间性抗锯齿、不做放大。

单独成篇的原因：这条路径有自己的一套状态机、自己的失败语义、以及大量「看起来可以统一、
改了就坏」的约定。总览见 [`README.md`](README.md)。

---

## 1. 角色分工

```
视频设置：DLSS 开关 / 质量档位 / preset / 抗锯齿=DLAA
        │
        ▼
SuperResolution（门面）── DLSS 开关变化时只做「认领后端 + 让位」
        │
        ▼
DeepLearningSuperSampling（本篇主角，dxrmc/sr/dlss/DLSS.kt）
  ├─ 开关 / 档位 / preset 的持久化与推送
  ├─ 每帧数据面：速度 pass、深度快照、相机矩阵、jitter
  ├─ 抗锯齿互斥与 DLAA 的锁定/释放
  └─ 输出纹理交接（经 DLSSOutput）
        │  FFM
        ▼
MCDR_ABI（mcdr_dx12EvaluateDlss / SetDlssEnabled / SetDlssQuality /
          SetDlssRenderPreset / SnapshotDlssDepth / SetDlssOutput / QueryDlss）
        ▼
MCDR\src\advanced\d3d12_dlss.cpp ── Streamline（sl.interposer + sl.dlss_g）── NGX
```

`DeepLearningSuperSampling` 只在**渲染线程**上被调用；它的 native 内存（参数包、状态串）
每个会话只分配一次，复用同一个 `MemorySegment`，不每帧新建 `Arena`。

---

## 2. 配置项与返回值语义

| `options.txt` 键 | 取值 | 默认 | 说明 |
| --- | --- | --- | --- |
| `dxrMcDlss` | `true` / `false`（也接受 `1` / `0`） | `false` | DLSS 总开关 |
| `dxrMcDlssQuality` | 档位名或下标 | `2`（balanced） | 下标**就是**送给 native 的数字，不要重排 |
| `dxrMcDlssPreset` | 名字或 NGX 数字（`0` / `10` / `11` / `12` / `13`） | `0` | 模型 preset |
| `dxrMcAaMode` | `3` = DLAA | `0` | DLAA 走抗锯齿选项，不走 DLSS 开关 |

这几条 ABI 的返回值语义是**刻意不对称**的，误读会产生假警报：

| 函数 | 成功返回 | 其它 |
| --- | --- | --- |
| `mcdr_dx12SetDlssEnabled` | 调用后的开关状态（`1` 开 / `0` 关） | `0` **不是**失败：关掉 DLSS 时返回 0 是正常的；`-1` 参数非法；`Int.MIN_VALUE` = 本地 DLL 没有该导出 |
| `mcdr_dx12SetDlssQuality` | **传进去的档位号**（`0` = Performance、`1` = Balanced、`2` = Quality、`3` = DLAA） | `0` 表示「Performance 已生效」，**不是**「DLSS 不能跑」；`-1` 越界 |
| `mcdr_dx12SetDlssRenderPreset` | 记录下来的 preset（`default` 就是 `0`） | `-1` 越界 |
| `mcdr_dx12DlssAvailable` / `mcdr_dx12QueryDlss` | `1` = 可用 | `0` / `-1` / `Int.MIN_VALUE` 见第 6 节 |

「这台机器能不能跑 DLSS」只由 `mcdr_dx12QueryDlss` 回答；`SetDlssEnabled` / `SetDlssQuality`
回答的是「开关/档位现在是什么」。把后者当能力探测，就会在用户**关闭** DLSS 或选择
Performance 时打出一条「DLSS 无法运行」的假警告。

---

## 3. 质量档位

| 下标 | 名字 | 渲染比例（`percentForTier`） | 说明 |
| --- | --- | --- | --- |
| 0 | `ultra_performance` | 34 | 不能是 33：NGX 的 3.0x 要求 render ≥ target/3；2560 宽时 33% 给 844×440，低于 853.33，NGX 每帧报 `Dynamic scaling error … outside of Min … Max` 后交回空纹理 → 世界全黑、GUI 正常 |
| 1 | `performance` | 50 | NVIDIA 官方值 |
| 2 | `balanced` | 59 | 官方 58.14%；58 只靠 NGX 容差，所以取 59。默认档 |
| 3 | `quality` | 67 | 官方 66.6% |
| — | DLAA | 100 | 不缩放；对应 native 的 `NVSDK_NGX_PerfQuality_Value_DLAA`（= 3），由抗锯齿选项驱动，不占用户档位 |

为什么比例必须精确：Streamline 会在 `dlssBeginEvent` 里算「最优输入尺寸」（日志形如
`Created DLSSContext feature (1280,667)(optimal) -> (2560,1334)`），我们分配的输入缓冲必须正好是那个尺寸。
差一点不会崩，但 DLSS 会做两次拉伸，且 jitter 的像素幅度按错误宽度换算——静止画面上会留下残余亚像素抖动。

档位变化时 `pushQuality` 会做三件事：推档位、`resetOutputSampleSchedule()`（新档位意味着 NGX 重建 feature）、
`bindRenderScaleToDlss()`（把渲染比例绑到该档位的标称值）。

`bindRenderScaleToDlss` 用 `D3D12RenderScale.option().set(percent)` 而不是 `setPercent`：
后者只写内部字段，视频设置里那一行不会动，用户看到的值与实际生效值不一致。

---

## 4. Preset（模型选择）

| UI 顺序 | 名字 | 送给 native 的值 | 语义 |
| --- | --- | --- | --- |
| 0 | `default` | 0 | 不设 hint，由 NGX 按档位自选（默认） |
| 1 | `j` | 10 | 与 K 同代；官方说明「鬼影更少、闪烁更多」 |
| 2 | `k` | 11 | 官方说明「A/B 里画质最好」；DLAA / Balanced / Quality 的默认 |
| 3 | `l` | 12 | Ultra Performance 的官方默认 |
| 4 | `m` | 13 | Performance 的官方默认 |

- 数值是 NGX 的 `NVSDK_NGX_DLSS_Hint_Render_Preset`，**不是**本工程自定义编号；
  `5`（E）/ `6`（F）已不再支持，`7`～`9`（G/H/I）保留，所以没有放进选择器。
- preset 是 `CreateFeature` 的输入：改一次就重建 feature（世界层重建一帧，和改档位同量级），
  tooltip 里写明了这一点。
- 保存时若不写这一行，按钮每次启动都会回到 `default`，而且日志里没有任何痕迹——这是最容易漏的一处持久化。

---

## 5. 每帧：输入怎么摆

### 5.1 `DLSS_INPUT` 载荷

`MCDRLayout.DLSS_INPUT` 与 `MCDR\include\mcdr_abi.h` 的同名结构逐字段对应（C 侧 `sizeof = 112`）：

| 字段 | 值 / 来源 |
| --- | --- |
| `renderWidth` / `renderHeight` | 世界层颜色附件的真实纹理尺寸（= 窗口 × 渲染比例） |
| `targetWidth` / `targetHeight` | 输出目标（GUI 目标）的真实尺寸 = 窗口尺寸 |
| `jitterOffsetX` / `jitterOffsetY` | `(Jx, -Jy)`：唯一 jitter 源是 `D3D12Temporal` 的 Halton(2,3)，上报时只翻转 y |
| `mvScaleX` / `mvScaleY` | `MV_SCALE = 1.0`（MV 已是像素空间） |
| `preExposure` | `1.0`（颜色缓冲已是显示单位，没有曝光纹理） |
| `exposureScale` | `1.0` |
| `exposureMode` | `0`（无曝光纹理） |
| `alphaUpscaling` | `0` |
| `nearZ` / `farZ` | 载荷里填 `0.0`（JVM 侧拿不到关卡 `Projection` 对象）；native 只用它们写自己的诊断行与变化检测，不作为 NGX 参数 |
| `depthInverted` | `1`：MC 是 reverse-Z，native 要求 1 |
| `reset` | `resetReason(...)` 非空时置 1 |
| `depthFormatOrdinal` | 主目标深度附件的 `GpuFormat.ordinal`（诊断） |
| `pInColor` / `pInDepth` / `pInMotionVectors` / `pInOutput` | 四张纹理的 `ID3D12Resource*` 句柄 |

### 5.2 三个数据依赖

| 依赖 | 生产者 | 缺了会怎样 |
| --- | --- | --- |
| 亚像素 jitter | `D3D12Temporal`（注入到关卡投影） | 没有子像素样本，时间性累积退化，DLSS 只做空间放大 |
| 运动矢量 | `D3D12Velocity` 的速度 pass | native 在 `pInMotionVectors == nullptr` 时立刻返回 0，DLSS 一帧都不评价 |
| 本帧世界深度 | 主目标深度 + native 私有 D32 快照 | 用被清过 0 的深度，重投影全错（移动时拖影） |

这三样都由**构建期属性**（`dxrmc.temporal` / `dxrmc.temporal.velocity`，默认 `false`）只做**初始播种**，
运行期由 `D3D12Temporal.enabled` / `D3D12Velocity.enabled` 控制。选中 DLSS 时必须把两者都打开，
否则「选了 DLSS 却一帧不跑」——而且不需要任何 `-P` 参数。

速度通道的「自我关闭」是一票否决：`D3D12Velocity.record` 里任何一步失败都会 `enabled = false` 并把
失败步骤记成不再重试，之后只把 `enabled` 置回 true 撤不回来。所以 DLSS 侧每帧检查
`D3D12Velocity.isLatched()`，按 1 秒限速调 `forceEnable()` 清闩锁（`VELOCITY_RETRY_INTERVAL_NANOS`）。
**不能**用「每会话一次」的额度制：首帧就闩锁时会形成死锁（没 MV → 不评价 → 不批额度 → 闩锁永不清）。

### 5.3 深度快照

颜色只有到「世界层画完、GUI 未画」那一刻才完整，但深度在那之前已经被清过。JVM 侧没有任何**单一**
挂点能同时满足两者，所以拆成两步：

1. main pass 的 RETURN（世界几何刚画完、所有清深度之前）：`mcdr_dx12SnapshotDlssDepth(ctx, depth)`，
   native 把深度拷进自己的 D32 快照，并**自己负责把调用方深度状态恢复**；
2. evaluate 时（`GuiRenderer.render` 之前）：优先用快照，而不是载荷里的 `pInDepth`。

快照与 `pInDepth` 取自**同一个表达式**（`mainRenderTarget().depthTexture`），否则「快照」和「输入」不是一回事。

旧 DLL 没有这个导出时返回 `Int.MIN_VALUE`，JVM 静默退回被清过的深度，只记一行说明——
不会因此拒绝运行，只是画质降级。

### 5.4 相机常量（`DLSS_CONSTANTS`）

`pushDlssConstants` 每帧给 native 的 Streamline `sl::Constants` 填：`viewProjection`、`prevViewProjection`、
`projection`、jitter、`mvecScale`、相机基（right/up/fwd）与位置、near/far、FOV、aspect 以及几个布尔。

三个硬约束：

- **视图矩阵必须含相机平移**：`worldSpaceView = V_rot * T(-camPos)`，平移列 `[12..14]`
  等于 `-(row_r(V_rot) · camPos)`。世界层是相机相对空间，原始视图只有旋转；少了平移，静止正常、一走就糊。
- **相机基是矩阵的行**（列主序下 `(row r, col c)` 在 `r + c*4`）。按行主序取会得到一组被转置的基。
- **`mvecScale` 要归一化**（`1/renderWidth`、`1/renderHeight`），与载荷里的 `mvScaleX = 1.0` 是两个不同参数。

FOV 从投影矩阵反算（`m11 = proj[5]`，`2*atan(1/m11)`），不读 `options.fov()` 的标称值：
标称值漏掉疾跑 / 飞行 / 水下 / 药水 / 望远镜等修饰符，静止看不出来，一运动就是重投影错位。
near/far 从 `m[10] = A`、`m[14] = B` 解出（`n = B/(A-1)`、`f = B/(A+1)`），并按大小修正——
reverse-Z 的投影在构造时 near/far 是互换的。

---

## 6. NGX 可用性与降级

### 6.1 探测

| 入口 | 用途 |
| --- | --- |
| `D3D12DlssAvailable()` | 快速能力位（`isAvailable()` 用它判断适配器 + DLL 是否就绪） |
| `D3D12QueryDlss(note, 256)` | 返回 `1` 可用；`Int.MIN_VALUE` = DLL 没有该导出；`note` 是 native 写的 NUL 结尾 UTF-8 原因 |
| `statusLine()` | 设置界面那一行状态；内容**完全**来自 native，JVM 不再自己推导 |

`ensureAvailable()` 每次进入 DLSS 会话只探一次（`availabilityProbed`），把 `result` 与 native 文本
拼进 `ngxSummary`，可用时 INFO、不可用时 WARN 一行。这样「不能跑」的原因既在日志里、也在界面上，且只有一份事实。

### 6.2 停滞（stalled）

`isStalled() = consecutiveEvaluateFailures >= 3`。典型成因：驱动里的 NGX 比随包的
`nvngx_dlss.dll` 旧，插件起不来（日志里能看到 `NGXInitValidateSnippets` 之类的错误）。

停滞状态的危险不是「没超分」，而是**jitter 还在注入却没人消费**——整幅画面每帧 ±0.5 像素地晃，
用户看到的就是「DLSS 抖动」。所以 `D3D12Temporal.hasJitterConsumer()` 在「DLSS 开启且未停滞」之前
不注入 jitter；TAA（含 DLAA）是另一个合法消费者。

### 6.3 依赖的 DLL 与发行版差异

- **DLSS / Streamline 的 DLL 不进发行包**（`nvngx_*.dll`、`sl.*.dll`）：许可证不允许再分发。
  开发环境由 Gradle 任务把它们复制到 `run/dxrmc/`（与 `MCDR.dll` 同目录），所以开发客户端能看到
  DLSS 可用；发行版则如实报告不可用。
- 反过来，**XeSS 与 Microsoft 的运行库（`D3D12Core.dll`、`dxcompiler.dll`、`dxil.dll`）随 JAR 打包**
  并在启动时解到 `dxrmc/`——那是图形管线的硬依赖，不是「某个 SDK 缺失」级别的问题。
- 因此「DLSS 不可用」在发行版上是**预期状态**，不是 bug。界面与日志都必须照实说，
  不能因为配置里写着 `dlss` 就假装它在跑。

### 6.4 旧 DLL 兼容桥

旧 DLL 上没有 `mcdr_dx12SetDlssEnabled` 这个导出，只认「抗锯齿模式 == 4」，所以保留了一段桥：

- `LEGACY_DLSS_AA_MODE = 4`，直接调 `D3D12SetAntiAliasing`（绕过 `D3D12AntiAliasing.validateValue`，
  因为公开取值域里已经没有 4）；
- 关掉时恢复 SMAA（若原来是 SMAA）否则 OFF；TAA / DLAA 在旧 DLL 上本来就会被降级成 OFF；
- **旧 DLL 上 SMAA 与 DLSS 不能共存**（`aaMode == 4` 时 `applySmaaToTexture` 不会跑）；
- 启动时两个 TAIL 注入（`Options.load`）的执行顺序不确定，谁最后跑就可能把 `aaMode == 4` 抹掉，
  所以每帧在 `applyIfEnabled` 里比对一次 `D3D12AntiAliasing.current()`，不一致就补推；
  稳态下这条检查零次 native 调用。

这套桥在 native 全面提供 `mcdr_dx12SetDlssEnabled` 之后应当删除。

---

## 7. 抗锯齿互斥与 DLAA

### 7.1 互斥

DLSS 自带时间性抗锯齿，先跑我们自己的 AA 只会把输入糊掉。所以 DLSS 开启期间抗锯齿被锁在 OFF
并置灰（`setDlssLock` / `isLockedByDlss`），退出时恢复用户原值。

`reconcileAntiAliasing()` 做三件事，且有两条**必须**遵守的细节：

1. `syncDlaaFromAntiAliasing()`：只在「进入/离开 DLAA」的跳变上动作；
2. `forceDlssGate(dlaaActive || OPTION.get())`：**每帧无条件推一次**。不能缓存「上次推过什么」——
   同一次设置回调里可能先开又关，缓存还认为一致，闸门就永久关着；也不能只在跳变时推，
   因为 `push(false)` 关掉闸门后没有任何东西会重新打开它；
3. 非 DLAA 时把 DLSS 开关同步给抗锯齿锁。

`reconcileAntiAliasingEveryFrame()` 挂在「清深度之前」而不是 `applyIfEnabled()`：因为「DLSS 关了要恢复
抗锯齿」恰好发生在 `applyIfEnabled` 不被调用的那些帧。它是幂等的，稳态只读一个 volatile。

### 7.2 DLAA

DLAA = 「DLSS 在原生分辨率」：`renderScale == 100%`、NGX 档位固定为 DLAA（`3`）。

- 进入：`D3D12RenderScale.setDlaaLock(true)` 把比例钉到 100%（锁定在 `setPercent` 里生效，
  比模式夹取更早——否则 DLSS 模式的 25..100 会把 100 夹走），打开闸门，
  **只在跳变时**推一次 `pushForcedQuality(3)`。
  每帧推是不行的：native 用「这一轮档位被接受」当基线，会在下次尺寸重建时自愈回 DLAA，
  反复推只会每帧触发重建。
- 离开：推回用户自己的档位，再 `bindRenderScaleToDlss()`。少了这一步，DLAA 的 100% 会留在
  当前模式的记忆键里，退出后比例停在 100%。
- DLAA 期间输出目标：`targetForFrame()` 返回 null（没有独立的窗口尺寸目标），
  输出回落到主目标颜色本身；native 把结果重定向到交接纹理（同尺寸同格式），再拷回来。
  这条路径上 `prepareForGui` 不做世界层 blit。

### 7.3 让位（standDown）

超分后端从 DLSS 切到 FSR/XeSS 时，门面调 `standDown(reason)`：只关开关层（推 `false`、释放比例模式、
解锁抗锯齿），**不动**档位与 preset 偏好。必须排到帧边界，因为会改渲染比例。

只关 native 闸门是不够的：`reconcileAntiAliasing` 每帧会把闸门对齐回 `OPTION.get()`，
如果不关开关，DLSS 和 FSR/XeSS 会同时往同一张颜色纹理里写。

---

## 8. 输出纹理契约（`DLSSOutput`）

### 8.1 为什么必须由 JVM 创建

实测结论：**native 侧用 `CreateCommittedResource` 创建的纹理存不住 NGX 的写入，而模块
`createTexture` 创建的可以**。所以 DLSS 的输出纹理必须由 JVM 建、再通过 `mcdr_dx12SetDlssOutput(handle)`
交给 native；native 把 `pInOutput` 指到它（`s.externalOutput` 分支优先于输入包里的 `pInOutput`），
写完再拷回 GUI 目标。

### 8.2 纹理参数

| 项 | 值 | 为什么 |
| --- | --- | --- |
| 格式 | `GpuFormat.RGBA8_UNORM`（native 枚举 ordinal `28` = `DXGI_FORMAT_R8G8B8A8_UNORM`） | 必须与 `pInOutput` 一致，否则 NGX 的输入/输出格式对不上 |
| 用途位 | `USAGE_STORAGE(32) \| RENDER_ATTACHMENT \| COPY_DST \| COPY_SRC` | 位 32 是「语义位」：native 的 `textureFlags()` 只在 `usage & 32` 时加 `ALLOW_UNORDERED_ACCESS`，而 NGX 只写这种资源；COPY 位供读回/转储 |
| 尺寸 | 目标（窗口）尺寸，与 `pInOutput` 同一档 | 超分输出就是呈现尺寸 |
| 生命周期 | 尺寸变化时 `release()` 后重建；退出时 `release()` | — |

### 8.3 交接与释放顺序（唯一不能省的一步）

```
release():
  1) 若已交接 ⇒ D3D12SetDlssOutput(0L)   // 先撤销 native 的引用
  2) existing.close()                    // 再关纹理
```

反过来（先 close 再撤销）会留下一个窗口：native 的 `s.externalOutput` 还指着那张已经排进
`ID3D12DestroyResource` 队列的资源，下一次 evaluate 写穿它。症状是**拖动渲染比例（resize → release →
recreate）后进程直接消失、没有任何 Java 异常**，与「设备移除 / GPU 挂起」一致。这也解释了为什么
只有开交接模式才会崩。

### 8.4 失败即降级

设备未就绪、尺寸非法、没有导出（`Int.MIN_VALUE`）、native 拒绝（`0` / `-1`）——任何一种都只是
「本帧不交接」，native 继续用它自己的私有 UAV，行为等同于没有这个特性。所有失败路径都有
一次性 note，且只在状态变化时打一行。

交接与释放的硬约束（违反的后果是黑屏、悬空指针或静默失效）：

- **先撤销 native 的引用、再 `close()`**：`D3D12SetDlssOutput(0L)` → `existing.close()`；顺序反了 native
  手里就是悬空指针，拖动渲染比例后进程无异常消失。
- **jitter 一帧只推进一次**（按帧戳去重），上报值固定 `(+Jx, -Jy)`；一帧内推进多次会让几何与 DLSS
  拿到不同相位。
- **改渲染比例、进出 DLAA 一律走 `Minecraft.schedule`**，不要在渲染线程里就地 `GameRenderer.resize`
  （`Minecraft.execute` 在渲染线程会内联执行），否则会关掉本帧还在用的深度纹理。
- **判断 DLSS 有没有在干活看 evaluate 返回值与日志**，不要读回输出纹理做黑帧熔断——读回内容不可信。

---

## 9. 文件导航

路径以 `Illusion\src\main\kotlin\dev\nts\expdxr\` 为起点（mixin 在 `Illusion\src\main\java\` 下，已标注）。

| 文件 | 职责 |
| --- | --- |
| `dxrmc/sr/dlss/DLSS.kt` | 本篇主体：开关/档位/preset、数据面、深度快照、相机常量、DLAA、抗锯齿互斥、旧 DLL 兼容桥 |
| `dxrmc/sr/dlss/DLSSOutput.kt` | 输出纹理的创建、交接、释放顺序与失败降级 |
| `dxrmc/sr/SuperResolution.kt` | 门面：DLSS 与 FSR/XeSS 的分流、让位、后端认领 |
| `dxrmc/sr/FrameGenerationConstants.kt` | 帧生成常量（与 DLSS 常量是两条独立通路） |
| `d3d12/extern/MCDR_ABI.kt` | `mcdr_dx12*Dlss*` 的 FFM 绑定与返回值语义（`skipOptional` → `Int.MIN_VALUE`） |
| `d3d12/render/advanced/taa/D3D12Temporal.kt` | Halton(2,3) jitter、消费者判定、投影/相机捕获 |
| `d3d12/render/advanced/taa/D3D12Velocity.kt` | 速度 pass、闩锁与 `forceEnable` |
| `d3d12/render/advanced/vrs/D3D12RenderScale.kt` | 渲染比例两套键、DLAA 锁、帧边界重建 |
| `d3d12/render/advanced/smaa/D3D12GuiSplit.kt` | `pInOutput` 来源（`targetForFrame` / `ensureTargetForDlss`）、`markWorldLayerDone`、hudless |
| `d3d12/render/advanced/D3D12AntiAliasing.kt` | 抗锯齿取值域与 DLSS 锁 |
| `mixin/render/GameRendererSmaaWorldMixin.java` | 清深度之前（记账）、`GuiRenderer.render` 之前（TAA→SMAA→SR→prepareForGui） |
| `mixin/render/LevelRendererMainPassDepthMixin.java` | main pass RETURN：速度 pass、深度快照 |

native 侧（本仓库 `MCDR\`）：`MCDR\src\advanced\d3d12_dlss.cpp`、
`MCDR\include\mcdr_abi.h`（`mcdr_dlss_input` / `mcdr_dlss_constants` 布局）。

---

## 10. 文档维护

- 本文对应源码：`Illusion\src\main\kotlin\dev\nts\expdxr\dxrmc\sr\dlss\`（`DLSS.kt`、`DLSSOutput.kt`），
  以及上表列出的 jitter / 速度 / 渲染比例 / GUI 拆分 / mixin 挂点。
- 改 DLSS 这条路时最容易破坏的四件事：**返回值语义**（第 2 节）、**比例必须与档位一致**（第 3 节）、
  **输出纹理的创建方与释放顺序**（第 8 节）、**帧内顺序**（总览第 3 节）。
  任何一条改了，本文与 [`README.md`](README.md) 都要同步。
- 文档与代码冲突时以代码为准；代码里没写清的地方保留「代码中未明确」，不要替它补脑。
