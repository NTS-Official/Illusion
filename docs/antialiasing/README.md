# 抗锯齿：TAA / SMAA / 后处理锐化

本目录记录 Illusion 的 D3D12 后端里「抗锯齿」这一层的设计：玩家能选的三种模式（TAA / SMAA / 关闭）
如何落到后端、时间性重投影需要哪些跨帧状态，以及为什么 GUI 与帧生成会把一帧拆成两个渲染目标。

阅读前提：

- JVM 侧只负责**选模式、算参数、选挂点**；真正的 resolve、全屏 pass、历史纹理都在 native
  （`MCDR.dll`）里，由 `MCDR_ABI` 的几条导出驱动。本文写「谁决定」时会明确区分 JVM 与 native。
- 只记录**能从代码确认的事实**。注释写了但代码里找不到对应实现的，标注「代码中未明确」或
  「注释声明，未见调用点」。
- 「世界层」指 `GameRenderer.render()` 里世界几何 + 后处理已画完、GUI 还没开始画的那一刻，
  主渲染目标里的内容；它的尺寸是**渲染分辨率**（受渲染缩放影响），不是窗口尺寸。

## 1. 一帧里的顺序

抗锯齿的挂点集中在一个 mixin：`GameRendererSmaaWorldMixin`，注入点是 `GameRenderer.render()` 里
对 `GuiRenderer.render()` 的 INVOKE。这个位置前面是 `fogRenderer.endFrame()`（世界渲染与后处理
都已结束），后面才是 GUI 绘制。调用顺序是硬约束：

| 顺序 | 调用 | 作用 |
| --- | --- | --- |
| 1 | `D3D12TAA.applyIfEnabled()` | 选 TAA 时，在世界分辨率上做 resolve + 回填 |
| 2 | `D3D12SMAAWorld.applyIfEnabled()` | 选 SMAA 时，对同一张主目标做三阶段 SMAA |
| 3 | `SuperResolution.applyIfEnabled()` | DLSS / DLAA 的 Evaluate（FSR / XeSS 走同一个门面） |
| 4 | `DeepLearningSuperSampling.pclRenderSubmitStart()` | 仅 DLSS 的 Reflex 打点 |
| 5 | `D3D12GUISplit.prepareForGui()` | 把世界层搬进窗口尺寸的 GUI 目标，或切到 hudless 布局 |

为什么 SMAA 必须排在 SR 之前：SMAA 在**世界分辨率**上工作，做完之后 SR 才有东西可升采样。
反过来先升采样再做空间抗锯齿，等于在放大后的像素上找边缘，既贵又没用。

同一帧里还有两个更早的挂点，和抗锯齿强相关：

- 主渲染 pass 的 RETURN（世界几何刚画完、任何一次 `clearDepthTexture` 之前）：
  `LevelRendererMainPassDepthMixin` 调 `D3D12TAA.captureTemporalData()`。这一刻深度缓冲里还是
  本帧世界，速度缓冲必须在这时录；晚一点深度就被清了。
- `D3D12GpuSurface.present()` 末尾：`D3D12Temporal.onFramePresented()` 递增「帧身份」，
  然后 `D3D12GUISplit.armUiComposition()` 为**下一帧**裁决 hudless 呈现。

数据流可以压成一条线：

```
主渲染目标（渲染分辨率）= 世界层 → TAA resolve / SMAA 三趟 → SR 升采样进 GUI 目标（窗口尺寸）
                              → GUI 画在 GUI 目标上 → presentSourceTexture() 选一张 blit 上屏
```

## 2. 三种模式与互斥

### 2.1 取值与存放位置

`D3D12AntiAliasing` 定义了四个取值，持久化在 `options.txt` 的 `dxrMcAaMode` 一行里：

| 值 | 名称 | 真正干活的地方 | 时间性 | 备注 |
| --- | --- | --- | --- | --- |
| 0 | OFF | 无 | 否 | 也是 DLAA / DLSS 锁定时被写入的值 |
| 1 | SMAA | native 三趟全屏 pass，世界层 | 否 | 不消费 jitter，也不碰历史 |
| 2 | TAA | native resolve + 回填两趟 | 是 | 依赖速度缓冲 |
| 3 | DLAA | DLSS 的 1:1 模式（SR 通道） | 是 | **不走**抗锯齿通道，见 2.3 |

选项值通过 `MCDR_ABI.D3D12SetAntiAliasing(mode)` 推给 native；native 只记录，**下一次 blit
时才生效**，改选项不需要重启游戏。返回值是一份契约：

- 返回生效模式本身，成功返回 0 合法；传入 3（DLAA）返回 0 —— 抗锯齿通道没这一档，降级成 OFF。
- `-1` = 非法模式；`Int.MIN_VALUE` = 本机 `MCDR.dll` 没有这条导出。

模式选择在 JVM 侧有两道过滤，都会真正影响玩家看到的画面：

1. `D3D12AntiAliasing.isAvailable()`（即 `D3D12AdapterInfo.current != null`）为假时，画质预设的
   映射不写这个选项 —— 没跑在 D3D12 后端上时不要留下一个看起来生效、其实没有后端的取值。
2. DLSS 锁定期间忽略 `options.txt` 里的恢复值，见 2.5。

### 2.2 TAA 与 SMAA 的互斥

两者是**同一个选项的两个取值**，所以天然互斥。实现上也确实是同一挂点里的两次独立判断：

```kotlin
D3D12TAA.applyIfEnabled()        // isSelected() == TAA 才动手
D3D12SMAAWorld.applyIfEnabled()  // 不看模式，交给 native 判
```

一个容易误解的细节：`D3D12SMAAWorld.applyIfEnabled()` **不检查当前模式**，它只检查
「有没有世界、主目标是不是 D3D12 纹理、有没有共享 encoder」，然后无条件调
`recordSmaaWorldPass()`；模式判断在 native 里（不是 SMAA 就返回 0）。这样做的好处是挂点恒定、
JVM 侧没有第二份模式状态；代价是 SMAA 分支每帧都要付一次调用。

### 2.3 DLAA 的特殊地位

DLAA 占着抗锯齿选项的第 4 个档位，但**它不是抗锯齿通道的模式**。push 时被显式改写：

```kotlin
val pushed = if (mode == D3D12AntiAliasing.DLAA) D3D12AntiAliasing.OFF else mode
MCDR_ABI.D3D12SetAntiAliasing(pushed)
```

真正做 DLAA 的是超分特性：渲染分辨率 == 目标分辨率（渲染缩放 100%）、SR 后端仍然按 DLSS 那套
流程评价，输出走 DLSS 的 handover 路径。`D3D12AntiAliasing.current()` 仍然返回 3，界面显示
「DLAA」，但 native 的抗锯齿通道一直是 OFF。

因此「TAA 与 DLAA 冲突」这件事不需要额外逻辑：DLAA 让抗锯齿通道变成 OFF，TAA 的
`isSelected()` 为假，`applyIfEnabled()` 第一行就返回。

`D3D12AntiAliasing` 不检查 DLSS/NGX 是否可用（它只问 D3D12 后端在不在），所以「DLAA 选了但
NGX 起不来」时画面就是没有抗锯齿的世界层 —— 具体降级由 SR 侧的每帧评价结果决定，
**DLAA 自身的降级策略在抗锯齿代码里未明确**。

### 2.4 SMAA 与 DLSS 不互斥

这一点和 TAA 不同：mixin 注释明确写了 SMAA 与 DLSS「互不排斥，两处注入各判各的开关」，
所以「SMAA + DLSS」是合法组合 —— SMAA 先在世界分辨率上做完，DLSS 再升采样。TAA 则与 DLSS
共用同一套时域数据面，同时开会互相打架，由 2.5 的互锁处理。

### 2.5 DLSS 互锁

DLSS 是一个独立设置（不是抗锯齿选项的档位）。它被打开时，`D3D12AntiAliasing.setDlssLock(locked)`
接管选项：

- 锁定期把抗锯齿**静默**置成 OFF，并把原值存进快照；解锁时还原。静默是指不触发「画质预设
  变成自定义」的副作用。
- 当前值本来就是 DLAA 时不再改动（只打一行日志）：DLAA 自己拥有 DLSS，锁它没有意义。
- `isLockedByDlss()` 给界面用，把选项置灰。
- 锁定期 `load(file)` 读到的 `dxrMcAaMode` 被忽略，避免「重启后配置把锁顶开」。

### 2.6 画质预设与自动降级

原版三个画质档位的映射写在 `applyFromPreset` 里：

| 预设 | 抗锯齿 |
| --- | --- |
| `fast` | OFF |
| `fancy` | SMAA |
| `fabulous` | TAA |

映射只在 `isAvailable()` 且未被 DLSS 锁定时执行；改完之后会调
`OptionsSubScreen.resetOption(OPTION)` 刷新控件缓存 —— 直接 `OptionInstance.set()` 不会让
CycleButton 重新读值，界面会停留在旧文字上。

自动降级分几层，触发条件和后果都不一样：

| 情况 | 谁发现 | 后果 | 是否可恢复 |
| --- | --- | --- | --- |
| `MCDR.dll` 没有 `mcdr_dx12ApplyTaaToTexture` | `D3D12TAA.record()` | TAA 自我关闭（`disabledReason` 记原因） | 需重启（换 DLL） |
| native 返回 `< 0` | `D3D12TAA.record()` | 同上，附 `D3D12LastError()` | 同上 |
| 没有世界 / 没有速度缓冲 / 没有共享 encoder | `applyIfEnabled()` | 本帧静默跳过，表现等于没 TAA | 下一帧自动恢复 |
| 速度通道已闩锁（`abandonedSteps != 0`） | `D3D12Velocity.isActive()` | TAA 静默不跑 | `forceEnable()` 可清闩锁 |
| 没有 `mcdr_dx12SharpenTextureToTexture` | `probeSupport()` | 回退普通 blit，锐化失效 | 需重启 |
| SMAA 任一步失败 | native 返回 0 | 静默跳过，等价于关 AA | 下一帧自动恢复 |
| DLAA 选中但 SR 起不来 | SR 侧评价返回 0 | 没有抗锯齿；jitter 被压 0（避免整幅画面抖） | 取决于 NGX 是否恢复 |

「静默跳过」是刻意设计：抗锯齿不该让这一帧渲染不出来。代价是玩家很难分辨「没生效」和「关掉了」，所以 TAA 与 SMAA 各自有一条**每会话一次**的结论性日志（native 确认真的跑了）。

### 2.7 配置项在哪

| 位置 | 键 | 默认 | 作用 |
| --- | --- | --- | --- |
| `options.txt` | `dxrMcAaMode` | 由选项初值决定 | 玩家选的抗锯齿模式（0..3，唯一持久化位置） |
| `config/dxr_mc-client.toml` `[renderer]` | `sharpenAmount` | `0.67` | DLAA / DLSS 升采样后的锐化强度 |
| 同上 | `taaSharpenAmount` | `0.816` | TAA resolve 之后的锐化强度 |
| `[debug]` | `temporalChain` | `false` | 强制点亮时间性链路（开发者开关，出货不需要） |
| `[debug]` | `temporalJitter` | `true` | 是否把亚像素抖动注入投影矩阵 |
| 视频选项 | 渲染缩放 | `100%` | 偏离 100% 才需要 GUI 拆分；同时决定 DLAA 是否成立 |
| 帧生成设置 | 后端 | 关 | hudless 只对 FSR-FG 生效，见第 6 节 |

旧的 `-D` 属性（`dxrmc.temporal`、`dxrmc.temporal.jitter`、`dxrmc.sharpen`）仍能被读到，
但只在配置停在默认值时才生效，属于**已弃用的逃生口**，不是功能入口。

## 3. 时间性重投影

### 3.1 抖动序列

时间性抗锯齿的第一块拼图是亚像素抖动：每帧把投影稍微偏一点点，让同一像素在不同帧采到不同的
子像素位置，再由历史混合把这些样本攒成更高的有效分辨率。

- 序列：Halton(2, 3)，两轴分别取 base 2 与 base 3 的 radical inverse，值域 `(0,1)`，减 `0.5`
  之后变成以 0 为中心的像素偏移，范围 `[-0.5, +0.5)`。
- 长度：`DEFAULT_JITTER_SAMPLES = 16`（2 的幂，与后端 8/16 采样档位对齐）；Halton 表惰性生成并
  缓存，长度取 `max(jitterSamples, 16)`。
- 取第几个：`index = frameIndex % 16`，`frameIndex` 每帧加一，与速度缓冲的乒乓、TAA 的帧号
  同一个节拍（都在 `D3D12Temporal.beginFrame()` 里推进）。

注入位置是 vanilla 的关卡投影矩阵 UBO：

- buffer label：`"Camera projection matrix UBO level"`（64 字节）；
- 列主序的 `out[8]`（`m[0][2]`）与 `out[9]`（`m[1][2]`）各减去 `clipJitterX/Y`，其中
  `clipJitter = 2 * jitter / 尺寸`。

推导（代码注释里给了完整链条）：`clip.x = ... + m[0][2] * view.z`，透视下 `clip.w = -view.z`，
所以 `m[0][2] += δ` 等价于 `clip.x += -δ * clip.w`；要让 NDC 平移 `c`，取 `δ = -c`。

**只抖 `level`，不抖 `"3d hud"`**：后者是正交投影，抖了会让物品栏与手持物错位。

注入有双重闸门，缺一不可：

```kotlin
fun isActive() = enabled || D3D12TAA.isSelected() || DeepLearningSuperSampling.isEnabled()
private fun hasJitterConsumer() = D3D12TAA.isSelected() ||
    (DeepLearningSuperSampling.isEnabled() && !DeepLearningSuperSampling.isStalled())
```

只有 `isActive()` 且 `hasJitterConsumer()` 且 `jitterEnabled` 都为真才真正改写矩阵，否则
`jitterX/Y = 0`。第二道闸门是修出来的：SMAA / OFF 下没有任何消费者会补偿这份偏移，
再注入就是整幅画面逐帧轻微抖动（用户实测过）。`[debug] temporalChain = true` 不能绕过它。

### 3.2 历史缓冲的 ping-pong

TAA 的历史**不归 JVM 管**。`D3D12TAA` 只每帧算三样东西交给 native：本帧 jitter、上一帧 jitter、
历史代数；双纹理、RTV 堆、根签名、PSO、参数块、resolve 与回填两趟全在 native 的
`DeviceContext::applyTaaToTexture` 里。

历史纹理是两张同尺寸纹理乒乓：本帧 resolve 读旧的那张、写新的那张，回填那一趟再把结果写回
主渲染目标。JVM 每帧只调一次：

```kotlin
MCDR_ABI.dx12ApplyTaaToTexture(ctx, scene, motion, jitterX, jitterY, prevJitterX, prevJitterY,
    blend = 0.0625f, reset = reset, sharpenAmount = D3D12PostSharpen.taaAmount)
```

为什么历史要归 native：早期 JVM 侧自己持有历史纹理，在传送、改窗口尺寸这类时刻会在上一帧的
命令列表还在飞的时候销毁资源，实测能把 D3D12 设备打掉（设备移除，整局结束）。现在 native 在
录制新一帧时按当前尺寸确保历史存在（`taaEnsureHistory`），JVM 只负责告诉它「这份历史还能不能
用」。

混合权重 `BLEND = 0.0625f`，即 `lerp(history, current, 1/16)`，时间常数 16 帧。代码注释里
比较过另外两档：1/8 收敛快但更糊，1/32 更平滑但运动边缘残留拖影。1/16 是实测定下来的。

`reset = true` 时 native 只把当前帧写进历史、不与上一帧混合。JVM 侧把
`D3D12Temporal.historyGeneration` 与自己记的上一份比较，不等就置 `reset`。代数在
`D3D12Temporal.reset()` 里自增，且**放在函数最前面** —— 即使后面的资源释放抛异常，
消费者也已经知道「别再混合了」。

### 3.3 速度缓冲

速度（运动矢量）缓冲是一张独立的屏幕空间纹理，格式 `RG16_FLOAT`，用途
`RENDER_ATTACHMENT | TEXTURE_BINDING | COPY_SRC`，当前帧与上一帧两张乒乓，`beginFrame()` 时
一起换。输出的 `xy` 是**像素单位**的屏幕空间位移，方向是「本帧 → 上一帧」，也就是
`prevUv - uv`；历史取样时 `historyUv = (coord + 0.5 + mv) / screenSize`。

它是一条独立的全屏 pass（raw ABI 的 `MCDRPipelineDesc`，内联 HLSL，DXC 编译，硬要求 SM 6.9 /
DXIL，没有 SM 5.1 兜底）。资源绑定：

| 槽位 | 内容 |
| --- | --- |
| `register(b0)` | 常量缓冲，256 字节 |
| `register(t1)` + `register(s1)` | 深度 SRV + 静态采样器（POINT / WRAP） |
| `register(t2)` | 物体行 texel buffer（`R32_FLOAT`） |

常量缓冲的实际布局（偏移，字节）：

| 偏移 | 字段 | 说明 |
| --- | --- | --- |
| 0 | `PrevViewProj` | 上一帧 view-projection |
| 64 | `InvCurrViewProj` | 本帧 view-projection 的逆 |
| 128 | `ScreenSize` | 像素尺寸 |
| 136 | `ObjectParams` | x = 行数 + 1，y = 允许的位移像素上限 |
| 144 | `JitterPixels` | 本帧抖动 |
| 152 | `PrevJitterPixels` | 上一帧抖动 |
| 160 | `CameraDelta` | 相机世界坐标位移 |

共用 172 字节，补齐到 `CBV_SIZE = 256`。物体表上限 `256 × 16 × 4 = 16384` 字节。

像素着色器的步骤（顺序即代码顺序）：

1. `uv = SV_Position.xy / ScreenSize`；`depth <= 0` 或 `>= 1` 直接输出 0（天空/远平面没有可信深度）。
2. `ndc = (uv * 2 - 1, 1 - uv * 2, depth, 1)`；`world = mul(InvCurrViewProj, ndc) / w`。
3. `prevClip = mul(PrevViewProj, world + CameraDelta)`；**`if (prevClip.w <= 1e-4) return 0`**。
   负的 w 会把位置镜像到画面另一处，实测表现为沙地变白、树叶变紫。
4. `motion = (prevUv - uv) * ScreenSize + float2(JitterPixels.x, -JitterPixels.y)`。
5. NaN 兜底用 `!(dot(motion, motion) <= 1e12)`（NaN 参与比较恒为假，写成 `>` 会漏）。

两个细节最容易搞错：

- **本帧投影必须用抖动后的那份**再乘 view。深度就是用抖动后的投影渲出来的，反投影用未抖动的
  矩阵，逐像素就会差半个像素。
- **jitter 的 y 要反号**。抖动注入在投影矩阵第 2 列，经透视除法后内容在像素坐标上位移
  `(+Jx, -Jy)`，补偿量因此是 `(+Jx, -Jy)`。
- 相机平移必须单独补：terrain 与实体的顶点是**相机相对坐标**，只靠 view-projection 变换
  会漏掉相机自身的位移；`CameraDelta` 就是补这一块。

`invert()` 是自写的列主序高斯-约当求逆。不能直接用 JOML 的 `Matrix4f.invert()`：它的 `set`
按行主序解释、`get` 按列主序读出，混用会把结果转置。

### 3.4 逐物体运动

只有相机运动时，速度缓冲只需要深度 + 两套矩阵。要让移动的实体（生物、掉落物、船）不拖影，
还得知道「这个像素属于哪个物体、它自己这帧移动了多少」。

JVM 侧每帧采一份快照（`D3D12ObjectMotion`），遍历 `ClientLevel.entitiesForRendering()`，
每行 16 个 float：

| 下标 | 字段 |
| --- | --- |
| 0 | 实体 id |
| 1..3 | 本帧世界坐标 `cur` |
| 5..7 | 上一帧世界坐标 `prv` |
| 8..10 | 半宽 / 半高 / 半宽 |
| 11 | 绕 Y 的朝向 `yaw` |

位置用 `Mth.lerp(partialTick, xOld, x)`；上一帧同相位用外推
`extrapolate(old, now, partialTick - 1.0)`，这样「上一帧的渲染位置」和「本帧的渲染位置」在同一
相位上，差值才是真正的运动。

过滤条件（都在 CPU 侧做掉，着色器只信这张表）：

- `isRemoved` / `!isAlive` / `isInvisible` 跳过；
- 相机实体只在 `isDetached` 或睡觉时算可渲染；
- 本地玩家旁观时跳过；
- 距相机超过 `MAX_DISTANCE_BLOCKS = 96.0` 格跳过（平方比较，不开方）；
- **不做**视锥与区块可见性裁剪 —— 得到的集合是 vanilla 可见集的超集，多做一点判断是安全的，
  漏掉反而会出现「物体在动但速度缓冲说它没动」。

着色器侧先按**上一帧**的 AABB 判断像素属于哪个物体：用 `cur` 字段的中心、`half` 半尺寸、
绕 Y 转 `yaw` 得到 8 个角再取 AABB，外加一个 `OBJECT_BOX_MARGIN = 0.25` 的余量。命中后用
物体自身位移重投影：

```
prevWorld = world + (prv - cur) + CameraDelta
```

同样有 `prevClip.w <= 1e-4` 守卫；如果物体自身运动超过
`OBJECT_MAX_MOTION_PIXELS = 128.0` 像素，就放弃这一行、回退到相机重投影（多半是传送或
快照过期，硬算会把整片画面拉花）。多个物体重叠时**先到先得**，取表里第一行命中的。

### 3.5 失效与 reset

跨帧状态有三份（TAA 历史、速度乒乓、物体快照），它们的失效条件并不相同：

| 事件 | 期望动作 | 代码里实际发生 |
| --- | --- | --- |
| 首帧 / 刚进世界 | 丢弃历史，只写当前帧 | 代数变化 → `reset = true` |
| 相机瞬移（传送） | 丢弃历史 | 注释声明会 `reset()`；**JVM 侧未见对应调用点**（见下） |
| 换世界 / 退出世界 | 丢弃历史与物体快照 | 同上 |
| 窗口 / 渲染尺寸变化 | 历史按新尺寸重建 | native 在录制时确保历史（`taaEnsureHistory`）；JVM 侧未见显式 `reset()` |
| 退出 DLSS 会话（换后端 / 关掉） | 释放速度纹理与时间性状态 | `D3D12Temporal.reset()`（唯一确认的调用点） |

`D3D12Temporal.reset()` 会做完整复位：`frameIndex = 0`、`uploadFrameNs = MIN`、
`historyGeneration++`、jitter 与上一帧 jitter 归零、投影 / view-proj / 相机位置 / 区块记录全部
清空，然后依次 `D3D12Velocity.reset()`、`D3D12TAA.reset()`、`D3D12ObjectMotion.reset()`。

但代码里**只有 DLSS 退出会话时调用它**（`DLSS.kt` 的 `leaveDlssMode`）。所以「传送、换世界、
切分辨率会重置历史」只是 `reset()` 注释声明的**语义目标**，JVM 侧没有对应接线；分辨率变化有
一部分由 native 自己兜住（按新尺寸重建历史）。传送 / 换世界后 TAA 是否会短暂重影取决于 native
判断，**JVM 代码中未明确**，这是本文最需要复核的一处。

`D3D12TAA.reset()` 本身不释放 GPU 资源，只复位帧号、`recordedFrame`、代数记录与
`disabledReason`；历史纹理的生死完全在 native。

## 4. 为什么速度缓冲要单独一张 RT

不是「顺手多开一张纹理」，有四个彼此独立的理由：

1. **格式与精度不同**。颜色目标是 `RGBA8_UNORM`（或 HDR 格式），运动矢量是 `RG16_FLOAT`：
   只需要两个通道、需要亚像素精度、需要负值。塞进颜色目标的 alpha 通道既不够精度，也会和
   后续的 alpha 混合语义打架。
2. **同一帧里既写又读**。速度 pass 写自己这张，同时颜色目标与世界深度都在被采样；D3D12 不允许
   同一资源同时是 RTV 与 SRV。独立 RT 让「写速度」和「读深度/颜色」互不干扰。
3. **vanilla 拿不到逐物体运动**。实体变换提交时烘进顶点（`ModelPart.render`），
   `DynamicTransforms.ModelViewMat` 只是相机 model-view，连续几何还会合并提交，绘制级没有稳定
   物体身份；早期 MRT 直接写运动矢量的方案拿不到位移，已删掉，改成现在的「深度反投影 + 物体表」。
4. **尺寸与生命周期独立**。速度缓冲跟着渲染分辨率走，GUI 目标跟着窗口走；两者的重建时机、
   失败处理、是否被帧生成借用都不一样。混在一张目标里，任何一边 resize 都要同时照顾另一边。

代价是每帧多一张全屏 pass 和一次深度采样；换来的是 TAA 与 DLSS 共用同一份运动矢量，两条链路
不会各自维护一套。

## 5. 为什么 GUI 不参与

GUI 不参与抗锯齿，也不参与速度缓冲，原因分三层：

1. **时序**：GUI 在抗锯齿之后才画，TAA / SMAA 都在「世界画完、GUI 未画」那一刻执行，界面天然
   不被处理。早期把 SMAA 放在最终 blit 之前时，世界与 GUI 已合成完毕，界面文字被一起柔化 ——
   这就是「GUI 发虚」的来源；TAA 同理，resolve 之后再画 GUI，界面保持像素锐利。
2. **语义**：速度缓冲是「世界坐标 → 屏幕」的重投影，需要深度。GUI 没有世界坐标、没有可信深度，
   对它算运动矢量没有意义；把 GUI 混进去只会让文字随镜头轻微拉伸。
3. **尺寸**：GUI 的 scissor 按**窗口像素**算，世界层按渲染分辨率。两者在渲染缩放不等于 100%
   时尺寸不同，混在一张目标里 vanilla 的 scissor 校验会直接抛异常（越界）或把界面裁到左上角。

所以拆分不是「为了好看」，是正确性要求；它同时又是帧生成的前提，见下一节。

## 6. GUI 拆分与 hudless

### 6.1 拆分解决什么问题

`D3D12GUISplit` 维护一张**窗口尺寸**的渲染目标（`DXRMC GUI`，`RGBA8_UNORM`，深度格式跟随
主目标：主目标有深度就用 `D32_FLOAT_S8_UINT`，否则 `D32_FLOAT`）。它有两个用途，共用同一张纹理：

1. 渲染缩放 ≠ 100% 时，让 GUI 待在自己的窗口尺寸目标里，scissor 与世界层尺寸解耦；
2. 帧生成的 hudless 模式下，它就是交给后端的 **UI 纹理**。

启用条件是 `D3D12RenderScale.isEnabled()`（`percent != 100`）**或** hudless 已武装。100% 且
非 hudless 时整体关闭，行为与 vanilla 完全一致 —— 这条「零行为变化」的默认路径是刻意保留的。

### 6.2 一帧里的搬运

`prepareForGui()` 在 SMAA / SR 之后调用：

1. 清 `preparedThisFrame` 与 `frameTarget`；
2. 读走并清零 `worldLayerDoneThisFrame`（DLSS 在更早的挂点已经把升采样结果直写进 GUI 目标）；
3. hudless 已武装时**无条件**走拆分（100% 缩放也不能让界面落回世界层）；
4. 否则主目标真实尺寸就等于窗口尺寸时直接返回 —— 不需要拆分；
5. `ensureTarget()` 建/校正目标；**先定 `frameTarget` 再考虑 blit** —— 界面画在哪与搬运成败无关，绝不能因为 blit 失败把界面塞回世界目标（会重新触发 scissor 越界）；
6. 清 GUI 目标的深度（vanilla 在 GUI 之前会清一次主目标深度，界面换了目标就要补同样的清）；
7. hudless 分支：把 UI 层清成全透明 `(0,0,0,0)`，不做世界层搬运，`preparedThisFrame` 保持
   `false`；
8. 世界层已就位（DLSS 直写）时只置 `preparedThisFrame = true`；否则
   `recordTextureBlit(world, gui)`，线性采样放大/缩小，成功才置位。

第 7 步的透明清屏有明确理由：vanilla `BlendFunction.TRANSLUCENT` 的 alpha 通道是
`ONE / INV_SRC_ALPHA`，所以「透明底 + 普通混合」得到的正好是「按自身 alpha 预乘的 RGB +
正确的覆盖 alpha」，与 FSR-FG 的 `USE_PREMUL_ALPHA` 合成约定一致。不清成全透明，UI 层会带着
上一帧的残留参与合成。

### 6.3 hudless 武装

`hudlessArmed` 不是每帧重算，而是在 **present 之后**裁决一次（`armUiComposition()`）：

```
want  = 目标已创建 && FrameGeneration.hudlessWanted() && SR 后端 != DLSS
ready = want ? MCDR_ABI.D3D12FgUiCompositionReady() : 0
able  = want && ready == 1
```

- `FrameGeneration.hudlessWanted()` = 帧生成开着 **且** 后端是 FSR-FG **且** 当前有 3D 世界
  （菜单 / 加载屏没有可插帧的世界层，那两处直通合成层）。
- `D3D12FgUiCompositionReady()` 回答的是「SDK **现在真的在合成 UI** 吗」，判据是链已包 +
  UI 纹理已交 + 已经发生过插帧 dispatch。它**不回答**「hudless 开没开」—— 否则第一次打开时
  会因为还没打开而永远打不开。
- 状态变化时才调 `D3D12FgSetUiComposition(able)`，返回 1 才认账；`hudlessArmed = able && pushed`。
- 「想开但后端还没在合成」有专门日志，10 秒最多一行
  （`NOT_READY_LOG_INTERVAL_NANOS = 10_000_000_000L`），因为「界面整层消失」和「没武装」
  在画面上看起来一样，只能靠日志区分。

为什么放在 present 之后：世界层的去向（搬进 GUI 目标 vs 直接呈现）必须与**本帧** SR 的行为
一致，而 SR 的挂点排在 `prepareForGui()` 之前。只有在上一次 present 之后定下来，下一帧的 SR、
界面与最终 blit 才看到同一个值。同一帧里不换模式。

### 6.4 呈现源怎么选

`presentSourceTexture(fallback)` 是最终 blit 的唯一决策点：

1. hudless 武装且本帧世界层有效 → 世界层（SR 输出）；世界层已释放则回退 `fallback`
   （换世界时旧纹理可能已经关了，宁可回退也不能把野指针交给 blit）；
2. 本帧真的把世界层搬进了 GUI 目标 → GUI 目标的颜色纹理（世界 + 界面已合成）；
3. 其余 → vanilla 传进来的主目标，与原路径完全一致。

`replayTarget()` 在 `!preparedThisFrame`、已放弃、hudless 三种情况下返回 `null`，这是给 DLSS
准星补画用的：拆分启用时界面画在 GUI 目标上，往主目标画等于画进没人读的纹理；hudless 时
GUI 目标只是 UI 层，也不该补进去。

### 6.5 和帧生成的耦合

耦合点不止「交一张纹理」，而是**同一张目标同时扮演三个角色**：

- 渲染缩放下的 GUI 画布；
- 帧生成后端的 UI 纹理（`MCDR_ABI.D3D12FgSetUiTexture(handle)`，只在目标创建/重建时推一次，
  同一 handle 不重复推；`Int.MIN_VALUE` = 本地 DLL 没有这条导出，只记一次）；
- hudless 模式下的「UI 层」，与世界层分开呈现、由后端合成。

由此产生几条必须一起改的约束：

- 目标尺寸变化后要把 UI 纹理**重新推一遍**：FFX 在注册那一刻就记下了资源描述与状态，尺寸不对
  会直接踩坏合成（即使句柄没变）。
- DLSS 路径被显式排除在 hudless 之外：DLSS 的 `pInOutput` 直接写 GUI 目标，没有独立的世界层
  纹理可交出去；它同时又是 DLSS-G 的 hudless 来源，两套语义不能混用。
- SR 成功写完世界层后要调 `markWorldLayer(texture)`，`prepareForGui()` 据此跳过搬运；
  合成模式下 SR 仍要自己把世界层 blit 进 GUI 目标，这个标记只用于 hudless。
- 拆分一旦放弃（`givenUp`），`presentSourceTexture` 永远走 fallback，`replayTarget` 返回
  `null`：宁可少一层效果，也不要每帧在两张目标之间摇摆。

### 6.6 失败与兜底

- 目标创建失败：`givenUp = true`，强制 `D3D12RenderScale.setPercent(100)` 并
  `gameRenderer.resize(...)`。这是唯一安全的兜底 —— 没有窗口尺寸的界面目标时，界面只能回主
  目标，而主目标比窗口小就会 scissor 越界。
- blit 失败 / 拿不到纹理 / 没有 encoder：`degrade(reason)` 只打一行（状态变化时才打），
  界面继续画在自己的目标里，这一帧只是呈现的是世界目标。
- `targetForFrame()` 里还有一次**迟到 resize** 校验：窗口可能在 `prepareForGui()` 之后、
  `GuiRenderer.draw()` 之前又变了尺寸，目标只要小一点，vanilla 的 `enableScissor` 就会抛异常。
- `ensureTargetForDlss()` 是独立入口：`targetForFrame()` 在主目标等于窗口时会早退返回 `null`，
  但 DLSS 的 `pInOutput` 必须是窗口尺寸目标。「需不需要拆分」和「SR 需不需要输出目标」是两件
  事，不能共用一个早退。

## 7. 后处理锐化

TAA 的 resolve 以 `lerp(history, current, 1/16)` 结束，本身就是低通；DLAA / DLSS 在 100% 缩放时
也没有升采样帮忙补细节。所以两条链路之后都跟一条 5-tap unsharp，native 一条全屏 pass
（`dx12SharpenTextureToTexture`）。不能指望 SDK 自带锐化：Streamline 头文件把 DLSS 的锐化定义成
`SR_DEPRECATED_SHARPENING`（"Sharpness is not supported"），NGX 侧传的 `InSharpness = 0`。

强度（都是 `0..1`，`0` = 完全关闭，调用方回退普通 blit）：

- DLAA / DLSS：`DEFAULT_AMOUNT = 0.67f`，配置 `[renderer] sharpenAmount`；实测 0.9 过头。
- TAA：`TAA_AMOUNT = 0.816f`，配置 `[renderer] taaSharpenAmount`；resolve 比 DLAA 软，同一个
  0.67 不够，实测 0.9 略过头。

两条硬约束：

1. **锐化绝不写回 TAA 历史**。历史里存锐化后的颜色，会让锐化随每帧的 resolve 递归叠加，
   产生振铃（ringing）—— 画面边缘出现一圈亮暗交替的光晕。
2. **native 拒绝 `src == dst`**（`blitTextureToTexture` 与 `sharpenTextureToTexture` 都检查），
   所以就地锐化必须准备第二张同尺寸纹理。

DLAA / DLSS 的落地方式因此是两趟：

```
output（GUI 目标或主目标） --sharpen--> scratch --blit--> output
```

`scratch` 是 `RGBA8_UNORM`，用途 `RENDER_ATTACHMENT | TEXTURE_BINDING | COPY_SRC`（既能当 RTV
写，也能被第二趟采样），按需创建、尺寸变化重建。

TAA 的锐化不是单独一趟：`sharpenAmount` 作为参数进 native 的 resolve，回填那一趟直接按 5-tap
算，省掉一张中间的 scratch。TAA 侧是否锐化由传进去的 `taaSharpenAmount` 决定，与 DLAA 的
`sharpenAmount` 各自独立 —— 两个配置可以一个为 0、另一个不为 0。

导出探测是一次性的：`probeSupport()` 用 `skipOptional` 绑定
`mcdr_dx12SharpenTextureToTexture`，旧 DLL 没有这条导出时整条链静默退化成直通 blit，只记一次
警告。`isEnabled(strength) = strength > 0`。

## 8. 关键常量

| 常量 | 值 | 位置 / 含义 |
| --- | --- | --- |
| TAA 混合权重 `BLEND` | `0.0625f`（1/16） | `D3D12Taa.kt`，`lerp(history, current, BLEND)` |
| 抖动序列长度 | `16` | `D3D12Temporal.DEFAULT_JITTER_SAMPLES` |
| 抖动值域 | `[-0.5, +0.5)` 像素 | Halton(2,3) 减 0.5 |
| 抖动注入下标 | 投影矩阵 `[8]` / `[9]` | 列主序 `m[0][2]` / `m[1][2]` |
| 速度缓冲格式 | `RG16_FLOAT` | 两张乒乓，`beginFrame` 同时换 |
| 速度常量缓冲 | `256` 字节（实用 172） | `CBV_SIZE` |
| 物体表上限 | `256` 行 × `16` float = `16384` 字节 | 超出丢弃 + 每秒最多一行 warn |
| 物体距离裁剪 | `96.0` 格 | `MAX_DISTANCE_BLOCKS` |
| 物体 AABB 余量 | `0.25`（HLSL 写死） | Kotlin 侧另有 `0.35f` 常量但**从未被引用** |
| 物体自身位移上限 | `128.0` 像素 | 超过则回退相机重投影 |
| 半宽下限 | `0.01f` | `max(bbWidth * 0.5, 0.01f)`，防止零宽 AABB |
| 负 w 守卫 | `1e-4` | `prevClip.w <= 1e-4` 直接输出 0 |
| DLAA / DLSS 锐化 | `0.67f` | `DEFAULT_AMOUNT` / `[renderer] sharpenAmount` |
| TAA 锐化 | `0.816f` | `TAA_AMOUNT` / `[renderer] taaSharpenAmount` |
| 锐化 scratch 格式 | `RGBA8_UNORM` | 需 `RENDER_ATTACHMENT | TEXTURE_BINDING | COPY_SRC` |
| GUI 目标格式 | `RGBA8_UNORM` | 深度跟随主目标（`D32_FLOAT_S8_UINT` / `D32_FLOAT`） |
| hudless 未就绪日志节流 | `10` 秒 | `NOT_READY_LOG_INTERVAL_NANOS` |
| SMAA 趟数 | `3` | native 三阶段，最后一趟经 scratch 拷回 |

## 9. 容易踩的点

1. **jitter 的符号约定**：注入投影用 `-clipJitter`，上报给 DLSS 与速度缓冲补偿用 `(+Jx, -Jy)`；
   三处不一致就会「翻符号照样抖」。
2. **本帧投影必须抖动后再乘 view**，否则反投影逐像素差半个像素，边缘发虚。
3. **`prevClip.w <= 1e-4` 不能省**：负 w 会把位置镜像到画面另一处，症状是沙地泛白、树叶发紫。
4. **不要用 JOML 求逆**：列主序与 `set/get` 的行主序约定混用会把结果转置，表现为整幅反向拖影。
5. **`"3d hud"` 的投影不能抖**：它不参与时间性重投影，抖了只会让物品栏与手持物错位。
6. **同一帧只能推进一次**：投影每帧可能上传多次，`beginFrame()` 用 present 计数而非时间窗判定；
   时间窗在低帧率下会误判，导致 jitter 多滚一次、整幅画面逐帧跳动。
7. **TAA 历史不要自己拿**：JVM 持有会在传送、改尺寸时踩掉还在飞的命令列表（实测能打掉设备）。
8. **锐化不能写回历史**，也不能 `src == dst`；DLAA / DLSS 必须走 scratch 两趟。
9. **SMAA 挂点的意义**是「只处理世界层」，挪回最终 blit 之前就会把 GUI 一起柔化。
10. **`D3D12Temporal.reset()` 只有 DLSS 退出会话一个调用点**；传送 / 换世界 / 切分辨率没有显式
    接线，最需要复核。
11. **Kotlin 的 `OBJECT_BOX_MARGIN = 0.35f` 是死常量**，实际生效的是 HLSL 的 `0.25`，边界处
    可能漏判。
12. **`hasJitterConsumer()` 用的是 `isSelected()` 而非 `isActive()`**：TAA 自我关闭后 jitter 仍
    注入但没人消费，画面会轻微抖。

## 10. 文件导航

路径相对于 `Illusion\src\main\kotlin\dev\nts\expdxr\`。

| 文件 | 职责 |
| --- | --- |
| `d3d12/render/advanced/D3D12AntiAliasing.kt` | 模式选项（OFF / SMAA / TAA / DLAA）、options.txt 读写、DLSS 互锁、画质预设映射 |
| `d3d12/render/advanced/D3D12PostSharpen.kt` | 5-tap unsharp 的强度、scratch 纹理、导出探测与两趟落地 |
| `d3d12/render/advanced/taa/D3D12Taa.kt` | TAA 的 JVM 侧薄壳：选模式、传 jitter / 代数 / 锐化量、失败自关闭 |
| `d3d12/render/advanced/taa/D3D12Temporal.kt` | 时间性数据面：Halton 抖动、投影注入、相机矩阵快照、历史代数的唯一来源 |
| `d3d12/render/advanced/taa/D3D12Velocity.kt` | 速度缓冲：双 RT 乒乓、常量块、内联 HLSL 全屏 pass、闩锁与资源释放 |
| `d3d12/render/advanced/taa/D3D12ObjectMotion.kt` | 逐物体快照：位置外推、可见性/距离过滤、物体行缓冲 |
| `d3d12/render/advanced/smaa/D3D12SmaaWorld.kt` | SMAA 的世界层挂点与「确实跑了」的结论性日志 |
| `d3d12/render/advanced/smaa/D3D12GuiSplit.kt` | 窗口尺寸 GUI 目标、世界层搬运、hudless 武装与呈现源选择 |
| `d3d12/backend/D3D12CommandEncoderBackend.kt` | 把四条 pass（velocity / SMAA / TAA / 锐化）追加进本帧命令列表 |
| `d3d12/render/core/D3D12GpuSurface.kt` | 最终 blit 与 present：选呈现源、录兜底速度 pass、帧边界裁决 hudless |
| `dxrmc/sr/dlss/DLSS.kt` | DLAA / DLSS 的升采样与锐化两趟、退出会话时的时间性复位 |
| `dxrmc/framegen/FrameGeneration.kt` | `hudlessWanted()`：帧生成后端与「有没有世界层」的判定 |
| `misc/settings/D3D12Settings.kt` | 配置到运行期快照的解析（含已弃用的 `-D` 逃生口） |
| `IllusionConfig.kt` | TOML 定义：`sharpenAmount`、`taaSharpenAmount`、`temporalChain`、`temporalJitter` |

Java 侧挂点（不在上面的相对路径下）：

| 文件 | 职责 |
| --- | --- |
| `Illusion\src\main\java\dev\nts\expdxr\mixin\render\GameRendererSmaaWorldMixin.java` | 抗锯齿 / SR / GUI 拆分的唯一顺序挂点，以及每帧的 DLSS 记账 |
| `Illusion\src\main\java\dev\nts\expdxr\mixin\render\LevelRendererMainPassDepthMixin.java` | 主 pass RETURN：采集时域数据并录速度 pass（深度被清之前） |

## 11. 文档维护

- **事实来源是代码**：每条数值都对应一个 Kotlin 常量或配置默认值，改代码时顺手改第 8 节常量表
  与第 2.7 节配置表。
- **改抗锯齿前先读第 1 节的顺序表**：TAA → SMAA → SR → prepareForGui 的次序不能挪，否则会打破
  「SMAA 在世界分辨率上工作」与「GUI 不被柔化」这两个前提。
- **新增时域消费者时**，同时检查 `D3D12Temporal.isActive()` 与 `hasJitterConsumer()`（前者决定
  数据面滚不滚，后者决定 jitter 注不注），只改一处会出现「抖但没人补偿」或「有消费者但没数据」。
- **新增 GUI 目标用途时**，同步 `pushUiTextureToFrameGen` 的重推时机与 `presentSourceTexture`
  的优先级，这两处是耦合最紧的地方。
- 本文不写问题编号、会话记录或仓库外的排障文档；指代历史决策用描述性说法。超分与帧生成的细节
  见同级 `superresolution`、`framegen` 目录。
