# 超分辨率（Super Resolution）总览

超分（super resolution / upscaling）在本工程里只指一件事：**世界层用比窗口更少的像素着色，
再由一个后端把结果升采样（upscale）到窗口尺寸**；那张窗口尺寸的图随后与 GUI 合成、最终呈现。

这份文档讲三个问题：这套抽象的结构、每帧的数据怎么流动、以及**改动时不能破坏的约束**。
DLSS 的专项细节（NGX 初始化、preset、输出纹理契约）见 [`dlss.md`](dlss.md)。

> 建议阅读顺序：第 1～2 节建立概念，第 3 节看一帧的时序，第 4～6 节按你关心的后端或掩码继续，
> 第 7～8 节是动手前的交互关系与硬约束。

---

## 1. 三个尺寸、一个比例

超分之所以容易出错，是因为「分辨率」在这条链路上同时存在三份，而且三者可以互不相等。

| 名字 | 载荷字段 | 含义 | 由谁决定 |
| --- | --- | --- | --- |
| 世界层渲染尺寸 | `renderWidth` / `renderHeight` | 主渲染目标**颜色附件的真实像素尺寸**，世界几何就在这个分辨率上着色 | 窗口尺寸 × `dxrMcRenderScale` |
| 呈现目标尺寸 | `displayWidth` / `displayHeight`（DLSS 侧叫 `targetWidth` / `targetHeight`） | 窗口尺寸的那张目标（界面目标 / DLSS 的 `pInOutput`），超分的输出尺寸 | 窗口尺寸 |
| 渲染比例 | `percent` | 上面两者的比值，百分比整数 | 视频设置里的滑条，或被质量档位绑定 |

- 界面（GUI）**永远**在窗口尺寸的目标里 1:1 渲染，不参与缩放；世界层画在缩放后的主渲染目标上，
  再由一次带线性采样的 texture→texture blit 铺满界面目标（`D3D12GUISplit`）。
- 超分做的事就是替代这一次「线性采样 blit」：把低分辨率的世界层重建到窗口尺寸，
  同时消掉锯齿与闪烁。所以超分**成功时**必须跳过那次线性 blit，否则结果会被盖掉。
- 100% 时没有超分可做：输入与输出是同一张纹理，DLSS/NGX 直接拒绝，FSR/XeSS 也没有独立的输出目标。
  代码对这个状态有两条处理路径（见第 7 节）。

为什么存在：着色成本大致与像素数成正比。把边长降到 59%，像素数只剩约 35%，
再用时间信息把细节重建回来——这是「分辨率不够但帧率不够更难受」时的标准取舍。

### 术语

| 术语 | 在本工程里的含义 |
| --- | --- |
| SR / 超分 | 本子系统，`dxrmc.sr` 包；不包含帧生成 |
| upscale / 升采样 | 把渲染尺寸的图放大到呈现尺寸。注意 FSR/XeSS/DLSS 都是**时间性**的（吃上一帧历史） |
| jitter | 亚像素投影抖动，逐帧给相机投影加一个 `[-0.5, +0.5)` 像素的偏移，唯一来源是 `D3D12Temporal` 的 Halton(2,3)，16 相位 |
| MV | motion vector，屏幕空间像素位移，由 `D3D12Velocity` 的速度 pass 产出 |
| reactive / responsive mask | 「这一块像素历史不可信」的掩码（半透明、粒子等），本工程用 OIT 透射率重建 |
| DLAA | DLSS 的 anti-aliasing 模式：渲染比例钉在 100%，只做时间性抗锯齿不做放大 |
| hudless | 帧生成要求「呈现的世界层不含 UI」，UI 由 FG 后端单独合成；这会改变超分输出的去向 |
| 线性 blit | 超分失败/未启用时把世界层拉伸到窗口的那次采样，画质上的兜底路径 |

---

## 2. 两条实现路径

`SuperResolution` 是门面（facade），但它**不是**所有后端的统一实现：DLSS 原样委托给
`DeepLearningSuperSampling`，FSR / XeSS 走一条完全不同的、以 `mcdr_sr_dispatch` 载荷为中心的路径。

```
              options.txt: dxrMcSrBackend / dxrMcDlss
              视频设置：超分后端、质量档位、掩码、锐化
                              │
                              ▼
                 SuperResolution（JVM 门面，本目录）
        ┌─────────────────────┼──────────────────────┬─────────────┐
     DLSS│               FSR / XeSS                 │ NONE        │
        ▼                     ▼                     ▼             ▼
DeepLearningSuperSampling   打包 SR_DISPATCH      releaseIfCreated()
（状态机、NGX、Streamline、 （28 个字段、直接调    （释放 native 上下文、
  DLAA）                      mcdr_dx12SrEvaluate）  还回数据面）
        │                     │
        ▼                     ▼
 mcdr_dx12EvaluateDlss   mcdr_dx12SrEvaluate
        │                     │
        ▼                     ▼
 MCDR\src\advanced\      MCDR\src\advanced\
   d3d12_dlss.cpp          d3d12_fsr.cpp / d3d12_xess.cpp
```

两条路径的差别不是风格问题，而是**状态归属**不同：

| | DLSS（含 DLAA） | FSR / XeSS |
| --- | --- | --- |
| 每帧入口 | `DeepLearningSuperSampling.applyIfEnabled()` | `SuperResolution.evaluateOther()` |
| 载荷 | `MCDRLayout.DLSS_INPUT`，C 侧 `sizeof = 112` | `MCDRLayout.SR_DISPATCH`，28 个字段，JVM 侧缓存偏移 |
| 上下文 | NGX feature，由 native / Streamline 持有；JVM 还负责 preset、quality、输出纹理交接 | native 自己管 provider，JVM 只 Create / Evaluate / Destroy |
| 输出 | 直接写进窗口尺寸的 GUI 目标（`pInOutput`），或 JVM 交接的 UAV 纹理 | 写进 JVM 提供的 `USAGE_STORAGE` 纹理，JVM 再 blit 进 GUI 目标 |
| 掩码 | NGX 在这条路径上**不吃**掩码（能力面 `takesMasks == 0`） | FSR 吃 reactive、XeSS 吃 responsive |
| 历史/复位 | JVM 维护 `resetReason`（首帧、换世界、尺寸、比例、窗口） | `createKey` + `percent` 组合成 `frameKey`，变了就 `reset` |
| 失败表现 | 跳过本帧，世界层继续走线性 blit | 跳过本帧并记一条「did not run this frame: 原因」 |

门面刻意不做「顺手统一」：把 DLSS 那条链路的载荷翻译成 `SR_DISPATCH` 或反过来，收益为零而破坏面极大。

---

## 3. 一帧里的数据流

超分不是「一个 pass」，而是分散在三个时刻、由三个 mixin 挂点驱动的数据面。顺序是硬约束。

```
① 帧图 main pass 的 RETURN（LevelRendererMainPassDepthMixin）
   ├─ D3D12TAA.captureTemporalData()                    ← TAA 自己的数据面
   ├─ DeepLearningSuperSampling.captureMotionVectors()  ← 录速度 pass（此刻深度还有内容）
   ├─ DeepLearningSuperSampling.captureDepthSnapshot()  ← 把本帧世界深度拷进 native 私有 D32
   └─ D3D12SrMask.captureTransmittance()                ← 记住 OIT 透射率小波系数附件

② GameRenderer.render：clearDepthTexture 之前（INVOKE，shift = BEFORE）
   └─ backend == DLSS 时：reconcileAntiAliasingEveryFrame() + pclFrameBegin()

③ GameRenderer.render：GuiRenderer.render() 之前（INVOKE）
   ├─ D3D12TAA.applyIfEnabled()
   ├─ D3D12SMAAWorld.applyIfEnabled()                   ← 先在世界分辨率上做 AA
   ├─ SuperResolution.applyIfEnabled()                  ← 超分：打包 + evaluate + blit / 交接
   ├─ DeepLearningSuperSampling.pclRenderSubmitStart()  ← Reflex 打点
   └─ D3D12GUISplit.prepareForGui()                     ← 世界层搬进 GUI 目标（超分成功则跳过）
```

| 时刻 | 为什么必须是这里 |
| --- | --- |
| ① main pass 的 RETURN | 世界几何刚画完，**深度里还有本帧内容**，且排在所有 `clearDepthTexture` 之前。26.2 的关卡渲染是帧图，`LevelRenderer.render` 只负责 addPass；主目标深度会被清三次（always_on_top 的 pass 体、`GameRenderer.renderLevel`、`GameRenderer.render`）。挂在 `GameRenderer.render` 的清深度之前**已经太晚**：实测那里深度 `mean=0.0`，速度 pass 会写出整张 0，DLSS 于是把移动画面当静止累积（「只在移动时出现、只在 DLSS 开启时出现」的拖影）。`addAlwaysOnTopPass` 也不能用：它只在 `hasAnyAlwaysOnTop()` 为真时才进帧图。 |
| ③ 在 SMAA 之后 | 世界层颜色只有到这一刻才完整。更早（清深度那一刻）主目标颜色还是清屏色，喂给超分就是「整片米白」。 |
| ③ 在 `prepareForGui` 之前 | 超分要把升采样结果放进 GUI 目标（或让 `prepareForGui` 跳过 blit），顺序反了会被线性采样覆盖。 |

两条路径在这一步之后的行动不同：

- **DLSS**：`markWorldLayerDone()` 让 `prepareForGui` 跳过世界层 blit（前提是本帧交接成功且 native 报
  evaluate 成功）；DLAA 或 `renderScale == 100%` 时输出就是主目标颜色本身。
- **FSR / XeSS**：`mcdr_dx12SrEvaluate` 成功后，若 `D3D12GUISplit.isHudlessArmed()` 为真则
  `markWorldLayer(output)`（这张 UAV 纹理就是呈现源），否则 `recordTextureBlit(output, guiColor)`
  再 `markWorldLayerDone()`。

---

## 4. 后端选择与调度

### 4.1 枚举与持久化

`dev.nts.expdxr.IllusionConfig` 里的 `SrBackend`（`id` 与 `mcdr_abi.h` 的 `MCDR_SR_BACKEND_*` 逐值对应）：

| 枚举 | id | 含义 |
| --- | --- | --- |
| `NONE` | 0 | 不超分（等价于「关掉超分」） |
| `DLSS` | 1 | NVIDIA DLSS / DLAA，委托给 `DeepLearningSuperSampling` |
| `FSR` | 2 | AMD FidelityFX Super Resolution |
| `XESS` | 3 | Intel XeSS |

`SuperResolution` 自己写在 `options.txt` 的键：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `dxrMcSrBackend` | 名字 | `none` / `dlss` / `fsr` / `xess`；无法识别一律落到 `NONE`（**绝不**静默选 DLSS） |
| `dxrMcSrSharpness` | 0..100 | 打包时除以 100 变成 `0.0..1.0` |
| `dxrMcSrFsrQuality` | 名字或下标 | 5 档，见 4.4 |
| `dxrMcSrXessQuality` | 名字或下标 | 同上 |
| `dxrMcSrMask` | 布尔 | 掩码开关的**覆盖值**；未设置时跟随 TOML `[sr] mask` |

UI 里 FSR / XeSS 是两个互斥开关，共用同一个 `currentBackend`；DLSS 的开关在 NVIDIA 那一段、
不参与这个互斥（它有自己的 `dxrMcDlss`）。

### 4.2 谁在「真正生效」：`isEnabled()` 三种语义

| 后端 | 判定 |
| --- | --- |
| `NONE` | 永远 false |
| `DLSS` | `DeepLearningSuperSampling.isEnabled()`，即「开关打开 **或** DLAA 生效」 |
| `FSR` / `XESS` | `cachedCaps(id)?.available == true`——**必须问能力面**，不能只看配置 |

为什么 FSR/XeSS 要问能力面：运行库可能不存在（发行版不带 FidelityFX 运行时、用户机器缺 DLL）。
若只看配置，`isEnabled()` 会骗过地形采样路径的 mip bias 逻辑，出现「配了 FSR 但没跑，地形却按低分辨率偏置」。
能力面查询按秒缓存（`CAPS_TTL_NANOS = 1_000_000_000`），因为 `isEnabled()` 在地形路径上每帧都会被问。

### 4.3 三个动作：选、记、建

1. `requestBackend(requested)` —— 用户意图变化。会把渲染比例调到该档位的标称值（`NATIVE_AA_TIER`
   除外），并且在 DLAA 生效期间**拒绝** FSR/XeSS（DLAA 把比例锁在 100%，没有可放大的东西），
   只打一行 WARN。
2. `pushBackendToNative(selected)` —— `mcdr_dx12SrPick(id)`，纯记录。它推的是**选中**的后端而不是
   「正在跑」的后端：100% 时 FSR/XeSS 不跑、DLSS 可能已经让位，但「用户选了谁」是意图，
   帧生成的可用性判定（「超分是不是 DLSS」）读的就是这个意图。
3. `D3D12SrCreate` / `D3D12SrEvaluate` / `D3D12SrDestroy` —— 生命周期。`mcdr_dx12SrCreate` 幂等，
   但仍按 `createdFor`（后端 + 渲染尺寸 + 显示尺寸）去重，避免每帧付一次跨 ABI 往返。

**`requestBackend` 不调 `mcdr_dx12SrDestroy`**：那一刻可能还有打开的命令列表，销毁上下文会踩到
正在录制的资源。它只清掉 `createdFor` / `lastKey` / `lastState`，由 native 的 `ensureProvider`
自己释放旧 provider 并保证 GPU 侧已经跟上。

### 4.4 质量档位

两家的档位名字、顺序、百分比完全一致（`QUALITY_NAMES`）：

| 下标 | 名字 | 渲染比例 | 备注 |
| --- | --- | --- | --- |
| 0 | `ultra_performance` | 34% | **不能是 33%**：NGX 的 3.0x 要求 render ≥ target/3，2560 宽时 target/3 = 853.33；33% 给出 844×440，NGX 每帧报 `Dynamic scaling error … outside of Min … Max` 并交回空纹理 → 世界全黑、GUI 正常。34% 给出 870×453。 |
| 1 | `performance` | 50% | NVIDIA 官方档位值 |
| 2 | `balanced` | 59% | 官方是 58.14%，58 会留下 0.24% 的差，只靠 NGX 容差活着；默认档 |
| 3 | `quality` | 67% | 官方 66.6% |
| 4 | `native_aa` | 100% | 只有 FSR/XeSS 有这一档（`NATIVE_AA_TIER = 4`），不做缩放 |

DLSS 的选择器只有前 4 档，DLAA 由抗锯齿选项驱动（见 4.6）。改档位时门面只会在
`currentBackend() == target` 时改渲染比例——否则改 FSR 那一行会顺手改掉 DLSS/XeSS 的比例。

比例调整一律走 `D3D12RenderScale.setPercentAtFrameBoundary(...)`：**帧中间**改比例只会改字段，
本帧后半段创建的世界层目标按新比例分配，而主目标附件还是旧比例，
`RenderTarget.copyDepthFrom` 会抛 `IllegalArgumentException: Depth texture: size does not match expected attachment size`。

### 4.5 能力面（caps）

`SR_CAPS`（`MCDR_ABI.SrCaps`）是「这台机器能不能跑这家」的唯一来源，字段：

| 字段 | 用途 |
| --- | --- |
| `backend` / `available` | 编号与可否用。UI 红字、`isEnabled()` 都读它 |
| `coupledDenoise` | 后端是否自带降噪（本工程目前没有消费者） |
| `needsExposure` | 后端是否需要曝光纹理；本工程没有曝光纹理，`preExposure` 固定 1.0 |
| `takesMasks` | 位域：`SR_MASK_REACTIVE = 1`、`SR_MASK_RESPONSIVE = 2` |
| `externalHeap` | 是否需要外部堆（当前无消费者） |
| `jitterPhaseCount` | 后端期望的 jitter 相位长度 |
| `maxRenderWidth` / `maxRenderHeight` | 后端尺寸上限 |
| `nativeAA` | 是否支持 100% 原生抗锯齿档 |
| `note` | native 写的短原因（固定 `char[28]`，UTF-8），给人看，不做机器判定 |

`statusLine(backend)` 只把 `note` 翻译成一行文本，**不再自己推导**「运行库在不在」——
否则就有两个事实来源。它会触发一次 `LoadLibrary` 尝试，所以只在设置界面打开时调用。

### 4.6 与抗锯齿、DLAA 的关系

- 抗锯齿取值：`OFF = 0`、`SMAA = 1`、`TAA = 2`、`DLAA = 3`（`dxrMcAaMode`）。DLSS 不占第 4 档（取值域
  里没有 `4`）；旧 DLL 的兼容桥仍会把 `4` 当作「DLSS 开」推给 native。
- **DLSS 与抗锯齿互斥**：DLSS 自带时间性 AA，先跑我们的 AA 只会把输入糊掉。DLSS 开启期间，
  抗锯齿选项被锁在 OFF 并置灰（`D3D12AntiAliasing.setDlssLock`），退出时恢复用户原值。
- **DLAA 是「DLSS 在原生分辨率」**：由抗锯齿选项选中 DLAA 触发，此时独立的 DLSS 开关置灰；
  `isEnabled()` 把 DLAA 也算作开启。DLAA 要求 render == target，所以渲染比例被钉到 100%。
- **DLSS 与 FSR/XeSS 互斥**：切到 FSR/XeSS 时门面会让 DLSS `standDown()`——关闭开关、释放渲染比例
  模式、解锁抗锯齿。这个动作必须排到**帧边界**（`Minecraft.schedule`），因为它会改渲染比例。
  同时，门面在每一帧发现「DLSS 还开着」时会补一次让位，防止 `options.txt` 里两个开关同时为真、
  导致两家争同一张颜色纹理。

---

## 5. FSR / XeSS 的载荷与输出

### 5.1 载荷字段

`MCDRLayout.SR_DISPATCH` 是 JVM 侧打包、native 侧解析的定长结构。字段与来源：

| 字段 | 值 / 来源 |
| --- | --- |
| `renderWidth` / `renderHeight` | 主目标颜色附件的真实尺寸 |
| `displayWidth` / `displayHeight` | 呈现目标尺寸（窗口） |
| `jitterOffsetX` / `jitterOffsetY` | `(Jx, -Jy)`：唯一的 jitter 源是 Halton(2,3)，y 取反是 DLSS 侧的统一约定；FSR/XeSS 沿用同一份值（待真机复验） |
| `motionVectorScaleX` / `Y` | `1.0`（MV 已是像素空间，见 5.2） |
| `preExposure` | `1.0`（没有曝光纹理） |
| `frameTimeDeltaMs` | 帧间隔，钳在 1.0..200.0 ms，首帧 16.7 ms |
| `cameraNear` | `0.05`（与 `Projection.setupPerspective(0.05F, …)` 同源） |
| `cameraFar` | `CameraRenderState.depthFar`，取不到用 `512.0` |
| `cameraVerticalFov` | 从投影矩阵反算：`m11 = proj[5]`，`2*atan(1/m11)`；`m11` 不合理时退回 `options.fov()` 的标称值 |
| `cameraAspectRatio` | `displayWidth / displayHeight` |
| `viewSpaceToMetersFactor` | `1.0` |
| `sharpness` | 滑条 0..100 ÷ 100 |
| `reset` | `frameKey` 变化（后端/尺寸/比例）时置位 |
| `hdrInput` | **固定 0**：本工程把超分输入一律当 SDR 处理，是否设置 FSR 的高动态范围标志由 native 决定 |
| `flags` | 0 |
| `pColorInput` / `pDepth` / `pMotionVectors` | 主目标颜色、主目标深度、速度 pass 本帧的句柄 |
| `pColorOutput` | 必须是带 `USAGE_STORAGE` 的纹理（见 5.3） |
| `pReactiveMask` | `D3D12SrMask.produce(...)` 的返回值，0 表示「没有掩码」 |
| `pExposure` | 地址 0（没有曝光纹理） |
| `depthFormatOrdinal` | 主目标深度附件的 `GpuFormat.ordinal`（诊断用） |
| `backend` | `SrBackend.id` |

### 5.2 运动矢量的空间

`D3D12Velocity` 输出的是**屏幕空间、单位像素**的位移（`RG16_FLOAT` 乒乓纹理），
方向是「本帧 → 上一帧」（着色器里 `motion = (prevUv - uv) * ScreenSize`）。因此三家的 `mvScale`
都是 `1.0`，不是归一化的 UV 比例。

不要和 Streamline 的 `mvecScale` 搞混：那是另一个参数，要求归一化值，所以 DLSS 的
`DLSS_CONSTANTS` 里写的是 `1.0 / renderWidth`、`1.0 / renderHeight`。

> 注意：`SuperResolution.kt` 与 `DLSS.kt` 的注释把这条位移写成了 `(uv - prevUv) * screenSize`，
> 与 `D3D12Velocity` 的着色器实现符号相反。着色器是权威（方向必须「本帧 → 上一帧」），
> 注释待修正；两条路都用 `mvScale = 1.0`，所以这个笔误不影响行为。

### 5.3 输出纹理契约

native 的 `textureFlags()` 只在 `usage & 32` 时加 `D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS`，
而超分后端只往 `ALLOW_UNORDERED_ACCESS` 资源里写。所以：

- `pColorOutput` 必须是 `USAGE_STORAGE` 纹理。界面目标（`D3D12GUISplit` 的 `RenderTarget`）是普通
  render target，没有 STORAGE；把它当 `pColorOutput` 会让 UAV 屏障在 `Close()` 时失败
  （`endCommandList: Close HRESULT 0x80070057`）。
- 因此 FSR/XeSS 借用 `DLSSOutput.textureForFrame(displayWidth, displayHeight)` 生成一张
  「`STORAGE | RENDER_ATTACHMENT | COPY_DST | COPY_SRC`、`RGBA8_UNORM`、窗口尺寸」的纹理，
  成功后再由 JVM `recordTextureBlit` 进 GUI 目标。
- 这张纹理由 `DLSSOutput` 统一管理（与 DLSS 交接共用同一个池子）。同一时刻只会有一条超分路径在跑，
  但**换后端时不要假设它已经空出来**：`DLSSOutput.release()` 必须先清 native 的覆盖、
  再 `close()` 纹理（顺序见 [`dlss.md`](dlss.md)）。

### 5.4 FSR/XeSS 的输出与 hudless

`mcdr_dx12SrEvaluate` 返回 1 后的两种去向：

| 条件 | 动作 |
| --- | --- |
| `D3D12GUISplit.isHudlessArmed()` | `markWorldLayer(output)`：这张 UAV 纹理就是呈现源，UI 由 FG 后端合成一次 |
| 否则 | `recordTextureBlit(output, guiColor)` 然后 `markWorldLayerDone()`：`prepareForGui` 不再搬世界层 |

hudless 的裁决只在帧边界（present 之后）做一次，且与超分共用同一个 latch，
避免「SR 认为世界层已就位、`prepareForGui` 认为还没搬」这种一帧内的自相矛盾。

---

## 6. SR mask（反应性掩码）

### 6.1 它解决什么

时间性超分会用上一帧的历史。对**半透明**表面（水、玻璃、粒子、火焰）来说，历史可能是错的：
这一帧透过玻璃看到的背景，上一帧可能被别的东西挡住。若不做标记，后端会把旧内容累积进来，
表现为半透明物体后面的拖影或「贴纸感」。掩码就是逐像素告诉后端「这一块别太信历史」。

FSR 的这类输入叫 `reactive`，XeSS 叫 `responsive`，能力面用 `takesMasks` 的位域表达。
DLSS/NGX 在这条路径上不吃掩码（`takesMasks == 0`），所以设置界面里那一行在 DLSS 下是灰的。

### 6.2 数据来源：OIT 的透射率缓冲

26.3 的 `LevelTargetBundle.transmittance` 里装的**不是**透射率，而是**吸收率（absorbance）的小波系数**：

- 每个片元由 `oit_add_transmittance.glsl` 写入 8 个系数（`OIT_COEFFICIENT_COUNT = 2^3`），
  分成 2 张 RGBA16F 附件（`OIT_TRANSMITTANCE_TARGET_COUNT = 8 / 4 = 2`）；
- 深度被分到与系数量相同的 bin 里，小波秩 `OIT_WAVELET_RANK = 2`。

要拿到「这一列半透明遮挡有多厚」，必须按 `oit_sample.glsl` 的 `sampleAbsorbance` 把小波树逐层还原成
整列 absorbance，再取 `1 - exp(-absorbance)`。`D3D12SrMask` 里的 HLSL 就是那段 GLSL 的逐行移植
（`originalDepth = 1`、`currentAbsorbance = 0`，即整列）。

### 6.3 重建与分发

```
两张 RGBA16F（8 个小波系数）
        │  HLSL，cs_6_0，[numthreads(8,8,1)]
        │  逐 level 上溯：waveletWidth = exp2(-power)
        │                waveletHeightScale = exp2(power * 0.5)
        │  父节点下标 >>= 1，符号 1 - 2*isRightHalf
        ▼
absorbance → transmittance = clamp(exp(-absorbance), 0.0001, 1)
        ▼
输出 R8_UNORM：saturate(1 - transmittance) * (scale / 1000.0)   // scale = 900
```

| 项 | 值 |
| --- | --- |
| 常量 | `MAX_MASK = 900`、`COEFF_COUNT = 8`、`ATTACHMENTS = 2`、`WAVELET_RANK = 2` |
| 分发 | `MCDR_ABI.D3D12CompileShaderDxc(HLSL, "main", "cs_6_0")` 编译一次；`D3D12CreateComputePipelineTexture3` 建 PSO |
| 参数 | 8 个 int 的 cbuffer：`width, height, attachmentCount, scale, coeffCount, rank, 0, 0` |
| 线程组 | `((w + 7) / 8, (h + 7) / 8, 1)` |
| 输出纹理 | `D3D12GpuTexture(USAGE_STORAGE \| 1 \| 2, "DXRMC SR mask", R8_UNORM, w, h, 1, 1)`；位 32 是 STORAGE 语义位，1/2 是 COPY_DST / COPY_SRC（供读回诊断） |
| 尺寸 | 与渲染尺寸相同（掩码是「输入分辨率」的逐像素标记） |

### 6.4 生命周期与失败策略

1. **每帧一次捕获**：main pass 的 RETURN 处 `captureTransmittance(views)`，只收未关闭的纹理，
   OIT 此刻已经合成完。最多 2 张；不足 2 张时未用的 SRV 槽位填 0，着色器按 `attachmentCount` 决定读几张。
2. **必须在调用方的命令列表里跑**：`produce(encoder, w, h)` 只 `Dispatch`，自己绝不 `Begin/Close/Submit`。
   掩码必须与随后的 `mcdr_dx12SrEvaluate` 在**同一条**命令列表里、且在它之前完成。
3. **失败即永久放弃**：编译、建 PSO、建视图、Dispatch 任一步失败都会 `fail(reason)` 置位 `failure`，
   之后 `produce` 直接返回 0（= 没有掩码），只 WARN 一次。超分本身继续跑。
4. **首帧统计一行**：`D3D12ReadbackTexturePixels` 读回，统计 mean / peak / 非零占比，
   日志 `SR mask first frame: mean=… peak=… non-zero=…%`。这是「掩码是不是真的全 0」最快的判据。

---

## 7. 与渲染比例、帧生成的交互

### 7.1 渲染比例的两套键

`D3D12RenderScale` 有两套取值域，切换由 DLSS 开关驱动（`setDlssMode`）：

| 模式 | `options.txt` 键 | 范围 | 步进 | 默认 |
| --- | --- | --- | --- | --- |
| 非 DLSS | `dxrMcRenderScale` | 25..400 | 25 | 100 |
| DLSS | `dxrMcRenderScaleDlss` | 25..100 | 1 | 58 |

- 两套键各自记住「用户上次调的值」；关掉 DLSS 时看到的是非 DLSS 那一把尺。
- DLSS 模式放宽到 25..100 / 步进 1，是因为这一行在 DLSS 打开时**被档位绑定、只读**，
  只承担「显示当前输入比例」的作用；取值域必须覆盖档位表里的每一个百分比，否则档位绑定的值落不到滑条上。
- DLAA 锁存在 `dlaaLock` 里，优先级高于模式夹取：锁定期间任何路径（滑条、`setDlssMode`、
  `load`、`store`）拿到的值都是 100，否则 DLAA 会因为「渲染 90%、目标 100%」被 native 拒绝。

### 7.2 100% 与「比例不够」的自愈

- FSR/XeSS：渲染尺寸 == 显示尺寸时，门面会把比例降到 `DEFAULT_RENDER_PERCENT = 75`
  （一次，`autoScaleAdjusted`），并记一行说明。75 是滑条的吸附值（非 DLSS 模式按 25 的倍数吸附，
  67 会被吸附到 75）。
- DLSS：`renderScale == 100%` 时会话内把比例绑回档位标称值；`0 < percent < 标称值` 时抬到标称值
  并 WARN（否则 NGX 每帧拒绝，世界全黑）。两种都走帧边界。
- 渲染尺寸低于 **320×180** 时，门面按 `max(ceil(320*100/w), ceil(180*100/h))` 抬比例，
  夹在 `minimumPercent()..100`。这个下限与 native 能力面的尺寸下限一致。
- 注意：`DLSS.kt` 里关于「主目标附件尺寸 ≠ 窗口 × 比例就重建」的自愈**只有注释、没有调用点**
  （`D3D12RenderScale.scheduledRebuild()` 在 DLSS 代码里没有被调用）。真正生效的是上面两条比例抬升。
  排查「比例显示 58% 但世界仍按窗口分辨率出图」时，先确认主目标是不是真的按比例重建过。

### 7.3 帧生成的常量为什么在超分目录里

`FrameGenerationConstants` 每帧把 view / projection / jitter / mvScale / resetHistory 通过
`mcdr_dx12FgSetConstants` 推给 native。它放在 `sr` 包里，是因为这些值由超分的相机捕获路径顺带产出，
但它服务的是**帧生成**，与超分后端无关：

- 这些常量不能只从 DLSS 路径推：把超分切成 FSR/XeSS 后，DLSS 那段代码不再运行，
  XeSS-FG 就拿不到相机矩阵——表现为 `xefgSwapChainSetEnabled(1)` 返回成功，
  但 `GetLastPresentStatus.isFrameGenEnabled` 恒为 0（链包上了，画面还是 1:1）。
- 视图矩阵必须把相机平移烘进去：`worldSpaceView = V_rot * T(-camPos)`。世界层是相机相对空间，
  原始视图只有旋转；少了平移，静止时重投影正常，一走就整幅糊。
- 两个矩阵按**行主序**写入（相机矩阵是列主序，写入前转置），这是 XeSS-FG 的契约。

### 7.4 hudless 呈现

FG 打开 hudless 时，最终呈现源变成「超分输出的世界层」，UI 由 FG 后端合成。这改变超分的输出动作
（见 5.4），也改变 `prepareForGui` 是否搬世界层。`hudlessArmed` 只在 present 之后更新一次，
同一帧内所有消费者看到同一个值。

---

## 8. 不变量与硬约束

违反下面任何一条，症状通常不是崩溃，而是「画面黑了 / 糊了 / 抖了 / 超分一帧都没跑」。

1. **超分必须在「世界层画完、GUI 还没画」的那一刻提交**，且排在 SMAA 之后、`prepareForGui` 之前。
2. **速度 pass 与深度快照必须挂在帧图 main pass 的 RETURN**，不能挂在 `GameRenderer.render` 的清深度之前。
3. **深度快照与 `pInDepth` 必须是同一张纹理**（`mainRenderTarget().depthTexture`），否则 NGX 的输入
   与快照内容不是一回事。
4. **运动矢量纹理为 0 时不要期待超分工作**：native 在 `pInMotionVectors == nullptr` 时立刻返回 0。
5. **改渲染比例只能走帧边界**（`setPercentAtFrameBoundary` / `Minecraft.schedule`），帧中间改会让
   世界层目标尺寸与主目标附件不一致。
6. **`pColorOutput` 必须有 `USAGE_STORAGE`**；界面目标不能直接充当它。
7. **掩码必须与 evaluate 在同一条命令列表里**，且 `produce` 不得自己提交命令列表。
8. **不要在可能有打开命令列表时销毁 SR 上下文**（`requestBackend` 只清记账）。
9. **DLAA 期间比例恒为 100**，且 DLSS 与 FSR/XeSS 不能同时生效。
10. **让位（standDown）必须排到帧边界**：它会改渲染比例，帧中间改会打穿 `copyDepthFrom`。
11. **`OptionInstance.set` 在游戏未运行时不会触发回调**；`Options.load` 期间必须显式补推
    （DLSS 侧 `load` 末尾显式调 `push` / `pushPreset` / `setDlssMode`）。
12. **同一帧内只有一个事实来源**：`isEnabled()` 对 FSR/XeSS 必须问能力面，`statusLine` 不得自行推导。
13. **失败一律降级，不改变渲染路径**：超分失败只跳过本帧，世界层继续走线性 blit。
14. **不要每帧读回 GPU 时间戳**：native 的 resolve 会建命令列表并 `flushAndWait`，所以
    `GPU_SAMPLES = 120` 帧才读一次。
15. **不要手动改纹理状态**：native 在 evaluate 返回 1 后自己保证「颜色/深度/MV 停在
    `PIXEL_SHADER_RESOURCE`、输出停在 `RENDER_TARGET`」。
16. **档位百分比不能手改**：`ultra_performance` 的 34% 是下界，33% 会让 NGX 每帧报
    `Dynamic scaling error … outside of Min … Max` 并交回空纹理（世界全黑、GUI 正常）。
17. **jitter 只在有消费者时注入**（DLSS 未停滞，或 TAA/DLAA 在跑），上报值固定 `(+Jx, -Jy)`；
    没有消费者还注入，用户看到的就是整幅 ±0.5 像素的晃动。
18. **速度通道的闩锁只能用 `D3D12Velocity.forceEnable()` 清**，并按 1 秒限速重试；只把
    `enabled` 置回 true 撤不回失败步骤。
19. **`DLSSOutput.release()` 先清 native 的覆盖（`D3D12SetDlssOutput(0)`）、再 `close()`**；
    `onDlssToggle` 也必须先认领后端（`DLSS`）、再推开关，否则那一行是灰的、超分开不起来。

---

## 9. 关键常量速查

| 常量 | 值 | 位置 / 含义 |
| --- | --- | --- |
| `NEAR_PLANE` | `0.05` | 与 `Projection.setupPerspective(0.05F, …)` 同源 |
| `PRE_EXPOSURE` | `1.0` | 没有曝光纹理，原样通过 |
| `MOTION_VECTOR_SCALE` | `1.0` | MV 已是像素空间 |
| `JITTER_SIGN_Y` | `-1.0` | 上报 `(+Jx, -Jy)` |
| `FALLBACK_FAR` | `512.0` | 取不到 `depthFar` 时的远平面 |
| `MIN_RENDER_WIDTH` / `HEIGHT` | `320` / `180` | 低于此抬比例（与 native 能力面一致） |
| `DEFAULT_RENDER_PERCENT` | `75` | 100% 没活可干时降到它（滑条吸附值） |
| `CAPS_TTL_NANOS` | `1s` | 能力面缓存 |
| `COST_SAMPLES` / `COST_LOG_EVERY` | `128` / `120` | CPU 侧录制耗时统计（不是 GPU 时间） |
| `GPU_SAMPLES` | `120` | 时间戳查询池长度，每 120 帧读一次 |
| `VELOCITY_DIAG_NANOS` / `VELOCITY_RETRY_NANOS` | `1s` / `1s` | 速度通道诊断与闩锁重试限速 |
| `MAX_MASK` | `900` | 掩码缩放（输出前 ÷ 1000） |
| `COEFF_COUNT` / `ATTACHMENTS` / `WAVELET_RANK` | `8` / `2` / `2` | OIT 小波系数布局 |
| jitter 相位 | `16` | `D3D12Temporal.DEFAULT_JITTER_SAMPLES` |
| `SR_BACKEND_*` | `0/1/2/3` | NONE / DLSS / FSR / XESS |
| `SR_MASK_*` | `1` / `2` | REACTIVE / RESPONSIVE |

---

## 10. 文件导航

路径以 `Illusion\src\main\kotlin\dev\nts\expdxr\` 为起点（mixin 在 `Illusion\src\main\java\` 下，已单独标注）。

| 文件 | 职责 |
| --- | --- |
| `dxrmc/sr/SuperResolution.kt` | 超分门面：后端选择与调度、`SR_DISPATCH` 打包、FSR/XeSS 的 evaluate 与 blit、UI 选项、CPU/GPU 耗时统计 |
| `dxrmc/sr/D3D12SrMask.kt` | 反应性掩码：OIT 透射率的小波重建 HLSL、PSO、dispatch 与首帧统计 |
| `dxrmc/sr/FrameGenerationConstants.kt` | 每帧给帧生成后端推 view/projection/jitter/mvScale/reset（与超分后端解耦） |
| `dxrmc/sr/dlss/DLSS.kt` | DLSS / DLAA 主实现：数据面、NGX 开关与档位、preset、深度快照、输出交接、抗锯齿互斥 |
| `dxrmc/sr/dlss/DLSSOutput.kt` | DLSS 输出纹理（JVM 侧创建、交给 native）与释放顺序 |
| `IllusionConfig.kt` | `SrBackend` 枚举（`id` 与 native 宏逐值对应）及 `[sr]` 配置项 |
| `d3d12/extern/MCDR_ABI.kt` | `mcdr_dx12Sr*` / `mcdr_dx12*Dlss*` 的 FFM 绑定、`SrCaps` 数据类、返回值语义 |
| `d3d12/render/advanced/vrs/D3D12RenderScale.kt` | 渲染比例：两套取值域、DLAA 锁、帧边界重建主目标 |
| `d3d12/render/advanced/taa/D3D12Temporal.kt` | Halton(2,3) 亚像素 jitter、相位长度协商、投影 / 相机矩阵捕获 |
| `d3d12/render/advanced/taa/D3D12Velocity.kt` | 速度 pass：屏幕空间像素位移、自我关闭闩锁 |
| `d3d12/render/advanced/smaa/D3D12GuiSplit.kt` | 窗口尺寸的 GUI 目标、`markWorldLayerDone` / `markWorldLayer`、hudless 呈现裁决 |
| `d3d12/render/advanced/D3D12AntiAliasing.kt` | 抗锯齿取值域（OFF/SMAA/TAA/DLAA）与 DLSS 锁 |
| `d3d12/render/advanced/D3D12PostSharpen.kt` | 升采样之后的后期锐化（两趟：target→scratch→target） |
| `mixin/render/GameRendererSmaaWorldMixin.java` | 帧内挂点：清深度之前（DLSS 记账）、`GuiRenderer.render` 之前（TAA→SMAA→SR→prepareForGui） |
| `mixin/render/LevelRendererMainPassDepthMixin.java` | 帧图 main pass RETURN：速度 pass、深度快照、OIT 透射率捕获 |

native 侧对应实现（本仓库 `MCDR\`，不在本文范围内）：`MCDR\src\advanced\d3d12_dlss.cpp`、
`d3d12_fsr.cpp`、`d3d12_xess.cpp`，以及 `MCDR\include\mcdr_abi.h` 里的载荷布局。

---

## 11. 文档维护

- 本文对应源码目录：`Illusion\src\main\kotlin\dev\nts\expdxr\dxrmc\sr\`（含 `dlss\` 子目录），
  以及上表列出的渲染比例 / jitter / 速度 / GUI 拆分 / mixin 挂点。
- **改了代码就同步改本文**，尤其是：载荷字段的增删、质量档位的百分比、常量数值、
  帧内挂点顺序、掩码的生产方式、后端互斥规则。这些地方一旦文档与代码不一致，
  下一个人会照着文档写出「看起来对但一帧都不跑」的代码。
- 文档与代码冲突时**以代码为准**，并顺手把文档改对；代码里没写清的地方保留
  「代码中未明确」的标注，不要替它补脑。
- DLSS / DLAA / NGX / 输出纹理的细节写在 [`dlss.md`](dlss.md)，本文只保留总览与跨子系统交互。
