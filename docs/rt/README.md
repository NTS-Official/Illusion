# 光线追踪：配置、几何、硬件路径与降噪接口

> **当前实现**：内置包为 B-DXR，生产追踪与场景自检均通过 BLAS/TLAS、状态对象、SBT 和 `DispatchRays` 执行；太阳遮挡使用独立 inline `RayQuery`。最低要求 DXR 1.1。`AUTO` 使用 DXR；WG 调度尚未实现，选择 WG 会报告回退 DXR。不支持 DXR 1.1 时关闭光追。
>
> **RAW 模式**：`[rt] denoise = "RAW"` 保留硬件追踪与合光，跳过降噪和时域重建。启动时自动迁移旧包名和旧后端、无降噪配置；迁移不提供软遍历兼容路径。
>
> **已知限制**：缺少对应材质的光源方块仍可能显示为白色，材质体素视差与 RR 阴影稳定性待独立目视验收。首次加速结构未就绪或没有有效快照时整帧直通原版；已有快照的变更按 section 体积屏蔽受影响光线。失效 section 包含相机时可能影响整个视野，失效边界仍需验证。统一 native 帧录制与提交确认边界已接入，完整空间/内容协议、世界内噪点与性能仍待验收。不同降噪器与 SR 方案分别验收，不设最终图像一致等级。

下面按接口与资源列出配置、日志、查询、几何导出、G-Buffer、追踪、降噪和合光。
DXR 的状态对象、SBT 与 `DispatchRays` 接线见 §16，NRD、RR 与 SR 适配见 §18 及后续章节；
native 已拥有几何 GPU 快照、BLAS/TLAS、场景候选/发布/局部失效、G-Buffer 附件、批次快照与回放、生产追踪、
raw/guide/信号资源、曝光、降噪、独立 SR 与合光资源、SDK 调度及历史；JVM 仍管理 CPU 几何池与范围选择、
G-Buffer 着色器生成与捕获通知、调试绘制与上屏 blit。生产录制使用统一 native 帧事务，见 §16.10。
世界几何的采集与 meshlet 在 [`mesh&vrs/README.md`](../mesh&vrs/README.md) §2.1 里。

## 1. 配置

全部在 `config/dxr_mc-client.toml` 里：`[rt]` 段的键改完不用重启，下一次 `pushToNative()` 生效；
`[shaderpack]` 段只有一个键，决定光追使用的 HLSL 包，见 §11。

`[rt]` 这组键是**包作用域**的：界面入口只有「视频设置 → 光影包 → 光影包设置」，由包在
`shaders/configs.json` 里声明要暴露哪几项（§11.6）；TOML 是这些值的存储位置，不是给人手改的开关面板。

| 键 | 默认 | 取值 | 作用 | B-DXR 生效 |
| --- | --- | --- | --- | --- |
| `backend` | `AUTO` | `AUTO` / `DXR` / `WG` | AUTO 使用 DXR；WG 当前回退 DXR；低于 DXR 1.1 时关闭光追 | 是 |
| `qualityProfile` | `BALANCED` | `VALIDATION` / `BALANCED` / `QUALITY` | 档位只调参数，不跳门 | 否 |
| `spp` | `1` | 1–16 | 验证 profile 是 1 | 否 |
| `maxBounces` | `2` | 1–8 | 路径最大弹射 | 否 |
| `samplingMode` | `BSDF` | `BSDF` / `COSINE` / `RESERVOIR` | `RESERVOIR` 只用于间接漫反射 | 否 |
| `voxelTrustRadius` | `8.0` | 0–32 | 体素轨的信任半径（米） | 否 |
| `hzb` | `false` | 布尔 | HZB 剔除（第一版只靠 `InstanceMask`） | 否 |
| `historyFrames` | `16` | 1–64 | 历史长度上限，降噪与缓存都夹到它 | 否 |
| `debugView` | `NONE` | 见下 | 调试视图 | 是 |
| `invalidationLog` | `false` | 布尔 | 历史复位时输出原因位和观察帧号，局部几何更新不输出复位日志 | 是 |
| `[shaderpack] active` | `B-DXR` | 包名；空串表示无光影 | 选择 HLSL 包并应用包声明的硬件后端 | 是 |

`debugView` 取值：`NONE` / `DEPTH` / `NORMAL` / `ALBEDO` / `ROUGHNESS` / `HIT_DISTANCE` / `SHADOW` / `COMPOSITE_MARK` /
`DIRECT` / `INDIRECT` / `CACHE_CONFIDENCE` / `REACTIVE_MASK` / `MOTION_VECTORS` /
`HISTORY_REJECTION` / `TRAVERSAL_STEPS` / `DUAL_TRACK` / `GEOMETRY` / `BLAS` / `COVERAGE` /
`PRIMARY_VALIDITY` / `DIRECT_VALIDITY` / `DIFFUSE_VALIDITY` / `SPECULAR_VALIDITY`。
B-DXR 的诊断入口包括：`DEPTH`（第五张线性深度附件）、`ALBEDO`、`NORMAL`（`debugChannel` 模式 4：
`0.5 + 0.5 * n`）、`ROUGHNESS`（`debugChannel` 模式 3，取 `g_NormalRoughness.w`）、
`DIRECT`（合光 `g_resolvePad.y = 1`：只显示追踪出的辐亮度，不乘 albedo、不做边缘淡出，没命中写黑；
整屏黑 = 射线没打到几何，有明暗 = 追踪有东西但画面接近原版）、
`HIT_DISTANCE`（同一分支的 `2` 档：命中距离按 64 格归一化成灰度，没命中写黑），
`COVERAGE`（`3` 档：`albedo.a >= 0.5` 写白，用来判断 G-Buffer 到底覆盖了多大面积），
`SHADOW`（`5` 档：被太阳挡住红、没挡住深灰、未覆盖黑；可见性由 `rt_dxr_lib.hlsl` 写进 `gAux.y`），
分信号有效性视图（`7`–`10` 档）的色标和限制见 §28.3。光影包设置页提供这四个新选项。

每 120 次上屏还会打一行 `rt blit: <n> tries, <k> fail, last <w>x<h>`：`fail` 增长说明合光产出了但没搬上屏（目标是 `null` 或尺寸不符），`source=none` 说明合光本身没返回纹理——两种「画面没变化」的成因靠这一行区分。
以及 `COMPOSITE_MARK`（`6` 档：整屏常量品红，只证明「合光算完并搬上主目标」这条通路通不通）和
`BLAS`（包内 `rt_debug_blas.hlsl` 画实例线框）。
其余枚举值切过去画面会停在合成结果上（不铺任何调试图），它们等各自的 RT pass 出现才接线。

B-DXR 固定 1 spp，分别采样漫反射与 GGX 镜面的一次间接弹射。降噪、倍率和曝光按 §18、§26 生效；采样质量、体素、HZB 等预留参数不改变追踪实现。

## 2. 日志

设备创建后第一次 `pushToNative()` 打两行：

```
RT caps: rt=12 mesh=10 wg=10(queried) vrs=2 sm=0x69 ser=0 dxc=1 sched=WG note=ok
RT config: backend=AUTO mode=3 profile=BALANCED spp=1 bounces=2 sampling=BSDF hzb=false
RT config: history=16 view=NONE invlog=false trustRadius=8.0 pack=B-DXR
```

- `wg=…(queried)`：`unqueried` 表示 `D3D12_FEATURE_D3D12_OPTIONS21` 查询失败，此时
  `workGraphsSupported` 一律为 false——"没问成"和"不支持"必须分开。
- `ser=0` 恒成立：本工程的 Windows SDK（10.0.26100）没有 `OPTIONS22`，等换 `directx-headers`。
- `sched=…`：**只看能力**的首选后端（WG → DXR；无候选时为 OFF）。真实后端还要乘验证状态与用户选择。

关闭设备时打一行影子跟踪汇总：

```
rt state shadow: transitions=… firstUse=… uav=… tracked=… mismatches=… promotions=… widened=… <note>
```

四个计数器的分工：

| 字段 | 含义 |
| --- | --- |
| `transitions` | 经过跟踪器的屏障条目总数 |
| `firstUse` / `tracked` | 首次遇到、只登记不判错的资源数 / 账本里当前资源数 |
| `promotions` | 账本记的是 `COMMON`（D3D12 允许隐式提升到任意读状态，不需要屏障）——**正常情况下它很大** |
| `widened` | 声明的 `before` 是组合掩码、而账本只有其中单个状态（子集关系），合法 |
| `mismatches` | 以上两类都不是的真不一致，附 `<note>` 里的 `decl=/track=` |

2026-10-29 16:49 的实机基线：`transitions=1392095 firstUse=4221 uav=0 tracked=4221 mismatches=0 promotions=607942 widened=0`。
`promotions` 占四成多、`widened=0` 说明真实用到的合法写法是"从 COMMON 隐式提升"，组合掩码那条路本机没走到。

## 3. 查询接口

```kotlin
MCDR_ABI.D3D12QueryRtCaps()?.let { caps ->
    if (!caps.workGraphsSupported) { /* 退回 DXR；DXR 不可用时关闭光追 */ }
    caps.capabilityBackends      // 能力侧候选，按优先级
    caps.preferredBackend
}

MCDR_ABI.D3D12QueryRtTrackStats()?.let { stats ->
    if (stats.mismatches > 0) IllusionLogger.warnMessage("rt state: ${stats.note}")
}
```

两个函数都返回 `null`：前者表示设备还没建或 ABI 缺失，后者表示跟踪器没数据（尚未提交过屏障）。

## 4. 屏障

状态转换一律走 `D3D12Barrier(ctx, descs)`，不要直接调 native 的 `ResourceBarrier`；
`before == after` 会被丢弃，split 必须成对。细节见
[`misc/backend-and-device.md`](../misc/backend-and-device.md) §3.9。

## 5. 阶段 0 的两项实机确认

1. 进世界跑够 500 帧后正常退出，看日志里 `rt state shadow: … mismatches=0`；
2. 3 张 UAV 的 compute 在 S2 接上后，看同一次启动里没有 `dispatchComputeEx` 的报错行。

## 6. 源码位置

| 位置 | 内容 |
| --- | --- |
| `d3d12/extern/MCDR_ABI.kt` | `D3D12QueryRtCaps` / `D3D12QueryRtTrackStats` / `D3D12Barrier` / `D3D12CreateComputePipelineEx` / 第 12 节的七个加速结构入口与 `RtGeometry` |
| `d3d12/extern/MCDR_Layout.kt` | `mcdr_rt_caps`(80 B) / `mcdr_rt_track_stats`(92 B) / `mcdr_barrier_desc`(40 B) / `mcdr_rt_geometry`(64 B) |
| `misc/settings/D3D12Settings.kt` | 配置解析、`RT caps:` 与 `RT config:` 两行日志 |
| `IllusionConfig.kt` | `RtBackend` / `RtQualityProfile` / `RtSamplingMode` / `RtDebugView` |
| `dxrmc/raytracing/D3D12RtGeometryPool.kt` | 顶点 / 索引 / 材质三块导出池、64 B 段记录 |
| `dxrmc/raytracing/D3D12RtMaterialTable.kt` | 图集精灵 → 40 B 材质记录、逐 quad 材质索引 |
| `dxrmc/raytracing/D3D12RtGBuffer.kt` | native G-Buffer 借用元数据、待重放批次、完成发布与旧图像拒绝 |
| `dxrmc/raytracing/D3D12RtGBufferShader.kt` | 地形 VS/PS → `ms_main` + `gbuf_main` |
| `dxrmc/raytracing/D3D12RtTrace.kt` | 主光线 + 一次弹射 compute、四场景自检 |
| `dxrmc/raytracing/D3D12RtFrame.kt` | 逐帧派发、合光、调试视图、镜像与常量槽轮转 |
| `dxrmc/raytracing/D3D12RtFrameConstants.kt` | `WgaFrame`（416 B）的 Kotlin 镜像 |
| `dxrmc/raytracing/D3D12RtShaderSources.kt` | 按包读 HLSL、展开 `#include`、三级覆盖查找 |
| `dxrmc/raytracing/D3D12ShaderPacks.kt` | 光影包发现、`pack.json` 元数据、兼容性、选中与应用 |
| `dxrmc/raytracing/D3D12RtStats.kt` | `rt_stats.json` 行与 `rt stats:` 日志 |
| `dxrmc/raytracing/D3D12RtBlas.kt` | 第 13 节的加速结构：每段一条 BLAS、每帧一条 TLAS、实例表与压缩 |
| `mixin/render/LevelRendererRtDebugViewMixin.java` | `debugView` 把附件铺到主目标 |
| `d3d12/render/advanced/mesh/D3D12SectionGeometry.kt` | 上传采集入口、`Layer` 枚举、契约视图 |

## 7. 导出几何缓存（S2）

`D3D12RtGeometryPool` 在上传时把每段每层的顶点、索引与逐 quad 材质索引复制进三块只增池，供
B-DXR 的硬件追踪着色器当 `ByteAddressBuffer` 读。仅在 `D3D12Settings.rtEnabled` 为真时收集；
`AUTO` 与 `DXR` 都可以使用该缓存，未选包、mode 为 OFF 或硬件不支持 DXR 时关闭。

为什么不直接复用原版 Uber 缓冲的切片：切片只在提交当帧有效，Uber 缓冲扩容或整理会让句柄失效
（`D3D12SectionGeometry` 的头注释记录了这条）。跨帧稳定可读的缓冲要等 S3 的
`mcdr_dx12BufferGpuAddress` 与 AS 管理器。

用法：

- 写完几何不用通知谁——`captureUpload` 在 `geometryVersion` 变化时自己调 `publish`；
- `D3D12RtGeometryPool.summary()` / `layerSummary()` / `validate()` 是一行的规模、分层与自检；
- 周期日志（每 1024 次上传）末尾追加 `rt geometry pool: …` 与 `bad=`，验收看这两个数。

验收口径：`triangles=` 与同一行的 `section geometry: … triangles=` 同量级（池只收 2-a 生效后上传的段，
首次会偏小）、`bad=0`、`skipped=` 持续增长（说明没在重复拷贝）。

容量与回收（三类 arena 各自封顶：顶点 768 MiB、索引 96 MiB、材质 96 MiB）：

- **半透明层不收**（`hidden=` 计数）。当前三角形池不提供完整透明覆盖，不能将未收录的层当作已知空区域。
- **容量不足时撤销无法更新的旧内容**。分配先复用空闲区间，再按需淘汰 dormant；失败时报告
  `RT pool: storage budget exhausted`，不清空其它有效记录，也不保留已被编辑推翻的旧 live 几何。
- **后续上传可重试**。同内容 dormant 重入不增加分配；其它完整上传在空间释放后重新申请。
  当前缺失要求进入有界局部重捕获，持续失败时退避。容量缺口也可能影响近场，不能只按远处不可见处理。

段的所有权、实际释放、内存统计及快照边界见 §28.1。`full` 是最近分配失败的状态，不是永久停收开关。

一条记录 64 B，布局：`originX/Y/Z`(int3)、
`layer`(u32)、`vertexOffset`/`indexOffset`(u64)、`vertexCount`/`indexCount`(u32)、
`indexStride | flags << 8`(u32)、`geometryVersion`(u32)、`materialOffset`(u32)、`quadCount`(u32)、
`sectionNode`(u64)。`D3D12RtGeometryPool.recordBytes()` 按 `(sectionNode, layer)` 排序导出。
`flags` 取 `D3D12SectionGeometry.Layer.flag`，`layer` 取同一个枚举的 `ordinal`。
CPU active/dormant 索引使用完整的 `(sectionNode, layer)` 键，禁止通过左移 section 编码拼接 layer 而丢失高位。

**世界载入后开启光追与缺失内容恢复**：关闭 RT 时仍由上传钩子登记当前非空层要求，撤销被编辑推翻的旧载荷，
不把新的几何字节存入 RT 池。开启后，`D3D12RtGeometryRecovery` 从缺失要求中轮转选择恢复目标，
要求本身与调度顺序独立；调度不推进几何 revision，不改变已导出的版本表字节。

- 在 `ExtractLevelRenderStateEvent` 的主线程提取阶段，最多检查 64 条要求、准备 2 个 region；
  提取间检查 1 ms 软时间片，不中断单次 `RenderRegionCache.createRegion`。
  中心及周围 3×3 chunk 均已加载才复制，附带原版 modelData 和 NeoForge additional renderers；不得为恢复强制加载区块。
  尚为 `CompiledSectionMesh.UNCOMPILED` 的已加载来源同样可准备首次编译，不等待一份已经安装的 mesh。
- 在 `LevelRenderer.compileSections` TAIL、原版任务已经提交后，调用 `RenderSection.compileAsync`。
  不经过原版 NEARBY 的同步编译选择，不作废全景几何，不以记录总数代替缺口检查，也不依赖视锥。
  恢复在途上限为 4；原版编译队列已有 4 项或无空闲 buffer 时延后。
- 准备与提交之间复核 world、epoch、ViewArea、sectionNode、section 对象、已安装 mesh、编译任务身份与当前缺失版本。
  已有未取消的编译任务必须等其结果实际安装；CPU 任务完成不表示 GPU 安装完成。
  旧任务晚到的 mesh 不结束新任务的等待，真实空网格也必须经过安装事件。
- `RenderRegionCache.createRegion` 在复制前取得当前 section 的 `CompileScope`，冻结中心 ±1 的 27 个
  `SectionCopy` 输入段各自的内容代次、当前 `modelRevision`、block/fluid 模型集合身份，以及活动编译器身份与 `compilerRevision`；
  region 携带该令牌进入编译任务。
  cache 的 world 或 `contentRevision` 改变时，先清除缓存的 `SectionCopy`，再复制；已导出的 region 仍持有自身数据。
  创建任务时绑定 world identity、geometry epoch、sectionNode 与 region 的原始令牌，不在任务创建时刷新令牌。
  安装时同时认证当前未取消任务、实际结果身份、绑定范围与当前内容令牌。
  `SectionCompileTaskMixin` 在五参数 `SectionCompiler.compile` 调用处记录实际接收者，
  由 `D3D12RtCompilerInputs` 读取其实际 `blockModelSet`、`fluidModelSet` 与 `blockColors`。
  安装时编译器对象必须与原 scope 及当前未关闭 dispatcher 一致；相同模型集合不能替另一编译器补授资格。
  仅记录模型集合对的入口不取得活动编译器身份资格。
  编译来源只对已经绑定且尚未安装的任务记录有效；事后绑定 scope 不能为先前捕获的结果补授资格。
  编译开始先撤销该任务先前捕获的结果；实际集合对与 scope 不符、未记录编译来源或资源代已经失效时，
  非空结果与共享 EMPTY 均不得安装。拒绝结果后按当前任务身份重排 dirty，不能让错误编译器的任务永久阻塞恢复。
  EMPTY 的对象身份是共享的，必须在调用安装的任务处再次校验当前生产者；非空上传回调也只接受当前任务结果。
  拒绝安装时将传入结果交还原版释放，不能返回当前仍在使用的旧 mesh。
- section reset 先撤销任务资格，再由原版回收绘制表示。真实 chunk 仍登记该段，且当前 `CompileScope`、
  完整安装层清单与全部当前载荷均有效时，`removeSection` 保留 RT 内容和原始依赖索引；EMPTY 也须有完整来源证明。
  保留集最多 1024 段、128 MiB，字节包含顶点、索引、逐 quad 材质与每条 64 B 记录，EMPTY 仍占一个段名额。
  重复 reset 不刷新淘汰顺序。先达到数量或字节上限即撤销最早保留来源，转为整段 unknown 恢复要求。
  不完整、未认证或单段超过字节上限的来源同样保留整段要求，不把表示回收或淘汰解释为来源卸载或已知空。
  保留集共用现有 arena 上限，与供字节重用的 dormant 缓存分别计量；`storageStats` 的 `retainedSourceSections` /
  `retainedSourceBytes` 显示仍具当前资格的保留子集。
  dirty、邻接输入、tint/light、编译器或模型资源变化、实际卸载与世界切换沿原依赖撤销 scope 和内容。
  保持未恢复段原有的 4/5 空间状态与最早缺口年龄，不因 reset 推进输入内容代次。
  注册、安装及撤销使用 section 临界区，
  不将原版 reset/cancel 或 UberBuffer 的 copyLock 包进同一临界区。
  `commitCurrentUpload` 在几何锁内复核 world/epoch/内容令牌，不允许晚到旧任务切回旧世界或恢复编辑前的内容。
- `LevelExtractor.setSectionDirty(IIIZ)` 推进实际输入段的内容代次，原子撤销该段及依赖它的已观测中心段的
  内容令牌、旧 RT 载荷和旧层要求；每个待编译段保留独立 node 目标。
  原版的邻接 dirty 分发仍照常执行；注册表直接核验 27 段依赖，不依赖它覆盖所有旧编译结果。
  派生中心段的失效不再充当新的内容源事件，也不扩散至其余邻段。与当前 world 不同的回调直接忽略。
  此规则在 RT 关闭时仍生效。旧 region 的 EMPTY 结果也不能解除目标；新内容完整安装后才由真实层清单替换等待状态。
  当前无 terrain draw 的已编译结果可解除该目标，但不能据此宣称整个体素空间都是空气。
  当前任务因内容令牌过期被拒绝时，在主线程复核 world/epoch/node/任务身份后重新标 dirty；
  已取消、已完成或被替换的任务不重排，避免丢失后续编译机会或干扰新任务。
- 客户端在 mod bus 注册 `ModelEvent.BakingCompleted`；模型管理器发布新的 `BlockStateModelSet` 或
  `FluidStateModelSet` 后，`observeModelResources` 按两者的对象身份推进 `modelRevision` 与 `contentRevision`，不改变世界 epoch。
  在 section 注册表 → RT 池的锁顺序下撤销旧编译令牌、未提交采集、活动 RT 载荷与全部 dormant 内容，
  同时清除材质查找快照和旧材质历史资格。当前已加载 chunk 的全部来源段，以及额外已观测生产者，
  保留无界整段要求，等待新资源代的完整安装；未编译、先前为空或已经回收渲染表示的来源也参与撤销。
  同一段已有缺层或整段缺口时保留其中最早的 `sinceNanos`，资源代变化不重新计算等待年龄。
  卸载状态与 chunk 对象身份保持有效，不把旧 dormant 内容恢复成当前几何。
  同一模型集合对的重复通知不撤销当前任务、不重置材质历史；仅流体集合变化同样失效，准备阶段不发布新的模型代。
  原版仍持有的 mesh 元数据继续按对象身份保留，不能以 RT 资源失效为由删除当前光栅绘制所需的记录。
  新资源代的空/非空结果都须通过当前 `CompileScope`，旧结果不得借共享 EMPTY、同尺寸载荷或同坐标重入恢复资格。
- `SectionRenderDispatcher.setCompiler` 发布新的活动编译器时，同锁撤销旧 scope、未提交采集、活动与 dormant RT 内容，
  保留当前已加载来源和整段恢复要求及最早缺口年龄。`ambientOcclusion`、`cutoutLeaves` 和 `blockColors` 是编译器的 final 输入，
  更换编译器即建立新 `compilerRevision` 与 `contentRevision`，不推进世界 epoch 或模型资源代。
  发布相同对象保持幂等；其它 dispatcher 的设置不影响当前世界。原版编译提交尾部与绘制提取前复核活动对象链，
  安装校验同时直接读取实际 dispatcher，不能等待下一次观察才拒绝旧结果。
- `BlockColors.register` 修改活动编译器的同一颜色表时，经相同的注册表 → RT 池锁顺序撤销旧内容与编译资格，
  再执行实际注册；部分注册后抛错也不能保留先前资格。关闭 RT 时仍撤销，修改无关颜色表不撤销当前任务。
  已知 JDK/Kotlin 列表复制为不可变列表，拒绝从原列表、iterator 或 sublist 修改实际注册内容。
  未知列表保持原行为且不在冻结时求值；颜色回调对象自身的可变状态、实时 biome/tint、queued/aux light 与 model data
  依赖仍须单独认证。编译器与注册表发布版本不能代替完整内容证明或空间状态 1/2。
- `ModelBlockRenderer` 的实际 `colorInWorld` 调用和 `getTintColor` 的正常/异常返回边界检查方块着色来源。
  只接纳十二种已查证原版 `BlockTintSources` 工厂的精确回调类型、冻结列表或已查证不可变列表，
  并核对当前编译器 `BlockColors`、state 与原始注册列表身份。原回调、未知列表及动态扩展的 body 均在注册表锁外执行。
  已计算槽位和快速命中同样检查所选来源；未使用的未知槽位不连带撤销已知槽位，注册替换后不准复用旧 renderer 缓存。
  任意 `BlockTintSource` 与非默认 `IClientBlockExtensions` 保留原色值、异常与命中，但不得认证当前编译内容。
  原版默认扩展的白色和范围外槽位保留；其它 callback/model-data 仍需单独证明。
  此检查不授予空间状态 1/2，也不代替完整内容依赖、资源预算或世界验收。
- 静态 tint 的每次真实计算保存所选槽位的输入凭据，快速命中与已计算槽位消费时同锁复核并登记实际依赖。
  常量、redstone、stem 与 `waterParticles` 的 world 颜色只依赖已查证来源和当前 state，不增加 biome/cache 依赖。
  七类 biome 来源必须来自当前 exact `ClientLevel` 或绑定该世界的 exact `RenderSectionRegion`，
  并实际完成一次匹配固定 resolver、查询列和槽位的 canonical cache 读取；双高草上半部使用下方位置。
  凭据保存计算位置、世界 epoch 与 cache/列版本，RT-off 预热也可在新 scope 消费，消费不重跑原回调。
  局部/全局 cache 失效、biome 发布及颜色表变更通过原反向索引撤销实际消费者；值往返不能恢复旧凭据。
  位置或 world 不匹配、任意 getter、回调异常、嵌套计算及读取/返回之间发生发布时保持内容未知。
  未使用的 world 来源不影响纯来源槽位；原色值、异常和缓存语义保留，真实来源 body 保持锁外执行。
  renderer 按槽位持有凭据；凭据弱引用 world/cache，不保留编译 scope，reset 后下一次 tint 初始化清理槽位。
  `tesselateBlock` 的正常结束与异常出口调用原 `resetTintCache`，不将私有暖缓存跨 scope 探针当作普通方块跨次绘制的复现。
  此机制只认证实际静态 tint 输入，不代替其它动态输入、完整内容/空间状态、总预算或世界验收。
- `ClientExtensionsManager.BLOCK_EXTENSIONS` 的实际静态字段发布接管新建的空 reference map，
  以 `@Shadow @Final @Mutable` 允许包装操作的合成方法写入该字段，静态初始化仍在发布前完成；
  使用独立 backing 隔离此前保留的构造别名；未知表实现保留原行为且不能取得默认动态 tint 资格。
  `put`、移除、清空、entry 写入、iterator 及 key/value 视图均通过实际有效扩展变化发布，
  在写入前按 block 身份撤销实际读取者的 scope、未提交采集与活动/dormant RT 内容。
  `registerBlock` 的重复注册可能先写入再抛错，多目标也可能部分写入；不要在成功返回后才失效。
  静态 tint、无 tint 读取、无关 block 与有效值相同的 DEFAULT 发布保留资格。
  实际默认动态回调前保存输入凭据，每个暖缓存返回及安装边界复核；DEFAULT→custom→DEFAULT 不能恢复旧凭据。
  普通 RT-off 预热也保存凭据，注册变更不额外调用原回调或改写原色值。
  Map 的 compute/merge/replaceAll 计算函数与动态 tint body 在注册表锁外，实际写入与内容撤销按注册表 → RT 池顺序执行。
  凭据反向索引随 scope 更换、世界/资源/编译器变化清理，registry 的 block 凭据用弱引用保留。
  任意 tint cache 回调中读取动态 renderer 的路径保留原行为并保持内容未知，不借默认扩展认证外层 callback。
  此机制不认证其它动态 model-data、完整内容/空间状态、逐信号合法消费或总预算。
- 实际编译的 `D3D12RtModelSupport.Session` 在 `ClientLevel.getBlockTint` 调用真实 `BlockTintCache.getColor` 前登记读取。
  依赖绑定实际 cache 对象与查询列，可超出 27 段；不从中心段或 biome 混色半径猜测读取位置。
  列键使用原版 cache 的完整 `ChunkPos.pack`，不要用裁剪坐标位宽的 section node 代替。
  同一 session 的重复 cache/列读取先去重，再进入注册表；会话嵌套时恢复原 owner，非编译读取不登记。
  canonical `BlockTintCache.invalidateForChunk` 在注册表 → RT 池锁内先撤销实际读取其 XZ±1 列的 scope、
  未提交采集与活动内容，再执行原版失效；`invalidateAll` 对该 cache 的全部实际读取者执行相同规则。
  无 tint 读取的段、其它 cache 和范围外查询列保留资格；旧 scope 替换及世界/资源/编译器变化同时清理反向依赖。
  getter 的 source 计算在原版 cache 锁释放后执行；登记依赖后即释放注册表锁，再调用 getter。
  因此失效可以撤销仍在 source 计算中的读取，旧返回值不能随后安装；失败或颜色 A→B→A 也不恢复旧资格。
  此规则覆盖实际 cache 的显式失效，不认证 source callback 的外部可变状态、LRU 重算时的外部变化、
  自定义 cache、完整 biome 数据依赖或额外 renderer 的其它读取；完整内容资格、总预算及世界效果仍须验证。
- canonical `ClientChunkCache.replaceBiomes` 调用实际 `LevelChunk.replaceBiomes` 前，进入 biome 发布保护。
  开始与结束入口均为 static synchronized，统一取得 `D3D12SectionGeometry.class` monitor。
  先在该锁内为 chunk 的 XZ±1 查询列增加可嵌套计数，再失效当前世界各 canonical tint cache 的对应列，
  使其旧 scope、未提交采集与活动 RT 内容在首次 palette 写入前撤销。仅实际执行替换的 chunk 进入保护，
  无 tint 读取和无关查询列保留资格；不等待后续 `onChunkLoaded` 或 block dirty。
  palette 解码与 tint source 计算均在注册表锁外执行。保护中首次登记对应列的编译读取立即撤销自身 scope，
  其返回值不能安装；原始替换正常返回才显式标记完整，结束时再次失效对应 cache，清掉过渡缓存后减少计数。
  解码或结束时 cache 失效异常均保留该来源的 biome 失败状态，后续新 tint 采集继续拒绝安装，不能只撤销旧 capture。
  cache/dirty/load 通知不提供完整恢复；后续完整成功的 biome 包或完整 chunk 包才可恢复该类输入。
  嵌套发布未全部结束前保持保护；世界 epoch 改变时清空计数，旧 token 的结束操作不影响新世界。
  此边界只覆盖 biome 包的实际替换；完整 chunk 包与混色选项另走各自发布入口。直接 palette 修改或 biome callback 的
  全部输入仍须认证。完整 biome 内容证明、重复失效成本及世界验收仍开放，不签发空间状态 1/2。
- `OptionInstance.set` 的两个实际 `value` 字段写点通过 `publishBiomeBlend` 发布混色半径。
  只匹配当前 client options 的 `biomeBlendRadius()` 对象及实际校验后的新值，相同值或其它选项不撤销内容。
  对当前 canonical 世界，先在注册表 class monitor 内失效所有 canonical tint cache、撤销其实际读取者与 RT 内容，
  再执行实际值写入，最后再次失效缓存；旧半径的在途计算不能重新填充当前 cache 或安装旧 capture。
  原版值校验、未运行分支和 callback 语义保留；callback 与 tint source 计算在注册表锁外执行。
  不依赖后续 allChanged/dirty 才拒绝旧输入，callback 抛错也不恢复旧资格；RT 关闭时仍撤销。
  无 tint 读取的 scope 保留，world/model 资源身份不改变，重复失效保留最早缺口年龄。
  该入口只闭合混色选项发布，不认证全部 biome、外部 callback 或自定义 cache 依赖，也不签发空间状态 1/2。
- canonical `ClientChunkCache.replaceWithPacketData` 的整个方法进入 chunk 发布事务，覆盖已有 chunk 的逐段覆盖、
  首次插入 storage 和 `onChunkLoaded` 通知；只对当前世界的实际 source 及当前 storage 范围内的坐标生效。
  开始/正常完成标记/结束均使用注册表 class monitor，原版解码、load 与事件回调在锁外执行。
  开始时先开启对应 biome 保护，再撤销该列当前维度的全部 section、旧已加载 section 与现有 region 依赖者的内容，
  同步推进输入版本并保留整段恢复要求；不等待实际 block state 写入后的 load/dirty 通知。
  发布计数未清零时，读取该列的 3×3×3 region 不得建立有效 compile scope，旧 scope 与对应远处 tint 读取也不能安装。
  原方法正常返回后才标记完成；结束时再次撤销输入和过渡 tint，再解除本层保护。重复嵌套不能提前放行外层。
  原方法异常退出时，该列保留 block-state 失败状态，保持内容未知，邻接 region 和对应 cache 查询继续拒绝安装。
  block dirty、load 或 biome-only 更新不能清除 block-state 失败；同一列后续完整 chunk 包才可恢复该类输入。
  block-state 与 biome 输入分别记录来源列的在途计数、失败代次与失败状态，token 冻结开始时的失败代次。
  正常完成且期间未出现新的同类失败才清除该类失败；更早开始的外层正常返回不能覆盖内层后发生的失败。
  完整 chunk 包分别恢复两类输入，biome-only 包只恢复 biome；biome 失败不撤销无 tint 的独立 block 几何资格。
  开始阶段在实际解码前失败只释放本次在途保护，保留先前失败状态；结束清理即使抛错也释放活动计数并保留未知。
  来源仍有在途或失败时保留记录，干净且无在途时移除，不累计已经恢复的来源记录。
  世界 epoch 改变时清空发布与失败集合，旧 token 不得作用于新世界；无关输入和 world/model 身份保持稳定。
  此事务认证上述发布时序与可获得性，不认证任意直接 palette 修改、外部 model data 或 callback 的全部依赖，
  也不授予空间状态 1/2。实际编译的 27 段输入域、逐信号恢复、总预算及世界验收仍须分别闭合。
- canonical chunk lookup 在实际可见性写点发布，不能等待 `ClientLevel.unload` 或 `onChunkLoaded` 通知。
  `ClientChunkStoragePublicationMixin` 包装活动 Storage 的 `AtomicReferenceArray.compareAndSet` 与 `getAndSet`；
  `ClientChunkCachePublicationMixin` 包装 `updateViewCenter` 的两个字段写入，以及 `updateViewRadius` 的 `storage` 字段发布。
  使用活动 source、Storage 和 slots 的实际对象身份，按来源列比较写入前后的可见 chunk 对象。
  同坐标/同对象的保留项、失败的旧身份 compare、重复中心坐标，以及尚未成为活动 storage 的复制不撤销当前内容。
  注册表 class monitor 内，先为变化列开启不可恢复的 chunk/biome 输入保护，撤销关联 region 与实际 tint 读取者，
  再执行原始 lookup 写入；释放锁前撤销离开来源、登记新可见来源，并清除过渡缓存。
  中心移回可重新登记仍驻留的同一对象，但不能恢复旧 capture；半径缩小未复制的来源只能由后续实际数据重新进入。
  原版 `ClientLevel.unload`、load 和事件回调在原位置继续执行，位于此写点事务之外。
  lookup 成功只解除本次保护，不能清掉已有 block-state/biome 失败；异常保留未知，所有已开始的列仍须释放活动计数。
  部分 lookup 发布后正常返回也不能代替完整内容恢复；只有对应完整成功的数据包才能按各自失败代次恢复输入。
  换世界后旧 token 不操作新 epoch。无关列保留资格，RT 关闭时仍执行资格撤销。
  此规则只覆盖上述 canonical lookup 写点；绕过这些入口的原始 slots 修改、完整 biome/callback 依赖、总预算与空间认证仍须闭合。
- canonical loaded chunk 的 block palette 与 section 通过对象身份索引关联来源段。
  只绑定当前世界实际 `LevelChunk` 的 `LevelChunkSection` 和 `PalettedContainer`；开始写入时复核当前 loaded chunk、
  section 数组元素与 states 对象。复制的 palette、卸载或被替换的旧对象不影响当前输入；重复观测重建绑定，卸载移除绑定。
  `BlockPalettePublicationMixin` 包装 `getAndSet(III,Object)`、`getAndSetUnchecked`、`set(III,Object)`、`onResize` 与 `read`；
  `BlockSectionPublicationMixin` 包装五参数 `setBlockState`、`read` 与 `recalcBlockCounts`。
  开始、正常完成标记与结束均取得注册表 class monitor；实际 palette 锁、原始写入和 section 计数更新在锁外执行。
  开始写入前先保护来源段，撤销该段及读取它的 27 段 region scope、未提交采集、活动与 dormant RT 内容，
  并失效对应 canonical tint cache 查询列。过渡中的 region/tint 读取不得安装；结束时再清过渡内容后解除本层保护。
  保留无关列和垂直方向不依赖该来源的段，不能把单段写入扩大成整列几何失效。
  嵌套 palette/section 及完整 chunk 发布仍维护各自计数和失败代次，已被外层保护的段共用该失效边界，
  不为每次内层 read/resize 重复撤销。外层结束前不能提前建立有效 scope。
  每段分别维护 palette 和 section 整体输入的在途计数、失败代次与失败状态，token 冻结开始时的失败代次。
  点写、resize、section edit 或重算计数成功不能清掉先前失败；完整 `PalettedContainer.read` 只恢复 palette 域，
  完整 `LevelChunkSection.read` 才能恢复 section 整体域。恢复期间出现更新的同类失败时，较早的成功不能清掉它。
  完整 chunk token 同时保留各段两个输入域的失败代次，完整成功包可按代次恢复；更晚的内层失败继续保持未知。
  biome-only 和成功 lookup 均不能恢复失败的 block 输入；模型重载也不清除来源失败状态。
  异常结束释放在途保护并保持对应输入未知。世界 epoch 改变后清空索引和输入状态，旧 token 不作用于新世界。
  此规则只覆盖上述 canonical block 方法；公开数组和底层存储另按各自协议处理，palette entry 表别名或其它绕过入口的写入，
  外部 callback/model-data 依赖与完整内容证明仍须闭合。该入口不签发空间状态 1/2，逐信号恢复、统一帧事务与总预算继续独立验收。
- canonical loaded chunk 的 biome palette 与 section 使用独立对象身份索引及失败域，只撤销实际 tint 依赖。
  `BiomePalettePublicationMixin` 包装 public `getAndSet`、`getAndSetUnchecked`、public `set`、`onResize` 和 `read`；
  `BiomeSectionPublicationMixin` 包装 `read`、`readBiomes` 与 `fillBiomesFromNoise`，覆盖临时 palette 构建到实际字段发布的全过程。
  开始、完成标记、结束使用注册表 class monitor；原始 palette 锁、解码与全部 biome resolver 调用在锁外执行。
  首次写入前失效对应 canonical tint cache，撤销其实际编译读取者、采集及 RT 内容；过渡 tint 不得安装，
  结束再次清缓存后释放本层保护。无 tint 的独立 block 几何和无关查询列保持资格。
  三个 section 方法的实际 `biomes` 字段写点另调用 `publishBiomePalette`，在同一 class monitor 内写字段并刷新身份索引。
  新 palette 发布后立即具备当前来源身份，旧 palette 立即退出索引；不能等外层 section 方法返回才登记。
  `recreate()` 的临时对象与复制的 section/palette 不拥有当前来源；重复观测重建绑定，卸载移除，开始写入复核当前 chunk/section/palette。
  每段分别维护 biome palette 与 section 整体输入的在途计数、失败代次和失败状态。
  点写和 resize 成功不恢复先前失败；完整 palette read 只恢复 palette 域，完整 section read、readBiomes 或 fill 才可恢复两域。
  section 解码未发布临时对象、resolver 失败或字段发布后的异常均保留未知，不能因当前指针看似完整而直接放行。
  较早的 section、biome 包或完整 chunk 包成功不能清掉更新的内层失败；所有恢复均复核开始时冻结的失败代次。
  完整 biome/chunk token 同时持有来源列各段两个 biome 域，后续完整成功包可恢复对应输入；普通 lookup 没有恢复权限。
  单段完整内容不能恢复整列 biome 包失败，完整 biome 内容也不能恢复 block 失败；block palette read 不恢复 biome 失败。
  嵌套 token 共用外层 tint 失效边界并保留独立计数；RT 关闭仍撤销，世界 epoch 改变后旧 token 不操作新世界。
  此规则只认证上述 canonical 方法和字段发布，不认证任意 biome resolver/callback 外部状态、custom palette、
  `BitStorage.getRaw()` 数组/entry 表/slots 别名修改或全部 `BiomeManager` 输入。完整内容/空间证明、逐信号恢复与总预算仍须分别闭合，不签发状态 1/2。
- canonical `ClientLevel` 中实际 `LevelChunk` 的公开 `ChunkAccess.getSections()` 返回原始、可写的 final 数组。
  `D3D12RtSectionArrays` 用弱数组键保留外部取得事实；即使尚未写入，也立即撤销该列及其 region、实际 tint 读取者和 RT 内容。
  段数组元素替换会同时改变方块和 biome 来源，不能仅依靠 palette/section 方法的写入保护。
  数组仍在外部持有时拒绝新 scope；点写、重算计数、完整 chunk 包、dirty、lookup 回访、模型重载或世界重观测均不能恢复。
  卸载移除当前列的依赖；新 chunk 的独立数组可以恢复，旧数组的后续修改不撤销替代来源。
  首次 loaded 观测也检查此前的逃逸事实；来源身份检查直接读取受保护字段，不自行暴露公开数组。
  只在已查证的原版调用点临时借用 getter：`getSection`、`getHighestFilledSectionIndex`、客户端 Storage 的三种维护方法、
  `enableChunkLight`、`SectionCopy` 构造、实体流体扫描，以及完整 chunk/biome 包各自的大小计算与序列化。
  借用只包住该次 getter 调用，并在 finally 中恢复线程边界；原方法继续在边界外消费自己的局部引用。
  `ImposterProtoChunk.getSections()` 会把实际数组继续返回给调用者，必须保留外部取得语义，不能列为内部只读消费。
  该规则只覆盖上述 canonical 来源与实际数组入口；其它 producer/外部可变输入仍须另行证明，不签发空间状态 1/2。
- canonical palette 的 `Data.storage` 另保存底层内容资格，支持精确 `SimpleBitStorage` 与不可变的 `ZeroBitStorage`。
  `BitStoragePublicationMixin` 包装实际 `set`、`getAndSet`、`fill` 与 `copy`；原方法继续返回自己的值并保留异常。
  写入开始前保护当前 block/biome 来源，撤销受影响的 region 与实际 tint 读取者、未提交采集和 RT 内容；
  没有读取 tint 的独立 block 几何不因 biome 存储写入而撤销。开始/完成/结束取得注册表 class monitor，写入与复制 body 保持锁外。
  已由 palette、section 或完整包保护的来源共用外层失效边界，底层输入仍独立保存版本、在途计数和失败代次。
  `D3D12RtPaletteStorage` 使用弱存储键，在首次来源登记前即记录写入状态；卸载或换世界只清当前反向索引，保留对象的在途与失败事实。
  点写不能恢复先前失败；独立完整 `fill` 只恢复自己的存储，开始后出现更新写入或失败时不能恢复，不能清除上层 palette/section/包失败。
  `copy` 在真实 body 前后核对来源资格和版本；失败、在途、未知或复制期间变化的来源使副本保持未知，来源后来恢复不能补发副本资格。
  副本独立完整填充后可以恢复；未成为当前来源的独立副本写入不影响当前采集。
  `PaletteStoragePublicationMixin` 包装 `PalettedContainer.read/onResize` 的实际 `data` 字段写点，先撤销旧来源，再在同锁内发布和刷新存储索引。
  新存储在外层方法返回前即受保护，旧存储立即退出来源；完成或关闭旧写入时，只更新同一存储的内容事实和当前实际读取者。
  未知存储类型保持未认证。原始数组与 palette entry 按后续两项保护；本规则不签发空间状态 1/2。
- `D3D12RtPaletteArrays` 按实际 `long[]` 身份记录所有权和外部取得事实，所有共享该数组的 storage 共用内容版本、在途计数与失败代次。
  `SimpleBitStorage` 自行分配的数组拥有来源资格；`(int,int,int[])` 消费输入并写入自己的新数组，不保留 int 输入别名。
  `(int,int,long[])` 保留原数组和可写语义，默认未认证；不能因构造成功就推定调用者已交出所有权。
  外部 `getRaw()` 在返回前撤销实际数组的 block/biome 依赖，返回的仍是原内部数组；取得但未写也保持未知。
  弱数组键跨卸载、世界重观测和首次来源登记保留事实。点写、完整 fill、同数组网络解码和重复观测均不能收回外部别名。
  原始数组反向索引覆盖当前所有 storage，构造第二个保留别名会撤销已有来源，旧数组退休后不影响独立替代来源。
  仅在实际 `PalettedContainer.Data.getSerializedSize/write` 的两个 getter 调用点借用只读数组；借用在 finally 里恢复线程边界。
  `PalettedContainer.read` 的 getter 另用限定借用，实际 `FriendlyByteBuf.readFixedSizeLongArray` 前后保护数组写入，body 锁外。
  真实截断和写后异常保留数组失败；独立完整网络读取只恢复自己的数组内容域，不能清除外部别名或上层失败。
  从未知或在途来源执行 copy、resize 的重编码或 pack/unpack，副本保留未知；恢复来源不能补发副本资格。
  `pack` 前后复核同一 Data 身份与存储版本，失败 PackedData 使用弱身份键，不能触发 entries 或 stream 的 hashCode/equals 回调。
  正常 `unpack` 仅在已验证的 JDK `LongPipeline.toArray` final 方法产生独立数组时授予一次性构造所有权，
  后续别名构造不能复用凭据。自定义 `LongStream.toArray` 可保留数组，结果不得取得所有权。
  独立 copy 或重编码结果在自身完整填充后可恢复内容；保留的 long 输入数组及外部取得数组始终不能靠填充恢复所有权。
  此规则保留 API 返回、数组身份和异常；entry 按下项独立认证，任意外部 callback/model-data 与完整空间来源仍须证明。
- `D3D12RtPaletteEntries` 为实际 `Data.palette` 保存独立的内容版本、在途计数和失败代次，使用弱身份键，
  不在注册表锁内调用外部 palette 或 PackedData 的 hashCode/equals。block 与 biome 当前来源分别反向索引 entry 对象。
  `SingleValuePalette`、`LinearPalette`、`HashMapPalette` 的完整 `read` 在真实 body 前后保护内容，body 保持锁外。
  Single 的 `idFor` 只在实际 `value` 字段写点同锁比较并发布；同值字段写入保留资格，只在该叶子字段写入期间持锁。
  Linear 只在 `idFor` 第三个 `values` 字段读取、即实际数组写入前开始保护，直到方法正常返回或异常结束；
  HashMap 只包装 `idFor` 的实际 `values.add`，在后续 `onResize` 回调前结束保护。普通查询和无写入的扩容转交保留 entry 资格。
  Linear 的嵌套调用各持有自己的线程边界，finally 释放；不得靠方法开始时的查找结果推定稍后仍不会写入。
  entry 写入撤销实际 block region 与 tint 读取者；biome entry 只撤销实际 tint 内容，保留没有 tint 的独立 block 几何。
  data 字段发布即时替换 storage 和 entry 来源；旧 entry 退出来源后不再影响替代内容。卸载或换世界只清当前反向索引，
  首次来源登记前及跨世界在途、失败事实仍保留在同一实际对象上。
  `SingleValuePalette.copy()` 返回自身，后续读取者共享同一个失败域；Linear 与 HashMap 的真实 copy 使用独立表。
  三类构造器消费而不保留输入 List，HashMap 的 `getEntries()` 返回独立列表；保留这些列表不撤销实际 entry。
  独立 copy 前后核对源版本；`Data.copy` 同时冻结 storage 与 entry，resize 重编码也验证源 entry，避免组合出未经认证的内容。
  返回同一对象的 copy 沿用共享对象当前的资格，不因复制期间的成功写入新增失败；实际写入已撤销其所有旧读取者。
  pack 前后复核 entry、storage 和同一 Data 身份；unpack 分别传递两类失败。来源恢复不能补发独立失败副本的资格。
  独立完整 entry read 只恢复 entry 失败，不恢复外部数组所有权或上层 palette/section/包失败；完整 storage fill 不能清 entry 失败。
  `GlobalPalette` 没有本地 entry 写入，其外部映射按下一项独立认证；本项不签发空间状态 1/2。
- `D3D12RtIdMaps` 保护精确 `IdMapper`、NeoForge `ClearableObjectIntIdentityMap`、`MappedRegistry`、
  `DefaultedMappedRegistry` 与精确 `Registry.asHolderIdMap()` 适配器的实际 owner；未知实现保留调用语义并保持未认证。
  `addMapping`、四参数 `register`、`registerIdMapping` 和完整清空的原方法与注册/清空 callback 在锁外执行。
  两个方向映射的实际 put、slot set、append、clear 在注册表锁内发布；反向映射部分失败也撤销该 owner 的全部当前读者。
  同值写与未使用 ID 的追加保留现有 capture；正向映射变化仅撤销实际使用该 ID 的 block region 和 tint 依赖。
  每段按实际 storage 索引链接映射与 Holder 身份，存储版本缓存只用于已认证存储的 ID 集合；字段替换、卸载与换世界清当前链接。
  原映射对象的失败与在途版本使用弱身份保存，首次登记或换世界不得将其洗成可信。独立完整清空只恢复本映射失败域；
  后续单次 addMapping、完整 palette/section/包或 storage fill 不能清除此失败。
  `MappedRegistry.iterator()` 保留真实迭代、remove 与异常；有效 remove 在移动实际 ID 表前后保护全部该 owner 的读取者。
  `DefaultedMappedRegistry` 的真实 defaultValue 字段发布覆盖直接按 ID 查找的回退值；holder 适配器缺 ID 时仍返回空，不套用该回退。
  `Holder.Reference.bindValue` 的真实 value 字段同锁比较并发布，local/global biome palette 都保留共享 Holder 依赖；
  同值写不撤销，实际变化只撤销依赖 tint，未绑定值保持未知。独立完整 value 字段写入只恢复 Holder 本域失败。
  entry read 核对实际解码 IdMap 的前后版本；resize 重编码分别核对源 entry 和目标全局映射，pack/unpack 分别冻结所用 strategy 映射。
  `GlobalPalette.copy()` 返回共享原表；普通 Data copy 保留其实际共享关系，独立物化结果不能继承在途或已改变的映射资格。
  失败 unpack 若使用共享 GlobalPalette，只将独立 storage 标成失败，禁止污染其它仍有效的共享 entry 读者。
  上述规则只覆盖 canonical 映射和 Holder value；完整 BiomeManager/resolver、tags/components、callback/model-data、
  producer 空间闭包、逐信号恢复与总预算仍须闭合，不签发状态 1/2。
- `LevelChunkSection.getNoiseBiome` 在真实读取前向当前编译 session 登记段身份。直接 `BiomeManager` 查询和
  `ClientLevel.calculateBlockTint` 沿用真实 seed、quart 角点选择及 `ChunkAccess` 高度夹取，不在 RT 中重写该算法。
  每个 session 按段身份去重，scope 保存实际 biome 段节点与反向读取者。核对当前 chunk/section 来源、段数组所有权、
  biome 包与 palette/section 的在途和失败，以及 storage、entry、映射和 Holder 的可用性；没有当前来源则撤销编译结果，
  原查询仍执行并保留返回身份、数值和异常。完整查询 body 保持注册表锁外。
  实际内容发布在写入前撤销远程直接读取者；现行列失效同时保守撤销该列其它高度的直接读取者。
  来源替换、卸载、段释放、模型与世界切换清理反向依赖，重新观测不能复活旧结果。
  scope 撤销后，待恢复的整段请求仍参与 region 输入失效；后续编辑更新其版本并保留最早等待时间，
  不用残留的旧 scope 代替恢复请求的成员关系。
  此规则只覆盖实际进入该 canonical 段方法的内容读取；缓存命中的完整来源、任意 resolver/callback、
  Biome 其它动态输入和完整查询闭包仍须分别认证，不据此签发状态 1/2。
- `ClientLevel.getUncachedNoiseBiome` 和 `EmptyLevelChunk.getNoiseBiome` 在实际方法入口登记未知内容。
  缺失 chunk 的 `LevelReader.getNoiseBiome` 仍按原路径返回 plains，空 chunk 仍返回自己的占位 Holder；
  保留原返回身份、null 和异常，禁止把这些回退值当成已加载 biome 内容的证明。
  入口只在短注册表边界内撤销当前直接 scope，并将所有在途嵌套 cache source 标成内容未知；原方法和 source 在锁外执行。
  每个实际 tint 数组槽位分别保存连续性认证位与内容未知位。RT-off 或无 scope 时计算的回退值继续按原版缓存命中，
  但后续 capture 不能由命中重新取得资格；内层未知命中必须继续传到外层 source 和外层缓存槽位。
  后续已知读取或 source 捕获原异常不能清除同一次计算中出现的未知输入。真实失效后，以当前已知输入重算才恢复新槽位。
  同数组的独立槽位、其它 cache 和无关 scope 保留自己的资格；直接无 session 的回退不撤销无关既有内容。
  新位图复用现有弱数组层及数量上限，不增加对 world、cache 或数组的强引用；完整来源协议与总预算仍须独立验收。
- `BiomeManager.getBiome(III)` 与 `getNoiseBiomeAtQuart(III)` 在实际 HEAD 核对接收者和 final `noiseBiomeSource`。
  只允许 exact `BiomeManager`，且 source 必须是当前 exact `ClientLevel` 本身。BlockPos、double 与 seeded 查询沿原版委托路径执行。
  `withDifferentSource` 引入的任意 callback、转发到真实 section 的 callback、其它 world、未知子类和 null source 均保持内容未知。
  `ClientLevel.calculateBlockTint` 同样在实际 HEAD 核对当前 exact world；resolver 必须与 `BiomeColors` 的
  `GRASS_COLOR_RESOLVER`、`FOLIAGE_COLOR_RESOLVER`、`DRY_FOLIAGE_COLOR_RESOLVER` 或 `WATER_COLOR_RESOLVER` 为同一对象。
  使用身份比较，不调用任意 resolver 的 `equals`、`hashCode` 或颜色回调来判断资格。
  未知查询撤销当前直接 scope，并标记全部在途嵌套 cache source 的内容未知位；RT-off/无 scope 预热、已暖命中和晚到写入
  都不得恢复资格。随后读取已知 section 或捕获原查询异常，也不能清除同次计算的未知事实。
  保留真实返回身份、颜色、null 和异常；只有短入口检查持注册表锁，完整查询和任意 callback 在锁外执行。
  当前线程既无编译 scope 又无在途 tint cache read 时直接返回，普通 biome 查询不得等待 RT 注册表锁。
  此规则限定实际 manager/calculator 入口；cache 的构造来源按下一项认证，其它外部输入仍须独立证明。
  运行 `python _port/rt_world_geometry_check.py --chunk-inputs-only` 检查真实查询、已暖/嵌套 cache、异常、跨世界和锁外执行；
  `--biome-blend-only` 使用实际 loaded biome palettes 和原版 WATER resolver 验证混色半径、并发发布及缓存恢复。
- 在 `ClientLevel.lambda$new$0` 的四个实际 `BlockTintCache` 构造点登记 world、cache、实际 source 与固定 resolver 身份。
  先让原版构造返回，再进入短注册表边界；不要在完整构造或 source body 外持有注册表锁。
  `ColorResolverManager.registerBlockTintCaches` 的真实构造同样登记，只有四个原版 resolver 可取得来源资格。
  任意 `ToIntFunction` 即使调用过已认证 calculator，也保持内容未知；保留其返回值、原版命中和异常。
  每次 cache read 核对当前 world 与 cache 的真实 final `source` 字段身份；`getBlockTint` 还核对查表 resolver 与来源 resolver。
  旧 scope 的 `isCurrent` 同样复核实际 source，不能只检查下一次读取。source 身份恢复不能洗掉已存入槽位的未知内容。
  未知来源事实沿当前 scope 与全部在途嵌套 cache 传播，RT-off 预热与已暖命中同样执行；外层任意 callback 不得借内层来源取得资格。
  所有权记录复用有界弱 cache 索引，world 与 source 均使用弱引用；不要通过 source 回调反向保活 world。
  来源只登记一次，已开始使用的未知 cache 不得事后登记取得所有权。缓存失效代、槽位内容位、色表依赖与构造来源分别验证。
  运行 `python _port/rt_world_geometry_check.py --tint-sources-only` 检查真实默认/注册构造、任意 source、已暖命中、
  真实 source 字段替换、跨世界和错误 resolver 查表；该探针不启动完整客户端。
- canonical `ModifiableBiomeInfo` 保存独立的弱身份内容状态。将实际使用的 direct/reference biome Holder 链接到
  当前 info owner；Holder value、entry、storage 或段来源替换时同步更新链接，退休 owner 不得撤销替代来源。
  在 `applyBiomeModifiers` 的实际 `modifiedBiomeInfo` 字段写点进入短注册表边界，写前和写后撤销该来源列的
  tint cache、远程 biome 读取者及直接 info 读取者。modifier、builder 与前后 codec body 保持锁外。
  `get` 和 `getModifiedBiomeInfo` 在方法 HEAD 向当前 session 登记实际 info 读取；不经过 palette 的直接使用也要登记。
  未初始化、未知、在途或失败的 info 来源撤销编译资格，原方法仍保留返回身份、空值与异常。
  同身份成功字段写保留稳定内容；叶子失败即使发生在同值写也必须撤销，并保留首次登记前及跨世界的失败。
  独立完整字段写只恢复 info 本域，较早成功不能清除更晚嵌套失败，也不能恢复 palette/section/包等其它域。
  方法提前返回或 callback/codec 在字段写前失败不创建叶子失败；字段已完整发布后，后续 codec 失败不撤销新内容资格。
  清理 scope、世界、模型与编译器时同时清理直接 info 反向索引，不能恢复旧结果。
  NeoForge 的 `ReplaceFieldWithGetterAccess` 在运行时把 Biome 的 climate/effects/attributes 字段读取转换为 modified getter；
  单独执行原始 class 的探针必须执行发行 JAR 自带的该 processor，再验证真实 getter 与 codec 行为。
  此规则仅认证 info 字段发布与实际读取；内部 attributes/generation 动态状态、自定义色表、缓存完整来源和任意 callback 仍须认证。
- canonical `BlockTintCache.getColor` 的实际 `int[]` 读取和写入经过短注册表边界，完整方法与 source callback 保持锁外。
  方法入口保存 cache、真实列键、槽位、world epoch 和当前 scope；不经过 `ClientLevel.getBlockTint` 的直接调用也登记失效依赖。
  全缓存及真实 ±1 列失效即使没有 scope 读者也更新代次，并在异常退出时结束在途状态。
  方法入口、数组叶子与返回前共同核对代次；失效期间启动、跨失效完成或跨世界的结果不能取得槽位资格。
  按真实数组身份保存各槽位的认证位，未知或过期值在实际读取前恢复成原版 `-1` miss，交由真实 source 重新计算。
  连续性有效但内容未知的槽位保留原版值和命中行为，以独立内容位拒绝 RT 认证，禁止将它与过期槽位混为一类。
  晚到或失败写入不得留下可被下一次新编译认证的值；不改数组身份。不同值的并发覆盖先撤销该 cache 列的读者。
  正常同值命中、独立列和 RT-off 预热保留；source 的真实返回和异常继续传递。未知 cache 子类不得沿用 canonical 资格。
  弱 cache/数组键不反向持有真实对象，层状态只弱引用 world。局部上限为 512 个 cache、每 cache 4096 个列、16384 个数组层；
  超限的新增结果继续返回真实值，但拒绝内容认证，已认证且仍有效的结果可继续使用。
  槽位发布与失效代连续性不能单独认证任意 source callback；实际 manager/calculator 入口及 cache/source 构造按前述规则检查，
  其它外部输入仍须完整来源证明。三个 canonical 色表按下一项认证，
  其它自定义色表保持未知；总 CPU/GPU/字节/退休预算和原存档事件矩阵仍须验收。
- `GrassColor`、`FoliageColor`、`DryFoliageColor` 的真实 `pixels` 字段读取登记直接 scope 和实际 cache source 的依赖。
  嵌套 cache 即使命中内层旧槽位，也向外层传递已经记录的表依赖。表发布先撤销直接读者、相关 cache 读者和 RT 内容，
  再执行实际字段写入；相关 cache 的槽位代次同时更新，无 scope 读者与 RT 关闭期间也要保留该关系。
  发布结束前再次登记期间首次读取此表的 cache，撤销它们的过渡槽位；晚到 source 写入不能认证旧值。
  独立表及未读取色表的 cache 保留资格，不通过模型重载或全屏 history reset 代替内容失效。
  公开 `init(int[])` 保留原数组身份、可写别名与 null 行为，但外部仍持有数组时不认证稳定内容；
  原版 getter 的数值、异常和普通 cache 命中行为继续保留。三个实际 `ColorReloadListener.apply` 的 `init` 调用点
  将输入复制成独立快照，再发布对应表；此副本只在该调用边界内取得所有权，不把普通 `init` 一并认作拥有数组。
  默认私有数组首次观测可用，首次写失败、嵌套失败和过渡读取保持未知；较早成功不能清除更新的失败，
  只有后续独立且完整的拥有数组发布才能恢复相应表域。世界、模型与编译器切换清理 scope 反向索引，
  不清除全局表的所有权或失败事实。表状态仅弱引用当前数组，cache 关系复用既有有界弱身份索引。
  完整 getter、cache source 与 reload 方法保持锁外，只有真实字段叶子进入短注册表边界。
  此规则只认证三个色表的内容域；cache/source 构造按前述规则检查，其它 callback 输入与动态状态仍须分别闭合。
- canonical `ClientLevel` / `ClientChunkCache` / `LevelLightEngine` 的编译 scope 另保存实际可见光照表的读取依赖。
  block light 记录 27 个输入段各自的 `DataLayer`；sky light 按原版规则向上寻找实际回退层，区分本段三维读取、
  上方层的底面读取与常量天空。不可把 sky 依赖裁成中心段的 Y±1。
  `LayerLightSectionStorage.swapSectionMap` 在 `visibleSectionData` 字段写点进入注册表 → RT 池锁，
  先撤销受影响 scope、未提交采集和活动 RT 内容，再执行原始指针发布；不跨完整光照传播或 dirty 回调持锁。
  普通发布按改变列的反向索引查当前读取者；sky 的 `currentLowestY` 或默认 top 改变时复核全部已观测 scope。
  实际读取层不变的 scope 保留资格，安装时也直接复核当前可见表，避免等待下一次 dirty 通知。
  旧 scope 被移除，A→B→A 不能恢复其安装资格；缺口保留最早年龄，world epoch 与 `modelRevision` 不变。
  dormant 几何须经后续新采集的实际位置、索引及 RT 所用着色字段比较后复用，不能由旧 scope 直接恢复。
  原版 dirty 通知继续执行，其重复撤销成本和 sky 元数据变化时的遍历总预算仍待验证。
  活动世界的已认证可见表在首次 scope 前登记全部实际 `DataLayer` 身份；正常 swap 按冻结的 changed section 增量更新归属，
  表身份意外改变时重新登记。共享对象按实际引用计数保留，失败状态使用弱键，不把每次 COW 生成的旧对象永久驻留。
  map 来源默认未知。`BlockLightMapOwnershipMixin` 与 `SkyLightArrayPublicationMixin` 只在两个 storage 构造器的
  实际初始 map 构造点授予新表所有权；验证 concrete map 类型、FastUtil 表类型与实际 backing table 指针。
  sky 同时验证 `topSections`。普通 map 构造保留传入表的身份和可写语义，外部保留的层表、top 表不能取得资格。
  不遍历未认证表登记可见层；成功点写、同表发布、typed copy 或世界重观测均不能恢复未认证来源。
  27 段预绑定不限制模型或 tint 回调实际读取光照的位置。实际 block `getLightValue(J)I`、sky `getLightValue(JZ)I`
  在原 body 前把当前编译 session 的 storage、节点及可见输入证据登记到同一 scope；相同 storage/节点在 session 内去重。
  读取必须来自当前世界的实际 engine/storage，updating-map 读取不能借用可见光证明；未认证、在途或失败来源撤销该 scope。
  任意距离的实际读取加入列、层及数组反向索引，sky 仍记录向上回退空槽和常量天空元数据。
  等价表发布重新绑定额外读取；后续实际输入改变撤销采集与 RT 内容，已替换的旧层不撤销独立新来源。
  安装时复核全部实际读取证据；读取 body 在注册表锁外，读取与写入交错后不能沿用先前采集。
  session 关闭或完成后不再收集读取；嵌套 session 沿用自己的 scope，普通游戏读取不登记成编译依赖。
  `LayerLightSectionStorage.getDataLayerData` 优先返回排队层，其次返回原始可见层，不等价于 `getLightValue`。
  在实际 HEAD 登记当前 session 的 storage 和 section node，按 scope 去重依赖并逐次复核当前资格。
  `LightSectionStoragePublicationMixin` 仅在原版构造器的 `queuedSections` 字段写点登记队列来源，验证实际队列身份和类型。
  每次 raw 读取同时绑定队列槽位版本、在途计数和失败代次，队列缺席也参与证明；queued 非空时绑定该层及数组资格，
  缺席时只绑定可见表的 exact slot。sky raw 查询不能借用亮度的向上回退或常量天空证明；真实 null 可取得限定槽位的缺席证明。
  队列插入、替换、移除和 `markNewInconsistencies` 的 iterator 消费均在原操作前撤销精确 storage/node 的读取者，
  其它槽位和 storage 保留资格。原始读取、put/remove/iterator body 在注册表锁外；保留原返回身份、数值与异常。
  queued 读取不依赖被遮蔽的 visible slot；可见表等价发布须重绑定 raw-visible 读取，后续新表变更仍撤销旧采集。
  未认证队列、其它 engine、在途或失败来源拒绝当前 scope；来源换回也不能恢复已撤销身份。
  完成或关闭后的普通读取不再登记，嵌套 session 使用自己的 scope；旧世界 session 不能撤销当前新 scope。
  完整 put/remove 只恢复自己的槽位，不能清除其开始后出现的更新失败；世界重观测不清除队列的在途和失败事实。
  `LightMapPublicationMixin` 包装 `DataLayerStorageMap.setLayer/removeLayer/copyDataLayer`。
  每个实际读取的 map 槽位保存版本、在途计数与失败代次；sky 回退同时记录途中未命中的槽位，插入更近的层必须撤销原读取者。
  原位方法开始前撤销相关 scope、采集与 RT 内容，原 body 在注册表锁外执行；map 身份不变也要验证槽位资格。
  新 capture 在 map 结构写入期间拒绝读取该表，已有无关 capture 可继续使用自己的冻结输入。
  可见层登记保存独立的槽位索引；最外层原位方法完成后按最终指针增量更新，支持同槽位嵌套与 map 发布交错。
  完整 set/remove 只恢复自己的槽位，且不能清掉开始后发生的新失败；copyDataLayer 保留原失败。
  `BlockLightMapCopyMixin` 与 `SkyLightMapCopyMixin` 包装两个 concrete typed `copy`，保留原版协变返回。
  认证副本前验证源类型以及层表、sky top 表均为独立的新表；源在复制前后均须可用且版本相同。
  复制失败槽位不能恢复其资格；与源 map 写入重叠或跨过源版本变化的复制使整张副本未知，不能靠修一个槽位恢复。
  从独立且稳定的来源重新复制可恢复；弱 map 键保留未完成与失败事实，世界重观测不清除它们。
  成功安装等价 map 时重绑定实际槽位依赖；随后修改新 map 仍撤销原 capture。
  正常发布只更新 changed nodes，复制认证只复制失败槽位集合，不为此逐帧扫描全部认证节点。
  `SkyLightArrayPublicationMixin` 包装实际 `onNodeAdded/onNodeRemoved`，从 storage 的 `updatingSectionData` 取得变更来源。
  元数据保存独立版本、在途与失败状态，并推进 map 复制版本；每个 sky Read，包括常量天空，都绑定该状态。
  未发布 updating map 的元数据变化不撤销独立的可见输入；若变更作用于实际可见 map，先撤销其全部 sky 读取者，
  再在注册表锁外执行原 body。完成前拒绝新 scope，跨元数据变更复制的 map 保持未知。
  元数据写入异常后保持整个 sky 来源未知；后续节点回调、层填充、同表发布、复制及世界重观测不能清除失败。
  只有独立、稳定且完整的 sky 来源可恢复，恢复 block 光不能代替 sky 来源认证。
  原位读取同时检查层的内容版本、在途计数与失败状态；即使两张可见表身份不变，也不能跳过该检查。
  `LightLayerPublicationMixin` 包装 public `set(IIII)`、`fill`、`copy` 和 `getData`。
  canonical DataLayer 首次方法调用即保存内容状态，不以可见表登记或活动世界为前提；弱层键跨世界重观测保留失败与在途事实。
  层复制跨世界重观测仍复核源版本、在途、失败和数组资格，不能将未登记层或失败来源复制成已认证内容。
  set/fill 开始前在注册表 class monitor 内增加在途计数和内容版本，撤销实际读取该对象的 scope、采集与 RT 内容；
  真实写入在锁外执行，过渡或失败的层拒绝新 scope。远距 sky 回退和跨 block/sky 的共享对象使用同一实际读取关系，
  未发布 COW 层、无关层与已替换旧对象的写入不撤销独立当前读取者。
  每层独立记录失败代次，点写成功和同对象表发布不能清旧失败；完整 fill 只在开始后没有更新失败时恢复该层。
  copy 在真实复制前后核对来源版本和可用性，在途、失败或复制期间改变的来源使副本保持未知；副本须通过自己的完整 fill 恢复。
  正常光引擎仍先复制再更新、最后发布可见表，未发布更新不提前改变实际可见光照。
  指针发布抛错后按实际字段更新当前归属；旧世界不更新新注册表的指针归属，层写入 token 仍记录同一对象的实际内容结果。
  `D3D12RtLightArrays` 以弱数组键记录所有权与外部逃逸；公开 getter 返回原 backing array，byte-array 构造器保留原数组。
  外部取得数组即撤销其实际读取者，不等待第一次写入；同数组的全部层别名共同失去资格，重复登记和换世界不能清除逃逸事实。
  点写或所有权声明不能恢复已逃逸数组。完整 fill 脱离旧数组后可恢复自身层；其它别名仍未知，失败或过渡来源的副本仍未知。
  外部 getter 在原方法执行前增加按层的在途计数，并通过层发布保护撤销旧采集与 RT 内容。
  原 getter 可能先发布零数组、随后填充非零默认亮度；该间隔与异常退出均不能取得新 scope。
  在途计数独立于世界注册表，重叠 getter、完整 fill 或重建注册表不能提前释放另一个读取；原 getter body 在锁外执行。
  getter 异常使其实际当前数组保持未知；canonical 层还保留独立失败代次，后续完整 fill 才能恢复。
  限定内部借用只用于 private `set(II)` 的 getter 和实际 `repeatFirstLayer`，在 finally 中恢复线程边界。
  点写继续受外层 public set 保护；非同质天空光复制在真实复制前后复核来源版本与可用性。
  只在已查证的新数组构造点授予所有权：`DataLayer.copy` 的 clone、天空光首平面复制的新数组、
  `ClientPacketListener.readSectionList` 的网络载荷 clone；异常或未认证来源不能借复制恢复。
  实际客户端光照包仍先排队再发布，保留的网络数组不能修改该 clone；出站光照包只暴露独立副本。
  序列化与其它普通 byte-array 构造继续保留外部别名语义，未经上述内部构造点证明的数组不取得资格。
  此规则覆盖 canonical 可见亮度与已认证 queued/visible raw 层在上述输入域内的读取。
  未认证 map/queue 来源、未观测来源的完整构建依赖、custom layer、aux 光照以及 callback/model-data 的其它外部可变依赖
  不由此认证，不签发空间状态 1/2。
- 等待当前编译的整段目标用内部 `CaptureTarget.layer=-1` 进入同一恢复队列，与缺层要求公平轮转；
  不把此标记写入 native 的 node/layer/version 表。新的输入编辑替换恢复版本并保留首次缺口年龄，
  准备过的旧 region 不因目标仍存在而恢复资格。当前安装使用实际层清单替换整段要求；
  仍缺载荷的层沿用原有最早缺口年龄。实际卸载与世界切换退休来源要求；loaded section reset 替换恢复版本。
- 临时忙碌退让 100 ms；一次失败等待 50 ms，连续失败倍增至 2 s。每段/层独立退避，重试状态最多 64 项；
  当前版本替换、恢复、明确删除与切世界都重新验证资格。缺口年龄从首次缺失持续累计，重试不重新计龄。
- `rt_stats.json` 的 `capture_recovery` 提供当前 `missing_layers`、`oldest_age_ms`、`prepared_regions`、
  `in_flight`、`retry_entries`，以及候选/提交/延后/丢弃/复制累计次数、`copy_ms_total`、`copy_ms_max`
  和 `over_slice_copies_total`。`missing_layers` 沿用字段名，计入缺层与等待当前编译的整段要求。
  累计项为进程生命周期统计，不是 5 s 窗口差值。

次数限制和复制间的软时间片不是硬时间/字节预算。单次 region 复制可能超时，完整负载性能、未观测空间、
范围外目标以及各光照信号的缺口恢复仍需独立验证；已释放容量不表示缺失内容已经恢复。

**当前查询空间状态**：canonical lookup 的实际写点在同锁事务内登记新可见来源、撤销离开来源。
`ClientLevelGeometryStateMixin` 继续在 `ClientLevel.onChunkLoaded` 结束后登记实际缓存中的 chunk，
在 `ClientLevel.unload` 入口撤销同一 chunk 对象的编译资格与载荷；这些通知是补充入口，加载不触发强制取块。
同坐标的新 chunk 替换后，旧对象的 unload 不得撤销新对象；同一个对象收到新的完整区块数据也必须撤销旧编译令牌。
每次加载按 level 的 section 范围登记全部来源段，首次编译或上传前即进入当前 node 目标、
`versions().outputs` 和同一恢复队列。待编译来源导出 `layer=-1`、finite 标志 0 的无界输出支持，
不向真实 node/layer/version 内容表写入虚构层。当前查询半径与网格边界不裁剪这些未证明的潜在影响。
新 chunk 的来源范围变小时，旧范围多出的段显式退休为 3=卸载，不能继续保留 loaded 来源或恢复要求。
加载、替换与卸载也推进对应输入段的内容代次，撤销依赖这些输入的中心段；受影响的仍加载中心段进入同一有界恢复队列。
依赖中心段包括已加载但尚未创建编译 scope 或渲染表示的来源；来源要求变化保留未解决的最早缺口年龄。
加载范围使用该 level 的 section 数，只用于登记生命周期，不据此声明物理几何界。
`LevelExtractor.setSectionDirty(IIIZ)` 在首次编译前也记录 dirty；卸载后的晚到 dirty 或安装不得恢复旧内容。

`D3D12RtGeometryPool.coverageNear` 在同一池锁内冻结 live versions、所需 node 与独立空间网格。
所有加锁注册表入口使用类监视器；Kotlin object 中的 `@Synchronized` 入口同时使用 `@JvmStatic`，
避免写入锁住类、快照锁住单例而读取交错版本。锁顺序固定为 section 注册表、RT 池；池内不得反向获取注册表锁。
单次 `invalidateSections` 合并受影响中心段后扫描 live 记录与缺层表；整段恢复要求按实际输入更新版本。
多列 lookup 当前逐列开始与结束撤销，后续原版通知仍可能重复失效；其合并成本与总预算尚须验证。
积压年龄只在要求变化后重算最早时间，轮转和稳定帧查询不重新扫描全部要求。
空间目录由 `D3D12RtSpatialDirectory` 生成 schema 2 小端载荷：64 B 表头、16 B/页目录与 2048 B/页状态。
页包含 8³ 个 section，页坐标为完整有符号 section 坐标的 floorDiv(8)，目录按 Z/Y/X 有符号顺序排列。
每页状态 X 最快，只有 1/2 可证明已知；缺页为 unknown。全零页省略，域外页内单元保持 0。
声明域不受 39³ 限制，X/Z 最多 4194304 section、Y 最多 1048576 section，且 min + size 不越过 SectionPos 边界。
页预算最多 512 页，载荷最多 1056832 B；从中心页按距离和稳定坐标顺序选择，未入选页不能当成空气。
来源先按认证全集的潜在影响范围分发至已选页，再以三维差分认证相关页，避免每页重新扫描全部来源。
空间状态变化推进池 revision，来源、声明域、中心页与 targets 不变时复用只读目录；
同锁冻结 live versions、targets 与来源事实，记录或字节预算省略的内容仍不能获得查询完整资格。
native 保留 schema 1 密集载荷支持；两种布局分别校验，FFM setter 与统一帧事务的函数签名不变。
当前目录仍由近域调用方请求，384 records、96 方块追踪半径与原查询预算没有随目录扩展自动放宽；
512 页选择、认证、打包及 native 校验的实际游戏帧成本和完整分页调度尚待验证。

**编译候选支持清单**：`D3D12RtModelSupport` 在实际编译调用内按资源集合缓存不可变的候选界，
由 `SectionCompilerSupportMixin` 接收真正传给 `ModelBlockRenderer` 的模型、段内位置与强制不透明设置。
`ModelBlockRendererSupportMixin` 记录原版实际返回并用于输出的 offset，不额外求值位置偏移函数。
位置运算沿用原版的 float 顺序：段内位置加转换为 float 的 offset，再加 quad 顶点。

- 只认证实际类型为 `SingleVariant`、`WeightedVariants`、`MultiPartModel` 的结构；不按资源命名空间判断。
  `SingleVariant` 的 part 必须为 `SimpleModelWrapper`，其中 `QuadCollection` 也须为实际已知类型。
  复制全部 quad 的四个顶点数值，包含六个剔除方向与 unculled 输出；不保留向量引用作为界。
- `WeightedVariants` 对所有候选取并集，不抽取一个随机分支。
  multipart 对全部 selectors 的子模型取保守并集，不执行其 predicate，也不依赖一次实际选中的子集。
  任一未知子模型、自定义 subclass、非有限顶点或无法读取的模型结构使该编译清单保持未证明。
- 分层记录候选界；强制不透明时将全部候选 remap 到 `SOLID`，半透明候选保留其独立层语义。
  导出的每层界与来源 `[0,16]³` 段盒取并集。缺少非空候选的实际 offset 时保持未知。
- 单次收集最多访问 4096 个模型节点、递归深度最多 64、最多扫描 65536 个不同 quad；
  遇到循环或超限时保持未知，不发布部分候选界。同一模型资源集合内复用已冻结的数值结果。
- 编译任务先冻结实际传入的 additional renderer 列表；列表非空或编译器为自定义 subclass 时保持未知。
  普通 fluid 只消费绑定原始 region 与实际资源表的来源证明，将整个段盒并入其实际输出层。
  自定义 fluid 回调和没有当前来源证明的普通调用使编译清单保持未证明。

成功完成编译后，任务持有绑定原始 `CompileScope` 的不可变 `Manifest`；重新开始编译先撤销旧清单。
安装时在 section 注册表 → RT 池锁顺序内发布清单及实际层内容版本，不用 scope 更新替旧清单补授资格。
当前已捕获输出越出候选界，或实际非空层没有对应候选界时，整个相关清单不能用于缺层覆盖。
`versions().outputs` 仍为 48 B 记录：当前缺层的 layer/version 与编译清单完全匹配时可导出 finite 界，
并由现有 FFM/native unknown 代理消费；更换内容版本不能借用此前的有限界。
实际安装的每个非空层都必须有对应候选界，包括尚无 RT 载荷的 TRANSLUCENT 层；缺任一层界则整份编译清单不获认证。
缺层或等待整段编译的输出没有当前自身界时，使用同次 `versions()` 冻结的认证 `universe.bounds`，以 flags=1 导出保守范围。
两者都无可信有限界时仍导出 flags=0。有限后备只限定 unknown 代理，不提供内容、层版本或空空间证明，实际要求和 targets 继续保留。
监听器、注册表、活动编译器或资源变化撤销全集时，新 revision 重新导出无界要求；已冻结的旧输出字节保持不变。
输入 dirty、装卸、section reset、模型资源发布和世界切换撤销相关清单。
旧的只读输出快照不随这些事件改变。GPU 几何记录仍为 64 B，输出支持记录仍为 48 B，当前契约为 ABI 45。

等待整段编译时使用独立的 `SourceManifest`，不把编译前的候选界当作实际编译结果或层内容版本。
任务创建时保存原始 `RenderSectionRegion` 身份；实际五参数编译调用在冻结 additional renderer 列表、
记录实际 block/fluid 资源对后，先在编译线程计算来源支持，再调用原版编译器。
来源扫描不持有 section 注册表或 RT 池锁；发布前重新核对原始 region、编译调用序号、scope、资源对与整段要求版本。

- 只认证实际 `SectionCompiler`、`RenderSectionRegion`、中心 `SectionCopy` 与 `PalettedContainer` 的已知类型，
  region 的 3×3×3 来源坐标必须匹配当前 section；block 模型集合及其 `IdentityHashMap` 也须为已知类型。
  中心 copy 缺少真实状态容器或处于 debug 世界时保持未知，不借 `hasOnlyAir()` 的省略信息认证空空间。
- 遍历中心 copy 的全部 4096 个位置。只接受实际 `BlockState`，fluid 须为精确 `EmptyFluid` 或取得下述普通路径证明；
  不求值 air/RenderShape 回调、随机 selector 或动态模型。即使某状态当前不输出地形，也保守纳入其全部已知模型候选。
- 位置偏移只接受无偏移，或与原版 `Properties.offsetType(XZ/XYZ)` 工厂返回对象身份一致的函数。
  查找实际 block 类继承链中的幅度方法，要求水平方法由 `BlockBehaviour` 实现；XYZ 另要求垂直方法由同一原版类实现。
  XZ 不要求未使用的垂直方法。任何所用幅度方法被重写或函数身份不明时保持未知，不执行该回调；不按方块名称认证。
- 对已认证原版偏移按实际世界坐标求值，先将 offset 转为 float 并加段内位置，再加候选顶点。
  候选数值沿用模型资源缓存与枚举上限，所有层取并集并包含 `[0,16]³`；不得忽略偏移或用单位方块盒覆盖跨段模型。
  强制不透明只重映射层，不能扩大该整段并集界。
- 任一未知模型、非有限坐标、未认证或自定义 fluid、未认证的 offset function、自定义 compiler 或 additional renderer
  使整段要求保持无界；不发布部分有限界，不跳过这些输入的实际渲染。
- 有限来源界绑定当前整段恢复版本及唯一清单对象，`versions().outputs` 用 layer=-1、flags=1 导出它。
  不增加实际层版本，不解除缺口或改变最早等待年龄；真正当前结果安装后才替换整段要求。
  任务取消、编译异常或重新开始编译立即撤销自身清单；旧所有者的晚到取消不能撤销新所有者的界。
  输入变化、表示退休、chunk 数据替换、模型发布、卸载和切世界沿用既有原子撤销。

普通 fluid 的范围认证由 `D3D12RtFluidSupport` 提供：

- 仅中心段存在非空 fluid 时扫描中心段外扩一格的 `18³` 邻域；27 个 copy 均须为精确 `SectionCopy` 且非 debug。
  非空状态容器须为精确 `PalettedContainer`；邻段省略的容器只按该已知 copy 的 `getBlockState` 行为读取 air，
  不据此认证空空间。region 的最小段坐标和数组布局须与当前来源一致。
- 非空 fluid state 必须是原版 water/lava 的 canonical 状态对象；amount 在 `1..8`，ownHeight 有限且在 `[0,1]`，
  所属流体必须保持已知同类语义。伪造状态、同类的非 canonical 实例、未知邻居与自定义高度实现保持未知，
  扫描不调用未知的高度或流向函数。
- `FluidStateModelSet` 须为精确类型，其资源表仅接受构造时冻结的 `D3D12RtModelTables.Frozen`、
  JDK `Map.of/copyOf` 的已知实际类型、精确 `HashMap` 或 `IdentityHashMap`；不调用未知查找实现。
  中心段各流体模型须无 custom renderer，输出层须可识别；模型或资源表身份变化不能借用旧证明。
- `beginSupportSession` 绑定当前任务拥有的 `SourceManifest`。普通调用逐次核对原始 region、
  精确 `FluidRenderer`、其实际资源集合/表/模型、段内位置及原始 block/fluid state 身份。
  核对失败或遇到自定义 fluid 回调时，立即撤销自身发布的有限来源界；自定义回调返回 true 或 false 均保持未知。
  旧会话只撤销其持有的清单对象，不能撤销新任务或新编译调用的证明。

上述证明只约束普通 fluid 的几何输出范围与层，不包含 tint/光照回调的完整内容依赖，也不提供透明输运或介质支持。
当前 RT 池仍不接收 `TRANSLUCENT` 载荷；透明层范围可被记录，不代表该层已成为可追踪的实际内容。

生产网格以 0=未观测、3=卸载、4=dirty、5=已加载但查询空间未认证作为未证明状态。
只有受认证的有限来源全集覆盖全部潜在来源、相关来源均已完成当前编译安装，而且实际非空层全部有当前载荷时，
才允许签发 1=已认证空或 2=当前完整查询空间。相关非空来源还必须全部进入同一 coverage 的实际 scene targets；
目标集之外的非空来源不能借邻格或相机半径取得完整空间资格。
普通 EMPTY、完整 terrain mesh、27 段编译输入或单个有限候选界均不能单独签发空间证明。
未知模型、自定义 fluid、额外渲染器及未完成的内容依赖继续保持未证明。
物理界的 flags bit 0 保持关闭；0、3、4、5 均按 unknown 消费。没有可信全局输出范围时，
生产空间表头置 flags bit 1（值 2），由 native 复制并传给 shader；已知格也不能消除这项无界来源义务。

`sourceInventory()` 与 `Coverage.sources` 同锁冻结世界 epoch、池 revision、所有已观测来源的生命周期、
候选支持、编译 scope、整段恢复版本和层要求。原始映射在锁内复制，逐来源与逐格清单按需从这些冻结数据生成，
不在每帧展开格子与来源的笛卡尔积。改变相机半径、镜像记录数或字节预算不裁剪来源清单。
`requiredAt(sectionNode)` 用闭盒相交收集有有限支持的来源，面、边、角接触均保留；
没有自身有限界的已观测来源先使用受认证的 `universe.bounds` 保守限定影响域；全集也无界时影响任意格子。
未观测补集按当前冻结的 `universe` 单独求要求，
不能把空的已观测列表当作来源闭包。

`ModelSetTablesMixin` 在 `BlockStateModelSet` / `FluidStateModelSet` 的构造参数进入字段之前，
把已知 JDK map 复制成保持对象身份查找的 `Frozen` 表；原始 map 的后续修改不影响实际编译器查找，
表、entry、迭代器和集合视图均禁止修改。未知 map 保持原调用语义，同时不具备全局范围证明资格。
每个模型资源代建立 `D3D12RtSourceUniverse`：并集 `Block.BLOCK_STATE_REGISTRY` 与已冻结 BLOCK 注册表的全部状态，
再枚举 FLUID 注册表全部状态、
每个实际模型及独立的 missing model，并集全部候选顶点、段内位置和受认证的原版 XZ/XYZ 偏移。
幅度方法接受 `BlockBehaviour` 的默认实现、`SpeleothemBlock` 的水平实现和 `SmallDripleafBlock` 的垂直实现；
未知重写不执行，也不取得资格。普通 canonical fluid 使用已有单位格输出范围，自定义 fluid 或模型使全局范围未证明。

全局范围还要求实际 `NeoForge.EVENT_BUS` 对 `AddSectionGeometryEvent` 的完整监听器列表为空；
通过实际 `EventBus` / `ListenerList` 读取，不调用监听器、不以某个位置的空 renderer 列表代替全局判断。
未知事件总线或反射失败保持无界。全局状态 ID 表须继承原始 `IdMapper` 的枚举和计数实现，允许 NeoForge 的可清空实现，
不执行自定义枚举。每次冻结池版本前复核注册表的冻结状态、数量、全局状态 ID 和逐对象身份及实际监听器；
变化推进池 revision，旧快照保留原证明，新快照立即撤销不再成立的范围。
同时从当前 `Minecraft.levelRenderer` 读取活动 dispatcher：`LevelRenderer`、`SectionRenderDispatcher`、
`SectionCompiler` 均须为原始精确类型，dispatcher 须未关闭，编译器实际 block/fluid 资源须与证明对象相同。
缺少活动调用链、自定义编译器或实际资源不匹配时保持无界，不能用一次普通编译的观察给其它生产者授权。

有限全局范围把未观测补集和无自身支持的已观测来源收敛为有限影响域，不证明内容已取得。
`unobservedRangeAt` 在各轴计算 `target - floor(maximum / 16)` 到 `target + 1 - ceil(minimum / 16)`，
按 SectionPos 的有符号 X/Z 22 位、Y 20 位域裁剪，闭盒接触仍计入。
范围用六个整数紧凑保存，先与已观测数量比较，再在可覆盖范围内查缺，避免枚举巨大世界或乘积溢出。
`requiredAt(...).unobservedMayInfluence` 仅在所有潜在来源均已登记时为 false；登记但未捕获、dirty 或卸载的内容要求仍保留。
有限类别可清除调用方的全局 bit 1；格子仍须逐一通过内容及空间认证才能成为状态 1/2，
存在未观测补集的格子不得获得已知状态。native 自身的无界要求仍与调用方标记按位或。

当前输出 AABB 和普通 EMPTY 安装均不能给整个来源授予有限界。
完整编译候选清单按全部层取并集且包含来源段盒；层版本与当前载荷不一致时撤销该资格。
完整实际层清单只从本次安装 mesh 的 draw 取得，并与该次 `replaceSection` 发布作用域、世界 epoch 绑定；
候选模型有 quad 而实际剔除结果为空时不虚构非空层；实际非空 draw 缺对应候选界时拒绝整份清单。
实际存在的 TRANSLUCENT draw 没有 RT 载荷时，
保留 version=null、captured=false 的层要求，不虚构内容版本。发布失败或世界切换撤销该作用域。
完整空编译仍保留来源段盒，只有全部潜在来源的当前内容闭合后才能签发空格。
卸载撤销自身有限界与内容，在来源快照中保留由全集保守限定或无界的义务，
既不重新加入恢复队列，也不向实际层版本表写入占位记录。dirty、资源发布和世界切换与现有输入协议一起撤销资格。
逐格认证要求来源状态为 5、没有整段待捕获版本、实际层集已知、scope 属于当前 epoch，且所有实际层已捕获并有版本。
完整空来源签发状态 1；存在完整非空来源且相应 node 全部在实际 targets 中时签发状态 2。
缺来源、缺层、dirty、卸载、支持界矛盾或未知全集均不取得已知状态；空间证明不代替后续 native 的实际载荷与 AS 准入。
网格用三张有界三维差分表累计来源依赖，最大边长 39，仅缓存一份按来源快照身份、网格和 targets 匹配的只读结果。
世界清理同时释放缓存与发布作用域；缓存复用返回独立的小端只读视图。
动态生产者协议、完整内容依赖、逐信号恢复与整个世界的空间覆盖继续独立验收，网格外仍为 unknown。
`D3D12RtNativeFrame.record` 在当前 G-Buffer 重放之后、追踪之前调用 `D3D12RtFrameSetSpatial`，
提交同一 coverage 的 world epoch、liveRevision 和网格；冻结失败不得继续追踪。
生命周期与 FFM 接通不代表查询覆盖或逐信号缺口恢复已完成，完整世界路线仍需独立验收。

### 7.1 材质索引与材质表

26.3 的地形顶点缓冲没有方块 id 列：`DefaultVertexFormat.BLOCK` 只有 `Position`(RGB32_FLOAT)、
`Color`(RGBA8_UNORM)、`UV0`(RG32_FLOAT)、`UV2`(RG16_SINT)，共 28 B。所以逐 quad 材质只能由
**UV0 四角平均**反查图集精灵的矩形、再拿顶点 `Color` 当 albedo 乘子推出来。

`D3D12RtMaterialTable` 是一条 40 B 的记录表（`albedo` RGB、`emissive` RGB、`roughness`、`flags`、`metalness`），
表项按 `(精灵索引, 顶点色)` 去重，逐 quad 存一个 **u16** 索引进几何池的材质块：

| 偏移 | 内容 |
| --- | --- |
| 0 / 4 / 8 | `albedo` RGB（线性色，精灵平均色 × 顶点色） |
| 12 / 16 / 20 | `emissive` RGB（线性色，强度取启发式表） |
| 24 | `roughness` |
| 28 | `flags`：bit0 半透明、bit1 次表面、bit2 双面、bit3 脉动自发光 |

表项 0 是兜底（中性灰 0.6、`roughness=0.8`、不发光），查不到精灵或表满的 quad 都指它。

- 颜色一律存**线性**空间：光追在线性空间积分，出图前不要再转一次。
- 表项上限 65536（跟随 u16 索引），比 12 bit 的紧凑 ID 宽：逐 quad 索引本来就是
  u16，没有省位的理由；间接表那一层等 S6 的体素导出需要时再加。
- 烘表在客户端 tick（渲染线程）惰性做一次，结果是不可变快照；UV 候选桶是 128×128 的 CSR 网格，
  查询先取桶再做矩形包含测试，不逐 quad 扫几千个精灵。
- 表就绪之前上传的段只有兜底索引，`tick()` 烘完会调 `D3D12RtGeometryPool.refreshMaterials()` 用池里的
  顶点副本重算一遍——顶点副本稳定，不需要重新上传几何。
- 日志：`rt materials: entries=… sprites=… quads=… spriteMiss=… full=… bakes=… bakeUs=…`。
  `full` 只该在真撞上 65536 项时增长，`spriteMiss` 大幅增长说明 UV 中心没落进任何精灵矩形。

警告：

- 动画精灵（水、岩浆、火）只采第 0 帧，`albedo` 与当前帧的贴图不一致——S2 只要求结构正确。
- 生物群系染色由顶点 `Color` 带进来，草/树叶的颜色因此是对的；但同一方块在不同群系下会拆成
  多条表项，这是去重键的一部分，不是漏算。
- `flags` 的 bit2 双面只对植被类生效；真正的双面求交在着色器里按 `abs(dot(n, d))` 处理。

重建时机：只在 `TextureAtlas` 对象身份变化时重烘。`TextureAtlas.getTextures()` 每次返回新的
- 材质表的 `revision()` 有两处 +1：重烘成功时、**每新增一个条目时**。少加后者会让上传镜像停在首帧那份，
  光追读到的材质缓冲里除了兜底项全是 0（弹射项乘上这个 albedo 就是一片黑）。
`unmodifiableMap` 包装，按 Map 身份比较永远不等——那样会每 tick 重烘一次并全量回填材质，
`rt materials:` 行会以每秒十几条的频率刷屏，`quads=` 累计值也一路涨。

### 7.2 把缓冲绑进 compute（S2 起）

`D3D12DispatchComputeEx` 的 SRV/UAV 表**纹理与缓冲都能收**，缓冲走 raw view，
HLSL 侧就是 `ByteAddressBuffer` / `RWByteAddressBuffer`：

- 只读缓冲：`device.createBuffer(label, usage or GpuBuffer.USAGE_COPY_DST, data)` 一步上传；
- 可写缓冲（UAV）：usage 必须带 **1024**（本工程内部约定，见 native `createBuffer` 的注释；
  不要用 512 —— 那是 `GpuBuffer.USAGE_INDIRECT_PARAMETERS`，撞位会让 UPLOAD 堆资源带上
  `ALLOW_UNORDERED_ACCESS` 而非法）；
- 绑定顺序就是表里出现的顺序：SRV 从 `t0` 起、UAV 从 `u0` 起，`b0` 是 0~8 个 int32 常量。

## 8. G-Buffer pass（S2 2-c）

不透明地形每帧写五张附件，外加自己的一份深度，构成光追要读的 G-Buffer。
仅在 `D3D12Settings.rtEnabled` 为真时工作；`AUTO` 同样执行 G-Buffer 与光追合成。

| 附件 | 格式 | 内容 | 清除值 |
| --- | --- | --- | --- |
| `rt-gbuffer-albedo` | `RGBA8_UNORM` | rgb = 反照率（图集采样线性化 × 顶点色），a = 1 表示「被 G-Buffer 写过」 | (0, 0, 0, 0) |
| `rt-gbuffer-specular` | `RGBA8_UNORM` | rgb = 常量 0.04（金属性 F0 占位） | (0, 0, 0, 1) |
| `rt-gbuffer-normal-roughness` | `RGBA16_FLOAT` | xyz = 世界空间面法线，w = 粗糙度 | (0, 0, 0, 0) |
| `rt-gbuffer-emissive` | `RGBA16_FLOAT` | rgb = 自发光（当前恒 0） | (0, 0, 0, 0) |
| `rt-gbuffer-depth-linear` | `RGBA16_FLOAT` | rgb = `sqrt(saturate(相机距离 / 64))` | (0, 0, 0, 0) |
| `rt-gbuffer-depth` | `D32_FLOAT` | 本 pass 自己的深度（写、`GREATER_THAN_OR_EQUAL`） | 0.0（reverse-Z 远端） |

反照率的 `a` 是「这一像素有地形」的标记，合光靠它把光栅没画的像素（天空、实体、粒子）退回
`indirect`。清成 (0,0,0,0) 而不是白色：清白会把那些像素当白色反照率乘出一片死白。

**深度必须自己写，不能借主 pass 那份做只读比较**：同一几何在同一帧里第二次绘制，深度值浮点不完全相等，
`GREATER_THAN_OR_EQUAL` 会把整片像素拒掉，画面上是地平线附近一条锯齿白带和近处的楔形漏画。
自己的深度从 0 开始（reverse-Z 远端），第一个通过的片元就是它，不存在相等性问题。

**不要试图把深度附件当 SRV 读回**：`rt-gbuffer-depth` 直接做 compute 的 `t0` 时实测读出来全是 0
（native 已把 `D32_FLOAT` 映射成 `R32_FLOAT` 的 SRV，仍读不到），所以第五张附件专门存线性距离给调试视图用。

肉眼查看：

- 选择 B-DXR，令 `[rt] mode` 为非 OFF 值；设备需支持硬件 DXR；
- `[rt] debugView` 设 `ALBEDO` / `NORMAL` / `DEPTH` / `ROUGHNESS`，主目标上直接显示那一张；
  前三张直接 blit，`ROUGHNESS` 要先过一遍解包 compute（见 §9.5）。

改 `debugView` 不用重启游戏：配置文件被监听，几秒内下一次合光就走新档位。

每帧数据流：

1. 主 pass 里每次地形 `DispatchMesh` 成功（`D3D12RenderPassBackend.tryMeshletDraw`）立即调用 native 捕获，
   提交程序令牌、几何范围与组数，以及该次绘制的 uniform slice 和 texture view；JVM 只保留捕获令牌与计数；
2. 主 pass 结束（`D3D12CommandEncoderBackend.submitRenderPass`）时若捕到过批次，调用 native Begin G-Buffer，
   由帧上下文分配/复用五张颜色与独立深度并打开 pass，全部颜色每帧清，深度清成 0.0；
3. `D3D12RenderPassBackend.replayGBuffer()` 以令牌调用一次 `ReplayCapturedGBuffer`。
   native 预检整组后重建 globals（原始组数、`cull = 0`、VRS 全 1X1、相机偏移），逐批绑定 PSO/描述符并派发 mesh。
   `Projection` 替换为 native 持有的 64 B 采样矩阵，分配按 256 B 对齐；原版 UBO 保持不变；
4. 所有批次成功才提交本帧偏移；finally 调用 native End G-Buffer 并传入成功标志。失败重放关闭 pass，禁止发布部分输入；
5. native 核对重放编号、时间戳、尺寸、encoder 与未提交 fence 后才消费本次输入。主目标保持稳定投影，
   新关卡投影上传与重放开始均撤销旧光追输出资格，避免失败时合成上一帧结果。

附件生命周期由 native 负责：尺寸变化成组替换，旧资源按实际 fence 退休。`Targets`/`Image` 只借用句柄，不能调用纹理销毁。
每次重放创建新的 Image 身份，旧帧图像不能进入调试 blit；同尺寸保留 resourceIdentity，避免每帧误清降噪历史。
Begin 在分配/清屏和撤销旧输出前检查上一份 producer ticket：同录制与同 encoder 的已提交顺序工作可复用，
跨 encoder 则必须等待原 producer 实际完成。未提交或未完成时返回失败、输出清零，原附件、版本与后续消费者资格保持有效。
明确取消或销毁的旧 producer 清理后可重新 Begin；结束 G-Buffer pass 不等于提交或完成。
执行后任一次 Signal 失败虽撤销输出，已提交 producer 仍保留到实际 fence 完成；在此之前禁止复用附件。
销毁 encoder 也保留整套列表槽位，直到原设备与 fence 给出实际完成证据；不得在固定等候超时后释放。
原设备与队列由 encoder 创建时保留，不能从 fence 父设备与 SDK 代理设备的裸指针相等性推导。
失败提交的补发完成 Signal 只允许回收资源，不恢复输出或 history 资格。
设备关闭若仍有打开的列表、队列完成标记失败或超时，就保留设备与 SDK 资源并等待调用方重试。
关闭顺序与 context 退休规则见 [命令列表管理](../../../MCDR/docs/device/README.md#54-延迟删除)。
`_port/rt_native_frame_check.py --gbuffer-ownership-only` 用两份 encoder 与真实阻塞队列验证拒绝、完成后重试、
同录制/同 encoder 顺序复用、取消/销毁及两次 Signal 故障恢复；执行后销毁 encoder 的用例不手工补 fence，
仍须保留在途附件、拒绝外来覆盖，并在实际完成后读回原 NRD 输出和恢复新输出。
探针另读回合光输出；
该检查也包含在完整 native 帧探针中。
`_port/rt_rr_encoder_retirement_check.py` 另检查真实 RR 在正常提交及两种 Signal 故障后直接销毁 producer，
不手工补 fence，核对实际 reset、完成前的 feature/槽位保留与完成后的原输出、恢复输出读回。
NRD 内部同时区分执行前取消与执行后提交失败：后者撤销输出，但旧池保留至实际完成，下一帧可使用独立新池恢复。
不能把共享 ticket 的 cancelled 位直接当作 GPU 已停止访问；内部状态与验证见
[NRD 帧输入](../../../MCDR/docs/advanced/nrd.md#帧输入)。

必须是独立 pass：native 的 `beginRenderPass` 在 pass 已打开时直接报 `render pass already open`，
主 pass 里嵌不进第二段带不同附件的渲染。代价是主目标与深度各多两次状态切换。

捕获批次时复制绑定映射，重放时禁止读取主 pass 结束时的最后一组纹理和 uniform。
快照里的 slice/view 只在本帧消费；后续批次改绑不能改变前面批次使用的图集与常量。
同一 native 捕获组按源 view descriptor 共享不可变纹理视图快照；不同 mip/cube 视图保持独立，不跨 token/pass 复用。
可改写的 UPLOAD/DEFAULT 缓冲仍逐批冻结；纹理引用与视图快照保活至所属列表槽的实际 fence 完成。
native 整组预检失败允许修正后重试；一旦开始录制后失败，本次回放只能取消。成功批次不可重复提交。

重放复用主 pass 的几何与材质绑定，投影使用 RT 专用采样切片；其它差异如下：

- **组数用原始 meshlet 数**，不是 `(n + 31) / 32`：G-Buffer 的 MS 恒不带 AS（带 payload 的
  `ms_main` 在没有 AS 时是非法入口），cull 也就在这一遍关掉；
- **深度用自己那份、写深度、`GREATER_THAN_OR_EQUAL`**：不借主 pass 的深度（理由见上）；
- **PSO 的颜色槽是五张 G-Buffer**：`D3D12Device.createGBufferVariant` 只为 `[mesh] terrain` 的
  SOLID / CUTOUT 地形管线建，半透明与 OIT 不建。

着色器怎么来的：`D3D12RtGBufferShader.build` 拿地形管线原来的 VS / PS HLSL 改写——MS 由
`D3D12MeshletShader.build` 生成后多输出一条 `float3 gbufWorldPos`（占下一个空闲 `TEXCOORD`，
相对本帧实际射线原点，见 §19.2）；PS 从 spvc 入口之前的前缀里捞出纹理、采样器、UV0、Color 字段，
拼上光影包的 `B-DXR/shaders/rt_gbuffer_ps.hlsl` 模板（占位符在运行时替换）得到 `gbuf_main`：反照率 = `pow(采样, 2.2)`（有顶点色再乘），面法线 =
`normalize(cross(ddy(worldPos), ddx(worldPos)))` —— **顺序反了**（D3D 屏幕 y 朝下，`cross(ddx, ddy)`
得到的是朝里的法线，地面会存成 `-Y`）。光照路径按射线方向自己翻面，所以顺序错了只影响存进 G-Buffer 的法线，
`debugView=NORMAL` 会整片洋红。

反照率取「图集采样 × 顶点色」：着色器里按 `register(t<n>, space<n>)` 找 PS 的 `Texture2D` 与同名寄存器的
`SamplerState`，用 `SampleLevel(..., 0.0)` 取 0 级 mip——图集里相邻精灵挨着，各向异性过滤会把邻块像素带进来。
spvc 的声明带 `space` 限定，正则漏掉它就会一路退回常量 `0.6` 灰色（`detail` 里显示 `albedo=constant*…`）。
找到的那一条 varying 名为 `gbufWorldPos`，语义取 `SPIRV_Cross_Output` 里最大 `TEXCOORD` 号 + 1。

G-Buffer 的 MS 在 `vs_main` 后用原始顶点 `Color` 覆盖输出的颜色 varying，保留群系染色与顶点 AO。
禁止直接使用原版 `Color * sample_lightmap(...)` 作为反照率，否则原版光照会再次乘进光追照明。

打开 `[debug] dumpHlsl` 时这一对产物会落到 `dxrmc/hlsl/gbuffer_ms_<hash>.hlsl` 与
`gbuffer_ps_<hash>.hlsl`：注入点靠正则在 spvc 输出里定位，坏了只有现场产物能拿去离线编译。
编译失败时**不看配置**也会落盘成 `gbuffer_failed_{ms,ps}_<hash>.hlsl`，两个阶段各自独立编译、
各自报错（只报第一个错会让下一个错误等下次启动才暴露）。

日志：附件分配成功一次后打 `rt gbuffer: targets {w}x{h} albedo=RGBA8_UNORM …`；分配失败 warn
四次后静默（`rt gbuffer: allocate failed: … (attempt n/4)`），重放失败打
`rt gbuffer: redispatch failed: …`。

源码见 §6 表里 `D3D12RtGBuffer.kt` / `D3D12RtGBufferShader.kt` / `LevelRendererRtDebugViewMixin.java`
三行，以及 `D3D12RenderPassBackend.replayGBuffer`、`D3D12CommandEncoderBackend.recordGBufferPass`。

警告：

- 只有**不透明地形**写进 G-Buffer：半透明归 S4 的 OIT，实体、粒子、手都还没写。
- 粗糙度整张是常量 `0.8`（`D3D12RtGBuffer.DEFAULT_ROUGHNESS`）：材质表还没进 P/S——mesh 根签名
  的 extras 表固定 3 个 descriptor range（t14 / t15 / t16）加 b8，加第 4 个要动 ABI。
- `specular` 只有常量 F0、`emissive` 恒 0，同样是等材质表。
- 法线来自屏幕空间导数：像素落在三角形边缘时会取到隔壁三角形的值，轮廓一像素内偏色属正常。
- 屏幕空间导数在**平面三角形**上是精确的，但台阶状的方块侧壁会得到「面法线」而不是「斜面法线」——
  这与原版的地形光照不同，S3 接 DXR 后按几何法线取。
- 没捕到地形批次（纯天空、主菜单）就不开这个 pass，`debugView` 也就没有可铺的图。
- `debugView` 的铺屏走 `recordTextureBlit`，它会用线性采样把源缩放到目标尺寸——G-Buffer 与主目标
  同尺寸时就是 1:1 拷贝。
- `MATERIAL` 档位还没有：材质索引还没进 G-Buffer（见上一条粗糙度的原因），`RtDebugView` 里也没有
  这个枚举，`GEOMETRY` 只是给 S3 的几何调试留的名字。
- `DEPTH` 视图铺的是 `g_DepthLinear` 的线性距离（已开平方），不是深度附件的 reverse-Z 原值：
  深度附件当 SRV 读回来全是 0，读不到。
- 追踪半径（`TRACE_RADIUS = 96`）之外的地面拿不到主光线命中，合光直接放过原版像素——远处没有
  光追阴影，与近处之间能看到一条亮度/色调分界，这是镜像范围的边界，不是几何错误。

## 9. 逐帧派发与合光（S2 2-d / 2-e）

### 9.1 一帧的顺序

主 pass 结束 → G-Buffer pass（§8）→ 光追派发 → 合光 → 上屏 blit。四段都在同一条 DIRECT 命令列表里，
只有 G-Buffer 与合光之间的两段需要 `inRenderPass == 0`。

- **光追派发生在 `D3D12CommandEncoderBackend.recordGBufferPass` 的末尾**（`D3D12EndRenderPass` 之后），
  因为 native `beginRenderPass` 只认「当前没有打开的 pass」，而 `dispatchComputeEx` 又不检查
  `inRenderPass`——在 pass 里派发不会被 native 拦住，只会被 D3D12 调试层报错。
- **合光与上屏在 `LevelRendererRtDebugViewMixin` 的 main pass 体 RETURN**：那时 OIT、outline 已合成，
  深度还在，GUI 未画；`debugView = NONE` 走「合光 → blit」，其余视图走「G-Buffer 单图 → blit」。

### 9.2 上传镜像

几何池与材质表各带一个 `revision()` 计数器（`D3D12RtGeometryPool` / `D3D12RtMaterialTable`），
`D3D12RtFrame.mirrorsFor` 把它俩和相机位移一起当键：池写进新段、材质表重烘、或相机移动超过
`MIRROR_SLACK = 24` 方块时才重传。

**镜像不是整池。** `D3D12RtGeometryPool` 为实际保留的不可变顶点计算 `OutputBounds`，
扫描完整 28 B 步长载荷，支持非零 buffer position；范围是全部顶点与来源 16×16×16 段盒的保守并集。
`coverageNear` 与 `mirrorNear` 都以该输出界判断半径 `TRACE_RADIUS + MIRROR_SLACK` 的闭球相交，
面、边、角接触也计入范围；来源段在半径外但输出伸入范围的几何仍进入目标和候选。
任一 layer 相交时，以同一 `sectionNode` 的全部已发布 layer 为完整选择单位，
仍按段中心距离、再按有符号 `sectionNode` 排序。
整组超过 `MIRROR_RECORDS = 384` 条 layer 记录，
或顶点 + 索引 + 材质 payload 合计超过 `MIRROR_BYTES = 128 MiB` 时停止，不能拆组或用更远区段填洞。
索引计入每条记录的 4 B padding；两个预算不含记录表和 quad 缓冲容量的末尾对齐字节。
最近一组本身超过预算时返回空镜像，不复用旧的大镜像。
范围判定以 Double 计算相机与段原点的差值，再计算到局部输出界的最短距离平方；
中心与半径必须有限，半径不得为负。没有有限输出界的现存 record 不以来源段盒排除。
输出界随当前不可变 record 更新、删除和 dormant 重入；offset 复用不继承上一内容的界，旧导出不变。
GPU record 仍为 64 B；输出界使用独立 48 B `mcdr_rt_output_support`，当前契约为 ABI 45。
`versions()` 在同一池锁内冻结当前 layer/version、缺失层与整段 dirty 要求的输出支持清单。
flags=1 表示包含来源段盒的有限支持界；flags=0 表示没有可信有限界且坐标全零，layer=-1 表示等待整段当前编译。
普通层的界来自当前输出或与当前缺层版本匹配的已完成编译候选；layer=-1 的有限界来自当前来源扫描。
警告：这些界不能证明空格、全部邻段生产者或整个世界物理范围；来源扫描也不提供当前可查询的实际内容。
缺载荷与 dirty 的目标没有可信有限界时始终保留，不能按来源段盒排除或借用旧载荷的界；相应空间义务保持 unknown。
不要把这张选择清单当作状态 1/2 的签发依据，也不要据此缩短生产射线。
空载荷仍有范围内已观测非空层要求时，`mirrorNear` 返回零记录 `MirrorSlice`，内容清单和三角形数均为 0。
生产以零记录 delta 取得合法非零 snapshotId，独立目标与输出清单保留有限代理或无界 unknown；不会伪造几何或将缺失当作天空。
没有范围内所需目标时返回不可用，并撤销原场景；这不表示未观测空间已知为空。
最终按 node/layer 稳定排序，仅生成 64 B 记录表与不可变 `ContentRecord` 清单。
每份顶点/索引/quad `Content` 具有独立 identity，导出强持实际 allocation，只向消费方提供只读 view。
释放、逻辑 offset 复用或换世界不能修改旧导出；dormant 重入和材质回填采用 quad 内容写时复制。
映射未变化时保留 quad identity 和 revision，材质映射变化不改变顶点/索引或兼容 BLAS 的内容版本。

选择变化立即生成新的镜像，不再按固定帧数延后。近场记录及其版本未变时复用同一个 `MirrorSlice`，
远处区块上传不重建近场数据。完整 CPU 快照与材质均未变时复用 native 令牌；
选择变化时上传完整小表，但未变内容只提交 identity 与长度，不再紧排、经 FFM 复制或重传。
只在成功复用选中切片或成功创建 native 快照后更新镜像中心；上传失败必须保留原中心，
使相机停在同一位置时下一帧仍能重试。明确收到 `rt geometry: reuse recording cancelled`
或 `rt scene: geometry recording cancelled` 时，丢弃当前/已发布镜像与 AS 场景，下帧完整重传。
普通 pending、跨 encoder 尚未完成和其它临时失败不因此丢弃仍有效的镜像。
arena 或上传预算不足且当前材质代仍匹配时保留可用镜像，保持中心/版本键不变并在后续重试；
scene 每帧继续检查最新 live 版本，不能因背压复活已编辑/删除的旧几何。
镜像上传日志首次为 INFO，后续为 DEBUG。

完整组选择只保证入选区段不因预算截断而丢层。完全未选的区段、客户端未加载区段和射线区间的覆盖证明
仍是独立边界；不能把镜像内 BLAS 全部 ready 当作整个光照支持域完整，也不能把 unknown 当作无遮挡。

整段提交保留仍存在的层。位置（每顶点前 12 B）、顶点数、索引内容、数量与宽度共同决定
`geometryVersion`；这些数据未变时保留 BLAS 身份，颜色或 UV 变化不触发几何重建。
颜色或 UV 变化仍生成新的不可变内容、推进池 revision 并更新选中镜像；GPU 读取当前属性和 quad 材质映射。
CPU `Record.contentVersion` 保存最后一次实际更新内容时的上传版本，参与镜像内容比较，
不写入 64 B GPU 记录。已导出的旧内容保持只读；active 更新和 dormant 回访均遵守相同规则。
完全重复上传或仅原版 lightmap（末 4 B）变化时保留内容版本和池切片；RT 光照不读取该 lightmap。
位置、索引或布局真正变化时使用新的 `geometryVersion`；层集合变化仍更新完整 section 的选择与失效。

常量缓冲就是包里那份 `WgaFrame`（416 B，`D3D12RtFrameConstants.BYTES`），建在**上传堆**
（`GpuBuffer.USAGE_MAP_WRITE`）并按 `FRAME_SLOT_BYTES = 512` 分槽轮转三份（`SCENE_RING = 3`）：
单份会在 GPU 还在读的时候被下一帧改写，而 416 B 的内容塞进 512 B 的槽是为了满足 CBV 偏移必须
256 字节对齐。它由 `sceneFor` 单独分配、不随镜像重建；每帧 `map(write = true)` 直接写 416 B，
不再经过 staging + `D3D12CopyBuffer`（上传堆本来就 CPU 可写）。字段偏移全在
`D3D12RtFrameConstants`，与包的 `wga_constants.h` 逐字段对拍（见 §10.1）。

五块 SRV 缓冲（记录 / 顶点 / 索引 / 材质 / 逐 quad 材质）由
`D3D12CreateRtGeometryDelta` 在 native 分配、上传和保活。三类 GPU arena 的逻辑容量固定为 256/64/8 MiB，
各内容占有不可变范围；native 改写 GPU 小表的地址，CPU 小表保留自身的紧排布局用于版本/诊断。
记录表与材质代独立保有；材质变化仍上传完整 blob。一次请求只创建一块合并 staging，不为每条内容创建独立 UPLOAD 资源。
JVM 只持有不可变快照令牌，不单独销毁借用缓冲。五个 SRV 顺序和 shader 解码不变。

当前镜像与已发布镜像分别持有快照；退出这两个角色的镜像，只有所属 `recordingIdentity`
已经收到成功提交回调，才进入 `hotMirrors`。未提交或取消的镜像退出角色时直接释放令牌。
几何提交资格独立于帧 history：只有实际 producer 的成功回调可以确认，上传编码器不能确认其它编码器的相机或历史。
native 仍检查每个范围的 producer，缓存中的提交资格不替代 fence 可消费检查。

热返回缓存按退出角色的先后顺序淘汰，最多 8 份快照、128 MiB 逻辑保留量。
按唯一 `Content.identity` 计几何字节；每份 record table 计 CPU 切片、native CPU 表与 GPU 表三份，
材质 blob 按借用 buffer 身份去重。当前/已发布角色、对象开销、GPU allocation 对齐与在途退休量另计；
实际在用 committed 容量继续受 native 的 512 MiB 门限约束，128 MiB 不是整个 scene 的内存上限。

创建新镜像时，仅合并同世界、同 geometry epoch、同三类 GPU arena 的当前/已发布/热缓存内容证明。
已经成功提交或属于当前录制的内容可省略 FFM payload；native 在共享 arena 内查找对应不可变范围。
因此材质代未变化、缓存仍命中的 A→B→A 往返只写新的 record table，几何内容无需再次暂存或上传。
每次 record table 始终来自当前 live 选择，真实编辑使用新 identity，删除立即移出表；缓存只保活内容范围。
世界或 epoch 变化、明确取消后的镜像丢弃以及 RT release 清空全部角色与缓存，并对快照令牌去重释放。
arena、DEFAULT 或 UPLOAD 背压时先清热缓存，保留仍有效的当前镜像，后续在同位置重试；
撤销令牌后在途资源继续按实际 fence 保活，淘汰不等待 GPU，也不表示 committed 容量已立即下降。

`MirrorSlice.vertices/indices/quads` 仅为低层检查按需生成紧排副本；生产上传不访问这三项。
`StorageStats.retainedContentBytes` 统计尚未被回收的 backing 内容，包含旧导出，弱引用清理后才扣除；
它不包含对象开销、record table 或诊断生成的紧排副本，不能直接当 JVM 总内存预算。
`RT upload: payload=… staged=…` 分别报告实际上传有效字节和 FFM 对 heap 内容的暂存字节。
`D3D12GetRtGeometryMemory` 分别返回在用 DEFAULT/UPLOAD 的 committed 容量，去重共享 arena，并计入旧快照与在途多代。
Snapshot 与 delta 入口共享两类在用内存各 512 MiB 的上限，换世界或 encoder 不重置账本。
Snapshot 分配前检查新增 DEFAULT/UPLOAD 容量；纯引用复用不重复计费，背压清零输出且不提交或等待 GPU。
RT 私有 buffer 仅复用相同物理容量的池内资源，容量按请求的 256 B 对齐值确定，不能以小请求借入大资源突破估算。
通用 buffer pool、AS、SDK 历史和完整 CPU 导出预算仍需分别核算。
接口与取消/退休约束见 [native 几何快照](../../../MCDR/docs/advanced/rt-frame.md#11-不可变几何快照)。
`_port/rt_world_geometry_check.py --recording-recovery-only` 检查产品镜像的提交归属、热返回、
共享 arena 内容并集、编辑/删除、唯一内容/材质预算、背压重试及释放；
`_port/rt_geometry_snapshot_check.py --delta-only` 通过实际 GPU 表与范围读回检查增量复用和在途退休。
这些检查不替代世界内噪点、卡顿与流送稳定性的目视和性能验收。

记录里的 `materialOffset` 存的是 **quad 序号**，不是字节偏移：shader 侧 `RtQuadMaterial` 自己乘 2 取 u16。

### 9.3 追踪尺寸与几何预算

`D3D12RtPipelinePlan` 统一决定 trace 与 G-Buffer 的 render 尺寸，追踪不再有独立低分辨率上限。
`TRACE_RADIUS = 96.0f`、`MIRROR_RECORDS = 384`、`MIRROR_BYTES = 128 MB` 限制近场镜像。
硬件 BLAS/TLAS 处理求交，镜像按段 AABB 与选择球是否相交裁剪，段中心距离只决定选择优先级。几何或材质改变立即刷新所选镜像；
只有当前镜像的加速结构全部就绪才派发。远处上传未改变近场集合时复用镜像与历史。

### 9.4 解调纪律

光追着色器只写「到达表面的辐射度」：`gDirect` = 太阳项直接可见性乘积，`gIndirect` = 一次余弦弹射的
结果（未命中给天空色）。`gAux.xyz` 保存主命中距离、太阳可见性与 diffuse 弹射距离，
`gAux.w` 保存主命中材质 ID 的原始 uint 位。`gSpecular` 与 `gSpecularGrazing` 保存镜面 Schlick 系数，见 §17.2。
主表面材质只在重调制阶段乘一次：

一次弹射写的是**命中面的出射辐亮度**：`emissive + albedo * (sun 项 * 可见性 + sky 项)`。
漏掉这个 `albedo` 会让背光的第二命中面贡献 0，合光乘上主表面 albedo 后就是地面上的一块纯黑；
末尾的 `sky` 项是「弹射到此终止」的近似环境光（§4.9 允许把超距当环境光），不是第二次弹射。

弹射打空按弹射起点和 `bounceDir` 计算环境辐亮度；二次命中的终止环境使用
`WgaAmbientApprox()`，只依赖太阳高度。禁止将主射线方向的天空颜色复用于二次命中照明。
`debug mode 9` 使用固定环境常量，供 `D3D12RtLightContract` 的 CPU 参考对拍。

```
if (direct.w == 0) out = 原版像素          // 没有光追数据：天空、实体、追踪半径外
else {
    linear = albedo * (direct + indirect) + specular * f0 + specularGrazing + emissive
    color = WgaLinearToSrgb(WgaTonemapAces(linear * exposureTexture[0,0]))
    fade  = saturate((radius - hitDistance) / (0.35 * radius))  // gAux.x = 命中距离
    out   = lerp(原版像素, color, fade)                        // 半径边缘淡出，避免一圈接缝
}
```

`WgaLinearToSrgb(WgaTonemapAces(...))` 是整条链里**唯一**的显示变换：主目标格式是 `RGBA8_UNORM`，
原版其余绘制都在 gamma 空间写，线性辐亮度直接写进去会整幅发暗。G-Buffer 里的 albedo 保持线性，不做任何编码。

合光的第 5 个 SRV 是主目标颜色本身、第 6 个是 `gAux`（只用到 `x` = 主光线命中距离）：
命中距离接近 `TRACE_RADIUS` 时淡回原版像素，否则半径处会有一条「RT 地面 / 原版地面」的硬边。`gDirect.w` 为 0（主光线在镜像范围内没打到三角形）或
albedo 的 `a` 为 0（G-Buffer 没写过这个像素）时**原样放过主 pass 的像素**：这样天空还是原版的天空、
实体与粒子还由原版画、`TRACE_RADIUS` 外的远景也保持原版外观。用辐亮度硬填这些像素的后果是
「天空变成常量色、远处出现一条偏蓝地带」。

合光读 G-Buffer 的 albedo（`RGBA8_UNORM`，已是线性空间）与 emissive，按 `gRadianceW/H`
把输出像素映射到降采样的辐亮度像素再取 16 B 三元组——**不能拿输出像素直接索引辐亮度缓冲**，
越界会读到 0 变成黑块。输出写进 `rt-hdr`（`RGBA16_FLOAT`），再 `recordTextureBlit` 铺到主目标。`rt-hdr` 必须按
`USAGE_STORAGE or USAGE_RENDER_ATTACHMENT` 创建：`blitTextureToTexture` 收尾会把**源**切回
`RENDER_TARGET`，只带 `STORAGE` 的资源切到这个状态非法，错误会留到 `Close()` 报 `E_INVALIDARG`
并移除设备（表现是整帧不提交、渲染线程卡在等栅栏）。

### 9.5 调试视图怎么出图

`[rt] debugView` 不为 `NONE` 时不再合光，而是把一张图铺到主目标上：

| `debugView` | 源 | 通道 |
| --- | --- | --- |
| `DEPTH` | `g_DepthLinear` | 直接 blit，灰度 = `sqrt(距离/64)` |
| `NORMAL` | `g_NormalRoughness` | `debugChannel` 模式 `4`：`0.5 + 0.5 * rgb` 编到 0..1 再 blit |
| `ALBEDO` | `g_DiffuseAlbedo` | 直接 blit |
| `ROUGHNESS` | `g_NormalRoughness` | `debugChannel` 取 `w` 拉成灰度 |
| `BLAS` | 合成结果本身 | `debugBlas`：把 TLAS 里每个实例的 16³ 段盒画成彩色棱线叠上去（§14.6） |

粗糙度这类单通道数据不能直接 blit（`recordTextureBlit` 只采三通道），要先过
`D3D12RtDebugShader` 的 compute：读源纹理第 `gChannel` 个通道、`saturate` 后铺成灰色写进 `rt-hdr`，
再 blit 上屏。`debugChannel` 在派发前会把 `g_misc.y` 打成 `channel`（`D3D12RtFrameConstants.patchDebugMode`）——
追踪每帧都会把这一位写回 0，不自己打一次就等于永远按通道 0 取。法线档写的是 `0.5 + 0.5 * n`（世界空间法线在 -1..1，直接 blit 会被 `RGBA8` 目标截成 0/1，
地面会因为 `y = +1 → 1`、`x/z = 0 → 0` 变成纯绿甚至全黑；编码后地面是淡绿、朝 +X 的面是淡红）。深度视图铺的是**线性距离**（不是 reverse-Z 深度值），已开平方压过动态范围：
近处 1 方块 ≈ 0.13、16 方块 ≈ 0.5、64 方块 = 1。

### 9.6 逐阶段耗时

`D3D12RtStats` 用一条 8 槽时间戳池记录帧起点与六个阶段终点（G-Buffer / 光追 / 降噪 / SR / 合光 / 上屏）。
统计窗口至少 5 秒；下一帧开始时先在当前命令列表中录制上一采样帧的 query 快照，再覆盖时间戳槽。
`D3D12QueueQueryValues` 只录制 resolve，`D3D12TryReadQueryValues` 只轮询所属提交的 fence；
两者均不主动提交或等待 GPU。同一池最多一份未消费快照，未完成时继续渲染，完成后才追加一行
JSON 到 `<user.dir>/dxrmc/rt_stats.json`。不能把 Present 已提交解释为 GPU 已退休。

```json
{"time":"…","tier":"dxr","frames":…,"width":…,"height":…,"trace_width":…,"trace_height":…,"records":…,
 "triangles":…,"cpu_ms":…,"dispatches":…,"vram_mb":…,
 "blas_builds":…,"blas_refits":…,"blas_compacts":…,"blas_ms":…,"blas_build_us":…,
 "blas_vram_mb":…,"blas_scratch_mb":…,"tlas_instances":…,"tlas_ms":…,
 "gpu_sample_frame":…,"gpu_sample_slots":…,"gbuffer_ms":…,"trace_ms":…,"denoise_ms":…,
 "sr_ms":…,"composite_ms":…,"blit_ms":…,"total_ms":…}
```

`tier` 记录实际追踪后端，当前成功派发为 `dxr`。GPU 字段只描述一个采样帧；
`gpu_sample_frame` 是统计器从启用起的本地递增编号，关闭时清零，不是全局帧身份。
`gpu_sample_slots` 只计从 START 起连续写过的槽，首个缺口后的阶段输出 `null`，不能混入上一帧的旧槽值。
`total_ms` 以最后一个连续槽为终点；无有效时间差则丢弃该样本，不写虚假的零耗时。

入队时冻结窗口帧数、尺寸、CPU 与 AS 指标，延迟读回不能套用当前帧的规模或累计值。
`cpu_ms` 是编码侧墙钟时间的窗口均值，`dispatches` 是窗口内每帧派发均值；
`blas_ms` / `tlas_ms` 是 CPU 录制耗时，不是 GPU AS 构建时间。
这些采样不能代表整窗 GPU 均值、P95/P99 或所有长帧，性能验收仍需逐帧记录。
`triangles` 取入队时 CPU 几何池规模，不等同于该 GPU 样本的 active scene 三角形数。
`vram_mb` 是启用以来自有镜像、相机常量、辐亮度、G-Buffer 与输出的峰值，关闭时清零，
不含驱动对齐及其它子系统；BLAS 与 scratch 的启用以来峰值另列。

轮询返回 `0` 时不消费快照、不覆盖输出；返回 `-1` 时丢弃失败窗口并重建 query 池，
不能输出旧时间戳。关闭统计时取消未消费窗口，native 保留已录制资源直到列表退休。
旧 DLL 缺少快照接口时跳过该统计，不回退到阻塞式 `D3D12ReadQueryValues`。
成功样本只在 DEBUG 输出 `rt stats: GPU sample … (…ms)`。

### 9.7 着色器自检

`D3D12RtTrace` 使用 DXR 执行四项检查：常量与输入缓冲探针、空视野、单三角形、地面与遮挡板。
空视野通过退化三角形建立合法 BLAS，要求没有主射线命中。其余场景须实际命中，阴影场景同时要求直射与遮挡。
CPU 参考独立计算命中、材质和辐亮度；受控场景的离散量和数值容差用于检查算法与绑定，
不用于比较 NRD、RR、XeSS 等最终图像。阴影场景的间接光擦边命中不作逐像素门槛。

自检等待 200 tick，读回等待最多 3 秒，连续推迟 8 次后报告失败。换包重置自检状态。
报告写入 `dxrmc/rt_selftest.json`，前次运行保存为 `rt_selftest.prev.json`。
`tier` 使用最近实际派发档位；未派发时为 `none`。`backend` 是配置序号（AUTO=0、DXR=1、WG=2）。
报告保留毫秒时间戳 `time`、`pass`、`step`、`detail`、`scenes`、`contract`、`blas`、`sbt`、`frame`，
几何和加速结构分别包含世界实例与自检实例的摘要。自检没有 compute 软遍历基线。

### 9.8 四条硬约束（缓冲读取与管线释放）

- **raw SRV 的元素粒度是 4 字节**：native 按 `NumElements = size/4` 建视图，缓冲长度不是 4 的倍数时
  尾部读成 0。索引按 u16 计，奇数个索引只有 2 字节余量，会把一条边读成 0 得到退化三角形。
  `D3D12RtTrace.upload` 与 `D3D12RtGeometryPool.mirrorNear` 都把长度补齐到 4 字节整数倍。
- **`ByteAddressBuffer.Load` 的字节偏移必须 4 字节对齐**：u16 索引在奇数位置（第 2、6 字节）直接
  `Load` 得到 0。`RtIndexAt` 统一先读对齐的 dword 再取高低半字（`byteOffset & ~3` + 半字选择），
  4 字节索引才直接读。
- **纹理用法位要覆盖 blit 的两端**：blit 的目标要 `USAGE_RENDER_ATTACHMENT`，而它收尾会把源切回
  `RENDER_TARGET`，所以 `rt-hdr` 必须同时带 `USAGE_STORAGE or USAGE_RENDER_ATTACHMENT`。
- **compute 管线只能按资源释放**：`D3D12CreateComputePipeline*` 在原生侧返回 `Dx12Object*`
  （`kind = ComputePipeline`），只有 `D3D12CreateGraphicsPipeline` 与 `D3D12CreateMeshPipeline*`
  才返回 `Dx12Pipeline*`。拿 `D3D12DestroyPipeline` 释放 compute 管线等于按错的类型 delete：
  `Dx12Pipeline` 的 `ComPtr` 与 `std::unordered_map` 会去析构 `Dx12Object` 的字段，读到野指针就是
  `EXCEPTION_ACCESS_VIOLATION`（关掉光追时崩在渲染线程，栈顶是 `D3D12RtFrame.release`）。
  释放一律走 `MCDR_ABI.ID3D12DestroyResource`（延迟销毁，命令列表可能还引用着它）；
  `_port/rt_pipeline_release_check.py` 拦这条。

警告：

- 天空走光影包的解析大气（`rt_dxr_lib.hlsl` 的 miss 分支调 `WgaEnvironmentRadiance`），不是原版那张贴图，
  所以天顶/地平线的颜色与原版不完全一致；主光线在镜像范围外没命中任何三角形时合光仍放过原版像素。
- 半透明、实体、粒子还没进 G-Buffer：它们既不写反照率也不遮挡，站在它们后面看到的是追踪出的地形/天空。
- 太阳方向读取同帧 `levelRenderState.skyRenderState.sunAngle`（弧度），按原版天空的
  Y 轴 −90°、X 轴太阳角旋转得到 `(-sin(angle), cos(angle), 0)`。保留地平线以下的方向，
  阴影方向与长度随世界实际太阳位置变化；太阳辐亮度仍为 `SUN_COLOR × SUN_STRENGTH` 常量。
- 辐亮度缓冲按窗口高度自适应派发（854×480 下 1/4、2560×1334 下约 1/9），边缘有块状放大；阴影越远越糊，
  这是 S2 的临时规模，S8 换自适应采样。
- 只扫相机 96 方块内的段：更远的地形既不投影也不遮挡，`TRACE_RADIUS` 之外淡回原版像素。
- 光追链上的任何异常都不许冒泡到帧图：`D3D12RtFrame.reportFailure` 只记前四次日志，`recordGBufferPass`、调试视图 mixin、池的 `publish` 都包了 try/catch——出图是开发档功能，崩掉整帧比丢掉一张图糟得多


**自检结果的读法**（三条线分开看，别混成"坏了"）：

| 现象 | 含义 | 处置 |
| --- | --- | --- |
| `probe ... -> fail` 且四个数全 0 | 常量缓冲没到着色器 | 先确认 `run/dxrmc/MCDR.dll` 不比源码旧（native 改了要 `MSBuild MCDR.vcxproj` + `gradlew copyNative`）；再确认 `wga_bindings.hlsli` 是包里唯一一份 `register(b0)` cbuffer |
| `probe -> pass`，`empty`/`triangle` 某条 fail | 光线语义或缓冲区布局不一致 | 看同一行的 `detail` 取证行（`gpuZero`/`cpuZero` 哪个 true） |
| `deferred` | 队列还没空闲，下个 tick 重试 | 不用管，等 200 tick 之后的行 |

## 10. 着色器源码与光影包

着色器**不在 Kotlin 里**，也不在 mod 仓库里：S2 的 B-DXR 后端整套源码在光影包
`Illusion-HPT-Shaders\B-DXR\shaders\`（与 `WorkGraph-Advanced\` 平级，后者是 Work Graph 后端的目标形态）。
一个包能不能被选中、选中之后怎么生效，见 §11。

| 文件 | 入口 | 作用 |
| --- | --- | --- |
| `rt_dxr_lib.hlsl` | `RtRayGen` | 主光线、漫反射与 GGX 镜面弹射，共五张 raw 缓冲（SRV t0..t5 / UAV u0..u4） |
| `rt_integrate.hlsli` | `RtIntegrate` | DXR 主光线、直接光与一次间接弹射的积分实现 |
| `rt_resolve.hlsl` | `RtResolve` | diffuse/specular 重调制、自发光、曝光、显示变换与半径淡出（SRV t0..t11 / UAV u0） |
| `rt_exposure.hlsl` | `RtExposure` | 线性 HDR 表面测光与 1×1 曝光倍率更新（SRV t0..t6 / UAV u0） |
| `rt_debug.hlsl` | `RtDebug` | 单通道调试图：0..3 = x/y/z/w，4 = 法线档 |
| `rt_motion.hlsl` | `RtMotion` | 运动矢量：线性深度重建世界点后按上一帧矩阵重投影（SRV t0 / UAV u0，见 §19） |
| `rt_gbuffer_ps.hlsl` | `gbuf_main` | G-Buffer 多目标输出模板；不能单独编译，前缀由 spvc 输出拼上 |

两份公共头由光影包与 mod 共用，**不要在本仓库再抄一份**：

- `Illusion-shaders-extension\shaders\include\rt_light_contract.hlsli` —— 采样与终止的契约；
  JVM 镜像是 `D3D12RtLightContract.kt`，Rust 镜像是 `src/rt_light_contract.rs`，场景自检按 1e-5 对拍，
  三者必须同步改（`python _port\contract_check.py` 会比对常量与函数覆盖）。
- `Illusion-HPT-Shaders\WorkGraph-Advanced\shaders\include\wga_common.hlsli` —— 颜色编码与数学
  （`WgaLinearToSrgb`、`WgaTonemapAces`、噪声）；合光的 gamma 编码就走它，不要另写 `pow(1/2.2)`。

三件事按顺序发生：

1. **构建期**：`Illusion\build.gradle.kts` 的 `stageShaders` 遍历 `..\Illusion-HPT-Shaders\<包>\shaders`，
   把每个包的 `*.hlsl` / `*.hlsli` / `pack.json` 拷进
   `build/generated/shaders/assets/dxr_mc/shaders/<包>/shaders/`，两处 `include\*.hlsli` 摊平进该包的
   `include/`，最后写一份 `packs.txt`（内置包清单，B-DXR 排最前），那个根被挂成资源 srcDir。
   顶层没有 `.hlsl` 的目录不算包（`WorkGraph-Advanced` 只有公共头，GLSL 参考包也不拷）。
   光影包缺失时只 warn、不失败——独立目录缺一半时 JVM 半边必须还能构建。
2. **运行期读取**：`D3D12RtShaderSources` 按 `<包>/shaders/rt_dxr_lib.hlsl` 读源码，查找顺序是
   `<工作目录>/dxrmc/shaders/<相对路径>`（**改 HLSL 不必重编 Kotlin**）→
   `<工作目录>/dxrmc/shaderpacks/<相对路径>`（整个用户包）→ classpath 里的内置包。
   `D3D12CompileShaderDxc` 没有 include 处理器，`#include` 在这里展开：先找包含方同目录，再找 `include/`。
3. **编译**：`D3D12CompileShaderDxc(source, entry, target)`。入口名与 SRV/UAV 槽数留在 Kotlin
   （`D3D12RtTraceShader.ENTRY` / `SRV_COUNT` / `UAV_COUNT`），必须与 HLSL 绑定顺序一致，
   少给一个 SRV 会让后面的缓冲整体错位。

改 HLSL 的流程：改光影包里的文件 → `python _port\hlsl_check.py`（dxc 取自 Windows SDK，
`-I` 同时指向两处 include，参数与运行期一致；`--keep` 留产物）→ 进游戏看日志。

G-Buffer 的三小块 HLSL 也是包里的片段文件（要塞进生成式着色器，不能单独编译）：`rt_gbuffer_varying.hlsl`（MS/PS 共用的世界坐标 varying）、`rt_gbuffer_ms_patch.hlsl`（meshlet MS 里的调用点补丁）、`rt_gbuffer_albedo_sample.hlsl` / `_constant.hlsl` / `rt_gbuffer_tint.hlsl`（反照率三种写法）。`D3D12RtShaderSources.loadSnippet` 读它们时会丢掉纯注释行与空行、保留缩进，所以片段里可以写注释说明，不必担心注释被插进 struct 内部。

静态检查一次跑完：`python _port/rt_check_all.py`（从仓库根跑；加 `--gradle` 连 Kotlin/Java 编译一起）。它按固定顺序跑下面各条、逐条打 `PASS`/`FAIL`，任何一条失败就以非零码退出——脚本把工作目录钉在仓库根，因为 `docs_refcheck.py` 这类老脚本按进程 cwd 找目标，从 `Illusion\` 里跑会扫 0 个文件并报「三组 0」的假通过。

一次性跑完：`python _port/rt_check_all.py`（`--gradle` 附带编译、`--gpu` 附带引擎级探针）。十六条静态检查 + 一次纯 CPU 自检：`python _port/docs_eolcheck.py` 检查 `docs/` 下每份 `.md` 的行尾（必须全 LF）、无 BOM、文件尾有换行——写歪了不影响渲染，所以只有机器会报；`python _port/rt_stats_keys_check.py` 对拍 `rt_stats.json` 的列（Kotlin 写出的键 × 本节的样例）——少一列基线表就缺数据、多一列按文档写的脚本读不到，两边都不报错；`python _port/frame_constants_check.py` 对拍 `WgaFrame` 的字段偏移（`host/wga_constants.h` 权威）与 Kotlin 镜像，并禁止包里出现第二个 `register(b0)` 的 cbuffer；`python _port/hlsl_check.py` 编包里的入口（三个 compute 入口 + `lib_6_8` 库目标 + G-Buffer PS 模板 + MS 补丁片段，片段用最小 harness 验语法）；`python _port/rt_bindings_check.py` 对拍着色器里实际用到的 `register(t/u)` 槽位、Kotlin 的 `SRV_COUNT` / `UAV_COUNT`、派发时数组的长度，以及**数组元素与槽位的对应顺序**（SRV 与 UAV 都是：第 N 个实参必须落在 `register(tN)` / `register(uN)` 上）——多声明一个槽位就是一张没写过的描述符，少声明会让整表后移、全部绑错缓冲，顺序反了则是每个槽位读到别的缓冲，三种都不报错；`python _port/rt_shader_files_check.py` 校验 Kotlin 引用的每个包内路径都存在、包内每处 `#include` 都能在三个 include 目录里解析到、三个 compute 入口的 `[numthreads]` 与 Kotlin 的 `THREADS` 一致、着色器里 `RtDebugMode() == N` 的每个 `N` 都有对应的 `DEBUG_MODE_*` 常量（探针档与自检天空档只在这两处出现，写错一侧只会让自检莫名失败）；`python _port/rt_placeholders_check.py` 对拍 G-Buffer 模板里的 `%X%` 占位符与 Kotlin 的 `replace("%X%", …)`——少一处替换会留下字面量（编译不过或语义错），多一处替换说明占位符被改名了。模板里的占位符只写在代码行，别写进注释：替换会把代码插进注释里；`python _port/rt_pipeline_release_check.py` 保证 compute 管线只走 `ID3D12DestroyResource`（`dxrmc/rt/` 里禁用 `D3D12DestroyPipeline`，见 §9.8），并要求原生每条加速结构构建/压缩之后 8 行内都出现 `accelBarrier(`（见 §13.6），同时禁止 `dxrmc/rt/` 里出现 `as D3D12Gpu*` 裸转型（原生分配失败返回 null，Kotlin 的 `as` 会抛出**没有 message 的 NPE**，日志只剩一句 `rt self-test: null`；要写 `as?` 加一条带名字的日志）；`python _port/rt_sbt_check.py` 保证四件事：SBT 的标识符按**命中组导出名**取、命中组记录的先后与实例的贡献下标一致（见 §16.4）、ray payload 装得进原生的 `MaxPayloadSizeInBytes`、排版常量自洽（`TABLE_BYTES` == 记录数 × 记录大小，三个起点分别是 0 / 64 / 128，`DispatchRays` 实参顺序对齐）——四种写错方式都是编译不报错、运行时才失效；`python _port/docs_log_check.py` 把文档里引用的日志行与源码对拍——本文的验收清单整个建立在日志行上，改了日志文案却不同步文档，读者照着清单去日志里找会发现一行都没有；只查日志标签（`rt trace:` / `as:` / `shaderpack:` / `dred:`），组合行的完整文本另计；`python _port/pack_config_check.py` 把包声明与引擎配置对拍——`configs.json` 里的每个 `key` 都必须在 `D3D12PackConfig.kt` 有读、有写，且 `IllusionConfig.kt` 里还有同名 TOML 键；少任何一边，那一行都会出现在包设置里、写进 `settings.json`，却永远到不了引擎（不报错，只是不生效）；它还会把每个枚举设置里声明的取值逐个对到 `IllusionConfig.kt` 的枚举上——设置页是按名字查的，包里多写一个不存在的取值不会报错，只是那一档点了没用；`pwsh _port/rt_pure_selftest.ps1` 在游戏外跑自检的纯 CPU 那一半（三个场景的缓冲 + CPU 参考），改过缓冲布局或参考实现后用它确认这条链本身不抛异常、命中像素数合理（见 §9.7）；`python _port/docs_table_check.py` 检查 markdown 表格中间没有空白行——空白行会把表格截成两段，下面的行退化成普通段落，在编辑器里看不出来（本轮 `rt_plan.md` 就是这样坏了 587 处）；
`python _port/rt_check_all.py --gpu` 另外跑 `_port/abi_engine_probe.exe`：它加载 `Illusion\run\dxrmc\MCDR.dll`，用引擎自己的 ABI 建一颗三角形的 BLAS/TLAS，分别走 `DispatchRays` 与 compute + 内联 `RayQuery` 两档，要求两档都打印 `PASS`、`aux:` 命中数一致，且 `planes:` 里 `lit=` 与 `dark=` **都不为零**——场景是「底面 + 挡板」两块 quad，挡板挡住的那部分是影子射线真的被拦下来的证据；全亮等于没有遮挡，全暗说明常量或法线不对。这是唯一能自动发现「D3D12 调用合法但引擎接线错」的检查，默认不开（需要 GPU 与已构建的 DLL）。
`--gpu` 运行 `_port/rt_scene_compare.py`，使用 `_port/SelfTestPureProbe.java` 从自检代码导出的固定场景缓冲与 CPU 参考，分别验证各档的解析命中、遮挡和光照。固定解析场景的容差只覆盖该输入，不扩展成实际场景的整图匹配要求。
同一个 `--gpu` 总门还运行 `_port/rt_guide_failure_check.py`：它验证本帧 motion/pack/radiance 生成失败时不消费缓存、不提交 vendor，恢复帧重置历史；vendor 求值在该探针中截获，不能用作画质通过证据。
`_port/rt_tier_compare.py` 保留为单独运行的差异诊断工具，默认 GPU 总门不执行三档两两逐像素匹配。
`python _port/abi_call_check.py` 检查 FFM 调用点：`invokeExact` 的第一个实参不能是 `MCDRLayout.*`，每个 `MH_*` 都必须在 `bindAll` 中绑定。

常量缓冲就是 Work Graph 后端那份 `WgaFrame`（`wga_bindings.hlsli`，`cbuffer` 在 b0、416 B），
宿主侧镜像在 `D3D12RtFrameConstants.kt`，与 `WorkGraph-Advanced\host\wga_constants.h` 的
`FrameConstants` 逐字节一致——两个后端共用一套常量布局，回归矩阵只加一列。

B-DXR 占用的字段：`g_viewProj`（采样射线方向、运动矢量与 viewZ）、`g_cameraPos`（xyz 相机、w 帧序号）、
`g_screen`（这一次派发的像素尺寸，即降采样后的辐亮度尺寸）、`g_sunDir`（w 太阳角半径）、
`g_sunRadiance`（rgb 辐亮度、w 直射强度）、`g_misc`（x 反射最大粗糙度、
y 调试模式、z 追踪半径、w 记录数）。`g_moonRadiance` 只在自检环境里装常量天空。

调试模式（`g_misc.y`）：`0..3` 取该通道铺灰、`4` 法线档、`8` 自检探针、`9` 自检环境
（天空换成常量 `g_moonRadiance.rgb`，CPU 参考才对得上）。

天空与太阳不再自己写：追踪的 miss 分支调 `wga_shading.hlsli` 的 `WgaEnvironmentRadiance(...)`，
合光走 `WgaTonemapAces` + `WgaLinearToSrgb`——与 Work Graph 后端同一条 HDR 出口。

### 10.1 常量缓冲是 b0 的 CBV，不是 root constants

compute 根签名固定四个参数：`[0]` SRV 表（`t0..`）、`[1]` UAV 表（`u0..`）、`[2]` **`b0` 的 CBV**、`[3]` `b1` 的 root constants（8 dword）。`WgaFrame` 是 416 B，超过 root constants 上限，所以必须走 CBV：

- 常量缓冲建在上传堆（`GpuBuffer.USAGE_MAP_WRITE`），一帧一份，按 512 B 分槽轮转三份；CBV 偏移必须 256 字节对齐，416 B 的内容塞进 512 B 的槽。
- 每帧 `Map` 后直接写 416 B，不经过 staging + `D3D12CopyBuffer`（上传堆本来就 CPU 可写）。
- 同一帧的追踪、合光、调试三次派发共用同一份常量与同一个偏移。
- `D3D12DispatchComputeEx(ctx, pipeline, srvs, uavs, constants, cbv, cbvOffset, x, y, z)`：`cbv = 0` 表示不绑（着色器读不到常量时会拿到全 0）。

着色器里只允许有一份 `cbuffer ... : register(b0)`（`wga_bindings.hlsli` 的 `WgaFrame`）。再声明一个 `b0` 的局部 cbuffer 会与它抢同一个寄存器槽，`g_exposure.x` 之类会读到别的字段，合光整帧乘 0 就是黑屏。

同一条纪律适用于 SRV/UAV 数量：`SRV_COUNT` / `UAV_COUNT` 必须等于着色器用到的最大槽位加一。`_port/rt_bindings_check.py` 把着色器槽位、这两个常量、派发数组三处一起校验。另外 `D3D12RtFrame.kt` 里 `traceSrvs` 的长度必须按**管线**选（`asSrvs = dxrTier || useAccelPipeline`），按 `accel` 选会在「加速结构建出来但管线没建起来」时多绑一个槽。


## 11. 光影包：目录形状、选中规则与生效时机

一个「光影包」就是 `..\Illusion-HPT-Shaders\<包名>\shaders\` 这样一个目录。包名来自目录名，也是
`[shaderpack] active` 里存的值，也是着色器加载键的第一段（`<包>/shaders/xxx.hlsl`）。

| 脚本 | 检查什么 | 违反后的症状 |
| --- | --- | --- |
| `_port/rt_gbuffer_clear_check.py` | G-Buffer pass 每帧都清全部附件（`clearFlags` 全 1）、`clearColors` 的分量数等于 `ATTACHMENTS * 4` | 清屏退化成「不清」或「少清一张」时 debug 视图与采样读到上一帧残留，画面只是偶尔旧一下、不报错 |
| `_port/rt_gbuffer_variant_check.py` | `D3D12RtGBufferShader.build()` 的失败出口全部带原因、调用点两行日志齐全、主 pass 转换链上的回旧链守卫在 | 变体静默建不出来时主 pass 照常画、光追整条链消失（没有 `rt frame: dispatch`），日志里一个字都不提 |
| `_port/rt_resolve_pad_check.py` | 调试图档位落在 `g_resolvePad.y`（Kotlin 常量数组第 3 项 `0`、第 4 项 `debugMode`） | 切到任何调试图都没有反应，画面停在合成结果上（`--selftest` 自带负例） |
| `_port/rt_upload_check.py` | `dxrmc/rt` 里出现「只给大小」的 `device.createBuffer` 重载（usage 0 + `…toLong()`）就 FAIL | 那个重载只分配、不上传，着色器读到未初始化显存；自检于是拿垃圾数据比对，日志里看不出原因 |
| `_port/rt_selftest_report_check.py` | `writeSelfTestReport` 写出的键集必须都在文档里写着；报告里注入的摘要（`contract` / `blas` / `sbt` / `frame`）与 `detail` 必须包 `jsonSafe()`（`--selftest` 自带负例自证） | 少了键 ⇒ 文件里有字段没人知道含义；少转义 ⇒ 一个引号就把这一行写成坏 JSON |
| `_port/rt_lock_check.py` | `D3D12RtTrace` 的两条管线、`D3D12RtDxr` 的状态对象、`D3D12RtBlas` 的加速结构、`D3D12RtFrame` 的释放与合光/调试入口，创建/释放/派发读句柄都在锁下 | 自检跑在客户端 tick、派发在渲染线程：不进锁会重复建造（泄漏）或在派发途中释放句柄（设备移除） |
| `_port/rt_material_layout_check.py` | `D3D12RtMaterialTable.ENTRY_BYTES`、`D3D12RtTrace.MATERIAL_BYTES`、`rt_shade.hlsli` 的 `RT_MATERIAL_BYTES` 必须是同一个数（`--selftest` 自带负例自证） | 只改一处 ⇒ 光追按错的步长读材质，albedo 与粗糙度整体错位，编译与日志都不会报 |
| `_port/rt_guide_layout_check.py` | 引导数据表（§19.7）与 `D3D12RtGuide.SLOTS` 的槽位名、格式、来源、顺序逐项一致，且 `specular` 占位值的说明还在（`--selftest` 自带负例自证） | 槽位换了格式没换、顺序换了 ⇒ 降噪与超分绑到别的缓冲，编译和日志都不报 |
| `_port/ngx_rr_probe.cpp`（由 `rt_check_all.py --gpu` 的 `ngx rr` 一步调用） | 游戏外问 NGX 本机是否支持 DLSS Ray Reconstruction（`SuperSamplingDenoising.Available`），探针缺失或本机不支持只打印 `SKIP` | 以为 RR 可用、接完才发现只能在别的机器上跑；或者反过来，明明可用却退到备选方案 |
| `_port/rt_nrd_adaptation_check.py` | 14 条源码契约，覆盖深度/命中距离/MV/历史/资源状态/显式偏移等；`--selftest` 包含 25 个内存负例 | 防止单边修改留下拖影、写入丢弃或禁用后仍派发；源码通过不等于 SDK 适配通过 |
| `_port/rt_jitter_contract_check.py` | 38 条共享采样接线规则及 38 个破坏负例；数值对照由 `rt_jitter_check.py` 执行 | 保持投影捕获顺序、consumer 闸门、当前/上一帧载荷、SDK 符号与 MV 去偏移一致 |
| `_port/log_line_check.py` | 日志正文字面量（占位符不算）硬上限 90 字符，且不得含日期、`because` / `so that` / `which means` 从句、`see …` 指路、文字级别前缀、工单号（`--selftest` 自带负例自证） | 日志是玩家唯一能读的东西；长了没人看，带日期与从句的句子在别的机器上还会立刻过时 |
### 11.1 必须有的文件

| 文件 | 作用 |
| --- | --- |
| `rt_dxr_lib.hlsl` | DXR 库（raygeneration / miss / closesthit） |
| `rt_resolve.hlsl` | 合光 compute（入口 `RtResolve`） |
| `rt_exposure.hlsl` | 曝光测光 compute（入口 `RtExposure`） |
| `rt_debug.hlsl` | 调试视图 compute（入口 `RtDebug`） |
| `rt_gbuffer_ps.hlsl` | G-Buffer PS 模板（`gbuf_main`，前缀由 spvc 输出拼上） |
| `rt_material_guide.hlsl` | 材质 guides compute（入口 `RtMaterialGuide`） |
| `rt_nrd_remodulate.hlsl` | NRD 后重调制与深度转换（入口 `RtNrdRemodulate`） |

七个缺一个，这个包在界面上是灰的、应用不了（判据在 `D3D12ShaderPacks.REQUIRED_ENTRIES`）。
其余片段与公共头（`rt_gbuffer_*.hlsl`、`include\*.hlsli`）按需读，缺了只会让对应阶段失败。

### 11.2 `pack.json`

可选，只用来填界面上的说明面板；键都可省，省了只显示包名与「这个包没有写说明」。B-DXR 自带一份：

```json
{
  "shaderDescription": "B-DXR ray-traced visibility for dxr_mc: one diffuse bounce per pixel ..."
}
```

认两组写法：`name` / `shaderName`、`author` / `shaderAuthor`、`version` / `shaderVersion`、
`shaderDescription` / `description`。`shaderDescription` 是 Iris 的键，照抄 Iris 的包能直接读；
不认识的键忽略。

### 11.3 内置包与用户包

- **内置**：`stageShaders` 拷进 JAR 的那些，清单在 `assets/dxr_mc/shaders/packs.txt`（一行一个包名）。
  读不到清单时只认 `B-DXR`。
- **用户**：`<工作目录>/dxrmc/shaderpacks/<包名>/shaders/…`，文件要求与内置包相同。
  同名时**内置优先**，用户包只能补内置没有的名字。
- 「打开文件夹」按钮打开的是用户包目录（`D3D12ShaderPacks.packsDirectory()`），不存在会自动建。

### 11.4 选中与生效

- 选择存在 `[shaderpack] active`；应用时**同时**写 `[rt] backend` 与 `[rt] mode`：两者都取包在
  `configs.json` 里声明的值（见 11.6），包没声明才回落 `DXR` / `FULL`；选「无光影」清空 active 并写
  `AUTO` + `OFF`。包与光追不是两个独立开关，只写包不改这两个键等于什么都没发生。
- 光追出图的判据是 `D3D12Settings.rtEnabled`：已选包、mode 非 OFF、设备支持硬件 DXR。

- 配置里写了不存在或缺入口的包时，`activeName()` 回落到 `B-DXR` 并只警告一次，光追照常跑。
- 生效时机：应用会清掉源码缓存，但**已经编译好的管线不会原地换**。关掉光追再打开
  （`release()` 里连追踪管线一起释放）或重启游戏，才会按新包的源码重编——界面提示就是这么写的。
- 换包会清掉场景自检的结论（`D3D12RtTrace.resetSelfTest()`），下次打开光追重跑。

### 11.5 界面

入口在「视频设置 → 光影包」页（侧边栏最后一页）的「光影包…」按钮。布局照抄 Iris 的包选择页：标题、斜体副标题、包列表、
悬停说明面板、底部两行按钮；已应用的包用黄色（`0xFFFFF263`），不可用的包灰色，「无光影」永远第一行。
逐行规则见 `docs/misc/settingscreen/README.md` §6.10。

光影包单独占一页，不要塞进别的页的固定区：那几行按设计不参与滚动，塞进去会把二级页签与内容区一起压出可视范围。

### 11.6 包自己声明的设置（`configs.json`）

光追那一组开关属于**包**，不属于全局配置：同一个键在不同包里含义可以不同，所以它们只出现在
「视频设置 → 光影包 → 光影包设置」，不放进全局配置页。

- 声明位置：`<包>/shaders/configs.json` 的 `attributes` 数组，键名与 Radiance 的包设置文件一致。
- 单条字段：`name`（稳定 id，同时是 `settings.json` 的键）、`description`（`a.b` 形态当语言键，否则当字面量）、
  `type`（`bool` / `int_range:min-max` / `float_range:min-max` / `enum:a-b-c`；`vec3` 等其他类型先跳过）、
  `default_value`、`group`（分组标题的语言键）、`performance_impact`（`low` / `high`，只用于行尾提示）、
  `key`（绑到 TOML，形如 `raytracing:debugView`）。
- 取值顺序：包的 `settings.json` → 它绑的 TOML 键 → `default_value`。
- 落盘：开关与滑块写 `<user.dir>/dxrmc/shaderpacks/<包>/settings.json`；带 `key` 的**同时**写 TOML 并立刻
  `D3D12Settings.reload()`，不等重启。内置包在 jar 里只读，值一律落在用户目录这一份。
- `[rt] backend` 与 `mode` 是包属性，声明缺失时使用 DXR / FULL；`reconcile()` 在首次 tick 对齐配置。

- 界面：标题、显示包名的副标题、按 `group` 分段的行、底部「完成」；布尔点击切换、枚举点击循环、
  区间项在行内轨道上点或拖取值。逐行规则见 `docs/misc/settingscreen/README.md` §6.11。
- B-DXR 包后端固定 DXR。其余选项按 §1 标明是否生效；没有加速结构开关。

## 12. 加速结构原语（S3 3-a）

3-f 又加了三条与状态对象相关的入口（RTPS / 着色器标识符 / `DispatchRays`）与 `mcdr_rt_hit_group`，见 §15。

这一节只讲"怎么建 BLAS / TLAS"这件事的 ABI 面。谁在什么时机建、refit 还是重建、计数器怎么记，
是 §3 的 AS 管理器（`BlasManager`）要决定的事。

### 12.1 七个入口

`MCDR_ABI_VERSION` 从 7 升到 8 时新增，全部是必需导出（旧 DLL 在握手阶段就会被拒）：

| 入口 | 作用 |
| --- | --- |
| `D3D12CreateAccelStructBuffer(size)` | 建一块能直接当 BLAS / TLAS 用的缓冲，失败返回 0 |
| `D3D12GetBlasPrebuildInfo(geometries)` | 查询 `[结果上限字节, scratch 字节]`，失败返回 null |
| `D3D12GetTlasPrebuildInfo(instanceCount)` | 同上，按实例数查 |
| `D3D12BuildBlas(ctx, dest, scratch, geometries, update)` | 在已打开的命令列表上建（`update = false`）或 refit（`update = true`） |
| `D3D12BuildTlas(ctx, dest, scratch, instances, instanceCount)` | 重建 TLAS |
| `D3D12CopyAccelStruct(ctx, dest, src)` | 压缩拷贝（`COMPACT`） |
| `D3D12CreateAccelStructView(accel)` | 给加速结构建 SRV，可直接放进 compute 的 SRV 表 |

`MCDR_ABI_VERSION` 从 9 升到 10 时又加了一条：

| 入口 | 作用 |
| --- | --- |
| `D3D12GetBufferAddress(buffer)` | 缓冲的 GPU 虚拟地址，失败返回 0。实例描述的 `AccelerationStructure` 必须写它（见 §13.3） |

原生实现在 `MCDR\src\core\d3d12_device.cpp`（`createAccelStructBuffer` / `getBlasPrebuildInfo` /
`getTlasPrebuildInfo` / `buildBlas` / `buildTlas` / `copyAccelStruct` / `createAccelStructView`），
C 包装在 `MCDR\src\c_abi\abi_device.cpp`，Kotlin 封装在 `Illusion\src\main\kotlin\io\github\dxegp\d3d12\extern\MCDR_ABI.kt`。

### 12.2 `mcdr_rt_geometry`（64 B）

一条几何就是"顶点缓冲的一段 + 索引缓冲的一段"：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `vertexBuffer` | 缓冲句柄 | 顶点缓冲，必须是 DEFAULT 堆 |
| `vertexStride` | `int64` | 顶点步长，地形是 28 |
| `vertexCount` | `int64` | 顶点数 |
| `vertexOffset` | `int64` | **字节**偏移（`StartVertexLocation * vertexStride`） |
| `indexBuffer` | 缓冲句柄 | 索引缓冲 |
| `indexCount` | `int64` | 索引数 |
| `indexOffset` | `int64` | 索引的**字节**偏移 |
| `flags` | `int32` | 0 = 不透明几何，1 = 需要 alpha 测试（`GEOMETRY_FLAG_NONE`） |
| `indexStride` | `int32` | 索引宽度字节数：2 = `R16_UINT`，4 = `R32_UINT` |

顶点格式固定 `DXGI_FORMAT_R32G32B32_FLOAT`。一条 BLAS 最多 4 条几何（`MCDR_ABI.MAX_ACCEL_GEOMETRIES`）。

S3 的 `D3D12RtBlas` 是**一 (段, 层) 一条 BLAS、每条只放一条几何**：记录里那 8 位是层标志位
（`SOLID = 1`、`CUTOUT = 2`、`TRANSLUCENT = 4`），`SOLID` 标 `GEOMETRY_FLAG_OPAQUE`，
后两层标 `GEOMETRY_FLAG_NONE`（驱动据此允许候选三角形，配合 §14.2 的 `CommitNonOpaqueTriangleHit`）。
把 `SOLID` 也标成 `NONE` 会让每次命中都走候选路径——结果一样，但白丢驱动的"不透明三角形"快路。

### 12.3 缓冲与对齐

- AS 缓冲走 `D3D12CreateAccelStructBuffer`，不要在别处拿普通缓冲凑：它必须在 **DEFAULT 堆**、
  带 `ALLOW_UNORDERED_ACCESS`、初始状态就是 `D3D12_RESOURCE_STATE_RAYTRACING_ACCELERATION_STRUCTURE`，
  而且**不进缓冲池**（池里的资源会被别的用途借走，状态也对不上）。
- 分配宽度按 256 B 对齐。scratch 和结果数据同样要求 256 B 对齐，预构建信息里的两个数字都是"字节"。
- **scratch 不能用加速结构缓冲**：`D3D12CreateAccelStructBuffer` 建出来的资源初始状态是
  `RAYTRACING_ACCELERATION_STRUCTURE`，而 scratch 必须是 `UNORDERED_ACCESS`。状态不对时驱动
  不会在调用点报错，而是整帧挂住（约 8 秒后 TDR，随后 `DXGI_ERROR_DEVICE_REMOVED / 0x887A0005`，
  游戏崩在下一个 `Map`）。scratch 用普通 UAV 缓冲（初始 `COMMON`，首次写入隐式提升），见
  `D3D12RtBlas.ensureScratch` / `ensureTlas`。
- 顶点与索引范围由原生侧校验（`vertexOffset + vertexCount * vertexStride`、
  `indexOffset + indexCount * indexStride` 必须落在缓冲宽度内）。越界读在 GPU 上同样是挂死而不是报错，
  所以这条检查失败时只返回 0 并写一条 `buildBlas: vertex range … exceeds the vertex buffer (… bytes)`。
- TLAS 的实例缓冲是普通的 `ALLOW_UNORDERED_ACCESS` 缓冲（上传或 GPU 写都行），每条实例 64 B、
  16 B 对齐；`InstanceID` 低 16 位放段索引、高 16 位放几何子集，主光线用 `0x01 + 0x02 + 0x04`。

### 12.4 建与 refit

- 首次构建：`PREFER_FAST_BUILD + ALLOW_UPDATE`，`SourceAccelerationStructureData` 写 0。
- refit：`PERFORM_UPDATE`，`SourceAccelerationStructureData` 写**自己**的 GPU 地址（B-DXR 目前不用，见 §13.1）；拓扑（索引数、
  几何数）变了不能 refit，只能重建。
- TLAS 每帧重建，用 `PREFER_FAST_BUILD`，不加 `ALLOW_UPDATE`。
- 压缩：建完立刻 `D3D12CopyAccelStruct(dest, src)`。紧凑版要等这次提交执行完才能当 SRV 用，
  所以**下一帧**再切过去，这一帧继续用未压缩的那块。

### 12.5 失败与日志

七个入口失败时返回 0 / null，原因走 `mcdrLastError()`（最常见的是"没有打开的命令列表"）。
原生侧每建一次会写一行日志：`as: accel struct buffer size=… aligned=… bytes`、
`as: blas prebuild result=… scratch=… geometries=…`、`as: accel struct view slot=… address=…`。
行首的 `as:` 是子系统标记（acceleration structure），不是级别；级别由结构化前缀给出。

加速结构的建/refit 细节日志（`as: accel struct buffer`、`as: blas prebuild`、`as: tlas prebuild`）每条几何每帧都会触发，默认降在 DEBUG：要看就把 `[logging] nativeVerbose` 打开（或 `logLevel = DEBUG`），否则整屏都是它们。INFO 级别的 `as:` 只有一行汇总：`as: inst=… blas=… b=… r=… c=… ms=…`。

### 12.6 与 compute 派发的关系

`D3D12CreateAccelStructView` 建出来的视图是 `Dx12Object::Kind::AccelStructView`：它自己不持有资源，
只占一个描述符槽。派发时它被当作 SRV 传进去，但**不做状态转换** —— 把加速结构转成
`NON_PIXEL_SHADER_RESOURCE` 会让下一次 Build 直接报状态不符。释放它用 `ID3D12DestroyResource`
（和加速结构缓冲一样，都是 `Dx12Object`）。

## 13. 加速结构管理（S3 3-b / 3-c）

`D3D12RtBlas` 是唯一的加速结构持有者：一条镜像段记录一条 BLAS，整帧一条 TLAS。它在
`D3D12RtFrame.dispatch()` 里、`writeScene` 之后、compute 派发之前调用，所以建与 refit 的命令
和 tracing 派发录在**同一张**命令列表上。

### 13.1 BLAS 的归属与键

- 键是 `(sectionNode << 4) | layer`，值里记 `geometryVersion`、顶点数、索引数、索引宽度。
- 一项是"新"的时候：`D3D12GetBlasPrebuildInfo` 拿结果尺寸与 scratch 尺寸，建两块 AS 缓冲
  （结果块 + 紧凑块），然后首建。
- 版本、顶点数、索引数、索引宽度**全都没变** ⇒ 这条 BLAS 仍然有效，直接复用（不录任何命令，
  只把它的地址交给 TLAS）。稳态下这是绝大多数段走的路。
- 镜像输入缓冲换地址不使已构建的 BLAS 失效。BLAS 保存构建结果；输入只需活到构建提交完成。
- 版本变化先把该条标记为未构建，只有 `D3D12BuildBlas` 成功后才登记新版本与拓扑。
  构建预算耗尽或失败时保留待处理状态；结果容量按新 prebuild 需求扩容，旧缓冲延迟回收。
- 位置、索引、数量或索引宽度变化 ⇒ 整条重建。颜色、UV 或 quad 材质映射变化更新着色内容，
  保留兼容的 `geometryVersion` 和 BLAS；`contentVersion` 不作为 AS 键。
  当前未分开记录位置移动与拓扑变化，尚未使用 refit（`PERFORM_UPDATE`），`REFIT_LIMIT` 与 `blas_refits` 恒为 0。
- scratch 只需一块：同一条命令列表里的多次 Build 顺序执行，不开第二条列表就不用给每条几何切分区。
- BLAS 按段常驻，所以要回收：每项记最后一次出现的帧号，`EVICT_FRAMES = 180` 帧没再出现就销毁
  （每 60 帧扫一次）。不回收的话走动起来条目只增不减，显存跟着世界大小涨。

几何经过 `setSectionMesh` 安装前的生产者/内容令牌校验，并在原版确实安装后按整段发布。
上传 HEAD 只暂存 CPU 字节，同一 mesh/layer 的重试不重复收集；整段替换同时删除不再存在的层。
当前空网格解除等待目标，reset 移除该段的注册与资格，取消任务释放暂存数据。
每次提交的非空层取得新的原版上传版本；RT 池按 §9.2 比较内容，分别保留或更新几何与着色身份。
同大小的位置和索引编辑仍触发 BLAS 重建；同大小的颜色和 UV 编辑仍上传当前属性。

镜像按当前选择的记录复用 CPU 快照；远处记录变化而近场选择不变时复用既有 GPU 镜像与历史。
近场实际变化立即重建，不等待帧数节流；新选择为空时立即撤销旧镜像与 AS。
CPU 池的 `materialOffset` 单位是字节，复制时直接使用；GPU 记录转换为 quad 索引后由 shader 乘 2，不能重复转换。

### 13.2 压缩晚一帧

建或 refit 之后 `compactValid = false`；下一帧开头先做 `D3D12CopyAccelStruct(compact, result)`，
成功后才把 `compactValid` 置真。给 TLAS 的地址是 `address()`：压缩过用紧凑块，否则用结果块。

**当前不分配紧凑块**（`val compact = 0L`）：切到紧凑块之后一条射线都打不到三角形——
`COVERAGE` 正常（G-Buffer 有覆盖）、`HIT_DISTANCE` 与 `SHADOW` 全黑，关掉压缩立刻恢复。
拷贝命令本身没错（`CopyRaytracingAccelerationStructure` 的目标、源顺序与屏障都对），
所以是紧凑副本的内容或地址在 GPU 侧不可遍历；查清之前一律交原始 BLAS，代价是显存不省。

### 13.3 实例表与 TLAS

- 实例表是**上传堆**缓冲（`GpuBuffer.USAGE_MAP_WRITE`），JVM 每帧 `map` 填写。
  native `buildTlas` 在录制时复制有效实例到 encoder 持有的独立上传切片，GPU 读取该快照；
  禁止直接让 GPU 读取下一帧会覆写的 CPU 表。切片按所属列表的实际 fence 完成值复用。
  加速结构本体与 scratch 仍然必须留在默认堆。
- 每条实例 64 B：`Transform` 只写平移（`row0.w = originX`、`row1.w = originY`、`row2.w = originZ`），
  因为镜像顶点是**段内局部坐标**；`InstanceID = 记录下标`（着色器命中后拿它在 `gRecords` 里取同一份
  材质与顶点，见 §14.2）、`InstanceMask = 0x01`（静态不透明）、
  `InstanceContributionToHitGroupIndex` 按层写 0 / 1（`HITOPAQUE` / `HITCUTOUT`，见 §16.3）、
  末尾 8 字节写 BLAS 的 **GPU 虚拟地址**（`D3D12GetBufferAddress`，见 §12.1）。
- `Flags` 固定写 `0x01`（`D3D12_RAYTRACING_INSTANCE_FLAG_TRIANGLE_CULL_DISABLE`，`INSTANCE_FLAGS`）。
  镜像三角形的绕序不保证与原版面一致，开着背面剔除时射线会被整片剔掉：`COVERAGE` 正常、
  `HIT_DISTANCE` 与 `SHADOW` 全黑，自检照样全绿的——这个坑只能靠进世界看调试档发现。
  体素几何都是闭合块，关掉剔除只是多走几个背面，代价可忽略。
- 这一格不是对象句柄：句柄是 CPU 侧堆指针，写进去等于让每个实例指向不存在的显存。写错时建 TLAS
  不报错，只是所有光线都命中不到东西；原生侧的 `buildTlas` 只做搬运，不做句柄到地址的翻译。
- 完整 section 的真实实例与未知代理使用 `0x01`；不完整 section 中认证有效的不透明层使用 `0x80`，
  仅供阴影查询取得可靠遮挡证明，见 §16.3。`0x02`（cutout）/ `0x04`（动态）等对象分类位尚未接入，
  不得将阴影证明位当作对象类别或主可见性发布资格。
- 实例池容量翻倍增长（起步 64 条），TLAS 结果块或 scratch 不够大时重建两块并**同时重建视图** ——
  视图指向旧的 TLAS 缓冲，换块后旧视图就是悬空地址。

RT 的 UPLOAD 堆 b0 常量同样在 `dispatchComputeEx` / `dispatchRays` 绑定时快照。
生产追踪使用 native 每次录制创建的不可变常量；JVM 三槽缓冲只用于调试副本。
录制后的 dispatch 不再直接读取会被后续 map 覆写的槽位。
native 页面与 allocator/list 同槽，等待实际 fence 后才复用；这不替代完整帧所有权与几何版本发布。

### 13.4 计数与日志

九个数字进 `rt_stats.json`：`blas_builds` / `blas_refits` / `blas_compacts`（窗口内累计）、
`blas_ms`（每帧均值的 CPU 录制耗时）与 `blas_build_us`（单条 BLAS 录制的峰值微秒）、
`blas_vram_mb` / `blas_scratch_mb`（峰值）、`tlas_instances`、`tlas_ms`（TLAS 那一段的每帧均值）。

`blas_ms` 与 `tlas_ms` 必须分开记：两者都是 CPU 侧录制时间，混在一个数里就没法按段对账。
§3.6 的成本预算（每帧 ≤ 0.15 ms、每段 ≤ 25 µs）说的是 GPU 侧，这里给的是 CPU 录制侧的下限估计——
GPU 侧要等时间戳槽扩到加速结构（`SLOT_*` 目前只覆盖 G-Buffer / 追踪 / 合光 / 上屏）才有。

第一次成功建完写一行 `as: inst=… blas=… b=… r=… c=… ms=…`（实例数 / 条目数 / 重建 / refit / 压缩 / 录制毫秒）；
失败只报一次 `as: …`，避免每帧刷屏。释放顺序：`D3D12RtFrame.release()` 先释放加速结构
（它们引用镜像缓冲的地址），再关镜像缓冲。

### 13.5 几何契约检查

`D3D12RtAccel` 在**第一次** `prepare` 时扫一遍全部镜像记录，逐条检查字段自洽性——这是 G3 的第一条
验收（几何契约完整性）。只看一次：记录内容的变化由 `geometryVersion` 兜住，逐帧重扫只会白烧 CPU。

查的是这六项（任一非零说明记录与 BLAS 的读法对不上，命中结果会是"看起来没问题"的垃圾）：

| 项 | 判据 | 后果 |
| --- | --- | --- |
| `empty` | `vertexCount <= 0` 或 `indexCount <= 0` | 这段会被跳过，实例数少于池里的段数 |
| `indices` | `indexCount % 3 != 0` | 最后一个三角形读到缓冲外 |
| `stride` | `indexStride` 不是 2 或 4 | 驱动按 16 位读 32 位索引（或反之），三角形全错 |
| `version` | `geometryVersion <= 0` | 变化检测失效，BLAS 永远不会重建 |
| `unaligned` | `vertexOffset` / `indexOffset` 不是 4 的倍数 | 半字读取跨 dword，索引值被拆错 |
| `outOfRange` | 偏移 + 计数 × 步长 超过对应缓冲字节数 | 读到别的缓冲的内容 |

日志一行 `rt geometry: contract records=… empty=0 indices=0 stride=0 version=0 unaligned=0 outOfRange=0`（每个进程首次镜像时执行）；
有任何一项非零会追加一条 `as: geometry contract: N record(s) violate the mirror layout`。缓冲区大小
由 `D3D12RtFrame.Mirrors` 在建立镜像时带进来，自检里的独立实例不传大小（跳过越界两项，其余照查）。

### 13.6 几何描述与缓冲状态（建 AS 的两个坑）

**`Triangles.VertexFormat` 必须是真格式**，写 `DXGI_FORMAT_UNKNOWN` 会让整条 build 命令非法：
`ID3D12GraphicsCommandList::Close` 返回 `E_INVALIDARG`，列表进错误态，后续 `Map` / 建缓冲全部失败，
表现是「进世界几秒后设备移除」。段顶点是 28 字节步长、位置在前 12 字节，所以格式与步长**两个都要写**：
`VertexFormat = DXGI_FORMAT_R32G32B32_FLOAT` + `VertexBuffer.StrideInBytes = 28`。

**scratch 不能是加速结构缓冲**：必须 DEFAULT 堆 + `D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS`，
初始 `COMMON`，用前显式转到 `UNORDERED_ACCESS`。用 `mcdr_dx12CreateAccelStructBuffer` 建 scratch 会
同时缺 UAV 标志与正确的初始状态。共享同一块 scratch 的多条 build 之间要插 UAV 屏障
（`D3D12RtAccel.prepare` 的 `builtThisFrame > 0` 分支）。

**建完必须插 UAV 屏障**：`BuildRaytracingAccelerationStructure` 与
`CopyRaytracingAccelerationStructure` 之后都要用 `D3D12_RESOURCE_BARRIER_TYPE_UAV` 收口目标资源
（原生侧 `buildBlas` / `buildTlas` / `copyAccelStruct` 已经内置）。同一帧的 `DispatchRays` 会把 TLAS
当 SRV 读，缺这条屏障等于读一份还没写完的加速结构——不报错，只是可能一个实例都命中不了。

这两条都用独立探针（`_port\as_probe.cpp`，不依赖游戏与 `MCDR.dll`）验证过：只建 BLAS + TLAS、
打调试层错误并等 fence，正确写法退出码 0，写 UNKNOWN 时复现 `Close` 的 `E_INVALIDARG`。

所有生产追踪都构建 BLAS/TLAS。未完成当前几何构建时跳过追踪和合光，禁止使用旧 TLAS 配新记录。

## 14. 硬件求交与命中解码

### 14.1 追踪入口

生产路径只使用 `rt_dxr_lib.hlsl` 的 `TraceRay`。内联 RayQuery 可用于独立几何探针，不提供整帧生产后端。

### 14.2 命中回读

`InstanceID` 对应镜像记录下标，`PrimitiveIndex` 对应局部三角形。`RtHitFromPrimitive` 读取同帧记录、
顶点、索引与逐 quad 材质，构造命中距离、法线与材质编号。

### 14.3 绑定与槽数

SRV 固定为 t0..t5：records、vertices、indices、materials、quads、TLAS。
UAV 固定为 u0..u4：direct、indirect、aux、specular、specularGrazing。b0 为 WgaFrame。

### 14.4 失败处理

状态对象、SBT、当前 TLAS 或帧常量不可用时跳过当前帧光追。失败不触发软遍历。

### 14.5 自检

场景检查直接对照 CPU 参考，见 §9.7；不编译 compute 追踪变体。

## 15. 光线追踪状态对象与 SBT（S3 3-f：ABI 层）

3-d 的 inline `RayQuery` 与这里的 `DispatchRays` 吃的是**同一份命中与解调代码**，区别只在入口：
前者是一个 compute 着色器，后者是一条 `D3D12_STATE_OBJECT_TYPE_RAYTRACING_PIPELINE`。本节只写
已经落进 ABI 的那一层；着色器库与逐帧派发接线见后续小节。

### 15.1 三个入口

| ABI | 作用 | 返回值 |
| --- | --- | --- |
| `mcdr_dx12CreateRaytracingPipeline(lib, size, raygen, miss, groups, count, srv, uav, recursion)` | 用 `lib_6_x` 编出的 DXIL 库建状态对象 | 句柄；`NULL` = 失败（原因见 `mcdrLastError()`） |
| `mcdr_dx12GetShaderIdentifier(pipeline, export, out32)` | 取某个导出名的 32 B 标识符 | `1` = 已写入 `out32`；`0` = 名字不存在 |
| `mcdr_dx12DispatchRays(ctx, pipeline, table, …, width, height)` | 在已打开的命令列表上派发 | `1` = 已派发；`0` = 参数错；`-2` = 没有打开的列表 |

`MCDR_ABI_VERSION` 随之升到 **9**（结构体与签名只增不改）；JVM 侧同一份数值在
`MCDR_ABI.MCDR_ABI_VERSION`，两边不一致时 `verifyABI` 在建设备之前就抛异常。

### 15.2 全局根签名为什么与 compute 相同

状态对象的 `D3D12_GLOBAL_ROOT_SIGNATURE` 与 compute 管线用**同一套四参数**：
`[0]` SRV 表、`[1]` UAV 表、`[2]` CBV `b0`、`[3]` root constants `b1`（8 个 dword）。
两边的绑定代码是同一段（`bindComputeStyle`），少写一步（描述符堆、CBV 偏移、常量）只会静默绑错，
不会报错。因此改绑定顺序时必须同时改 compute 与 `DispatchRays` 两条路径。

### 15.3 hit group

`mcdr_rt_hit_group`（32 B，四条 UTF-8 指针：`name` / `closestHit` / `anyHit` / `intersection`）。
三角形几何只需要 `closestHit`（`anyHit` 用于镂空判定）；填了 `intersection` 的组会建成
`D3D12_HIT_GROUP_TYPE_PROCEDURAL_PRIMITIVE`，否则是 `D3D12_HIT_GROUP_TYPE_TRIANGLES`。
一次最多 `MCDR_RT_MAX_HIT_GROUPS`（4）个组，与 cppm 的 `kMaxRtHitGroups` 必须一致。
名字只在建状态对象那一次调用里被读，调用返回后 Kotlin 侧不需要保留字符串。

### 15.4 SBT 布局与对齐

- 记录头部固定 32 B（`D3D12_SHADER_IDENTIFIER_SIZE_IN_BYTES`），由 `GetShaderIdentifier` 写入；
  记录本身建议 64 B，给以后放局部根参数留位置。
- 起点（`raygenOffset` / `missOffset` / `hitOffset`）必须是 32 的倍数，步长（`missStride` /
  `hitStride`）也必须是 32 的倍数 —— 不满足时 native 直接拒派发并写一条 ERROR。
- 目前唯一合法的源是上传堆缓冲（`USAGE_MAP_WRITE`）：CPU 每帧写标识符，GPU 直接从它读地址。

### 15.5 失败与日志

- 建状态对象失败时先 `dumpInfoQueueMessages()` 再返回；常见原因是导出名写错或
  `MaxPayloadSizeInBytes` 小于着色器实际写的载荷。
- raygen / miss 的名字在返回前就用 `GetShaderIdentifier` 验一遍，缺了就报
  `createRaytracingPipeline: missing export <name>`，不会拖到派发时才出空标识符。
- 成功时打一行 `createRaytracingPipeline: PSO handle=… lib=… bytes hitGroups=… srv=… uav=… recursion=…`。

### 15.6 当前派发

生产路径由 `D3D12RtNativeFrame` 提交一次 `mcdr_dx12RtFrame`，native 持有状态对象与 SBT，
绑定同帧镜像和 TLAS，顺序执行追踪与材质 guide。`D3D12RtDxr` 负责独立场景自检。
DXR 与 WG 调度的选择独立于降噪选择，当前可用的生产调度为 DXR。

## 16. DXR 路径：着色器库、SBT 与自检对拍（S3 3-f）

### 16.1 共享头 `rt_shade.hlsli`

`rt_shade.hlsli` 提供命中解码、材质读取和光照计算；`rt_integrate.hlsli` 组织主光线、太阳阴影与间接弹射。
`rt_dxr_lib.hlsl` 提供最近表面求交与 raygeneration / miss / closesthit 导出；
`rt_shadow_query.hlsli` 提供太阳遮挡查询，主表面和弹射命中面共用。

### 16.2 着色器库 `rt_dxr_lib.hlsl`

以 `lib_6_8` 编译（`_port/hlsl_check.py` 的 `LIBRARIES` 里登记，编译时不带 `-E`）。

| 导出 | 着色器类型 | 作用 |
| --- | --- | --- |
| `RtRayGen` | `raygeneration` | 主光线、太阳阴影、独立 diffuse/specular 弹射与二次命中阴影；共五张 UAV |
| `RtMiss` | `miss` | 把 `hit`、`t`、重心坐标清零 |
| `RtClosestHit` | `closesthit` | 用 `InstanceID()` / `PrimitiveIndex()` / `RayTCurrent()` 走 `RtHitFromPrimitive` 写 payload |
| `RtInvalidHit` | `closesthit` | 写 `hit=2`，使失效 section 命中的样本直通原版 |

载荷是 `struct [raypayload] DxrPayload`（24 B：`t` / `record` / `primitive` / `hit` / `barycentrics`）。
`[raypayload]` 属性必须写在 `struct` 关键字之后，写在下一行会报
`an attribute list cannot appear here`。24 B 必须小于原生建的
`D3D12_RAYTRACING_SHADER_CONFIG.MaxPayloadSizeInBytes`（32）——`_port/rt_sbt_check.py` 会把
payload 的字段大小与原生那个数字对拍，改着色器的人不必记得它。

主光线、diffuse/specular 次级光线与太阳遮挡均用 inline `RayQuery`。
最近表面保留最短可靠交点，instance mask 为 `0x01`；阴影可在找到任意可靠遮挡物后结束，instance mask 为 `0x81`。
状态对象保留 miss/closest-hit 导出和三类命中组，用于现有 ABI/SBT 调用；inline 查询直接返回 record、primitive 与重心坐标。

### 16.3 命中组

| 组名 | closest-hit | intersection | 用途 |
| --- | --- | --- | --- |
| `HITOPAQUE` | `RtClosestHit` | 无 | 不透明地形 |
| `HITCUTOUT` | `RtClosestHit` | 无 | 镂空（叶子等） |
| `HITINVALID` | `RtInvalidHit` | `RtInvalidIntersection` | 待重建 section 的程序化失效体积 |

两个组暂时共用同一个 closest-hit：镜像几何没有 alpha，镂空判据要等材质表带上 alpha 才有意义。
实例表里 `InstanceContributionToHitGroupIndex` 按层写：镂空 / 半透明层写 1（`HITCUTOUT`），
其余写 0（`HITOPAQUE`）。两个组暂时共用同一个 closest-hit，所以这条现在只影响走哪条 SBT 记录；
等材质表带上 alpha，`HITCUTOUT` 才会真的做出镂空。

失效代理的贡献下标为 2，使用共享的程序化 AABB BLAS，局部边界为 `[0,16]³`。
生产实例按当前冻结的有限输出界平移、缩放，保守舍入并校验包含性；不能可靠表示变换时显式保留无界 unknown。
代理变换与无界标记进入 TLAS 复用键，同 node、mask 和 snapshot 的输出界改变仍须重新发布。
`rt_unknown_volume.hlsli` 用原射线的 `TMin/TMax` 与候选的 object origin/direction 做 slab 区间求交，
平行轴单独判定；起止均在体积内的短区间也保留 unknown。不得延长原查询，或把 broad phase 候选直接当作相交。
inline 查询读取 `CANDIDATE_PROCEDURAL_PRIMITIVE`；`TraceRay` 路径由 `RtInvalidIntersection` 报告同一最早交点。
消费方必须与 ABI 45 匹配，不能启用 `SKIP_PROCEDURAL_PRIMITIVES`。
无界生产者不生成有限命中体；native 在追踪表注入 flags bit 1，使非空原查询从 TMin 保持 unknown。
只有无界要求且无可见实例时使用 mask=0 的 TLAS 占位实例；它不提供几何或空间证明。
普通代理的 `InstanceID` 从完整快照的记录数开始，不得用来索引 `gRecords`；`RtInvalidHit` 导出返回 `hit=2`。
最近表面查询遇到位于已知表面之前的代理，返回 unknown，不能越过代理选择更远的表面。

生产 scene 对全部层与 live 版本匹配、仅 AS 未建好的 section 认证原查询补齐资格。
快照最多 384 条 record；先认证全部当前层，再把首个到最后一个待建 record 作为补查范围。
该范围必须连续、只含同一 section、数量为 1–127，且各 record 与当前 live 版本匹配；内容认证与查询预算分开。
范围外已有当前可消费 BLAS 的层保持普通实例可见，其三角形不占补查额度；范围内已建记录仍补查并计费。
完整 section 的 records 可以不连续；两个待建 record 之间夹有其他 section 时保留 unknown。
有资格的代理使用 `InstanceID = 0x800000 | (recordCount << 16) | firstRecordIndex`：
bit 23 是认证标志，bits 16–22 是连续 record 数，bits 0–15 是首 record 索引。
`rt_pending_query.hlsli` 只在认证标志存在时解码补查范围，直接读取同一快照的当前记录并校验 sectionNode，
沿原 origin/direction/TMin/TMax 求实际三角形交点，每条原查询最多 256 个三角形、8 个 section。
最近表面必须能在剩余额度内完整测试认证范围，超额仍为 unknown，不能用局部命中推导最近表面。
阴影在范围超额时可只测试不透明类别 `(flags & 0xFF00) == 0x100`，按实际测试逐三角形扣除共享余额。
找到原区间内的真实遮挡即可证明阴影；没有找到、额度用尽或内容不合法时仍 unknown，不能证明无遮挡。
整个范围的 sectionNode 仍先于相交检查；超额 cutout 不授予该证明。可完整补查的范围保留原预扣费与 cutout 行为。
使用真实 record/primitive/重心坐标和距离解码当前材质，完整样本继续进入 guides、SDK、曝光与 resolve；
不重抽方向、不使用天空替代，也不向 SDK 注入缺样本黑值。缺层、版本变化或移除立即撤销资格，
即使失效 section 集合不变也重建实例表。超额且无可靠不透明遮挡证明时仍 unknown；普通强制失效过滤不授予补查资格。

警告：补查数量上限不等于 GPU 时间预算。当前生产射线的 `TMax=1e9`；
有限区间体积判定不扩充目标清单以外的空间覆盖，也不解决一般缺样本恢复。
几何认证不签发空间状态 1、2。当前生产 JVM 仅发布 0、3、4、5；几何补查与完整 BLAS 都不能越过空间 unknown。

太阳遮挡只需证明射线区间内存在可靠遮挡物。`RtTraceShadow` 使用
`FORCE_NON_OPAQUE` 与 `ACCEPT_FIRST_HIT_AND_END_SEARCH`：
代理只记录 unknown 并继续遍历，真实三角形 commit 后即可证明遮挡，即使代理在其前方。
没有可靠遮挡物但遇到代理时保持 unknown；两者都没有才是可靠无遮挡。
`CANDIDATE_NON_OPAQUE_TRIANGLE` 且 `CandidateInstanceID() < RtRecordCount()` 表示真实三角形，程序化候选表示代理；native 常量必须使用该快照完整记录数，
不能替换成 active instance 数。当前不透明与 cutout 都按已提交三角形遮挡，不提供 alpha any-hit。
可补齐的代理先进行上述实际求交；已知遮挡证明优先于其他未解决缺口。

未取得完整当前内容与补查认证的 section 中，已有可消费 BLAS、node/layer/version 与当前 live 表匹配，且 `(flags & 0xFF00) == 0x100` 的不透明层，
保留为 mask=`0x80` 的阴影专用实例。`RtTraceShadow` 可以使用它证明遮挡，`RtTrace` 不可使用它提供主表面或 bounce 命中。
代理继续表示缺失内容；未命中可靠遮挡时仍为 unknown，不能因保留某层而证明整个区间无遮挡。
cutout、translucent、过期、缺席、未建或不可消费的层不授予该资格；独立强制过滤不提供此证明。
即使失效集合与补查资格不变，证明记录集合变化也须重建不可变 TLAS/实例表并推进 sceneRevision。
全部层完成后用普通实例一起发布，撤销对应专用实例；取消生产者或编辑证明层须撤销资格，旧资源按实际 fence 保活。

`RtSunVisibility` 仅在未遮挡太阳辐亮度与局部视差可见性都非零时执行查询。
主表面使用当前 Lambert 直射响应；次级表面使用当前 diffuse 与太阳高光响应之和，不能只按法线朝向判断。
零光源、确切零响应或局部自遮挡为零时保留已知的零太阳贡献，不产生 shadow unknown，
自发光、环境项与其它 lobe 继续求值。判断不设能量 epsilon，任一通道有非零贡献仍查询。
未查询时 aux 的可见性使用局部视差可见性；`SHADOW` 图中的该值不能作为外部遮挡物存在或不存在的证明。

主光线、diffuse/specular 最近表面或上述阴影查询仍为 unknown 时，当前积分仍会清除整个样本的
radiance、aux 和有效标记，合光按 `direct.w=0` 直通原版；逐信号恢复尚未实现。
正常三角形命中必须显式初始化 `RtHit.invalid=false`，不能把减少阴影假 unknown 当作完整缺样本修复。

### 16.4 SBT 排版（native 生产与 JVM 自检）

- 记录步长 64 B（32 B 标识符 + 32 B 留给局部根参数），必须是 32 的倍数（原生会校验）。
- 布局：raygen `0`、miss `64`、命中组 `128`、`192` 与 `256`，整块 320 B。
- **标识符按命中组导出名取**（`HITOPAQUE` / `HITCUTOUT` / `HITINVALID`），不是 closest-hit 的函数名：
  `GetShaderIdentifier` 只认建状态对象时登记的导出名，写错不会报错，派发那一刻整张 SBT 失效。
- **记录顺序就是贡献下标**：命中组表第 0 条（偏移 128）必须对应 `D3D12RtBlas.HIT_GROUP_OPAQUE = 0`，
  第 1 条（偏移 192）对应 `HIT_GROUP_CUTOUT = 1`，第 2 条（偏移 256）对应 `HIT_GROUP_INVALID = 2`。
- **排版常量要自洽**：`TABLE_BYTES == RECORD_BYTES * (1 + MISS_COUNT + HIT_COUNT)`，三个起点分别是
  `0` / `RECORD_BYTES` / `2 * RECORD_BYTES`。多算一条记录不报错，只是那张多出来的记录永远读不到；
  起点写错一位则两条记录互相覆盖。
- `_port/rt_sbt_check.py` 把上面三条都守住：组名必须出现在标识符列表里、每条命中组记录的位置
  与 `D3D12RtBlas` 里声明的贡献下标逐一相等、排版常量自洽（记录数 × 记录大小 == 整块大小），
  并检查 `DispatchRays` 的实参按 raygen / miss / 命中组的顺序排列。
- 缓冲是上传堆（`USAGE_MAP_WRITE`）：标识符只在建的时候写一次，之后 GPU 直接读地址。
- `DispatchRays` 与 compute 共用本次录制的 `WgaFrame`；生产常量由 native 返回，偏移为 0。
  调试通道使用独立 JVM 常量副本并传入实际槽偏移，不能覆写已发布的 native 常量。
- 状态对象与 SBT 都按包创建：生产帧资源释放时调用 `D3D12RtNativeFrame.release()`；
  自检由 `D3D12RtTrace.releasePipeline()` 释放，下次分别按新包重建。

### 16.5 校验

编译 `lib_6_8`，校验 raygen、miss 与三类 hit group 的标识符、SBT 对齐及绑定槽数。
GPU 探针直接通过 MCDR ABI 建 BLAS/TLAS，再用包内 DXR 库追踪受控场景。命中距离、材质与遮挡须正确。
`_port/rt_shadow_query_check.py` 直接调用实际最近表面和阴影查询，覆盖未知前后的遮挡物、
代理内部起射、有限距离、已知空与几何失效；普通场景有 54 项 inline 查询与 54 项 `TraceRay` 检查，
后者实际执行程序化 intersection，按独立 CPU 区间参考校验最近命中类型与距离。
内部短区间覆盖正反向、侧向、竖向、近面和区间内真实遮挡。`--legacy-shadow` 验证最近表面查询不能替代阴影查询。
`--pending-scene` 使用真实 16/17 条 BLAS 部分发布，对照完整 AS 的最近命中、阴影和五张积分输出，
并验证同帧编辑/缺层在失效集合不变时撤销资格。`--dense-pending` 验证超额最近表面与未获遮挡证明的查询仍为 unknown，
已获证明的积分样本与完整当前数据一致；
`--two-layers` 验证当前全部层认证后的硬件/补查分工，以及编辑、缺层后退回独立阴影证明。
`--noncontiguous-layers` 验证已建层不连续时仍能补查唯一待建层；`--resident-quads 129` 验证 258 个已建三角形不占软件补查额度。
`_port/rt_pending_frame_check.py --partitioned-section` 沿未修改的生产 shader 执行待建/完成与两条输入编码的四个完整帧，
核对当前 primary、材质 guides、SDK 输入、曝光与合光；NRD 实际执行 31 passes，RR 仅检查准备输入。
加 `--unproven-space` 时改用状态 5，要求四帧均保留 primary unknown 与源画面所有权，不能由几何认证推导空间完整。
`_port/rt_pending_frame_check.py --shadow-proofs` 经真实 native 帧验证五类查询、三个阶段与两条 SDK 输入编码的 30 个用例，
检查可靠遮挡、primary unknown、PBR guides、SDK 输入、曝光和合光；NRD 实际执行，RR 仅检查准备输入。
`rt_check_all.py --gpu` 包含此项完整帧检查。
这些检查不等于实际 NRD/RR 世界画面或性能验收。

`_port/rt_pending_shadow_check.py --baseline <冻结基线目录>` 检查超额待建范围的有界不透明遮挡证明。
产品 HLSL kernel 覆盖第 256/257 个三角形、共享余额、16/32 位索引、cutout、错 node、非有限顶点与原查询区间；
完整帧隔离直射、diffuse 次级与 specular 次级阴影，核对当前 primary、材质 guides、曝光、实际 NRD、RR 准备输入与最终合光。
受控 fixture 明确提供完整空间证据；它不授予生产空间状态 1/2，也不验证历史恢复或真实 RR 求值。
当前 kernel 与 18 帧正例已登记到 `rt_check_all.py --gpu`；冻结旧版负例通过上述独立入口运行。

### 16.6 自检：DXR 与独立 CPU 参考

四项场景与报告格式见 §9.7。场景坐标满足镜像布局约束；无降噪诊断使用 `RAW`，仍走硬件 DXR。

### 16.7 逐帧档位

| 配置 | 实际执行 | 不可用时 |
| --- | --- | --- |
| AUTO / DXR | DXR 状态对象 + SBT + DispatchRays | 关闭光追或跳过未就绪帧 |
| WG | 当前回退 DXR，日志报告 WG 尚不可用 | DXR 不可用时关闭光追 |
| mode=OFF 或未选包 | 原版渲染 | 不分配 RT 帧资源 |

`RAW`、RR、NRD_SR 是独立的降噪轨道，不改变上述追踪路由。
实际成功派发的统计为 `tier=dxr`；WG 回退不得报告为 WG。

### 16.8 实机验收清单

进入原存档，分别检查 RAW、RR、NRD_SR 与独立 SR。行走、快速转向后停下，确认阴影位置、纹理、
亮点收敛、残影和闪烁；再沿路线执行新区块进入与 A→B→A 旧区块回访，并单独复验固定尺寸的失焦恢复和 resize。
破坏或放置方块后确认新几何与阴影同步，同时检查 RT 范围内外是否稳定。停下后数秒恢复只能记录恢复过程，
不能当作降噪通过。视觉结果由用户确认，日志只能证明派发、提交和资源状态；不同降噪器不做统一图像 diff。

### 16.9 覆盖边界

几何生命周期回归覆盖同大小修改、整段提交、空段/卸载、取消上传和世界切换。
GPU 回归覆盖分帧构建、拓扑扩容、镜像换址复用及真实射线距离随编辑更新。
GPU 回归还覆盖失效代理的实际命中、产品 DXR 有效标记与五张输出平面、未修改区域继续追踪、
失效集合稳定时复用历史，以及新快照发布后恢复真实几何。当前待补项为失效边界的世界内视觉复验、
完整空间/内容协议、逐信号恢复与旧区块回访的噪点/性能复验。

### 16.10 native 帧资源与录制边界

G-Buffer replay 成功后，`D3D12RtFrame.prepareFrame()` 保存 device、唯一录制身份、Plan、replay revision 与深拷贝的相机矩阵。
世界合光钩子取得实际颜色源后，核对录制身份、replay、时间戳及显示尺寸，再消费这一份准备结果。
`D3D12RtFrame` 提交当前 section 清单，由 native 场景入口选择配对镜像/AS；填写 CPU `WgaFrame` 后调用
`D3D12RtNativeFrame.recordTransaction()`，生产链只使用一次 `D3D12RtFrameTransaction` FFM 调用。
ABI 45 的 248 B 描述嵌套 trace/denoise 请求，并携带空间证明、实际颜色源、SR 后端、debugMode 与可选 query pool。
FFM 复制追踪常量、空间字节、NRD/Streamline 数组；native 再冻结这些输入，在一次帧锁内连续录制空间、trace/guide/曝光、NRD/RR、可选 SR 和最终合光。
最终合光成功后才返回 288 B 整组输出；JVM 核对 recorded、frameId、snapshotId 和最终句柄后发布 radiance、重建与 `CompositeImage`。
`fallbackFlags` 的 1/2/4 分别表示当前 raw、RR→NRD、保留 NRD 而不使用 SR；不借用上帧输出。
四位 `timestampMask` 来自各阶段之后实际录制的 GPU 时间戳，不能在 FFM 返回后连续补四个时间戳冒充阶段耗时。
预检拒绝允许修正同 replay 重试；消费 replay 后部分录制失败撤销 group 与 pending，已录制资源仍按实际列表/fence 保活。
录制期间的 history、曝光与输出资格只写 pending；最后一次重建结果已合光、`ExecuteCommandLists` 成功且两个 queue `Signal` 均成功后才提交，任一 Signal、async submit 或录制取消失败则取消
pending，JVM 上一帧矩阵、view、jitter 在匹配所属 `recordingIdentity` 的确认点更新。
省略合光、合光后又追加重建但未重合光的录制都不能确认新历史；失败或取消同时撤销 JVM 的合光与时域输出资格。
RR/SR 物理 SDK 求值编号保持递增，失败编号不复用；该编号与已提交逻辑历史分别管理。
同步提交返回 0 不得确认；一次性编码器不得确认或取消帧 pending。异步观察超时保留所有权，
阻止重新打开、重提或销毁列表，等待终态成功或失败后分别确认或取消。已提交但未确认的资源继续按实际 fence 保活。

native 拥有五张 raw 缓冲（aux 为五平面）、DXR 状态对象、320 B SBT、材质/曝光/motion/pack/信号 PSO、每次录制的
512 B 不可变常量、1×1 曝光纹理/适应状态、motion/packed-normal/viewZ 三张 guide 以及 diffuse/specular/反射距离三张信号纹理。
尺寸切换先创建替代资源；旧资源按实际提交 fence 退休。RAW 不发布六张降噪输入，RAW 尺寸变化时退役旧输入。
相同尺寸按队列顺序复用 raw 缓冲；消费者必须在下次录制前记录完，不得当成跨帧历史或自行销毁。
完整调用、版本和资源寿命约束见 [native RT 帧接口](../../../MCDR/docs/advanced/rt-frame.md)。

native 同时持有 NRD 双输出、RR 显示输出、SR 重调制 PSO/三张纹理、合光 PSO/输出和独立 SDK 历史消费者；
RR 失败时同帧重编码后回退 NRD。Plan 代际改变允许失败 SDK 重试，历史代际或帧间断触发 reset。
合光输出为 `CompositeImage` 借用句柄及尺寸/frameId，不包装成 JVM 拥有的 `D3D12GpuTexture`。
JVM 只允许最新发布、当前有效帧、相同录制身份且与目标同尺寸的图像进入 BLAS 调试覆盖或上屏 blit；合光失败后拒绝旧图像。
native 另持有 G-Buffer 五张颜色与独立深度，Begin/End 的 88 B 输出携带重放编号和资源代际；
trace 描述只引用已完成的同帧重放，不接收外部 guide 指针。取消、过期或重复消费均被拒绝。
G-Buffer 的 MS/PS DXIL 与 binding 配方通过 `D3D12CreateRtGBufferProgram` 注册；native 创建固定五附件/反向深度的 mesh PSO 与根签名。
按 DXIL 内容、binding 顺序、剔除和多边形模式复用程序，每次注册得到独立令牌。JVM 只在管线关闭/替换时撤销令牌。
native 弱引用缓存不延长程序寿命，捕获批次与直接回放保留程序至对应列表槽 fence 完成；令牌撤销不影响在途绘制。
G-Buffer 源绘制逐批调用 `D3D12RtCaptureGBuffer`；native 拥有批次快照与精确 texture view、程序引用，
UPLOAD 数据立即复制，DEFAULT 常量/表在主列表中复制，DEFAULT 顶点和纹理只保活，禁止在同列表中覆写其内容。
`D3D12RtFrameReplayCapturedGBuffer` 预检整组、上传 RT 投影与 b8、绑定并派发 mesh；JVM 仅保留令牌与计数。
令牌绑定 encoder/fence/pass/时间戳，单次消费；finally 释放覆盖提前退出，资源保留至所属列表槽完成。
几何 GPU 镜像由 `D3D12CreateRtGeometrySnapshot` 注册：120 B 描述携带 CPU 数据与复用令牌，56 B 输出返回快照编号、五个借用句柄和容量。
范围镜像使用直接缓冲；FFM 在同步 native 调用期间借用其 remaining 区间，避免重复分配并复制整块几何。
普通堆缓冲仍复制到调用作用域的 Arena；native 返回前完成数据快照，不保留 JVM 原始指针。
native 复制数据并在当前主列表上传，分别共享不变的几何组与材质组；创建和追踪所用资源保留至实际列表槽 fence 完成。
`Mirrors` 只保留 CPU 切片、版本与 native 输出，关闭时撤销令牌，不销毁借用缓冲；追踪按令牌查快照，禁止另传五个裸几何句柄。
同 encoder 可顺序消费未提交上传；跨 encoder 须等上传 fence 完成，已撤销令牌拒绝再次复用或追踪。
`D3D12RtBlas` 的生产路径仅持 native scene 令牌与 224 B 借用输出，不维护候选/发布 AS 状态。
`D3D12RtGeometryPool.versions()` 在同一锁内捕获 epoch/revision 和 16 B section/layer/version 表，按代际缓存只读快照。
该表包含可用记录与仍需补齐的非空层。CPU 导出容量不足、复制异常或非空上传缺少 CPU 字节时，
保留当前所需版本并撤销该层的旧载荷；条目存在不表示几何已经上传或可供追踪。
当前完整上传成功后用实际几何身份替换待补齐要求；只有明确移除该层或退出世界才删除要求。
`coverageNear` 在同一池锁内捕获版本表和独立 section node 清单，后者按 node 去重、排序并缓存为只读 8 B 数组。
目标包括范围内仍需非空层的段，以及已收到 dirty、等待当前编译结果的段；后者可以没有任何当前层要求。
生产范围沿用镜像选择锚点和 120 m 选择球，即 `TRACE_RADIUS=96` 加 `MIRROR_SLACK=24`，
按段 AABB 与闭球是否相交取得目标；没有增加追踪半径或记录/字节预算。
目标不受 384 条记录/128 MiB 的载荷选择上限裁剪；整段载荷缺失或被预算整组省略时仍是所需目标。
`D3D12PrepareRtSceneTargets` 同时提交两份清单，续建候选、推导局部失效并发布配对镜像/AS；native 拥有 BLAS/TLAS、scratch、TLAS view 与不可变实例表。
无当前层清单的目标保留无界 unknown，不能复用旧几何提供最近表面、待建补查或阴影证明。
已观测 dirty 目标不构成完整空间清单；未观测、卸载、范围外和无限太阳查询的支持域仍未认证。
同世界复用未变化的 BLAS；等待构建时保留未受影响的已发布实例，失效 section 改用代理。
trace 传 accelerationId，native 核对它与 snapshotId 配对、构建完整且生产者 fence 可消费；未知或撤销令牌拒绝。
构建、过滤与追踪的具体资源保留至实际列表槽完成，取消候选或替换已发布场景只撤销令牌。
JVM 仍负责着色器生成、捕获通知、CPU 几何池/范围选择与镜像令牌缓存、普通调试纹理及上屏 blit。
统一帧录制已有离线 FFM 与真实 GPU/SDK 检查；完整空间/内容协议、逐信号恢复、预算和世界内切换验收仍未完成。

## 17. labPBR 材质图接入

外部 PBR 材质包（如 SPBR）把光滑度、法线放在基础色贴图旁边：`block/stone.png` 对应的 `block/stone_s.png`
是 labPBR 的 specular 图，`block/stone_n.png` 是法线图。本节说明光追阶段怎么读它们、读到的数字进哪里，
以及没有材质包时凭什么退化。

### 17.1 两个来源

`config/dxr_mc-client.toml` 的 `[materials]` 段：

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `enabled` | `true` | 关掉就完全不查贴图，材质表退回内置启发式 |
| `packPath` | `""` | 非空 = 只读这个目录（开发期直连材质包仓库）；空 = 读游戏里启用的资源包 |
| `parallaxDepth` | `0.25` | `_n.A` 从 0 到 1 的最大深度（方块），范围 0–0.5；0 关闭视差与高度自遮挡，热切换重新烘焙 |

`packPath` 支持绝对路径，也支持相对游戏目录（`Illusion\run\`）的路径。两种来源都找不到图时返回空结果，
材质表保留 `lookOf(path)` 的启发式值 —— 发行版不带任何贴图，这条路必须能走。

一次重烘打一行：`rt materials: spec=<有 _s 的精灵数> normal=<有 _n 的精灵数> of <查过的精灵数> from <off|packPath|resources>`。
贴图按精灵名缓存，缓存随每次重烘清空；没有映射的精灵也缓存，否则几万个精灵会把重烘拖成卡顿。

首次重烘要开几千个文件（SPBR 里有 3 757 张 `_s`、2 396 张 `_n`），会有一次几百毫秒到一秒量级的停顿；
之后每次图集重烘（例如资源包切换）都要再来一遍 —— 缓存的键是精灵名，图集换了必须重解析。

### 17.2 材质记录（40 B）与光照

| 偏移 | 字段 | 来源 |
| --- | --- | --- |
| +0 | `albedo` RGB | 基础色贴图 4×4 采样均值（线性空间） |
| +12 | `emissive` RGB | 无 `_s` 贴图时的启发式自发光；有贴图时使用其 alpha 强度 |
| +24 | `roughness` | 无贴图时的启发式值 |
| +28 | `flags` | 启发式（透明 / 双面 / 自发光位） |
| +32 | `metalness` | 无贴图时的金属度 |
| +36 | 贴图元数据偏移 | 0 表示无图；非零为同一缓冲内的绝对字节偏移 |

记录后接不可变的贴图数据。每个精灵的 64 B 头含 atlas UV 起点与逆尺寸（+0），
`_s` / `_n` / 基础色各自的相对数据偏移、宽、高（+16/+28/+40），
视差深度（+52）与该图最大凹陷比例（+56）；像素按 ARGB uint 保存。
基础色来自当前 atlas 精灵首帧，和屏幕上启用的资源包一致。
不同 tint 的材质记录共享同一份精灵贴图。上传时复制记录和贴图，并将 +36 转成绝对偏移。
不得返回可被下一次烘焙覆盖的 CPU 缓冲视图。

`rt_integrate.hlsli` 使用命中采样的着色法线、粗糙度、F0、金属度与自发光，
计算太阳直射、漫反射和 GGX 镜面弹射；无贴图的命中保留启发式材质。

主命中的 diffuse 与 specular 分开采样。CPU 参考 `D3D12RtTrace.shadeBounce`
对应无贴图材质；贴图采样与视差由独立 GPU 探针验证。

主命中的材质因子由 `RtMaterialGuide` 在追踪后补写到 G-Buffer，见 §19.7。
它消费 DXR 同一次采样生成的 normal、roughness、F0、metalness 与 emissive，
以同次命中的基础色完成有色金属 F0 与自发光重调制；无贴图时保留光栅 albedo 与启发式值。
材质 guide 先由原始像素 albedo 计算 F0，再将 diffuse albedo 乘 `1-metalness`。

主表面镜面使用 GGX VNDF，`alpha=roughness²`，roughness 下限与 guide 同为 `1.5/512`。
diffuse/specular 的采样 seed 使用实际帧号 `RtFrameIndex()`，sampleIndex 分别为 0/1。
每帧更新弹射方向，CPU/GPU 自检按相机常量中的同一帧号复现。对反射入射光 `incoming`，
令 `Fc=(1-dot(view,halfDirection))^5`、`visibility=WgaGgxG1(dot(normal,light),alpha)`：

- `specular.rgb = incoming * visibility * (1-Fc)`，w 为独立的首次反射命中距离；
- `specularGrazing.rgb = incoming * visibility * Fc`，w 为 0；
- 重调制使用 `specular.rgb * pixelF0 + specularGrazing.rgb`，保留 F0 的像素纹理；
- 反射打空的距离为 `1e9`，采样朝内为 0；天空主像素的两个镜面缓冲均为零。

反射环境已包含太阳盘，主镜面不再额外叠加太阳 NEE。二次命中的光照仍使用本节的终止近似。

### 17.3 逐命中采样

`RtSampleSurface` 使用 DXR 返回的三角形重心坐标插值顶点 UV，将 atlas UV 转为精灵局部坐标，
以最近邻采样 `_s` / `_n`。主命中与二次命中调用同一函数，不把整张材质图平均成一个数。

`_n.A` 为高度，255 表示原表面，较小值向内凹。每个高度像素表示等高柱体，保留水平顶面与垂直侧壁，
禁止在相邻高度之间插值。沿入射方向按像素边界做 DDA，在每格内解析求顶面或入格侧壁交点；
遍历上限为高度图宽加高再加 1，不以固定层数跳过单像素窄脊。太阳自遮挡使用同一柱体与格边界。
基础色与 `_s` 共用交点 UV；边界坐标向命中格内部限制 `1e-4` 像素，避免负方向侧壁取到相邻材质。
高度视差命中使用柱体顶面或侧壁法线，并写入降噪引导；不再用 `_n.RG` 将这些棱角改成斜面光照。
基础色按 2.2 次幂线性化，再乘重心插值的顶点 RGB（包含群系染色/AO）。
视线超出精灵时延续其边缘柱体，太阳检查离开精灵即结束；均不访问相邻精灵。
全白高度图不做视差。存在透明像素的精灵关闭高度视差；掠射角余弦 ≤0.05 时保留原表面。

主命中的虚拟径向深度写入既有 linear-depth，MV、NRD viewZ 与 RR 读取该深度。
宏观阴影和弹射射线从虚拟表面沿出射方向返回三角形平面后再施加 bias，避免撞到自身宏观面。
该路径提供表面视差与太阳自遮挡，不改变 BLAS、方块轮廓或碰撞；间接微表面多次遮挡尚未实现。

- `_s.R` 为光滑度：感知 roughness 使用 `1-R/255`，GGX 的 alpha 再取 roughness²。
- `_s.G` 的 0–229 为介质 F0，230–237 为预定义金属折射/消光系数，238–255 使用 albedo 着色的金属 F0。
  不得把 G/255 当作连续金属度。
- `_s.A` 的 0–254 为归一化自发光强度，255 为不发光。alpha 是数据，0 不能当透明像素丢掉。
- 未执行高度视差时，`_n.RG` 解码切线空间 XY，Z 由单位长度恢复；按三角形位置/UV 构造带手性的切线基，
  将贴图向上的 Y 转到 atlas 向下的 V。扰动法线参与直射与弹射，并写入 NRD/RR 引导。
  射线起点偏移仍沿几何法线，映射法线背向观察方向时回退几何法线。

当前限制：贴图动画取首帧；`_n.B` 的 AO 和 `_s.B` 的孔隙/SSS 尚未接入。
有材质图的二次命中同样采样基础色，缺图材质继续使用精灵均值。

### 17.4 验收

- 不配 `packPath`、不启用材质包：日志那行是 `from resources` 且 `spec=0`，画面与接入前一致；
- 配 `packPath = Illusion-HPT-Shaders/SPBR-22_6`：`spec` 应是几千（该包 10 659 张图里 3 757 张 `_s`），
  石头/木头/金属的粗糙度与金属度各不相同；
- `rt_pbr_loader_check.py` 调用产品 PNG 读取与打包，验证 ARGB、alpha=0、首帧裁切、偏移与不可变快照。
- `rt_parallax_probe.py` 由 GPU 信号探针调用，用独立射线/柱体包围盒交点验证离散台阶、四向侧壁法线、
  正负方向和格边界、单像素窄脊与太阳自遮挡，并检查基础色/粗糙度/自发光共享 UV、顶点染色和深度。
  guide 探针检查虚拟深度、基础色与金属重调制；这些数值检查不替代世界内的材质观感验收。
- `rt_sr_signal_check.py --pbr-materials <产品导出的缓冲>` 经实际 GPU 检查空间变化、F0/金属编码、
  自发光、法线参与直射以及共享 guide；同时验证没有贴图时的退化。画质由用户在世界内确认。

## 18. 降噪与超分的单一真值表（S4 4-a）

`D3D12RtPipelinePlan.configured(displayWidth, displayHeight)` 生成一帧的轨道、尺寸与派发闸门。
G-Buffer 重放、viewport、trace、motion、NRD pack 与 radiance 使用同一个计划：

| 轨道 | trace 倍率 | 实际 G-Buffer / 降噪尺寸 | 实际降噪 | 放大 |
| --- | --- | --- | --- | --- |
| `OFF` | 按 100% | 显示尺寸 / 不派发降噪 | 无 | 原始 trace 合光插值 |
| `RR` | `renderScale` | render 尺寸 | 尝试 DLSS-D，不可用则 NRD | RR 输出显示尺寸；NRD 回退按所选 SR 后端重建或合光插值 |
| `NRD_SR` | `renderScale` | render 尺寸 | REBLUR_DIFFUSE_SPECULAR | 低于 100% 时按所选 SR 后端重建；NONE 或不可用时合光插值 |
| `RAW` | `renderScale` | render 尺寸 / 不派发降噪 | 无 | 原始 trace 合光插值 |

硬件追踪与 G-Buffer 使用同一 render 尺寸，见 §26.1。
`RAW` 与 `OFF` 不生成降噪输入，也不派发降噪。降噪 OFF 只关闭降噪，光追总开关由包选择、mode 与硬件能力决定。
光追总开关关闭时，计划将 G-Buffer 与 trace 尺寸置零，重放入口立即返回。

配置位于 `config/dxr_mc-client.toml` 的 `[rt]`：

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `denoise` | `OFF` | 选择降噪轨道 |
| `renderScale` | `100` | 25–100，参与计划尺寸与 trace 目标高度计算，见 §26 |
| `buildCpuMicros` | `3000` | 合法 scene 准备的 CPU 软预算；100–1000000 微秒，在 BLAS 之间检查 |
| `buildGpuMicros` | `1000` | AS 增量工作的 GPU 预测预算；100–1000000 微秒，由已完成 timestamp 校准 |
| `buildTriangles` | `65536` | 每真实帧新建 BLAS 三角形额度；1–1000000000，首条超大工作单独推进并记录 |

独立 SR 后端读取 `SuperResolution.backend()`，与 `[sr] backend` 初值及视频选项共享选择。
计划变化时记录 `rt: denoise=<track> render=<w>x<h> gbuffer=<w>x<h> sr=<backend>`。
此处的 render/G-Buffer 是计划值，不能证明资源已经按该尺寸分配。
实际 trace 网格查看 `rt frame: dispatch <w>x<h> of <displayW>x<displayH>`；实际资源以分配点为准。

## 19. 运动矢量与引导数据（S4 4-b）

运动矢量是降噪与超分的重投影输入：每个像素告诉后级「这个像素在世界里的那个点，上一帧落在屏幕的哪里」。
它在光追派发之后单独算一次，写进 `rt-motion`（RGBA16F，与 G-Buffer 同尺寸），由 `RR` / `NRD_SR` 消费。

### 19.1 为什么另开一次计算

G-Buffer 程序与 pass 固定五张颜色附件，不包含 motion 输出。运动矢量依赖光追更新后的主命中深度，
在 G-Buffer pass 之后单独计算，不能使用更新前的光栅深度替代最终 guide。
native 帧入口在主命中材质深度更新之后执行 `RtMotion`；输入与输出属于同一份已接受帧描述。

### 19.2 算法与约定

在 `D3D12CommandEncoderBackend.writeToBuffer` 捕获关卡投影 UBO 的实际上传矩阵。
该矩阵已包含原版 `bobView`、`bobHurt` 及屏幕效果；禁止只从 `Camera` 的位置、朝向和 FOV 重建。
捕获结果按 `D3D12Temporal.frameTimestampNanos` 校验，只供同帧 G-Buffer 与追踪消费；缺失时跳过派发。

`D3D12RtCamera.resolve` 将最终投影中的相机效果移入视图：

```
E = inverse(P_base) * P_final
V = E * R_camera * T(-logicalPosition)
VP = P_base * V = P_final * R_camera * T(-logicalPosition)
rayOrigin = inverse(V) * (0, 0, 0, 1)
```

追踪与运动矢量使用这份 VP；NRD 与 Streamline 使用 `P_base` 和包含摇晃的 V。
`g_cameraPos.xyz` 写实际射线原点。G-Buffer 重放在 b8 的字节偏移 32 传入
`gRtCameraOffset = rayOrigin - logicalPosition`，生成的 `gbufWorldPos` 减去该偏移，
保证径向深度与射线具有同一原点。b8 的前 32 字节保持 meshlet/VRS 布局，G-Buffer 的 VRS 关闭。

RT 从 `D3D12Temporal.frameJitter(frameNanos)` 取得同帧实际注入的唯一 Halton 偏移。
先捕获无 jitter 最终投影并解析 bob。RT 开启时原版世界 UBO 保持稳定；只有 G-Buffer 重放使用采样投影，
其全部批次成功后才提交偏移给 PT、guide 与 SDK。未重放、过期帧或不完整批次不能沿用旧光追输出。
clip 平移左乘最终投影，逐列执行 `row0 += clipX * row3`、`row1 += clipY * row3`。
`g_viewProj` / `g_prevViewProj` 与 `g_invViewProj` 含对应采样偏移，SDK 矩阵与持久历史矩阵保持无 jitter。
透视射线方向统一调用 `rt_camera.hlsli` 的 `RtRayDirection`：令 `horizontal = row0.xyz - ndc.x * row3.xyz`、
`vertical = row1.xyz - ndc.y * row3.xyz`，取 `cross(vertical, horizontal)` 并选择 clip.w 为正的一侧后归一化。
只使用投影的线性部分；禁止将近裁剪点反投影为绝对世界坐标再减相机位置，避免浮点消减误差使射线随 Halton 周期偏移。
RT 开启且当前 Plan 含 RR 或 NRD 时启用偏移消费闸门；它不随某一帧的 SDK 求值结果开关，
因此失败帧不会让世界投影在稳定与 jittered 两套坐标之间来回切换。首帧、RT 关闭、降噪关闭或窗口无效时为零。
偏移仍只进入独立 G-Buffer 重放与匹配的 RT 输入，原版世界 UBO 保持稳定。
逐帧 Halton 变化不触发 camera cut。行走摇晃仍按原版最终投影逐帧保留。

合光按输出像素直接读取稳定的原版 source，禁止再用 jitter 平移它。原始 RT/G-Buffer 输入仍按
`outputUv + contentJitter / radianceSize` 读取；RR/SR 已重建的颜色按 `outputUv` 读取。
无覆盖、范围外与半径淡出使用同一稳定 source，追踪未就绪时整帧直通也无需补偿原版投影。

`Illusion-HPT-Shaders\B-DXR\shaders\rt_motion.hlsl` 的 `RtMotion`：

1. 读 G-Buffer 的 `LINEAR_DEPTH`（`t0`）。存的是 `sqrt(dist / DEPTH_SCALE)`，所以 `distance = stored * stored * DEPTH_SCALE`。
   `DEPTH_SCALE = 64.0`，按浮点位放在 b1 根常量的第一个 32 位槽（b0 的字段已排满，布局见包里 `wga_bindings.hlsli` 的 `cbuffer WgaFrame`）。
2. 与追踪共用 `rt_camera.hlsli`：`uv = (id + 0.5) / size`、`ndc = (2u - 1, 1 - 2v)`，
   `dir = RtRayDirection(ndc)`，世界点 = 相机 + 方向 × 距离。禁止独立实现另一套射线反投影。
3. 世界点分别乘 `g_viewProj` 与 `g_prevViewProj`，取 `xy / w` 之差，乘 `0.5` 换成 NDC 跨度的半宽，再乘像素尺寸。
4. 屏幕位移为 `(dx,-dy)`，再加当前减上一帧的屏幕内容偏移以移除 jitter。b1 第二/三槽传入该差值；输出仍是像素单位、+y 朝下的真实运动。
5. 深度为 0 的像素（天空、G-Buffer 没写过的实体）位移记 0：它们自己就是静止的，写别的东西会被后级当成运动。

共享采样离线检查运行 `python _port/rt_jitter_check.py`，GPU 总门也包含此项。
JVM 直接调用产品生成器，导出 192 帧 bob/FOV/正反 Z/两种尺寸的真实矩阵与偏移载荷。
实际产品主射线、MV 与 viewZ HLSL 对拍 1,728 个解析点，CPU 参考计入 RGBA16F 径向深度量化。
预算分别为 MV 0.005 pixel、方向 0.0002、viewZ 0.002；遗漏 MV 去偏移的负例须超过 0.1 pixel。
七个独立进程各运行 32 帧，匹配 SDK 帧号、输入与运行库；NRD 检查有限输出与资源归还，
RR 使用 320×200 原生尺寸，RT DLSS SR 从 320×200 放大到 640×400。
RR/SR 的正确、反号及零偏移组只改变相机偏移载荷，末八帧均方误差与负例退化倍数记录为图像诊断，
不决定工程检查的退出码；共 120 次非法偏移须在资源接管前拒绝。
报告保存逐帧读回哈希、当前源码/SDK 运行库/输入哈希和末帧完整快照，并从快照重算误差。
该检查不覆盖世界内投影上传与 G-Buffer 重放、FSR/XeSS 非零偏移画质、主观残影或长时间运行。

`python _port/rt_jitter_check.py --precision-only` 检查远离原点时的射线精度：
1715×893、两组世界坐标、正反深度和 16 相 Halton，共 64 帧、576 条 GPU 射线。
用独立双精度相机相对矩阵将 GPU 方向投回屏幕，误差必须小于 0.02 渲染像素；这是投影几何检查，
不比较不同降噪器的图像，也不替代世界内阴影稳定性的目视验收。

### 19.3 上一帧矩阵

`D3D12RtFrameConstants.write(...)` 的两个可选参数负责轮转：`previousViewProjection` 传上一帧存下的矩阵，
`outViewProjection` 把这一帧的矩阵写回同一个 `FloatArray(16)`。没人传时 `g_prevViewProj` 等于当前帧，
位移恒为 0 且不报错 —— 这正是 `pending` 阶段该有的行为。

分辨率一变（`ensureOutput` 里 `outputWidth/outputHeight` 换了）就置 `previousValid = false`：宽高比变了，
旧矩阵当上一帧用会算出一帧乱飞的运动矢量。自检路径（`D3D12RtTrace` 里的 `write`）不传新参数，
所以自检的 `g_prevViewProj` 恒等于当前帧，不会污染世界帧的轮转。

### 19.4 成本与日志

一次 `8x8` 覆盖 G-Buffer 尺寸的派发，只读一张纹理、只写一张纹理，计入 `dispatches`。
native 帧入口录制耗时统一计入追踪阶段，没有单独的 motion 计时槽。
motion 与 packed-normal/viewZ 共占每像素 20 B，记入 native 输出的 `byteCount`；尺寸变化后按 fence 退休。
录制失败由帧入口报告且不发布输出，合光直通原版。

### 19.5 静态门

| 脚本 | 管什么 |
| --- | --- |
| `_port/hlsl_check.py` | `rt_motion.hlsl` 按 `cs_6_8` + 入口 `RtMotion` 编译一次 |
| `_port/rt_bindings_check.py` | `motionSrvs` / `motionUavs` 的槽位数与顺序对 `register(t0)` / `register(u0)` |
| `_port/rt_shader_files_check.py` | Kotlin 里出现的 `rt_motion.hlsl` 必须在包内存在 |
| `_port/rt_guide_layout_check.py` | 引导数据表（§19.7）与 `D3D12RtGuide.SLOTS` 逐项对拍 |

### 19.6 验收

`_port/rt_native_frame_check.py` 通过产品 native 帧入口回读 motion、packed-normal 与 viewZ，验证
32 相 Halton 抵消、静止/移动、RAW 切换、尺寸改变与无效 guide。工程检查不代替世界中的阴影稳定性与拖尾验收。

### 19.7 引导数据的单一定义

NRD、DLSS-D、FFX 要的是同一组输入，所以只有一份清单：`Illusion\src\main\kotlin\io\github\dxegp\experimental\raytracing\D3D12RtGuide.kt`
的 `SLOTS`。顺序就是 SRV 槽位顺序，消费方传入当前帧 motion 借用句柄并取 `srvHandles(motion)`；缺任何一项时它返回 null，
调用方整条跳过 —— 少绑一个槽位时原生会按数量校验拒掉整次派发，绑半套等于每帧静默失败。

| 槽位 | 格式 | 来源 |
| --- | --- | --- |
| `albedo` | `RGBA8` | `gbuffer` |
| `specular` | `RGBA8` | `gbuffer` |
| `normal-roughness` | `RGBA16F` | `gbuffer` |
| `linear-depth` | `RGBA16F` | `gbuffer` |
| `motion` | `RGBA16F` | `motion` |

`gbuffer` 指的是 G-Buffer 的四张颜色附件（§8），`motion` 是 §19.2 的 native 帧输出。
`linear-depth` 保存径向距离编码；NRD 的正 viewZ 由 §21 的 pack pass 单独转换。

G-Buffer 像素着色器先写电介质默认值：F0 为 0.04、roughness 为 0.8、emissive 为零。
DXR 将主命中材质 ID 按原始 uint 写入 `aux.w`；不要按浮点数值读取该槽。
native 帧入口随后派发 `RtMaterialGuide`，读取 `aux`、40 B 材质表、
像素 albedo 与径向深度，更新五张现有附件，不新增 RTV：

- `specular.rgb` 使用采样 F0；有色金属与启发式材质按像素 albedo 重调制，alpha 保存 metalness；
  无贴图时的 F0 为 `lerp(0.04, albedo, metalness)`；
- `normal-roughness.xyz` 使用 DXR 同次采样的世界空间法线，w 写入 roughness，下限为 `1.5 / 512`；
- `emissive.rgb` 使用采样强度乘像素 albedo，或启发式 RGB，并限制到有限的非负 FP16 范围。
- `albedo.rgb` 在 F0 计算后乘 `1-metalness`，纯金属的主漫反射归零。
- `linear-depth` 在高度视差生效时写入虚拟交点径向距离，供后续 MV、NRD 与 RR 使用。

绑定为两个 SRV（aux、materials）与五个 UAV（specular、normal-roughness、emissive、albedo、linear-depth）。
albedo 和 linear-depth 通过 UAV 读取并更新，禁止同时作为同一派发的 SRV。

生产 `aux` 为五个平面，每平面每像素 16 B：原距离/可见性/材质 ID 平面后，
追加 normal/roughness、F0/metalness、自发光数据/模式位、采样基础色/虚拟交点距离增量。五张 raw UAV 的槽位不变。
guide 从这些平面消费追踪时采样的属性，避免降噪使用与着色不同的法线或材质。
诊断的单平面 `aux` 仍读取 40 B 常量材质记录。

只接受有效主命中、有效材质索引、覆盖 alpha 与匹配的径向距离。
距离容差为 `max(0.02, surfaceDistance * 0.002)`；缺失记录、非有限值或深度不符时保留默认值。
主命中按 trace 尺寸映射，深度检查防止把相邻表面的材质写到当前像素。
材质补写必须在 NRD pack 之前执行，使 NRD 与 RR 读取同一帧的 roughness。

当前实际创建 `REBLUR_DIFFUSE_SPECULAR`，使用独立 diffuse/specular 输入、输出与各自命中距离。
镜面 RGB 在纹理化时除以 `max(F0,0.02)`，降噪后乘回相同因子；该正下限使解调可逆。

准备齐了才打一行（一次进程）：

```
rt guide: 5 slot(s), material factors ready
```

缺项时是 `rt guide: incomplete, motion or gbuffer missing` —— 这行说明降噪拿不到完整输入，不要按「能跑就行」放过。

静态门 `_port/rt_guide_layout_check.py` 把这张表与 `SLOTS` 逐项对拍（槽位名、格式、来源都要一致，
顺序也不能换），并检查主命中材质与双 lobe 限制的说明。
`_port/rt_sr_signal_check.py` 执行实际 guide HLSL，覆盖 RGBA8 F0、几何法线保留、
粗糙度下限、自发光范围、天空/漏命中、错位深度、非有限值与截短缓冲。

## 20. NRD 后端（S4 4-c）

NRD 4.18 是静态库（`MCDR\external\nrd\`），DXIL 内嵌在库里，不用我们自己编译它的着色器。代价是
它的管线数量、常量缓冲上限、纹理池尺寸与寄存器空间全部由库决定 —— 这些数字手填错一个，降噪就
整段不出图，而且不会报错。

### 20.1 第一步：把库自己算出来的清单读出来

`MCDR\src\advanced\d3d12_nrd.cpp` 建一个 NRD 实例、读 `InstanceDesc`、把字段按「每行一条事实」写进
调用方缓冲；`mcdr_dx12NrdInspect` 只是这条路径的 ABI 出口（纯 CPU，不需要 D3D12 设备）。
`Illusion\src\main\kotlin\io\github\dxegp\experimental\raytracing\D3D12RtNrd.kt` 的 `inspectOnce()` 在首帧派发后把这个
清单打一次。`MCDR_ABI` 里对应 `D3D12NrdInspect`（ABI v11）。

### 20.2 实测数字（NRD 4.18，`REBLUR_DIFFUSE_SPECULAR`）

| 字段 | 值 | 用途 |
| --- | --- | --- |
| `pipelinesNum` | `14` | 每次 `GetComputeDispatches` 的 pipeline 下标都落在这个范围内 |
| `constantBufferMaxDataSize` | `912` 字节 | root CBV 一次上传的上限，超过就截断 |
| `constantBufferAndSamplersSpaceIndex` / `resourcesSpaceIndex` | `1` / `0` | 两套根签名空间不能混 |
| `constantBufferRegisterIndex` / `resourcesBaseRegisterIndex` / `samplersBaseRegisterIndex` | `0` / `0` / `0` | 与上面两个空间联用 |
| `samplersNum` | `2` | `NEAREST_CLAMP` + `LINEAR_CLAMP` |
| `permanentPoolSize` / `transientPoolSize` | `13` / `8` | 纹理池条目数，格式由 `TextureDesc` 给出 |
| `perSetTexturesMaxNum` / `perSetStorageTexturesMaxNum` / `setsMaxNum` | `18` / `7` / `36` | 描述符池上限 |
| `pipelines[0].computeShaderDXIL.size` | `3596` 字节 | 库自带 DXIL，我们只建 PSO 不编译 |
| `shaderEntryPoint` | `main` | **不是**手册里写的 `NRD_CS_MAIN` |

### 20.3 接下来要按这份清单做

1. 按 `pipelinesNum` 为每条 `PipelineDesc` 建 compute PSO（DXIL 直接用，`hasConstantData` 决定要不要绑 CBV）。
2. 按 `permanentPool` / `transientPool` 建纹理池，格式来自 `TextureDesc`，`downsampleFactor` 参与尺寸换算。
3. 描述符堆按 `descriptorPoolDesc` 的上限建；每次派发按 `DispatchDesc.resources` 逐项绑。
4. 每帧 `SetCommonSettings`（`motionVectorScale` 用 1/renderW、1/renderH，我们的运动矢量是像素单位）
   + `SetDenoiserSettings` + `GetComputeDispatches`。

native 帧上下文按 Plan 和 render 尺寸保存初始化失败状态，同一配置失败后不逐帧重复创建。
切换降噪档位、超分方案或尺寸，以及关闭后重新开启光追时允许重试。
`D3D12RtFrame.updatePlan()` 推进 native Plan 代际，`release()` 释放帧上下文；
已就绪的同设备/同尺寸 SDK 上下文在连续录制时复用。尺寸变化与释放沿实际在途 fence 退休资源。
初始化重试与历史复位分别处理；RR、NRD、SR 的历史仍只在实际求值成功后消费。

### 20.4 日志样例

```
nrd: pipelines=14
nrd: cb max=912 bytes
nrd: spaces cb=1 res=0
nrd: registers cb=0 srv=0 smp=0
nrd: samplers=2
nrd: pool permanent=13 transient=8
nrd: desc set srv=18 uav=7 sets=36
nrd: pipeline0 dxil=3596 cb=1
nrd: entry=main
```

这份 DLL 没编进 NRD 时只有一行 `nrd: not built in`；建实例失败时那几行按 WARN 打，内容由库里给出。

### 20.5 门

`_port/abi_layout_check.py`（结构布局与版本）与 `_port\rt_check_all.py --gradle` 覆盖这一层：新增入口
必须同时出现在 `mcdr_abi.h`、`MCDR_ABI.kt` 的 `skipOptional` 与 `MCDR_ABI_VERSION` 的版本记录里。

工作区的 `python _port/rt_native_frame_check.py` 经帧入口执行实际 NRD 与 RR 不可用回退，
验证输出读回、Plan/尺寸/历史代际/帧间断、失败不发布及阻塞队列下释放。
额外使用 SDK 下采样池无法分配的 8×8 输入触发真实初始化失败，检查同配置抑制、
Plan 变化重试和 64×64 恢复。两次预期分配错误单独记录，正常阶段要求无调试层错误。
该检查不代替世界内切换、长期运行或画质验收。

## 21. NRD 输入编码（S4 4-c）

NRD 只看两种「引导」排布：法线-粗糙度与 viewZ。两者都不是我们 G-Buffer 里的原样数据，必须在派发之后
重排一次，否则降噪拿到的是解错的法线 —— 画面只会变糊，不报任何错。

### 21.1 为什么必须自己转

编码在 NRD 编译期定死，运行时只能问出来不能改：`LibraryDesc.normalEncoding = 2`
（`R10_G10_B10_A2_UNORM`）、`roughnessEncoding = 1`（`LINEAR`）。这两个值由 `D3D12RtNrd` 的清单打出，
见 §20.4 的 `nrd: normal encoding=2 roughness=1`。软件包的 `include` 里没有 `NRD.hlsli`，打包公式
照 NVIDIA-RTX/NRD 的 `_NRD_EncodeNormalRoughness101010` 逐位实现，放在
`Illusion-HPT-Shaders\B-DXR\shaders\rt_nrd_pack.hlsl`。

### 21.2 打包公式

法线先按 L1 归一，再把 `x/y` 斜切成两轴、`z` 的符号折进粗糙度：

```
n /= |n.x| + |n.y| + |n.z|
r.y  = n.y * 0.5 + 0.5
r.x  = n.x * 0.5 + r.y
r.y -= n.x * 0.5
roughness = max(roughness, 1.5 / 512)      // 不能为 0，否则 z 的符号位吃掉粗糙度
r.z = (n.z < 0 ? -roughness : roughness) * 0.5 + 0.5
```

`w` 是 `materialID / 3`，本工程恒 0（只有一类材质），所以恒定写 0。

### 21.3 格式偏差：用 `RGBA16_FLOAT` 存同一组归一化值

引擎的 `GpuFormat` 枚举里没有 10-10-10-2 打包格式（`javap` 查过：只有 `R8/RG8/RGBA8`、`R16/RGBA16`
的 UNORM/SNORM/UINT/SINT、`R32/RGBA32` 的 UINT/SINT、`R16/R32_FLOAT` 等），而 NRD 的输入只是一张
SRV，按 `float4` 读。所以这里存的是**同样的归一化打包值**，只是每像素 8 字节而不是 4 字节：
解码数学不变，精度更高。`IN_VIEWZ` 用 `R32_FLOAT`，与 NRD 的「线性视图深度」直接对应。

### 21.4 viewZ 是投影距离，不是到相机的直线距离

G-Buffer 的线性深度存的是径向距离（`sqrt(saturate(length(worldPos) / DEPTH_SCALE))`，见 §8），
NRD 要的是沿视线方向的投影。转换在同一个 kernel 里做：

```
radial = stored * stored * DEPTH_SCALE
dir = RtRayDirection(ndc)
viewZ = max(dot(g_viewProj[3].xyz, dir) * radial, 0)
```

`dir` 与主射线共用 `rt_camera.hlsli`。透视的 `clip.w = -view.z`，相机原点的 clip.w 为零，
因此直接计算相对方向的投影距离，避免先构造绝对世界点再投影造成消减误差；保留最终相机效果中的旋转与缩放。
无效像素写 `viewZ = 2 × DENOISING_RANGE`，法线取 `(0, 0, 1)`、粗糙度取 1，排除出降噪范围。

### 21.5 日志与验收

pack 由 native 帧入口拥有 PSO 与两张目标，降噪开启时增加一次 dispatch，尺寸随本帧 G-Buffer。
派发失败不发布任何当前帧输出，调用方直通原版；NRD 只消费已接受帧返回的 packed-normal 与 viewZ。
核验实际纹理的打包值、正 viewZ 与无效像素范围；画面噪点、闪烁与残影由用户判断。

### 21.6 门

| 门 | 覆盖什么 |
| --- | --- |
| `_port/hlsl_check.py` | `rt_nrd_pack.hlsl` 的 `cs_6_8` 编译 |
| `_port/rt_bindings_check.py` | 两个 SRV（`gNormalRoughness` / `gLinearDepth`）与两个 UAV（`gNrdNormalRoughness` / `gNrdViewZ`）的槽位顺序 |
| `_port/rt_shader_files_check.py` | Kotlin 里写的 `rt_nrd_pack.hlsl` 在包里真实存在 |

## 22. NRD 常驻上下文（S4 4-c）

NRD 的 14 条降噪管线不能每帧新建：`PipelineDesc` 里带的是库内编译好的 DXIL，根签名、纹理池、描述符堆
也必须与 `InstanceDesc` 逐字段对上。这一层做成常驻上下文，随设备与画面尺寸重建。

### 22.1 根签名四项从 `InstanceDesc` 抄

根签名的槽位、寄存器号、寄存器空间全部来自 `InstanceDesc`，写死任何一项都会让 `CreateComputePipelineState`
返回 `E_INVALIDARG`（不报哪一项不对）：

| 根参数 | 类型 | 寄存器 | 空间 | 来源字段 |
| --- | --- | --- | --- | --- |
| `[0]` | CBV | `b0` | `space1` | `constantBufferRegisterIndex` + `constantBufferAndSamplersSpaceIndex` |
| `[1]` | 描述符表（SRV） | `t0` 起 | `space0` | `resourcesBaseRegisterIndex` + `resourcesSpaceIndex` |
| `[2]` | 描述符表（UAV） | `u0` 起 | `space0` | 同上（NRD 的 SRV/UAV 共用一个空间） |
| `[3]` | 描述符表（采样器） | `s0` 起 | `space1` | `samplersBaseRegisterIndex` + `constantBufferAndSamplersSpaceIndex` |

今天读出来的值是 `spaces cb=1 res=0`、`registers cb=0 srv=0 smp=0`、`samplers=2`，与 `REBLUR_DIFFUSE_SPECULAR`
的约定一致；库升级后这些数字可能变，代码按字段读，不按常量写。

### 22.2 格式映射

`nrd::Format` 有 44 项（`Format::MAX_NUM` 是 43 之后的哨兵），必须逐项映射到 `DXGI_FORMAT`。前 40 项都是
常规 8/16/32 位格式，**40–43 是打包格式**：`R10_G10_B10_A2_UNORM` / `R10_G10_B10_A2_UINT` /
`R11_G11_B10_UFLOAT` / `R9_G9_B9_E5_UFLOAT`。漏掉这四项时症状是建池纹理失败：

```
nrd: context failed, create pool texture 0
```

映射错的症状相反 —— 不报错，降噪读到另一种精度，画面慢慢发糊。`D3D12RtNrd` 的清单给出 `ToDxgi` 的输入
（`nrd: pool permanent=13 transient=8`），改动格式后先看清单再跑。

### 22.3 重建时机

`denoiseRtFrame` 在派发 NRD 前调用 `mcdr_nrd_context_init`，
内部三条判据：设备指针、宽、高都没变就直接返回 `1`，否则退休旧上下文后重建
（纹理池按屏幕尺寸开）。分辨率变化必须重建，池纹理尺寸是建的时候定死的。

设备指针由 ABI 层给：`mcdr_dx12NrdInit` 在 `abi_device.cpp` 里用 `d3d12_mc::getDeviceHandle()` 取
`ID3D12Device*`，再转交给 `src/advanced/d3d12_nrd.cpp` 的 `mcdr_nrd_context_init` —— 后者是普通翻译单元，
拿不到 `gCtx`。

### 22.4 结果与失败定位

独立初始化接口成功时 `outText` 两行：

```
nrd: context ready, psos=14 pool=13/8 heap=900
nrd: cb max=912 samplers=2
```

失败时第一行就是失败的那一步，`%u` 是池索引或管线索引：

| stage | 含义 |
| --- | --- |
| `create instance` | `nrd::CreateInstance` 失败（denoiser 标识符或内存分配） |
| `serialize root signature` | `D3D12SerializeRootSignature` 失败 |
| `create root signature` | `CreateRootSignature` 失败（根参数与 `InstanceDesc` 不符） |
| `create pipeline state` | `CreateComputePipelineState` 失败（根签名与库内 DXIL 不匹配） |
| `create pool texture` | 池纹理建不出来（格式映射或堆属性） |
| `create descriptor heap` | 描述符堆建不出来（`setsMaxNum × perSet` 超出堆上限） |

`mcdr_dx12NrdInit` 返回 `0`（`nrd: not built in`）表示这份 DLL 没编进 `MCDR_HAS_NRD`；返回 `-1` 表示上面某一步
失败。`outText` 的容量由调用方给，native 帧调用时为 2048 B，写满截断不报错。
生产帧只在状态变化时报告 `rt NRD: initialization failed` 或 `rt denoise: NRD (31 passes)`。

### 22.5 门

| 门 | 覆盖什么 |
| --- | --- |
| `_port/abi_layout_check.py` | `MCDR_ABI_VERSION` 与新增入口的三处声明一致 |
| `_port/ffm_call_sites_check.py` | `MCDR_ABI.kt` 里新增的两个 MethodHandle 都有对应调用点 |
| `_port/rt_pipeline_release_check.py` | compute 管线使用资源销毁入口；帧/SDK 寿命由 native GPU 探针验证 |

本次改动不改变可见像素：控制台多两行，`rt_stats.json` 没有新增列。

## 23. NRD 逐帧派发（S4 4-c）

### 23.1 一条派发链要做什么

`nrd::GetComputeDispatches` 返回的是「这一次降噪要跑哪些 pass、每个 pass 绑哪些资源」。集成方负责建资源、建视图、绑根签名与派发，NRD 自身不分配任何资源。

固定顺序（顺序错了不报错，只会得到空结果）：

1. `nrd::SetCommonSettings`，把本帧的矩阵、尺寸、运动矢量缩放写进实例。
2. `nrd::GetComputeDispatches`，取回 `DispatchDesc` 数组。**反过来的话 `gridWidth/gridHeight` 全是 0**，派发等于空转。第三个参数是降噪器的 identifier 表，本工程的实例只有一个降噪器，传 `const nrd::Identifier ids[1] = {0}` 与条数 1；**不能传 `nullptr, 0`**（见 §23.2 第 8 条）。
3. 逐个 `DispatchDesc`：按 `pipelineDesc.resourceRanges` 里的段序把 SRV/UAV 视图连续摆进一张着色器可见堆（SRV 段在前、UAV 段在后），再 `SetComputeRootSignature` / `SetPipelineState` / `SetDescriptorHeaps` / `SetComputeRootDescriptorTable` / `SetComputeRootConstantBufferView` / 屏障 / `Dispatch`。

根签名的四个参数与 `InstanceDesc` 一一对应，见 §22.1。`DispatchDesc.constantBufferData` 是可选的每 pass 常量，按 256 字节对齐放进一块 upload 缓冲并逐次偏移。

### 23.2 六个真实踩过的坑

- **清理的 CPU 句柄必须来自非着色器可见堆**：`ClearUnorderedAccessViewFloat` 的 `UnorderedAccessViewCPUHandle` 若取自 `D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE` 的堆，整条清理会被静默丢弃（调试层只报 `points to a descriptor heap type that is CPU write only`）。清理用堆单独建一张 `FLAG_NONE` 的，GPU 句柄仍从可见堆取。
- **描述符堆要在任何 Clear/Copy 之前绑定**：命令列表还没 `SetDescriptorHeaps` 时清理同样被丢弃，报错文本是 `needs to be from the descriptor heap currently set`。
- **同一张可见堆内 CopyDescriptorsSimple 不可靠**：从可见堆往可见堆拷视图时调试层报 `SrcDescriptorRangeStart points to a descriptor heap type that is CPU write only`，拷完目标槽仍是空描述符。改为直接把视图建到目标槽后，调试层消息从 75 条降到 0 条。
- **写入的资源必须停在 `UNORDERED_ACCESS`**：UAV 写入的资源若还停在 `NON_PIXEL_SHADER_RESOURCE`，写入被静默丢弃（池纹理留 0、降噪输出全 0，调试层也不报）。每个 pass 之前按上一状态逐个过渡。
- **同一个 pass 里既是 SRV 又是 UAV 的资源要按 UAV 处理**：`OUT_DIFF` 这类资源写完又被下一步当输入读，若最后停在 SRV，UAV 那次写入被丢弃。合并角色时「有 storage 用途就按 UAV」。
- **读回的状态要按记录表取**：探针里读回 `mv` 时写死 `NON_PIXEL_SHADER_RESOURCE`，而它在上一次派发里被当作 UAV 写过，调试层立刻报状态不匹配。按同一张状态表取值后消息归零。

另外两个必需项：NRD 的 2 个采样器（`NEAREST_CLAMP` / `LINEAR_CLAMP`）要自己建并绑到参数 `[3]`，空采样器表会让降噪读到 0；`CommonSettings` 的 `viewZScale` / `denoisingRange`（500000）/ `disocclusionThreshold`（0.01）没有隐式默认值，零初始化会让整屏被当成超距。

7. **每段常量的步长必须是「对齐后的段长」**：`DispatchDesc.constantBufferDataSize` 常是 896 字节，而 `D3D12_CPU_DESCRIPTOR_HANDLE` 要求的 256 对齐只是**起点**要求。若把步长写成 256，后一段的 `memcpy` 会盖掉前一段的字节，所有 pass 都读到别的 pass 的常量（表现为降噪输出只有一条 tile 行有值或全 0，调试层 0 条消息）。正确步长是 `align(desc->constantBufferMaxDataSize, 256)`，实测本库为 1024。

8. **identifier 表不能留空**：`GetComputeDispatches` 在 `identifiersNum == 0` 时返回 `SUCCESS` 且 `dispatchDescs = nullptr`、`dispatchDescsNum = 0` —— 看着成功，实际一个 pass 都没跑。必须传实例自己的降噪器 identifier（本工程只有 `0`），并把「返回码不是 `SUCCESS`」「指针为空」「条数为 0」三种都当失败写进 `outText`；症状是每帧 `nrd: denoise unavailable (-1), no context`，而上下文明明已经就绪。

9. **每个 `Dispatch` 之后要一道 UAV 屏障**：`DispatchDesc` 里没有屏障信息，同一帧的 pass 之间纹理状态不变，D3D12 不会替 UAV 写入排序；NRD 自己的集成层在每次派发前插一道 storage 屏障，我们照做（`D3D12_RESOURCE_BARRIER_TYPE_UAV`，全资源范围）。少了它，后一个 pass 可能读到前一个 pass 写之前的像素。

10. **SRV/UAV 的格式取资源自己的**：视图格式与资源不符时调试层拒建视图、描述符留空，pass 读到垃圾且不报错。池纹理与输入纹理都按 `GetDesc().Format` 建，别按 `ResourceDesc.type` 猜。

11. **`SetCommonSettings` 的返回值不能丢**：参数非法时它返回 `INVALID_ARGUMENT`，但设置照旧生效 —— 只看内容不看返回值根本发现不了。`disocclusionThresholdAlternate` 与三个材质 ID（`strandMaterialID` / `cameraAttachedReflectionMaterialID` / `historyFixAlternatePixelStrideMaterialID`）留 0 就是非法值，本工程统一写 `999`；Reblur 拿 `NR.a` 与它们比较，0 会命中每一个像素。

### 23.3 当前状态

`REBLUR_DIFFUSE_SPECULAR` 已接入帧内，成功派发后将双通道结果交给合光。
首次成功日志中的 pass 数与 missing 数只证明派发与资源映射成功；画面质量仍须在世界内验收。

游戏外回归覆盖两类问题：

- Compute 描述符探针：3 个 encoder，各自 3 张在途列表，每张使用 600 个描述符，10,800 条记录全部匹配，调试层错误为 0。
- NRD 槽位与彩色信号探针：2,048 次派发、256 次忙槽拒绝。diffuse/specular 各 2,048/2,048 像素解码后分别接近线性 RGB `{0.9,0.4,0.3}` / `{0.2,0.7,1.1}`，调试层错误为 0。两路颜色不同且含负 chroma，能够发现通道交换或复制。
- 同一 NRD 探针交替开关 SDK `enableValidation`，1,024 次启用派发均包含独立 validation pass。
  常态派发数比相邻关闭帧多 1；诊断图 2,048/2,048 像素有限且 alpha 合法，存在非透明输出。
  SDK validation 是诊断图，不是错误计数；该检查不代替画质验收。

已知限制：`NRD_SR` 的独立 SR 依赖所选后端的运行库，不可用时降噪结果由合光插值到显示尺寸；
Streamline DLSS-D 不可用时回退 NRD。长时间运行、转动相机、窗口缩放以及噪点/鬼影的最终验收尚未闭合。

### 23.4 引擎侧的接口（S4 4-c）

- 实际 RT 帧调用 `mcdr_dx12NrdDenoiseTemporal`，接收 encoder、七张纹理、四个矩阵、独立偏移、帧号与 `resetHistory`。纹理顺序固定为 `motion / normal-roughness / viewZ / diffuse-in / diffuse-out / specular-in / specular-out`。
- 四个矩阵按列主序依次为当前投影、上一帧投影、当前世界到视图、上一帧世界到视图，共 256 字节。前两块不得含世界到视图变换。上一帧投影由上一帧视投影乘上一帧视图的逆矩阵恢复；读完所有上一帧输入后才更新输出数组，允许输入/输出共用历史数组。
- `nrdDenoise` 将零基索引 4/6 的输出纹理转为 `UNORDERED_ACCESS`，其余输入转为 `NON_PIXEL_SHADER_RESOURCE`，派发后回到 `COMMON`。永久池与瞬态池由 NRD 后端逐 pass 跟踪状态；同一 UAV 连续写入也要排序。
- NRD 的 8 个描述符/常量槽按实际 encoder 的 completion fence/value 回收，不按帧号取模覆盖。全部槽位仍忙时拒绝本次派发；重建或销毁上下文后，旧上下文保留到所有提交完成。
- 通用 compute / DXR 绑定与 VRS 的描述符按 encoder 的 command-list slot 分页分配，每页 256 个。一次录制只递增，不回绕；列表槽复用前等待该槽提交的 fence，之后才从第一页重新分配。
- 原版光栅与 G-Buffer 重放的 UPLOAD CBV 在 native `pushDescriptors` 录制时复制到列表槽持有的上传页；mesh b8 独立入口同样复制，包含重放相机偏移。CPU 后续覆写不会改变已录制的常量。页游标只在所属槽 fence 完成后复位；DEFAULT 堆常量仍按 GPU 命令顺序读取，上传 SRV 与 SBT 不在该快照范围内。CBV 偏移必须 256 B 对齐，长度不得超过剩余范围或 64 KiB；b8 复制剩余至多 256 B。
- JVM 瞬时映射常量和 staging 共用 512 KiB 起的上传块，按请求对齐且不回绕覆写。分配前结算异步提交并打开列表，保存当前 encoder fence 加一作为该切片的目标值；块复用和延迟队列释放均检查真实完成 fence。不能用 `rotate()` 次数代替 GPU 完成，不能在 native 录制快照之前覆盖已返回的源切片。具体规则见 [瞬时内存](../misc/backend-and-device.md#42-分块与回收)。
- BLAS 的 vertex/index 与 DEFAULT 堆 TLAS instance 输入在构建前转为 `NON_PIXEL_SHADER_RESOURCE`，scratch 转为 `UNORDERED_ACCESS`；UPLOAD instance 输入保持 `GENERIC_READ`，AS 结果保持 AS 状态。
- 双 lobe 的池、PSO 与描述符需求按 NRD `InstanceDesc` 动态生成；资源映射同时包含 `IN_SPEC_RADIANCE_HITDIST` 与 `OUT_SPEC_RADIANCE_HITDIST`。
- native 内部帧可提供 `validationOutput` 以启用 SDK validation 并映射 `OUT_VALIDATION`；公开七纹理入口保持该指针为空。独立探针负责诊断纹理的 UAV 状态与在途生命周期，默认游戏路径不额外派发 validation。

ABI 另提供 `mcdr_dx12NrdDenoiseTemporal`，在同一组输入上增加独立的四 float 偏移数组：
当前 x/y、上一帧 x/y，按 NRD 的渲染像素采样偏移约定直接传入 SDK。
矩阵保持无 jitter；不能把偏移追加到原入口的 64-float 数组之后。
四个分量必须有限且在 `[-0.5,0.5]`；空指针或非法值在接管纹理前返回 0。
FFM 对应 `MCDR_ABI.D3D12NrdDenoiseTemporal`，检查 256 B 矩阵与 16 B 偏移。
实际 RT 帧按共享 Halton `(Jx,Jy)` 传入 NRD `(-Jx,+Jy)`，上一帧同式。
原 `mcdr_dx12NrdDenoise` 保持零偏移兼容入口。

离线执行 `python _port/rt_nrd_temporal_check.py --jitter-interface`：
记录八帧原入口与八帧显式零偏移入口的两路完整读回差异，仅作图像诊断；
八组非零/边界偏移须成功求值且输出有限；29 次非法输入须在接管前拒绝，保持资源状态及累计计数。
该检查只验证接口与收尾，不验证非零采样投影、偏移符号或画质。
GPU 总门使用 `--jitter-interface`，通过标记包含 `engineering checks passed` 与 `jitter_interface=passed`。
`--highlight` 保留为单独运行的可选图像诊断。

### 23.5 日志

首次派发成功以 INFO 记录 pass 数与缺失资源数。pass 形状、输入尺寸等细节使用 DEBUG。
失败时记录精简诊断，帧内清空降噪输出并退回原始辐亮度；下一次成功调用重置历史。
日志和有限时长探针通过均不代替世界内目视验收。

### 23.6 辐亮度纹理化与帧内调用（S4 4-c）

`RtNrdRadiance` 将追踪网格的缓冲转换为降噪输入纹理：

- `t0 gDirect / t1 gIndirect / t2 gAux` 每条记录均为 16 字节；`t3 gViewZ` 读取 render 尺寸的视空间深度。
- `t4 gAlbedo / t5 gEmissive` 提供主表面材质，仅在线性 RR 模式中重调制为最终颜色。
- `t6 gSpecular / t7 gSpecularGrazing` 读取镜面 Schlick 系数，`t8 gSpecularMaterial / t9 gNormalRoughness` 提供 F0 与线性 roughness。
- `aux.x` 是相机主射线距离，`aux.y` 是太阳可见性，`aux.z` 是第一次 diffuse 弹射的距离。弹射打空写 `1e9`，归一化后为 1；`aux.w` 是主命中材质 ID 的原始 uint 位。
- `u0 gRadiance / u1 gSpecularRadiance` 均为 `RGBA16_FLOAT`。REBLUR 分别存解调 diffuse/specular 的 YCoCg；DLSS-D 的 u0 输入为 `albedo * irradiance + specularColor + emissive` 的带噪线性 RGB。
- `u2 gSpecularHitDistance` 为 `R32_FLOAT`，写世界单位首次反射距离；无效距离与天空主像素写 0，反射打空写 `1e9`。RR 读取这张独立纹理，禁止传入 NRD 的归一化 alpha。
- Diffuse 的归一化距离为 `saturate(aux.z / (3 + abs(viewZ) * 0.1))`，对应默认 REBLUR hit-distance 参数与 diffuse roughness=1。不得用 `denoisingRange` 作除数。
- Specular 使用独立反射距离，除数为 `(3+abs(viewZ)*0.1)*lerp(20,1,(1-exp2(-200*roughness²))*sqrt(roughness))`，与 NRD 默认参数一致。
- `CommonSettings.denoisingRange` 与 JVM 常量均为 500000。无效 G-Buffer 像素写两倍该范围的 viewZ，并使用有限的备用法线；零深度不会被 NRD 当作天空。

native 帧入口先追踪并补写主命中材质，再产生 motion 与 packed guides，最后纹理化辐亮度。
`signalEncoding=1` 写 REBLUR 输入，`2` 写线性 RR 输入；`0` 跳过三次降噪输入 compute。
降噪入口核对 frameId、encoder 和未提交的列表；RR 不可用时在 native 内重编码同帧 YCoCg，再派发 NRD。
回退失败不调用 NRD。只有成功的 SDK 输出才交给合光。
NRD 两张输出、显示尺寸的 RR 输出、输入与编码 PSO 均由 native 帧持有。

native 输入录制失败不发布当前帧，调用方直通原版。`denoiseFrame()` 再检查本帧六张输入句柄；
缺失输入或回退编码失败则停止本帧降噪及后续独立 SR，合光读取本帧原始辐亮度；禁止仅凭缓存纹理非空继续提交 SDK。
失败帧不确认历史消费者，下一次成功调用先 reset，之后连续帧继续累积。

合光第七槽 `t6 gDenoised` 绑定输出纹理。根常量 `g_denoiseEnabled` 为 0 时读原始缓冲，
为 1 时读取已包含主表面材质的线性 DLSS-D 输出，禁止再次乘 albedo 或叠加 emissive；
为 2 时分别解码 diffuse 与 `t10 gDenoisedSpecular` 的 YCoCg，再乘 diffuse albedo 与 `max(F0,0.02)`，叠加 emissive。
采样坐标按降噪纹理自身的尺寸映射，不沿用 trace 索引。
相机半径淡出始终使用 `aux.x` 的插值值，禁止把降噪后的 alpha 当作相机距离。

`denoise_ms` 使用 `D3D12RtStats.SLOT_DENOISE` 计时。没有降噪的帧在追踪结束后补打该时间戳。

### 23.8 适配检查与验收（S4 4-f）

`_port/rt_nrd_adaptation_check.py` 检查 14 条源码契约，`--selftest` 覆盖错误单位、空间、深度分量与接线缺失：

| 契约 | 判据 |
| --- | --- |
| 深度 | viewZ 使用世界单位，`viewZScale=1` |
| 无效像素 | JVM/native 范围一致，天空 viewZ 超过范围，禁止归一化零法线 |
| 颜色与距离 | REBLUR 编码/解码 YCoCg，diffuse 使用 secondary hit distance，相机淡出使用主射线距离 |
| 运动矢量 | 二维屏幕像素位移，`motionVectorScale={1/w,1/h,0}`、`isMotionVectorInWorldSpace=false`；NRD 从矩阵推导 viewZ 变化 |
| 历史 | 统一 `InvalidationReason` 位掩码与逐消费者确认，常态继续累积；见 §23.7 |
| 资源状态 | 五张输入 SRV，两张输出 UAV，完成后回 COMMON |
| 双 lobe | 实际双通道实例、specular 资源映射、独立命中距离与可逆 F0 解调 |
| RR 反射 | 八张纹理、世界单位距离标签、diffuse/specular seed 随实际帧号更新 |
| 轨道闸门 | OFF / RAW 早退，每帧先清空上次输出 |
| 抖动 | 无 jitter 矩阵与真实运动 MV，NRD 采样偏移为 `(-Jx,+Jy)` |
| 附件数 | G-Buffer 附件固定 5 张 |
| 粗糙度 | 打包下限为 `1.5/512` |
| 合光坐标 | 按降噪纹理自身尺寸计算采样位置 |

纯 CPU 矩阵回归覆盖相机平移、旋转、FOV 变化、历史数组别名，以及摇晃平移/旋转下的
18 个屏幕采样点、径向深度、运动矢量、正反 Z 投影与太阳四象限；GPU 彩色信号回归检查有色辐亮度与负 chroma。
实际 `rt_motion.hlsl` 的 GPU 回归使用独立解析场景，覆盖静止、相机平移/旋转、
FOV 变化与当前相机摇晃平移；105 个像素检查验证像素单位、屏幕 Y 方向与零/负深度清零。
这些检查只覆盖可自动判定的契约。噪点、鬼影、黑块、条纹与材质纹理须由用户在世界内确认。

`python _port/rt_guide_failure_check.py` 调用实际 JVM `denoiseFrame()`，在缓存已分配时，
分别提交缺少 motion、packed-normal、viewZ、diffuse signal、specular signal、反射距离的帧输出，覆盖 `NRD_SR` 和 `RR` 共十二组。
要求失败帧不调用 SDK、不发布降噪输出，恢复帧 reset，下一连续帧不 reset。
测试 fixture 通过产品 HLSL 准备有效 guide 和信号，native 帧录制、回退编码与寿命由 `_port/rt_native_frame_check.py` 验证。
native 降噪结果与历史消费者由 fixture 模拟，回退 fixture 执行产品编码 HLSL；
该检查证明 JVM 的输入拒绝和输出发布，不能据此认定 native 历史、SDK 求值或画质通过。
报告记录十二次拒绝、36 次截获调用及源码/字节码/运行库哈希；该检查纳入 `rt_check_all.py --gpu`。

同一探针的 `--routes` 运行实际 `denoiseFrame()` 与 `upscaleDenoisedFrame()`，覆盖四条降噪轨道、
四个 SR provider 和 100%/67%/50% 三种尺寸，共 48 个组合；另覆盖 RR 失败回退、NRD 失败恢复、
SR 求值失败、重调制失败和 RR 回退重编码失败后的抑制/恢复，共 19 组恢复路径、255 帧入口调用。
它核对提供各类 native 结果时的 SR 调用、输出发布尺寸、SR reset 与 raw fallback；native 结果与 SR 求值均使用 fixture，
不作图像质量或真实 SDK 画质验收。该运行已加入 `rt_check_all.py --gpu`。

`python _port/rt_nrd_temporal_check.py` 经引擎七纹理 ABI 执行实际 NRD 对照，
128×64、每组 24 帧、219 次 NRD 提交及一次编码 compute，逐帧等 fence 并读回双通道：
GPU 总门使用 `--jitter-interface`，检查新旧入口实际求值、非零偏移与非法输入拒绝。
`--highlight` 是可选的过滤后高光诊断，额外执行 864 次 NRD 求值，不作为默认阶段门。
表中的图像阈值是该探针的固定场景配置，用于诊断同一 SDK；不要求 NRD 与 RR 或其他降噪器达到同一输出，
也不以同一 SDK 的历史路径或编码负例达到固定图像差异作为阶段门。
输入编码、单位、资源安全与真实求值按工程约束核验；滤波后的高光和运动质量由用户分别实机判断。

| 项目 | 输入对照 | 当前脚本诊断配置 |
| --- | --- | --- |
| 深度 | 倾斜平面与轴向相机平移；viewZ 对比误喂 deviceZ/reverse deviceZ | 正确输入相对对比 RMSE 小于 1%；负例超过 2% 且 MSE 至少为正确输入 4 倍 |
| 法线空间 | 相机 0/0.45 rad 交替旋转；世界法线对比视图法线 | 视图法线的平均 MSE 超过世界法线 2 倍 |
| MV | 世界位置辐亮度渐变；解析像素 MV 对比反号和归一化 UV MV | 两个负例的平均 MSE 均超过正确像素 MV 2 倍 |
| 历史 | 预热后改变信号；reset 对比新建上下文首帧 | 同一 SDK 帧号及输入的最大差小于 0.002，CONTINUE 与首帧可区分 |
| 粗糙度编码 | 产品 pack 对比官方 pack/unpack，七档 roughness 对比平方与开方误用 | 8,192 样本匹配法线/粗糙度；GGX NDF 高光半宽与解析解一致，错误输入半宽至少相差 1.5 倍 |
| 过滤后高光 | roughness 0.2/0.35/0.5、三个 seed、32 帧；只改变粗糙度 guide 编码 | 中央八行镜面首通道（Y，输入为灰度）平均 profile 的 FWHM：正确九组相对误差均小于 5%，且随粗糙度递增；平方/开方误用的聚合宽度 MSE 各超过正确编码 2 倍，至少一组宽度相差超过 1 pixel |

深度/MV/法线的平均 MSE 为两路均值，单路数值另行记录，不能宣称每一路负例同等敏感。
报告写入工作区 `_tmp_s4_nrd_temporal.json`，包含 DLL SHA-256、设备、参数、阈值与结果，
并由 `rt_check_all.py --gpu` 调用。编码部分使用官方 HLSL 解码与解析 GGX 半宽；
过滤后高光另增加 864 次 NRD 求值与三次原始参考 compute，逐组重建上下文并匹配 SDK 帧号和噪声。
CPU 参考与无噪声 GPU 原始 profile 的最大差须小于 0.005，报告保留完整 profile、
阈值、探针/HLSL 哈希与 `filtered_highlight.measurement_complete`。
过滤后的宽度、随粗糙度递增趋势、负例倍数与新旧入口颜色差写入 `image_diagnostics`，不决定工程通过状态。
过滤后判据只覆盖 FWHM，不能外推到峰值、能量或世界内高光质量，也不要求每档错误编码都更差。
该测试不覆盖 RR 的 SDK 负例、非零 jitter 或曝光对照。

### 23.7 历史累积与复位（S4 4-e 的一部分）

`D3D12RtHistory` 是 RT 历史失效的唯一状态管理者。每次实际派发前，将同帧相机、世界时钟、
实际镜像版本、设备/guide 身份与当前 Plan 生成一份 `Snapshot`；`begin()` 合并显式请求与比较结果，
输出 `Invalidation(generation, frame, reasons)`。`reasons` 为 `InvalidationReason.mask` 的按位或，
除 `GEOMETRY` 外的复位原因增加 generation；观察帧号独立递增。`resetsHistory` 同时决定是否丢弃上一相机。

开启 `[rt] invalidationLog` 后，实际复位输出 `rt history: reset=0x… frame=…`。
同一配置还启用 §28.3 的异步有效性采样；没有 reset 日志不能证明逐像素输入与时域历史正常。
区块绘制与 G-Buffer 重放共用提取时的 meshlet 快照，不能在绘制阶段改查最新 section 元数据；
区块卸载或替换仍可能发生在提取之后，必须让旧批次完成，下一帧再采用新网格。见 mesh 文档 §2.3。

| 原因 | 触发条件 |
| --- | --- |
| `FIRST_FRAME` | 尚无上一份快照或资源释放后重新开始 |
| `RESIZE` | 显示、render 或 trace 尺寸变化 |
| `CAMERA_CUT` | 相机实体、第一/第三人称模式或用户 FOV 设置切换；单帧位移大于 16 m；投影宽高比或深度系数相对变化超过 10%，或类型/偏移突变；相机数据无效 |
| `WORLD` | 关卡对象身份或 `D3D12SectionGeometry.epoch()` 变化 |
| `GEOMETRY` | 本帧采用的场景版本变化，仅记录原因；局部几何更新不清空全屏历史，guide/disocclusion 处理受影响像素 |
| `MATERIAL` | 本帧材质快照的重烘/清空代际变化；追加新材质条目不重置已有表面历史 |
| `TIME_JUMP` | 同一关卡 default clock 倒退，或相邻观察帧前进超过 200 ticks |
| `DEVICE` | 设备对象改变，或 RT 上下文释放后重建 |
| `FRAME_GENERATION` | FG 选中后端、模式或倍率变化 |
| `PIPELINE` | RT 开关、降噪轨道、独立 SR 后端或 实际 trace backend 变化 |
| `RESOURCES` | G-Buffer albedo 附件对象改变；五张附件按组重建 |
| `FRAME_GAP` | 相邻观察帧时间戳不递增，或实际呈现帧编号不是上一编号加一；此前派发未成功 |
| `EVALUATION_FAILURE` | motion、packed guide 或辐亮度纹理化失败，或 NRD 求值未成功；下一帧保留复位请求 |

普通行走、摇晃、快速转向与动态 FOV 变化保持历史，新显露区域由 motion/guide/disocclusion 拒绝旧样本。
投影的两轴正焦距同比缩放不触发复位，包括长帧后疾跑、飞行或缩放效果一次变化超过 10% 的情况。
用户 FOV 设置由 `options.fov().get()` 单独捕获；改设置仍复位。禁止只按 `m00`/`m11` 的变化判断相机切换。
连续呈现帧耗时变长、失焦暂停后恢复本身不清空历史；真正漏过 RT 帧、相机切换、传送与资源变化仍复位。
开启 `raytracing.invalidationLog` 时，`CAMERA_CUT` 同时报告 entity、mode、fov-setting、position、basis 或投影原因，
用实际触发条件区分相机切换与投影突变，不能把所有 `0x4` 都解释为快速转头。
投影快照复制数组，禁止与下一帧写入共用可变数组。
相机、重放校验、历史失效和曝光时间共用 `D3D12Temporal.frameTimestampNanos`：按呈现帧身份缓存
`System.nanoTime()`，同帧多次读取保持不变，下一帧重新取样。禁止使用 `Minecraft.frameTimeNs` 作为时间戳；
该属性是上一帧的耗时，帧率变化会使其下降或重复，错误触发 `FRAME_GAP` 和曝光复位。
几何镜像的位移检查必须先于版本命中返回；相机移动超过镜像余量 24 m 时，即使版本未变也重新选择附近几何。
先按距离选择镜像范围，再按 section/layer 固定记录顺序，避免同一组记录因距离排序变化而重传。
镜像缓冲由 native 在当前主列表上上传，禁止为每块镜像创建临时 encoder 并提交等待。
未变化的几何或材质缓冲由 native 独立共享；native AS 上下文强引用几何快照并按记录构建或共享 BLAS。
待构建快照在同世界、材质重烘代际未变且位移未超过 24 m 时保持到构建结束，合并期间到达的更新，避免每帧丢弃已建 BLAS。
native 场景入口将已发布及刚完成的快照与同锁捕获的全池版本表比对，屏蔽缺失或版本变化的 section；
不得为了保留降噪历史重新显示已破坏的旧几何。版本表代际在同世界不得回退，相同代际的内容与顺序必须保持不变。

世界身份按对象引用比较，禁止使用 identity hash 代替身份。`D3D12SectionGeometry.observeLevel()`
由客户端 tick、上传入口和实际派发共同观察；世界变化时在同一注册表锁内清空 section 元信息、
RT 顶点/索引/逐 quad 材质池与材质间接表，推进 `epoch` 和材质 revision。
即使新世界没有上传几何，退出到菜单或换世界也会清空 CPU 数据；null 世界的上传不发布记录。
上传的版本计算与池发布共用注册表锁，池切片在自身锁内一并捕获 `geometryEpoch/geometryRevision`。

`D3D12RtFrame` 单独记录已观察的 epoch，使上传先发现换世界时，下一次 tick/派发仍执行 GPU 镜像失效。
镜像只能在世界对象及 epoch 同时匹配时复用；跨世界不受镜像节流和无新切片时返回旧镜像的路径影响。
本帧历史读取实际切片携带的 epoch/revision，禁止把新全局 epoch 与旧镜像混成同一快照。
世界变化使当前帧结果和上一相机失效，撤销候选/已发布 AS 与几何令牌；
已录制命令持有的资源由 native 实际列表槽 fence 保活。新世界的 AS 从新镜像重新构建，不共享旧世界 BLAS。
世界变化后材质表在下一次 tick 重烘，几何不足时允许重新触发原版上传。

native 的 RR、NRD 与独立 SR 各持有一个历史消费者。generation 改变或该消费者漏过上一观察帧时，
`requiresReset()` 返回 true；只有 SDK 接受求值且该阶段产出纹理后才 `accept()`。
未执行的消费者不消耗复位，失败或暂时绕过后恢复仍必须 reset。RR 成功后不执行 NRD/SR，
因此随后进入 NRD 回退时也不会复用间断历史。

NRD 用该结果选择 `CLEAR_AND_RESTART` / `CONTINUE`，RR 写入 `sl::Constants.reset`。
独立 SR 合并 Plan/尺寸/后端变化、历史代际/帧间断与 SDK 归属切换，分别传给 DLSS viewport 2 和 XeSS/FSR dispatch；
禁止只清 NRD 而让 SR 继续累积旧帧。所有 SDK 帧号仍递增，历史 generation 不代替 SDK token。
失效帧清除上一视图的有效标志，再写入同源相机矩阵，使该帧重投影不引用旧视图。

纯 CPU 回归覆盖必要复位事件、普通行走/摇晃/快速转向、16 m/200 ticks 边界、长帧与聚焦暂停、
动态 FOV 与几何更新同时发生、用户 FOV 设置、非法焦距、可变投影数组、三个延迟或跳过的消费者，
以及合并失效与释放后的代数单调性；源码契约门另检查三处消费与独立 SR 参数传递。
这些检查验证产品状态机，不代替逐原因的实际 SDK/世界内验收。
当前 FG 覆盖配置切换，尚未接入 native FG 内部 reset 回调；未来缓存/Reservoir、光谱配置和模型版本
仍需把各自事件接入相同位掩码。离线 `rt_world_geometry_check.py` 使用已编译 JVM 类检查
同名不同世界对象、同坐标新几何、空世界、退出到菜单、128 次双线程上传与 AS 延迟回收状态，已纳入静态总门。
默认 AS 回收检查使用合成句柄验证状态转换。追加 `--gpu` 时，通过实际 `D3D12Device`、FFM ABI
与产品 `D3D12RtBlas.prepare()` 建立 BLAS/TLAS：两个世界在同一 section 分别放置 4 m/9 m 深度的 quad，
交替切换后用 RayQuery 读回命中距离。四组各三次提交，队列等待独立 CPU fence；另一个队列 marker
确认提交尚未执行，此时失效旧 AS、关闭输入缓冲并切到空世界，然后放行并等待各 encoder 的提交 fence。
12 次读回均须匹配各自的 4 m/9 m，误差预算 `1e-4`，并要求 D3D12 InfoQueue corruption/error 为零。
这覆盖三张在途列表与产品 AS 失效/回收路径，不包含游戏窗口、完整帧降噪或世界内画质验收。

GPU 探针仅创建隐藏 Win32 窗口，不启动客户端。运行目录在工作区 `_tmp_s4_world_geometry_probe`，
classpath 首位放所选 DLL，防止已打包资源覆盖开发 DLL；结束时比对所选与实际 DLL 的 SHA256。
`_tmp_s4_world_gpu.json` 保存逐提交 epoch/fence/预期与实际距离、设备/驱动、调试层错误，
以及 DLL、运行库、探针、产品源码与 HLSL 哈希。失败启动先覆盖为 `passed=false`，禁止遗留上轮通过报告。
该检查已纳入 `rt_check_all.py --gpu`；真实世界切换、长时间运行与未来缓存清理仍须分别验收。

### FFM 句柄签名必须与 native 原型逐项一致

`MethodHandle.invokeExact` 的参数类型必须与 `downcallHandle` 时给的 `FunctionDescriptor` 完全一致，
差一个类型或一个参数都会在**调用时**抛 `WrongMethodTypeException`（编译期没有任何提示），
而包装层把它当成「功能不可用」打一行 warn 就降级了 —— 表现为 `nrd: not built in` 或
`rt RR: unavailable, using NRD` 这类回退日志。

- 首参数是 `mcdr_command_ctx*` / 设备指针的入口，描述符必须写 `PTR`，调用侧写 `ptr(ctx)`；
  传 `Long` 会在运行期抛异常，而不是自动转成指针。
- 参数个数要按 `mcdr_abi.h` 的原型数，多写一个 `I32` 同样是运行期才炸。
- 新增 ABI 入口时先对着头文件抄一遍描述符，再用 `_port/abi_layout_check.py` 与探针各跑一次。

## 24. DLSS Ray Reconstruction 的可用性（S4 4-d 的前置判定）

4-d 要接的是 DLSS-D（Ray Reconstruction）：它把带噪辐亮度加法线/粗糙度/深度/运动矢量一次吃进去，
直接吐出降噪并放大后的图像，与 4-c 的 NRD 是同一条轨道上的两个选择。接之前先确定本机能不能用。

### 24.1 判定用的两个能力键

NGX 的 RR 可用性不叫 `RayReconstruction.Available`，而是走 DLSS-D 自己的名字（`MCDR\external\dlss\include\nvsdk_ngx_defs_dlssd.h`）：

| 键 | 字符串 | 含义 |
| --- | --- | --- |
| `NVSDK_NGX_Parameter_SuperSamplingDenoising_Available` | `SuperSamplingDenoising.Available` | 本机是否支持 RR，1 表示支持 |
| `NVSDK_NGX_Parameter_SuperSamplingDenoising_FeatureInitResult` | `SuperSamplingDenoising.FeatureInitResult` | 特性初始化结果，NGX 的 `0x1` 是成功 |

拉能力参数的固定顺序与 `MCDR\src\advanced\d3d12_dlss.cpp` 一致：
`NVSDK_NGX_D3D12_Init_with_ProjectID` → `NVSDK_NGX_D3D12_GetCapabilityParameters` → 逐键 `NVSDK_NGX_Parameter_GetI`。

### 24.2 为什么必须放在独立进程里问

NGX 要求 `nvngx_dlssd.dll` 与进程同目录（它的加载器按进程目录找 DLL），而游戏进程里 NGX 由 Streamline 持有
——`MCDR\src\advanced\d3d12_dlss.cpp` 里写明：SL 拥有 NGX 时再调 `NVSDK_NGX_D3D12_Init_*` 会撞 `0xBAD00005`。
所以可用性用独立探针问，不进游戏：源码 `_port\ngx_rr_probe.cpp`、产物 `_port\ngx_rr_probe.exe`，
仅在 NVIDIA 适配器（vendor `0x10DE`）上创建设备，避免双显卡机器按 DXGI 顺序误选集显。
运行时把 `nvngx_dlssd.dll` 与 `nvngx_dlss.dll` 拷到它旁边，`_port\rt_check_all.py --gpu` 会自动做这一步。

构建（vcvars64；NGX 的导入库只声明 `msvcprt`/`MSVCRT`，所以必须 `/MD` 并屏蔽静态 CRT，另需 `advapi32`/`user32`）：

```
cl /nologo /std:c++latest /EHsc /O2 /MD ngx_rr_probe.cpp /I ..\MCDR\external\dlss\include ^
   /Fe:ngx_rr_probe.exe /link /NODEFAULTLIB:libcpmt /NODEFAULTLIB:LIBCMT ^
   ..\MCDR\external\dlss\lib\Windows_x86_64\x64\nvsdk_ngx_d.lib d3d12.lib dxgi.lib uuid.lib advapi32.lib user32.lib
```

### 24.3 本机实测

RTX 5070 Laptop，驱动高于最低要求：

```
ngx api version=21
device: NVIDIA GeForce RTX 5070 Laptop GPU
init: success (0x00000001)
capability parameters: success (0x00000001)
rr available=1 call=success
rr feature init result=1 call=success
sr available=1 call=success
needs updated driver=0 call=success
min driver major=470 call=success
min driver minor=0 call=success
```

结论：本机 RR 可用，4-d 按 DLSS-D 接，不必退到 `rt_plan.md` §6.4 的 FFX 备选。
游戏进程内走 Streamline 的 `sl.dlss_d` 插件（`Illusion\run\dxrmc\sl.dlss_d.dll` 已在运行目录），
接入时用 `sl::kFeatureDLSS_RR` 取函数，不要再自己调 `NVSDK_NGX_D3D12_Init_*`。

### 24.4 门

`_port\rt_check_all.py --gpu` 的 `ngx rr` 一步：探针缺失、或本机报不支持，都只打印 `SKIP`（那是机器与驱动的差异），
只有探针跑不起来才算 `FAIL`，避免把别的机器上的能力差异当成本仓库的回归。

### 24.5 游戏内那条能力行

设备挂接时（`MCDR\src\core\d3d12_device.cpp` 的 `applyStreamline`）打一行，把「插件支不支持、加载没有、
给定 1920x1080 输出该渲染多大」一次说完：

```
streamline: dlssd supported=1 load=0x0 optimal=1280x720 call=0x0
```

- `supported` 是 `slIsFeatureSupported(sl::kFeatureDLSS_RR, adapterInfo)` 的结果，0 表示这台机器的驱动/GPU 不支持。
- `load` 是 `slIsFeatureLoaded` 的返回码，插件加载成功时是 `0x0`。
- `optimal` 是 `slDLSSDGetOptimalSettings` 给的 `renderWidthMin x renderHeightMin`（`eMaxQuality`、输出 1920x1080）。
- 最后一个 `call` 是 `slDLSSDGetOptimalSettings` 的返回码。

三个条件都成全才允许走 RR 轨道；`kFeatureDLSS_RR` 已加进 `slInit` 的 `featuresToLoad`（与 DLSS、Reflex、PCL、DLSS-G 并列），
漏了它插件根本不会加载、`load` 会一直非零。

### 24.6 Streamline 进程里的实际判定

游戏进程内 NGX 由 Streamline 持有，独立 NGX availability 不能代替插件加载与实际求值。
`sl::Preferences` 必须与独立探测使用相同的非空 Project ID
`7c1f4a52-9b3e-4d68-8f21-5a6c0e2d9b74` 和 engine version `1.0`。
Streamline 在 engineVersion 非空时用 Project ID 查询能力，禁止只填版本而保留空 projectId。

设备日志分别报告 `supported`、实际 `loaded` 与 `optimalRenderWidth/Height`；
`sl::Result::eOk` 的数值为 0，不能将原始返回码直接当作 supported 布尔值。
实际闭合条件是 §25 中 `slSetConstants`、标签与 `slEvaluateFeature` 连续成功。
不可用时执行 NRD 回退，并保持“RR 未执行”的诊断，不能据回退成功判定 RR 已验收。

## 25. DLSS-D 逐帧求值（S4 4-d）

### 25.1 一帧的动作

求值在 native 的 `mcdr_dlssd_eval`（`MCDR\src\advanced\d3d12_dlss.cpp`）里，Kotlin 侧准备八张纹理：

1. 输入/输出尺寸变化时查询 `slDLSSDGetOptimalSettings`，选择包含实际输入宽高的动态区间。
   原生尺寸优先 `eDLAA`；缩放输入依次尝试 Quality、Balanced、Performance、UltraPerformance。
   没有合法区间时拒绝 RR 并回退 NRD。每帧下发相机视图正逆矩阵与选项，HDR 开启、alpha upscaling 关闭、preset F。
2. `slGetNewFrameToken` 使用持续递增的 RR 帧号。重置降噪历史时禁止将帧号归零；
   同一 viewport/帧号提交不同常量会得到 `eErrorDuplicatedConstants`。RT RR 固定使用 viewport 1。
3. 打标签（`slSetTagForFrame`），顺序固定：

| 槽 | `sl::BufferType` | 资源 | 生命周期 |
| --- | --- | --- | --- |
| 0 | `kBufferTypeScalingInputColor` | `rt-nrd-radiance`（带噪、已重调制线性颜色） | `eOnlyValidNow` |
| 1 | `kBufferTypeScalingOutputColor` | `rt-rr-output`（显示尺寸） | `eOnlyValidNow` |
| 2 | `kBufferTypeLinearDepth` | `rt-nrd-viewz`（R32_FLOAT，正的 viewZ） | `eValidUntilEvaluate` |
| 3 | `kBufferTypeMotionVectors` | `rt-motion`（像素单位） | `eValidUntilEvaluate` |
| 4 | `kBufferTypeNormalRoughness` | `rt-gbuffer-normal-roughness`（原始世界法线 xyz、roughness w） | `eOnlyValidNow` |
| 5 | `kBufferTypeAlbedo` | `rt-gbuffer-albedo` | `eOnlyValidNow` |
| 6 | `kBufferTypeSpecularAlbedo` | `rt-gbuffer-specular` | `eOnlyValidNow` |
| 7 | `kBufferTypeSpecularHitDistance` | `rt-specular-hit-distance`（R32_FLOAT，世界单位反射距离） | `eOnlyValidNow` |

4. `slEvaluateFeature(sl::kFeatureDLSS_RR, token, {viewport}, 1, list)`。

纹理与资源状态的对应关系是硬要求：输入必须停在 `NON_PIXEL_SHADER_RESOURCE`、输出停在 `UNORDERED_ACCESS`，
否则 SL 打标签成功但读到的内容未定义。`dlssdEval`（`MCDR\src\core\d3d12_device.cpp`）负责在调用前后做这组
过渡，返回后统一回 `COMMON` 并恢复绘制用的描述符堆。

### 25.2 法线、粗糙度与深度

DLSS-D 的 `normalRoughnessMode` 只有两种：`ePacked`（一张打包图）与 `eUnpacked`（法线 + 粗糙度分开）。
本工程使用 `ePacked`，直接传原始 G-Buffer `RGBA16_FLOAT`：xyz 为世界空间单位法线，w 为线性粗糙度。
禁止传入 NRD 的八面体编码纹理；RR 与 NRD 的 packed 含义不同。
Streamline DLSS-RR Programming Guide §4.1.3 同时允许世界与视图空间的单位法线。
验证本工程的世界空间约定时，构造与相机矩阵/所选空间不一致的负例；不能把视图空间本身判为错误。

深度传入已有 NRD pack 生成的正 viewZ，标签为 `kBufferTypeLinearDepth`，`depthInverted=eFalse`。
SDK 指南 §4.1.7 也支持 `kBufferTypeDepth` 硬件深度；必须按标签与反向深度标记配对验证，
不能把合法的硬件深度配置当作负例。
G-Buffer 深度附件没有 texture-binding 用法，不能作为 RR 的采样输入。
specular albedo 使用主命中材质 guide 生成的像素 F0；输入颜色包含独立 GGX 镜面贡献。
反射重投影使用世界单位 `specularHitDistance` 与本帧 `worldToCameraView/cameraViewToWorld`。
该距离为独立 `R32_FLOAT` 纹理，反射打空使用 `1e9`；禁止传入 NRD 的粗糙度归一化距离。
Diffuse 与 specular 的采样 seed 必须包含实际帧号，使静止画面能够积累不同弹射样本。

### 25.3 回退

`RR` 轨道上 SL 拒绝一次（返回 0 或负数）后，在当前 Plan 内停止重试，打一行
`rt RR: unavailable, using NRD`，这一帧起退回 NRD 的 `REBLUR_DIFFUSE_SPECULAR` 路径。
设备重建、光追关闭重开或 Plan 代际变化允许 native 上下文重新尝试。
Plan 包含轨道、超分方案和实际输入/输出/trace 尺寸；历史按失效事件复位，SDK 帧号继续递增。

### 25.4 日志

```
dlssd: eval 0x0 consts=0x0 2560x1334 -> 2560x1334 packed=1
```

六个字段：`slEvaluateFeature` 返回码、`slSetConstants` 返回码、渲染尺寸、输出尺寸、是否打包模式。
两个返回码分开看是关键：`eval` 非 0 而 `consts` 为 0 说明常量没问题、问题在标签或求值；`consts` 非 0
说明常量本身就过不去（多半是矩阵布局或必填字段）。

### 25.5 验收

进游戏把 `[rt] denoise` 设为 `RR`，日志里应出现上面那条 `dlssd: eval`；
`NRD_SR` 使用 `nrd: dispatch …`，`RAW` 不得派发 NRD 或 RR。
成功日志只报一次，须持续观察有无回退。实际执行 RR 后，在同一处暗面与高光表面静止观察，
确认离散 bounce 亮点随历史积累收敛，再行走、转动相机检查残影。噪点与残影由用户目视验收。

### 25.6 相机常量（`slSetConstants`）

常量在 Kotlin 侧由 `D3D12RtFrameConstants.write` 的 `outSlConstants` 生成，共 104 个浮点。
缺失或 `slSetConstants` 返回失败时立即停止 RR，不能带着无效相机继续求值。
`mcdr_dlssd_frame.constants` 透传到 `d3d12_dlss.cpp`，再由 `slSetConstants` 交给 SL：

| 偏移 | 内容 | 来源 |
| --- | --- | --- |
| 0 | `cameraViewToClip`（行向量、行主序） | 本帧投影矩阵的转置 |
| 16 | `clipToCameraView` | 本帧投影逆矩阵的转置 |
| 32 | `clipToPrevClip` | （上一帧视投影 × 本帧视投影的逆）的转置 |
| 48 | `prevClipToClip` | 上式的逆 |
| 64 | `cameraNear` / `cameraFar` / `fovY` / `aspect` | 投影矩阵的构造参数 |
| 68 | 当前屏幕内容偏移 xy、上一帧屏幕内容偏移 xy | 共享 Halton `(Jx,-Jy)`，首帧上一帧等于当前 |
| 72 | `worldToCameraView` | 当前视图矩阵的转置，行主序 |
| 88 | `cameraViewToWorld` | 当前逆视图矩阵的转置，行主序 |

全部六个矩阵都从 JOML 的列向量约定转换到 Streamline 的行向量约定。
相机位置读取转置后逆视图的最后一行，right/up/forward 从前三行读取（forward 取负 Z）；
pinhole 为零，`jitterOffset` 读取 68/69，motionVectorsDilated/Jittered 显式关闭。
68–71 四分量必须有限且在 `[-0.5,0.5]`，RR 与 RT DLSS SR 都在接管资源前校验。
视图正逆矩阵同时传给 DLSSDOptions，每帧更新。

三条硬约定：

- **行向量与行主序**。SL 的 `float4x4` 使用 `row[4]`，向量从左侧相乘，平移位于最后一行，
  见 `MCDR\external\streamline\include\sl_matrix_helpers.h`。`putStreamlineMatrix` 使用 JOML 的
  `get(float[], offset)`：原矩阵的列主序数组正好是数学转置后矩阵的行主序数组。禁止仅更换存储顺序而不转置变换。
- **共享亚像素采样**。RR/SR 偏移取 `(Jx,-Jy)`，SDK 矩阵不含 jitter；光栅/PT 使用采样矩阵，MV 移除偏移差。
  原版行走/受伤摇晃保留在视图正逆矩阵中，启用闸门见 §19.2。
- **运动矢量单位**。我们的 MV 是像素单位且 +y 朝下，所以 `mvecScale = {1/w, 1/h}`，
  与 NRD 的 `motionVectorScale` 同一口径（见 §23.8）。

验收时分别观察 `rr` 与 `nrd_sr` 的噪声、残影、闪烁、纹理/高光保留和运动后收敛。
两种方案允许滤波、锐度、噪声与收敛表现不同，由用户分别判断是否可接受；不要求两图接近或达到某种一致等级。
输入的深度/MV/jitter 单位、法线与粗糙度编码、曝光尺度以及资源生命周期仍须正确。

### 25.7 RR 离线 SDK 对照

离线数值用于检查 SDK 接线与辅助定位，不作为各降噪器之间的图像一致性门禁。
不以统一 MSE/FWHM、PSNR/SSIM 或错误输入必须退化两倍等标准决定 S4 是否完成。
下述脚本中的图像阈值是具体合成场景的诊断配置；工具的 INCOMPLETE 若仅来自这些指标，
表示该诊断未形成结论，不表示渲染功能失败。黑块、异常亮点、纯色材质、曝光错误、明显残影和资源/设备错误须分别处理。

总门使用的 RR core、NRD、共享 jitter 和曝光 ABI 报告按下表区分结果：

| 字段 | 含义 |
| --- | --- |
| `acceptance_basis` | `engineering_contracts`；只表示当前探针覆盖的工程协议检查 |
| `engineering_passed` / `passed` | 真实求值、输入与资源等检查的结果；工程错误仍返回非零 |
| `image_diagnostics` / `image_diagnostics_passed` | 合成图像指标及其预算结果；未测量时汇总为 `null`，未达到预算不改变工程退出码 |
| `world_quality` | 固定为 `unverified`；实际世界画质由用户确认，离线探针不能自动改成通过 |

在工作区根执行 `python _port/rt_rr_temporal_check.py --case core`，通过实际引擎八纹理 ABI，
以 640×360 原生尺寸、每组 24 帧验证 HDR、运动矢量与历史 reset。主进程 123 次求值，
独立对照进程 122 次求值，逐帧等 fence 并读回；报告写入 `_tmp_s4_rr_temporal.json`，
包含运行库/探针 SHA-256、设备、输入参数、阈值、RGB 误差与未覆盖项。

| 项目 | 对照 | 当前脚本诊断配置 |
| --- | --- | --- |
| HDR | 带噪线性 `(4, 2, 0.5)` | 各通道相对 RMSE 小于 5%，R 通道 MSE 比输入至少降低 4 倍 |
| MV | 世界位置渐变与相机交替旋转；正确像素 MV、反号、UV 单位 | 正确 R 通道 MSE 小于 0.005，两个负例均超过正确值 2 倍 |
| 历史 | 辐亮度改变后的按需 reset、CONTINUE 与独立进程每帧 reset | 对齐求值次数/SDK 帧号/输入后，reset 与对照的 RGB 最大差小于 0.002；CONTINUE 各通道 MSE 超过 reset 2 倍 |

历史对照进程使用全程 reset 预热；此为匹配内部采样相位的对照，不是冷启动首帧。
RR 帧号始终递增，禁止在同一进程复用编号。相邻两次 reset 的 SDK 内部采样相位可能不同，
不能将差异直接解释为旧历史残留。

`rt_check_all.py --gpu` 调用 `core` 检查。`--case all` 另外采集深度、法线与粗糙度候选场景，
报告写入 `_tmp_s4_rr_exploratory.json`；负例敏感度不作为工程门，候选场景不替代世界内画质验收。
`--case depth_edges` 另以三个独立进程比较 viewZ、误喂 deviceZ 与误喂 reverse deviceZ。
使用 `--depth-scene` 选择以下固定场景，每组默认 24 帧：

| 场景 | 解析信号与测量区域 |
| --- | --- |
| `textured`（默认） | 横移相机观察 5 m 遮挡半平面与 30 m 背景的世界坐标纹理，测量遮挡边缘两侧各 8 pixel |
| `disocclusion` | 相同几何与运动，前景辐亮度 0.2、背景 1.2，测量相同边缘区域 |
| `reflection` | 5 m 镜面反射身后 5 m 的纹理墙，虚像深度 15 m；roughness=0.1，hit distance 为反射段实际长度，测量画面中央半宽、半高 |

匹配全部相关运行库、探针、初始化/读回 shader 哈希、SDK 帧号、实际求值次数与噪声。
每帧在 SDK 调用前以独立 compute 读取 depth、MV.xy、normal.xyz 与 roughness，逐像素对照 CPU 解析参考。
深度最大误差须不超过 0.0001，两个 MV 分量各不超过 0.02 pixel，法线逐分量一致，roughness 误差不超过 0.0001。
保存每帧边缘/区域误差与完整输出读回哈希，以及末帧完整输出、参考/噪声和引导缓冲快照。
父进程另从快照重算引导缓冲检查，确认变体的深度确实不同，其他引导值完全一致；
SDK 接管/归还/恢复堆必须与帧数相等，无外部残留、violation 或 D3D12 corruption/error。
报告分别为 `_tmp_s4_rr_depth_edges.json`、`_tmp_s4_rr_depth_edges_disocclusion.json` 与
`_tmp_s4_rr_depth_edges_reflection.json`；启动父进程时先写入未完成状态，避免子进程失败后留下旧通过结果。
子报告的 `measurement_complete=true` 只表示采样完成，`passed` 保持 false；适配通过由父进程单独判定。
该脚本的诊断配置为正确深度 R 通道 MSE 小于 0.01、噪声误差降低四倍和错配组超过正确组两倍。
这些数值用于复现该实验，不是深度协议正确性或 S4 可用性的统一门禁。
三个场景的 viewZ 与误喂 deviceZ 在全部 24 帧的完整读回均相同；GPU 引导缓冲核验确认它们的深度值不同。
`disocclusion` 的正确输入末帧 MSE 约 0.00899326、输入噪声 MSE 约 0.0379590，达到正确输入预算，
但两个错配负例均未达到退化预算。`textured` 与 `reflection` 的正确输入也未达到全部预算。
三个场景均返回 `INCOMPLETE`/非零，不接入通过的总门；不能据此修改产品深度约定或宣称 SDK 忽略深度。

执行 `python _port/rt_rr_temporal_check.py --case highlight --frames 32` 测量过滤后高光。
三个独立进程分别提交 linear/squared/root guide，每个进程包含 roughness 0.2/0.35/0.5、
三个噪声 seed、每组 32 帧，共 864 次求值。保持 SDK 帧号、实际求值次数、输入噪声及参考信号匹配，
核对 DLL/SDK/探针哈希，保存每组末帧完整读回、参考/输入哈希与中央八行 R 通道平均 profile。
静止相机观察半径 3 m、中心 `(0,0,-10)` 的球，输入为峰值归一化的解析 GGX lobe，
基线为 0.02、峰值增量为 4、乘性噪声幅度为 0.8，合成 specular hit distance 固定为 5 m。
此为静态 guide 敏感度实验，不是实际光线输运或反射运动的参考渲染。

CPU 双精度参考用于检查 GPU 原始 profile，合成参考差异的容差为 0.005。
该脚本记录九组 FWHM、相对宽度误差、负例聚合宽度 MSE 和宽度差。
脚本使用的 5%、两倍和 1 pixel 配置仅描述此实验；不要求实际降噪高光还原解析 lobe。
峰值、峰值位置、基线上方积分及高光 ROI MSE 同样作为诊断输出。
当前正确编码最大宽度误差约 3.56%，CPU/GPU profile 最大差约 0.00153；
平方/开方误用的聚合宽度 MSE 仅为正确值约 1.162/1.236 倍，最大宽度差约 0.3474/0.2479 pixel。
匹配对照下输出有差异，但负例未达敏感度预算；父报告 `_tmp_s4_rr_highlight.json` 保持
`measurement_complete=true`、`passed=false`，命令返回 `INCOMPLETE`/非零，不接入通过的总门。
这些结果用于保留实验事实，S4 可用性与实机验收不依赖此处的固定宽度或退化倍数。
独立子报告的采样完成也不替代实机判断。法线空间的候选负例仍须遵守 §25.2：
官方允许 world/view normals，不能将 view normals 本身定义为错误输入。

高方差候选输入使用 `--highlight-noise sparse --highlight-spp N`，`N` 可为 1/4/16/64。
每个样本以 `1/8` 概率命中，命中权重为 8，再取 N 个样本的均值；基线 0.02 不参与稀疏化，
期望仍为同一个解析 GGX lobe。逐帧/逐像素/逐 seed 的 hash 固定，三个编码变体共享完全相同的输入。
这是 Bernoulli 采样模拟，不是实际反射射线或路径输运参考；不据此修改产品 roughness 编码。
例如 `python _port/rt_rr_temporal_check.py --case highlight --highlight-noise sparse --highlight-spp 64 --frames 48`。
稀疏报告/快照以 `_sparse` 和可选 `_sppN` 后缀保存，不覆盖默认 uniform 结果。

父进程从所有完整快照重算输出/参考/输入哈希、中央八行 profile、FWHM、ROI MSE 与 CPU/GPU 参考误差，
并核对九组 roughness/seed 顺序、SDK 帧区间、当前源码/运行库哈希和接管/归还/恢复堆计数。
稀疏输入另逐像素复算全部样本的 uint32 hash 与命中数，对照快照中的 R 通道输入，验证该通道的 GPU 噪声；
半精度每次写入使用一个 binary16 ULP 的保守误差界限，另留 `1e-5` 的 float32 运算误差。
该界限只用于输入校验；5%、两倍与 1 pixel 仍保存在探针配置中，图像指标不作阶段验收门禁。
64 样本、48 帧对照共 1,296 次实际 RR 求值，27 份完整快照和全部输入检查通过，
但正确编码最大 FWHM 误差约 7.24%，平方/开方负例的聚合宽度 MSE 比例约 0.841/1.067，
最大宽度差约 0.284/0.201 pixel；报告保持 `passed=false` 并返回 `INCOMPLETE`/非零。
16 样本、128 帧的延长对照共 3,456 次实际 RR 求值，27 份完整快照和逐像素 R 输入复算通过；
正确编码最大 FWHM 误差仍约 5.28%，平方/开方的聚合宽度 MSE 比例约 0.834/1.095，
最大宽度差约 0.277/0.317 pixel；同样返回 `INCOMPLETE`/非零。
输入/资源检查与合成图像度量分别报告；上述候选对照保留为可选诊断，不作为 S4 的阻塞项。

当前 RR 使用共享 jitter、未曝光线性 HDR、`preExposure=1`；本节的零偏移 HDR 检查不代替手动/自动曝光适配。
这些数值检查也不代替世界内画质、全部历史失效原因、状态归属或完整后端组合矩阵验收。

玩家 RT 路径的曝光参数遵守以下当前契约：

- PT、NRD 与 RR/独立 SR 的输入颜色保持未曝光线性 HDR，SDK 的 `preExposure/exposureScale` 均为 1。
  最终合光读取 `t11 gExposure`，只执行一次曝光与 tone map；`WgaFrame.g_exposure.x` 不再决定 B-DXR 合光曝光。
- RR 与 RT DLSS-SR 均通过显式曝光入口提交同一张 `kBufferTypeExposure`，它由引擎计算。
  SR 设置 `useAutoExposure=eFalse`；玩家的 AUTO 是引擎测光，SDK 仍使用显式资源模式。
  对于旧 API/无资源调用，Streamline SR 在缺少该资源时设置 AutoExposure feature flag。
- RR 支持可选曝光纹理，其 `DLSSDOptions` 没有 `useAutoExposure` 字段。
  所带 RR 插件读取曝光纹理并提交 NGX，创建 flags 未设置 AutoExposure；缺纹理时 SDK 内部如何处理曝光，代码中未明确。
  禁止将 SR 的自动曝光开关当作 RR 的同名模式。
- 可选曝光纹理是 1×1 `R32_FLOAT` 的最终曝光倍率，不是已 tone map 的颜色。
  若将输入预乘曝光，必须同步传实际 `preExposure`，并在输出与最终合光中保持同一颜色尺度。
- SR 的 AutoExposure flag 在创建 feature 时确定。当前所带插件的重建判断不包含 `useAutoExposure`；
  设计热切换时必须显式处理 feature 重建，不能仅凭 setOptions 成功声称模式已切换。

显式曝光入口 `D3D12DlssdEvalExposure` / `D3D12RtSrEvalExposure`，
接收独立两 float 的 `preExposure / exposureScale`，可额外传入一张 1×1 `R32_FLOAT` 曝光纹理。
相机载荷仍为 104 floats；新参数没有追加到相机数组。标量须有限且在 `[2^-16,2^16]`，
纹理完整预检发生在接管前；曝光资源与其他输入一起按 NPSR 接管、返回后回 `COMMON`。
RR/RT DLSS-SR 按实际录制 ticket 保存尚未退休的 feature 使用。相同 Pending 录制可顺序求值同模式，
外来 Pending 录制在接管资源前拒绝；存在任何 Pending 使用时也不允许切换曝光资源模式。
真实取消先关闭列表再重新 Begin，未执行使用直接退休并请求 reset；Begin 已打开列表只是幂等操作。
取消更新录制不删除更早的实际提交，提交失败后已执行的使用仍保活至 GPU 完成。
取消包含 SDK 使用的未提交录制后，同模式恢复也重建 feature；仅 reset 标志不能恢复 SDK 内部资源状态。
每份失败提交仅在首次观察时请求一次 reset；继续保活与轮询不重复请求。
恢复求值成功后可继续积累历史，恢复录制取消或 SDK 再次失败时分别重新请求。
取消恢复的重建与资源模式切换均等待全部实际使用完成，共享五秒上限，再释放 feature、清选项缓存并请求 reset；
拒绝或超时不接管资源。单纯修改同模式的两个标量会更新 SDK options。
玩家 RR 与独立 DLSS-SR 均使用显式入口，传入 `preExposure/exposureScale=1` 和同帧曝光资源。
NRD、XeSS、FSR 保持未曝光线性信号；最终合光读取相同倍率。手动/自动模式都保留资源，
因此切换玩家测光模式无需切换 SDK feature 的资源模式。
世界内曝光观感仍需用户确认；SDK 参数错配的图像敏感度只作可选诊断。
跨 encoder 的同队列等待、超时原子拒绝及恢复由下述 ABI 探针检查。

配置位于 `config/dxr_mc-client.toml` 的 `[rt]`，均支持热修改：

| 键 | 默认值/范围 | 行为 |
| --- | --- | --- |
| `exposureMode` | `MANUAL`，可选 `AUTO` | 手动固定倍率或引擎自动测光 |
| `exposureEv` | `0.0`，`[-8,8]` | 每增加 1 EV，曝光补偿乘 2；手动倍率为 `2^EV` |
| `exposureSpeed` | `1.5`，`[0.1,10]` / 秒 | 自动曝光在 log2 域的响应速率 |

native `mcdr_dx12RtFrame` 在 trace 与主命中材质更新之后、降噪之前派发一次 `RtExposure`。
JVM 每帧提交 NeoForge 曝光设置、单调帧时间戳与 reset，RR/SR/合光读取同帧借用句柄；适应状态只由 native 持有。
单个 256-thread 组均匀采样 64×64 网格，重建 `albedo*(direct+indirect)+specular*F0+grazing+emissive`；
只纳入 trace 命中且 G-Buffer 覆盖、RGB 有限的表面，使用 Rec.709 亮度系数 `(0.2126,0.7152,0.0722)`。
亮度 clamp 到 `[1e-4,1e4]` 后求几何平均，目标倍率为 `clamp(0.18*2^EV/geometricMean,1/256,256)`。
不计原版直通天空/实体；没有有效样本时保持上一倍率，reset 帧使用 `2^EV`。
正常帧在 log2 域以 `1-exp(-speed*dt)` 插值，dt 上限 0.5 秒。
首次录制、录制失败、尺寸/世界/模式/EV 变化、时间戳重复/回退及相机切换等历史失效会立即重设曝光；
仅几何/材质 revision 或适应速率更新不重设，避免正常区块上传反复打断适应。预检拒绝不改变已接受历史，资源随 RT release 释放。
固定参考亮度与单一标量曝光不改变白点或逐通道色度。

`python _port/rt_native_frame_check.py` 直接验证 native 帧入口的 28 组曝光时序、参数拒绝后的历史、
世界/尺寸/设置切换、空覆盖与有符号时间戳边界；由实际 raw/guide 回读构造独立曝光参考。

`python _port/rt_exposure_product_check.py` 运行实际测光与最终合光 HLSL，进入 GPU 总门。
52 次测光覆盖手动 EV、亮暗切换、几何平均、NaN 样本排除、空覆盖保持与 reset；
50 次合光检查分别验证原始与已合成线性颜色，另外两次 NaN 输入只验测光。
CPU 解析参考要求曝光相对误差低于 `5e-5`、最终颜色最大误差低于 0.0015。
保存 52 份完整读回并重算误差；八个漏乘/重复乘曝光负例须产生大于 0.02 的颜色差。
同时核验 HDR 输入未被曝光修改，并要求 D3D12 corruption/error 为空；不代替世界内画质验收。

`python _port/rt_exposure_abi_check.py` 经产品 ABI 检查 RR 原生 320×200 与 SR 50% 放大到 640×400，
包括旧入口、新入口默认参数、缺曝光资源/带资源/旧入口/带资源四段切换。
基础组共 384 次实际 SDK 求值、344 次非法输入或未提交模式切换拒绝。
脚本另诊断旧/新默认路径输出是否逐位相同，以及每段 32 帧的后八帧 MSE 是否低于 0.001；这些图像比较不作阶段门。
带资源段中途改变预曝光，逐帧核对 HDR 预乘输入与曝光值。
报告保存源码、SDK、运行库、输入与快照哈希；父进程复核模式序列、二十四份末帧快照的曝光值和误差。
同时要求接管/归还/恢复堆次数相等、无残留/violation、D3D12 corruption/error 为空。
另有 RR/SR 两个跨 encoder 组，共 328 次实际求值、两次未提交原子拒绝、两次超时原子拒绝与四次延迟等待。
两张真实 encoder 使用同一 GPU queue；先让另一 encoder 的 fence 超过旧列表的待完成值，
确认其完成不能满足尚未提交的旧 encoder，须在一秒内拒绝并在提交后允许重试。
另用 CPU-controlled fence 阻塞 GPU queue，检查已提交旧列表仍未完成时的超时与释放后的恢复。
双向切换时保持队列阻塞至少 0.25 秒，后台确认调用未返回、两个 encoder 的状态/归属/计数未改变，再解除阻塞。
切换成功后继续执行 31 帧；二十四份完整快照分别记录切换时刻及收敛结果。
RR 重建 feature 的首帧会重新积累历史；脚本对切换首帧与恢复 31 帧后的 MSE 分别使用 0.1 和 0.001 诊断预算。
两类统计分别记录；实际检查重试成功、输出有限与资源归还，世界内收敛由用户确认。
父进程复核顺序、fence 数值、等待时间、拒绝原子性、两套 scope 计数、快照哈希与颜色误差。
基础与跨 encoder 组合计 712 次 SDK 求值、348 次拒绝；`--cross-encoder` 只运行这两组并复用已有 JVM 输入。
范围为同队列串行求值；独立 GPU queue、同时求值、SDK 曝光错配敏感度及世界画质仍未覆盖。

`--recording-lifecycle` 额外运行六组真实 RR/SR 录制检查：同 Pending 录制的顺序求值、
外来 Pending 拒绝、真实取消后恢复，以及更新录制取消时更早的阻塞提交仍须完成后才能释放 feature。
每组先完成 32 帧，再经产品 ABI 触发边界；恢复输出须有限，曝光纹理和资源归还须正确，调试层不得有 corruption/error。
`--native-dll <path>` 在工作区隔离目录阶段化指定 DLL，子进程使用同一构建；不覆盖游戏运行目录。
六组共 208 次求值、两次外来 Pending 拒绝与两次延迟等待，默认运行也包含此组；
完整命令共 920 次 SDK 求值、350 次拒绝，进入 GPU 总门。
`--reuse-inputs` 只复用已有 JVM manifest，输入哈希检查仍执行；报告记为 `existing_manifest`，
不能以此代替默认入口要求的纯 JVM 自检。
`python _port/rt_sdk_history_check.py` 独立观察实际 SDK reset 常量，并验证此桥接的 Signal 失败恢复。
测试桥接按真实 SDK 头编译，经 MCDR 的 `GetProcAddress` IAT 插槽转发 constants/tag/evaluate/freeResources；
不猜测字段偏移，设备关闭后恢复插槽，桥接保持存活至设备关闭。
RR/SR 各覆盖同模式真实取消、更晚取消但更早提交仍在途、per-context / queue 两处 Signal 失败，
以及 constants/tag/evaluate 三种注入返回错误。
错误组先调用真实 SDK，再把成功替换为错误；报告区分真实结果与注入结果，不视为 SDK 自发失败。
Signal 组在 GPU 阻塞时实际执行命令，再补齐被注入的 Signal；恢复只请求一次 reset。
更新录制取消后，旧失败提交仍保活；feature 释放入口直接核对原 producer fence 已完成。
父进程复核完整调用轨迹、reset/结果序列、资源 scope 计数、读回哈希及输入 manifest/源码/运行库哈希；
子进程核对曝光纹理和有限值，调试层不得有 corruption/error。该组与曝光 ABI 组分别进入 GPU 总门。
该命令使用已有 JVM manifest，默认隔离 target DLL，支持 `--native-dll <path>`；
不提供纯 JVM 总门、其他 SR 上下文或世界内噪点与帧时的通过证据。

XeSS/FSR provider 的 `SrContextCompletion` 同样保存全部实际 `CommandRecording` 使用。
同 Pending 录制允许顺序同模式求值，外来 Pending 在资源接管前拒绝。
取消只移除未执行使用，同模式恢复也重建上下文；更晚的取消不得移除更早在途的提交。
Signal 失败只请求一次 reset，SDK execute 错误保留请求；恢复成功后继续积累历史。
重建、后端切换与关闭均等待原 producer 完成，实际提交共享五秒上限；Pending 立即拒绝，超时保留实例和资源。
`SrDestroy` 清除期望后端，但失败时保留生效后端；通过 `SrQuery` 查看状态，提交完成或取消后重试。
工作区 `rt_sr_recording_check.py` 通过真实 XeSS/FSR SDK 观察 reset、创建与销毁，
覆盖取消、跨 encoder、后端切换、双 Signal 故障、返回错误注入及销毁超时。
负例中的过早销毁由探针拦截并记为失败；正常销毁入口核对实际 producer fence，
同时检查资源归还、有限值读回与 D3D12 corruption/error。世界内噪点与卡顿仍须单独验证。

光栅 DLSS 的 Streamline viewport 0 与直连 NGX 独立保存全部实际录制使用。
外来 Pending 在结束 render pass 前拒绝，未执行取消后的同模式恢复也须等待旧提交并重建 feature；
执行后 Signal 失败只请求一次 reset。模式键包括尺寸、格式、请求质量档/preset、创建 flags 与 HDR 状态。
设备关闭先等待实际 producer 并释放 feature，Pending、五秒超时或释放失败均保留整个设备供重试；
每次求值先清旧交接状态，常量拒绝不得额外改写 GUI 输出状态。
`rt_dlss_recording_check.py` 在隔离目录分别运行真实 Streamline/NGX，检查取消、模式切换、阻塞提交、
两处 Signal 故障、关闭拒绝及恢复，并观察 Streamline 的 reset、调用顺序与实际释放入口 fence。
constants/tag/evaluate 的返回错误注入先转发实际成功调用，freeResources 另在调用前拒绝；
均不能记作 SDK 自发失败，释放已有副作用但返回错误后的自动恢复仍未闭合；
NGX reset/释放入口、深度快照 producer、device removal、FG 与世界路线仍单列。
详见 [native DLSS §4.9](../../../MCDR/docs/advanced/dlss.md#49-光栅录制与-feature-退休)。

工作区命令 `python _port/rt_exposure_check.py` 执行独立 SDK 曝光实验，`--analyze` 仅重新审计已有产物。
桥接使用引擎提供的 D3D12 device/queue，自行分配命令列表和纹理，直接调用 Streamline；
此路径不经过产品 RR/SR 的资源接管入口。RR 以 320×200 原生尺寸、SR 以 50% 放大到 640×400，
十二个独立进程各运行 48 帧，分别检查缺曝光纹理、1×1 R32_FLOAT 手动倍率 0.25、
四段预曝光 `0.0625 / 4 / 0.25 / 16` 及 SDK 参数保持 1 的错配组。
输入为十档亮度的纹理 HDR 平面，逐帧 CPU 核验预乘后的输入样本与实际曝光值。
保存源码、SDK 头、运行库、桥接和逐帧输入/输出哈希，以及每段末帧快照；父进程重新计算快照误差。
脚本的诊断阈值为正确组末八帧相对 MSE 低于 0.002、错配组误差超过正确值两倍，不作为阶段门。
当前正确组达到误差预算，但四组错配与对应正确组各有 48/48 帧完整输出相同；
实验报告 `measurement_complete=true`、`passed=false`，返回 INCOMPLETE/非零，不纳入通过的 GPU 总门。
不能据此推断 SDK 忽略曝光，也不能将直接 SDK 结果作为产品接线、模式热切换或世界内画质的通过证据。
错配参数未导致固定幅度的图像退化不构成 S4 失败；检查实际 HDR/preExposure/曝光资源责任、切换恢复和用户可见表现。

### 25.8 SDK 资源归属与状态验证

NRD、RR、RT 独立 DLSS SR、光栅 DLSS、XeSS 与 FSR 使用 native `SdkResourceScope`。
完整校验绑定后批量转态、登记 `externalResources`；SDK 返回时按契约重新声明状态，再归还引擎。
成功与失败均恢复 draw heap 并清空 PSO 缓存。各返回状态与诊断字段见
[native 设备层 §5.7](../../../MCDR/docs/device/README.md#57-sdk-资源接管与归还)。
FFM 查询为 `MCDR_ABI.D3D12QuerySdkResourceState(ctx, texture)`；仅用于与录制同步的状态检查。

工作区 `rt_check_all.py --gpu` 通过以下实际 ABI 探针验证：

| 探针 | 覆盖 |
| --- | --- |
| `rt_nrd_temporal_check.py` | 每次归还检查、四种非法绑定的无副作用拒绝、未初始化与第九在途派发的 SDK 拒绝收尾、后续双通道 compute 读回 |
| `rt_rr_temporal_check.py --case core` | 每次归还检查、四种非法绑定拒绝、主进程和独立 reset 对照的实际 RR 求值与后续读回 |
| `rt_sr_signal_check.py --backends` | 三后端可用时 39 次 SR 求值，包含四帧光栅 DLSS SDR；RT SR/XeSS/FSR 的 SDR/HDR、历史与切换；XeSS/FSR 可选 R8 mask、非零 jitter 与 MV 约定切换；22 次非法绑定、未知 flags 或未提交列表重建拒绝；后续颜色/mask compute 读回 |
| `rt_dlss_depth_check.py` | 隔离 NGX 与开发目录 Streamline，四格式往返及 R32/D32 的漏捕获、空参数、源/尺寸变化、开关与恢复，每实现 40 次、共 80 次光栅 SDK 求值；快照/当前源选择、源清零的逐像素读回与颜色 compute 读回 |
| `rt_dlss_failure_check.py` | Streamline 四次同帧冲突常量拒绝与四次临时 eOff 的 Evaluate 拒绝，验证接管前无状态变化、接管后完整归还、成功次数不误增、恢复前 compute 与新帧恢复；合计十二次成功求值、十六次 scope 归还 |
| `rt_dlss_recording_check.py` | 光栅 Streamline/NGX 实际录制、取消恢复、模式切换、双 Signal 故障及关闭/超时恢复；独立观察 Streamline reset、constants/tag/evaluate/freeResources 返回错误注入与实际释放入口 fence |
| `rt_exposure_abi_check.py` | RR/SR 显式曝光、旧入口兼容、四段资源模式切换和同队列跨 encoder 等待/超时恢复；712 次求值、348 次原子拒绝，48 份完整快照复核 |

各组探针都启用 D3D12 debug layer，要求 corruption/error 消息为空。
归还时要求 owner、尚未归还数量、violation 为 0，累计接管/归还/恢复堆次数相等。
归属检查配合 SDK 信号检查和真实后续访问使用，不能单凭跟踪计数证明 GPU 状态正确。
mask 共八次带资源求值，逐像素验证 1,843,200 个输入值保持不变；报告记录到工作区 `_tmp_s4_sr_backends.json`。
这只验证 mask 接管、切换及后续访问，不能证明动态/OIT 信号的掩码画质。
光栅 DLSS 在内部实际求值处经回调接管选定资源；独立 NGX 目录另验证四帧 SDR，覆盖 MV 中间纹理与 handover。
深度快照先将源转为 COPY_SOURCE，形状匹配后 CopyResource 复制全部子资源，再恢复源状态；
私有纹理的分配可跨帧复用，捕获有效期仅限当前帧；帧起点、捕获尝试、关闭与 shutdown 使其失效。
NGX 与 Streamline 共用选择器，只在源资源相同、尺寸与输入一致且本帧成功捕获时使用快照。
有效快照路径保持原深度状态，回退当前源时为 SRV；SDK scope/tag 使用实际选中的资源。
80 次检查覆盖单 mip/单 layer/单 sample 的格式往返、捕获有效期与状态归属；
两实现合计 18,432,000 个源深度值在 SDK 后逐像素读回为零。
不直接读回快照内部的 depth/stencil texel，不据常量颜色结果声称深度感知画质通过。
Streamline 常量提交失败在资源接管前返回，日志保留 SDK 错误码；同帧冲突常量为 `0x1B`。
实际 Evaluate 的 `eErrorInvalidIntegration`（`0x13`）在接管后拒绝；scope 仍完整归还，输出交接保持 0，成功求值次数不增加。
失败后、恢复前的 compute 检查 12,464 个 GUI 颜色样本，恢复 SDK 选项后四次新帧均成功。
独立录制检查额外覆盖 Streamline tag 与 freeResources 的返回错误注入；
options 错误、SDK 自身部分 GPU 写入后的错误、NGX SDK 错误及 FG 失败收尾仍缺实际错误注入验证。
世界离开/分辨率切换的实机行为、光栅 HDR surface、FG、NRC、完整子资源/Split 跟踪、跨 encoder 同时求值及跨队列同步不在本组覆盖内。

## 26. 渲染倍率与上采样（S4 4-e）

`[rt] renderScale` 驱动 G-Buffer、重放 viewport、motion、guide 与降噪纹理的实际分配。
光追开启时，主目标及世界层附件保持窗口尺寸，普通光栅渲染比例与 SR 质量档不再缩小这些附件。
RT Plan 是光追输入尺寸的唯一来源，RR 或独立 SR 的输出直接匹配窗口尺寸。
RR 将 render 尺寸输入直接求值到独立的显示尺寸输出；`NRD_SR` 在低于 100% 时按所选 SR 后端重建显示尺寸。
选择 NONE 或独立 SR 不可用时保留 NRD 结果，由合光插值输出。

### 26.1 倍率怎么落到尺寸上

`D3D12RtPipelinePlan.calculate` 统一计算 render 尺寸，单边下限为 16 且不超过显示单边；
OFF 忽略 renderScale、按 100% 处理，其他轨道接受 25–100。
例如窗口为 2560×1334、光追倍率为 67% 时，输入为 1715×893，SR 输出为 2560×1334；
普通光栅比例保存为 50% 或 75% 均不改变这组尺寸。
`D3D12RenderScale.reconcileRayTracing()` 在光追开关变化时安排帧边界重建；
重建前世界层仍读取 `appliedPercent`，与当前主目标附件保持同一尺寸。
trace 尺寸恒等于 render 尺寸，不存在第二套追踪缩放上限。

纯 CPU 回归覆盖四种轨道、四种 SR 选择、25/50/100%、常规/奇数/极小窗口以及总开关，共 288 组计划。

### 26.2 实际资源尺寸

`recordGBufferPass` 捕获一份 Plan，按其 gbuffer 尺寸分配附件与重放 viewport，再传入 dispatch。
50% 的 854×480 主目标对应 427×240 的 G-Buffer、DXR trace、motion、NRD pack/radiance，RR 与独立 SR 均输出854×480。
Plan 变化重置相机历史与降噪签名；纹理替换后释放旧目标，成对分配失败时释放已创建的第一张。
单通道/法线调试图按目标显示尺寸生成，源纹理按自身尺寸映射。

### 26.3 合光里的上采样

`rt_resolve.hlsl` 按各自资源尺寸采样：

- 覆盖判定使用最近邻 trace 命中标记与 G-Buffer albedo alpha。
- 原始辐亮度与主射线距离使用 trace 四邻域插值；相机半径淡出只读取主射线距离。
- G-Buffer albedo/emissive 通过自身尺寸映射。
- 降噪输出通过 `GetDimensions` 独立映射坐标，不能沿用 trace 下标。REBLUR 输出还须先解码 YCoCg，见 §23.6。

低倍率下的合光插值不能替代独立 SR。块面细节、边缘、噪点与相机运动效果须在世界内验收。

### 26.4 NRD 后独立 SR

`D3D12RtNativeFrame.upscale` 只提交低于 100% 的当前帧 REBLUR 输出。
native 的 `upscaleRtFrame` 拥有重调制 PSO、颜色/设备深度/显示输出、失败状态与 SR 历史；先执行
`rt_nrd_remodulate.hlsl`：在 render 尺寸分别解码 diffuse/specular YCoCg，乘同帧 albedo 与 `max(F0,0.02)`，加 emissive，生成线性 HDR 颜色；
同一 pass 将正 viewZ 经本帧投影转换为设备深度。SR 输入禁止直接使用解调辐照度或正 viewZ。

NRD 时间累积会抑制光照信号的子像素偏移。进入 SR 前，用当前屏幕内容偏移 `(Jx,-Jy)`
在 `pixel - contentJitter` 位置重采样两路 NRD 光照，使其与当前 G-Buffer 的采样位置对齐。
`RtReblurSampleJittered` 使用四邻域双线性权重，拒绝 viewZ 差超过 `max(0.01 × centerDepth, 0.01)` 的邻点；
零偏移直接读取中心像素。albedo、F0、emissive 与设备深度保持当前像素采样，禁止连材质一起再次偏移。
重调制 b1 载荷为四个深度投影系数加两个偏移 float；SDK 的 jitter 仍使用同一份当前偏移。
该处理只用于 NRD 后独立 SR；RR 原始输入与不运行独立 SR 的 NRD 输出不经过这一步。

Plan 的 `upscaler` 来自 `SuperResolution.backend()`，选择 NONE 时不分配或派发独立 SR。
DLSS 由 native `rtSrEval` 使用 Streamline viewport 2；光栅 SR/FG 使用 viewport 0，RR 使用 viewport 1。
五张纹理依次为 render 颜色、display 输出、render 设备深度、render 像素 MV、1×1 曝光倍率。
相机复用 RR 的 104-float 载荷、无 jitter 矩阵与显式 `(Jx,-Jy)` 偏移，`depthInverted` 跟随投影；
SR 深度标签为 `kBufferTypeDepth`，RR 的 `kBufferTypeLinearDepth` 只用于 RR。

XeSS/FSR 由 native `srEvaluateFrame` 提交完整 `mcdr_sr_dispatch`，由现有 provider 懒建上下文。
输入为线性 HDR，`hdrInput=1`、`preExposure=1`；MV 为 render 像素真实位移、scale=1，偏移同样取 `(Jx,-Jy)`。
RT MV 已扣除当前/上一帧的 jitter 差，必须置 `flags=MCDR_SR_FLAG_UNJITTERED_MV`（1）。
FSR 对应关闭 `MOTION_VECTORS_JITTER_CANCELLATION`，XeSS 关闭 `JITTERED_MV`，避免二次补偿；
光栅入口仍使用 `flags=0`，表示 MV 包含 jitter。
这两家 provider 固定使用 reverse-Z，重调制的深度投影分子按需改为 clipW−clipZ，转换不改变颜色与 MV。
相机 near/far/FOV/aspect 与 RR 同源；可选 mask/exposure 纹理为空。
输出纹理同时声明 storage、texture binding 与 render attachment，允许 provider 在 UAV 写后恢复 RENDER_TARGET。

SDK 按实际输入/输出尺寸查询合法档位。Plan 包含后端选择，尺寸、轨道或后端变化均复位历史，求值帧号保持递增。
FSR/XeSS 的 MV 约定变化须重建上下文，FSR 的 HDR 标志变化同样重建。
内部重建先核对最后一次求值所用 encoder 的 fence 与目标值；仍在当前未提交列表时拒绝本次重建，
其他未完成使用最多等待 5 秒。拒绝发生在资源接管前，保留旧上下文；重建后的首次成功求值强制 reset。
该保护针对 provider 内部上下文重建，不能据此认定整个 provider 热切与销毁流程均已验收。
倍率在 25–100 范围内不代表 SDK 支持所有整数输入尺寸；不被接受的尺寸仍按下述失败路径输出。
输入转 NPSR、输出转 UAV；DLSS 求值后恢复 COMMON，XeSS/FSR 输出恢复 RENDER_TARGET，均恢复绘制描述符堆。失败只报一次并保留 NRD 合光；
Plan、尺寸或后端变化允许重试。SR 失败不清除已成功 NRD 的历史，不发布上一帧 SR 输出。
SR 成功后合光按已重调制颜色处理，禁止再次乘 albedo 或叠加 emissive；
曝光与 tone map 只执行一次。RR 成功时不叠加独立 SR，RR 回退 NRD 时允许独立 SR。

FSR 仍仅在开发侧有运行库时可用，发行 JAR 不包含 FidelityFX DLL。
后端路由、载荷与 SDK 离线检查不能代替世界内的噪点、残影与窗口缩放验收。

RT 开启时光栅 `SuperResolution.applyIfEnabled`、DLSS guide 捕获与求值提前返回。
`sr_ms` 计入重调制、设备深度转换与 SDK 求值；`trace_ms` 必须在降噪之前结束。

## 27. G-Buffer 变体与着色器链的依赖

世界里能出光追的前提是地形管线带一个 **G-Buffer 变体**：`tryMeshletDraw` 只有在
`hasGBufferVariant()` 为真时才捕获批次，`recordGBufferPass` 才有东西重放。变体缺失时主 pass 照常渲染，
但 `rt frame: dispatch` 一次都不会出现，几何池恒为 0 段，合光打一行
`rt composite: skipped, missing inputs` 就结束 —— 看上去像「整套管线没生效」。

本帧没有派发产出时合光直接返回（`no dispatched frame`）。合光输出采用主目标尺寸，
G-Buffer 与降噪输入按各自尺寸采样，允许 renderScale 使其小于主目标。

变体由 `D3D12RtGBufferShader.build()` 从当前着色器链产出的 HLSL 生成，针脚按 SPIR-V Cross 的形态写
（`struct SPIRV_Cross_Input`、`void frag_main()`、`verts[vid] = vs_main(vin);`，见 §8）。
换一条不产这种形态的转换链，`build()` 找不到针脚就返回 `null`，变体随之消失。

因此主 pass 的转换链上有一条与 meshlet 包装同款的守卫（`D3D12Device.createPrimaryPipeline`）：
光追开着、管线又属于 G-Buffer 收录范围（位置里不含 `translucent` / `oit`）而变体建不出来时，
这条管线回 SPIR-V 链，日志记一行
`shader converter primary: <pipeline> keeps the old chain (gbuffer variant: <原因>)`。
回退不会让地形消失 —— 旧链本来就能画，只是少了转换链的收益。

三条日志可以判断状态：

| 日志 | 含义 |
| --- | --- |
| `gbuffer: variant ready (<pipeline>)` | 变体建起来了，世界里应当出现 `rt frame: dispatch` |
| `gbuffer: variant missing (<pipeline>)` | 变体是 0，光追链不会启动 |
| `gbuffer: variant unavailable (<原因>): <pipeline>` | `build()` 失败的具体针脚，与上表第二行成对出现 |

排查顺序：先看 `meshlet: d=` 是否在涨（主 pass 在画），再看 `gbuffer: variant` 三行之一，
最后才是 `rt frame: dispatch` 与 `rt stats:`。

光追开着而几何池连续 3 秒一段都没有时，`D3D12RtFrame.tick()` 会自己报一行：

```
rt: no geometry after 3s, gbuffer replay never ran
```

这行意味着重放一次都没发生（不是「光追画得慢」），最先怀疑的就是变体没建起来：
`hasGBufferVariant()` 为假时 `tryMeshletDraw` 不捕获批次，几何池因此永远是空的。


---
## 28. 加速结构的释放时机

生产 `D3D12RtBlas` 使用 native scene 上下文；JVM 门面只保留 scene 令牌和借用输出。
native 拥有 BLAS/TLAS 结果、scratch、TLAS view 与不可变 UPLOAD 实例表，构建/过滤/追踪均在当前主列表录制。
旧资源由 `CommandContext.rtAccelerationRetained` 按实际列表槽持有，等待该槽提交 fence 完成后释放引用。
取消候选、交换发布、换世界和关闭渲染只撤销各自注册，不提前释放已录制 GPU 命令需要的资源。
禁止按 JVM 帧数判断生产 AS 的退休时机，禁止销毁输出中的借用 view 或实例表。

`D3D12RtAccel` 仅供独立低层自检，不承担生产场景的资源所有权与寿命判据。
接口、生产者同步与输出字段见 [native 场景](../../../MCDR/docs/advanced/rt-frame.md#13-场景候选发布与局部失效)。

### 28.1 分批构建与发布

生产 scene 在一个真实渲染帧内最多新建 16 条几何 BLAS；同帧替换候选、切换列表或 encoder 不重置额度。
ABI 38 的 scene 请求追加非零 `frameId`，JVM 传入 `D3D12Temporal.frameIdentity + 1L`；新真实帧才继续领取额度。
仅执行前取消退还该录制消耗的额度；已执行后 Signal 失败仍计费，实际完成也不能在同帧重新领取。
独立 AS 自检入口的列表预算与生产 scene 分开，不能用命令列表或 RT 派发次数代替真实帧身份。
scene 只构建当前 live 表中 node/layer/version 匹配的记录，缺席或过期记录保持 unknown，不占 16 条额度。
待建 BLAS 按完整键首次请求的真实帧排序；候选替换、重排和执行前取消重试保留仍在目标中的工作年龄。
同帧首次请求的任务保留当前记录顺序；退出目标或版本改变丢弃旧年龄，空场景、换世界和释放时清空。
这能避免新记录持续抢占已等待的工作，但不构成整个 scene 的时间或内存预算。
`rt_stats.json` 每个统计窗口的 `scene` 对象记录真实帧、目标/pending 快照、scene/live 版本、
目标记录、已录制记录、待建记录、过期/缺席记录、已发布代理 section、本帧构建数和最老待建年龄。
它通过 `D3D12GetRtSceneStats` 在 CPU 读取，不提交或等待 GPU；兼容 ABI 中缺少该可选入口时写 `null`。
这些字段是采样时刻的状态，不是整个统计窗口的峰值；`recorded` 不表示 GPU 已完成，
`pending_builds=0` 也不证明无覆盖缺口。过期载荷仍须等当前版本，目标清单外的覆盖另行验证。
启用分信号统计时，250 ms 间隔的 `rt_validity.jsonl` 样本同时冻结该帧的 scene 统计，
要求 world/场景真实帧/scene/live/目标快照全部匹配，随对应 GPU 计数一起异步写出；不匹配或入口缺失写 `scene:null`。
顶层 `frame` 是 RT 输出帧号，`scene_frame` 是传给场景调度的真实帧号；后者须与 `scene.frame_id` 相等。
两种帧号在跳过 RT 或启动期间可以不同，不能互相替代。
不得在延迟读回时查询更新后的场景并拼接到旧计数上。
主可见性只发布已就绪 section 的完整 layer 组，其余 section 使用 unknown 代理；
其中仍有效的不透明 BLAS 可按 §16.3 作为阴影专用证明，不能被最近表面查询接纳。
TLAS、实例表与配对镜像一起形成当前可查询场景；静态复用还须核对阴影证明集合。
JVM 按返回的 snapshotId 借用对应 CPU 镜像；候选不会因尚未追上已发布 revision 而逐帧重置。
近场内容或层集合变化时，立即重新选择镜像并把当前几何交给同一 native scene；不能等待旧候选完成才提交新内容。
否则 live 表已经撤销旧版本，native 拒建过期记录，而 JVM 又继续复用旧镜像，候选可能无法完成。
未变选择集仍复用原镜像，远处上传不生成新的近场 GPU 快照。替换候选共享已建 BLAS，不能补充同一真实帧的 16 条额度。

同一 worldEpoch 内按完整 `(uint64 node, uint32 layer)` 键复用版本、顶点数、索引数和完整 flags 相同的 BLAS。
新候选先共享已发布场景与旧候选的有效成果，再查询最多 1024 条、256 MiB 的 LRU 缓存。
live 表暂时缺席的缓存只作为 dormant 保留，重新收到匹配的当前 live 版本后才能复用；新版本逐出旧缓存。
共享结果不可变，修改条目新建结果；空场景/换世界清除缓存，资源仍按实际 fence 保活。
scene 字节计量对已发布、候选、缓存和在途 AS 资源去重，使用实际资源描述的 committed 分配字节，包含代理的上传/源 AABB 缓冲。
256 MiB BLAS 缓存上限使用同一计费口径；资源对象缓存首次驱动查询结果，避免逐帧重复查询分配信息。
scene 计量不含几何快照、SDK 资源及失去 RT 强引用的通用延迟删除/缓冲池，不能当作设备实际驻留总量。
16 条预算只计新建或变化的 BLAS；纯删除或记录重排只替换 TLAS 和实例表，静态场景直接复用两者。
BLAS/TLAS scratch 容量不足时按至少两倍增长，旧 scratch 保留到使用它的列表槽完成。

所有 scene、独立 AS、世界与 encoder 共用 512 MiB 的 AS 分配准入。
按驱动 committed 字节计入 BLAS/TLAS、scratch、实例表及代理上传/源缓冲；共享资源只计一次，扩容的新旧分配同时占用额度。
计费租约随实际 native 资源保留至延迟删除真正释放底层分配；撤销令牌、换世界或 fence 完成不直接退还额度。
AS 的表与暂存使用不进入通用池的精确容量缓冲，不能借池内更大容量绕过准入。
超额返回明确错误并清空本次输出，不隐藏提交或等待；调用方在合法退休后重试，不能把旧场景输出当作成功结果。
`D3D12GetRtAccelerationBudget()` 可选返回 `byteCount`、`limitBytes`、`rejectionCount` 与 `peakBytes`；
字节与峰值包含尚未物化的短期准入预留，观察本身不执行回收。
这项上限不包含几何、SDK/历史、CPU 对象和描述符，也不提供整个 scene 的时间/内存预算或世界流送效果结论。

`D3D12RtBlas` 在每次生产准备前，将同一 `D3D12Settings` 快照中的
`[rt] buildCpuMicros`、`buildGpuMicros`、`buildTriangles` 通过专用 FFM setter 交给 native scene；配置热切换不重领当前帧额度。
预算到达时延后尚未开始的 BLAS，未建内容保持 unknown；同帧至多一次 TLAS/代理发布，不能用旧输出冒充更新成功。
取消只退还尚未执行的 GPU 预测、三角形和发布额度；CPU 耗时继续计入，执行后 Signal 失败也不退款。
真实已完成且提交确认的 AS timestamp 才参与 GPU 预测校准，16 份反馈满额时明确背压，不等待 GPU。
首条不可拆分工作与必要发布可能超过软时间片，不能把配置值当作已实现的硬毫秒上限或世界性能验收。

`D3D12GetRtSceneWorkBudget()` 读取当前工作账、未退休反馈、实际 GPU 时间及原始 world/frame 身份。
`rt_stats.json` 和 `rt_validity.jsonl` 的 `scene_work` 在采样时冻结并匹配 scene 的 world/frame，延迟读回不重新查询。
`last_gpu_frame` / `last_gpu_world` 标识实际用于校准的旧 producer，不能当作当前帧 GPU 时间。
完整字段与低层未配置行为见 native 文档的“场景增量工作准入”。

CPU 几何池的顶点、索引与逐 quad 材质分别使用可回收分段 arena，上限为 768 / 96 / 96 MiB。
每条记录独立持有 heap 缓冲，逻辑 offset 与实际缓冲分开；分配优先复用空闲区间，释放时合并相邻区间并收缩空闲尾部。
真内容替换释放旧段，分配和写入失败回滚本次取得的全部段，不再通过扩容复制整个池。

移出的记录进入最多 1024 条、引用内容合计 128 MiB 的 dormant 索引，不加入 live 表或范围镜像。
这项预算包含每条记录的 64 B 描述和三类数据；淘汰时同时释放三类数据段，分配压力也会先淘汰 dormant。
重入必须收到当前完整上传，并逐字节比较 RT 使用的 position/color/UV 和完整索引；沿用既有规则忽略原版 lightmap。
内容相同时复用旧内容版本及池内偏移，重新计算当前材质表的逐 quad ID，并清除镜像缓存；内容不同使用新版本。
section 键保留完整 node 和 layer，不能用左移 section 编码再拼 layer 导致高位别名。
满池仍允许无新增分配的同内容重入；真编辑无法写入时撤销旧 live 资格，不能继续把旧几何当作当前内容。
非空层导出失败时仍保留 section/layer/当前所需版本，与可用记录分开存储，二者不占用同一键。
`publishUpload` 捕获到非空上传但没有 CPU vertex 字节时调用 `markMissing`，撤销旧载荷并保留要求。
整段提交的层要求来自原版已安装 `SectionMesh` 的完整 draw 清单，不来自暂存 map 的键。
没有暂存、仅部分层完成、采集释放/复制失败、顶点为空、draw 数不匹配或缺自定义索引，都不能证明相应层已删除。
这些非空层保留当前要求并撤销旧载荷，当前完整重试才恢复；同一层首次完整字节快照不被后续重试改写。
提交给错误 section 不消费原 section 的暂存；切世界后不可复用旧 epoch 的字节。
只有原版 draw 清单明确移除的层、真实空 mesh 和显式 section 移除才清要求；`UNCOMPILED` 提交不改变当前几何。
自定义索引的来源和 2/4 B 元素大小均来自 `SectionDraw`，缺索引不能合成顺序索引；已安装 mesh 的 index-only 排序不重新暂存。
失败要求的新增、变化和明确删除均推进 revision；旧版本表快照保持只读不变。
只增加要求而没有改变可用载荷时，复用既有几何镜像与 GPU snapshot，native 按更新后的版本表重新认证资格。
容量失败不永久停收：释放空间后，后续完整上传可以重新分配。世界切换清空 active、dormant、待补齐要求和 arena。

`MirrorSlice` 在池锁内复制到独立 direct 缓冲，native 几何快照持有自己的 GPU 资源。
回收 CPU 段不修改既有镜像，也不需要等待 GPU fence；GPU 快照仍遵守本节的提交与退休规则。
`vertexBytes()` / `indexBytes()` / `materialBytes()` 返回保留逻辑 offset 的独立只读导出快照，不借用 arena 内部缓冲。
这些导出包含逻辑地址空洞，不用于生产镜像热路径。
`storageStats()` 分列三类实际数据字节、逻辑高水位、分配数及 dormant 数量/预算。
实际数据字节表示池仍持有的缓冲容量，不含 JVM 对象开销、尚待 GC 回收的数据、direct 镜像、native 或 GPU 资源，不能当作进程内存或总显存。

**限制**：arena 不移动仍在使用的段；空闲区间不能容纳一个完整新段时仍可能分配失败。
当前缺失层由 §7 的局部异步重捕获重试，只有当前完整上传成功才补齐缺口；不能以空间已经释放宣称覆盖恢复。
native 检查快照节点与独立目标清单的并集；预算整组省略的当前内容按冻结有限输出界加入 unknown 代理，没有可信有限界时保留无界 unknown，
同一 node 缺层时也不能将剩余层认证为完整主表面。没有完整当前内容的目标不授予原查询补查资格。
目标源于已观测的当前非空层要求，尚未描述未观测空间、已知空/卸载的独立状态或范围边界。
全无可用载荷但仍有已观测所需目标时，`mirrorsFor` 创建零记录 native delta，scene 发布带有限代理或无界标记的 TLAS。
首次零载荷的 record/vertex/index/quad 仅请求各 16 B 的 raw 容量；复用材质时没有 staging 上传。
真实载荷从最小空 arena 恢复时新建正常容量的 arena 代，沿用当前版本、构建预算与旧资源 fence 保活规则。
代理不授予待建三角形补查或不透明遮挡证明；ready 与零几何构建数不能证明查询支持域完整。
增量镜像已复用不变内容范围并上传完整记录小表；总时间/内存/在途背压尚未完成。
镜像选择变化重新生成紧凑记录表并引用不可变内容，native 只上传新增内容。
`RT mirror` 日志分别记录真实帧号、CPU 选择/导出和 native 上传录制耗时；该上传耗时不是 GPU timestamp。

等待候选期间，native 根据当前世界的 section 版本表和候选快照生成失效集合，移除旧实例并加入 `HITINVALID` 体积（§16.3）。
未受影响的实例继续使用配对的旧镜像；相同集合不重建，集合变化时新建 TLAS 与不可变实例表。
集合变化及完整发布推进全局单调的 sceneRevision，清空或重建场景不会回退；局部变化不清空全屏 NRD/RR/SR 历史，
由 guide/disocclusion 处理受影响像素。scene 自有 AS 拒绝独立入口的 Prepare/Filter/Release，避免绕过发布状态。
世界切换撤销旧场景，首次构建或空输入无可用场景时直通原版。

`_port/rt_world_geometry_check.py --gpu` 验证分批预算、同大小编辑、拓扑增长、共享 BLAS、记录重排/删除/新增、
候选取消、完整交换和静态 TLAS 复用；阻塞队列后读取实际 RayQuery 距离，并检查资源容量去重。
该 GPU 回归还调用生产 `mirrorsFor`，覆盖 17 条记录仅完成 16 条时的静止编辑：
旧快照变为 obsolete，当前版本替换候选，同帧不追加构建，下一真实帧只建剩余一条，实际命中距离从 4 更新到 9。
另有六阶段 GPU 检查：完整 solid、cutout 首次导出失败、当前载荷恢复、cutout 编辑失败、明确移除 cutout、
非空 solid 缺少 CPU 字节。缺层阶段保留 unknown，恢复后命中当前距离；新增纯要求不重新上传镜像，恢复只新建一条 BLAS。
六阶段中的无 CPU 字节阶段用仍注册的旧快照核查 native 撤销旧资格。
`--content-only` 包含 11 项所需版本检查，覆盖失败、恢复、删除、复制异常、半透明排除及世界退出。
`--publication-only` 检查完整原版层清单、部分/全无暂存、null/零字节顶点、取消、错 section/epoch、真实空网格、
不匹配的 draw 数、自定义索引缺失与重试，以及首次完整快照保持。测试使用 `SectionMesh` 的真实接口与空/未编译哨兵。
GPU 回归沿生产 `mirrorsFor`、scene 和原 RayQuery 验证 12 个发布阶段；缺层保持 unknown，完整恢复命中当前几何，
明确删除 cutout 后可命中 solid，真实空段后才允许命中后方表面。`UNCOMPILED` 不改变镜像；仅变化的几何新建 BLAS。
该入口另含 10 项范围目标检查，验证预算省略、整段缺失、零载荷时的元数据、去重、缓存、只读快照及退休。
GPU 另有八阶段目标检查：整段缺失、调用方数组改写、当前载荷恢复、记录/字节预算省略、范围排除、要求退休与缺失重入。
原查询不能越过范围内缺段命中后方 20 m 表面，补齐后实际命中当前 2 m 表面。
新入口拒绝重复 node、没有当前层要求的目标和已启用显式目标后的旧入口降级；失败不改变先前合法场景。
`--content-only` 另含六项零载荷镜像检查，覆盖缺失、整组预算省略、不可变缓存、恢复、范围外及最终要求退休。
五阶段生产 GPU 检查覆盖全部载荷缺失、恢复、再次缺失、新世界首次仅有缺失要求和首次真实载荷恢复，直接读取原查询命中。
`_port/rt_acceleration_check.py --empty-payload-only` 检查零记录 native 准入、代理发布、范围拒绝、取消和 GPU 阻塞期间的资源保活与计费退休。
`_port/rt_pending_frame_check.py --empty-payload` 沿两路 SDK 输入编码执行已知/零记录缺失/恢复六个完整帧：
产品查询和积分器保持原样，缺失帧保留 primary unknown、无有效材质或 SDK 照明、合光使用源画面，恢复帧重新出图。
其中 NRD 实际求值，RR 只验证准备输入；这些检查不代替原存档画面与性能验收。
CPU 录制恢复检查另验待建期间的近场更新、删除、远处上传与同一 scene 连续性。
实际产品 DXR 派发检查失效代理清除样本、邻区继续出图及完整发布后恢复；这部分仍验证旧的整像素失效行为，
不代表逐 lobe 缺样本处理正确。CPU 另验同内容重入、材质刷新、内容变化、待提交/取消/空段、世界切换、淘汰与完整 section 键，
并检查重复编辑的驻留字节稳定、dormant 淘汰释放、相邻空闲区间合并、容量释放后重捕获、异常写入回滚与旧镜像不被覆盖。
`_port/rt_acceleration_check.py` 检查 AS 拒绝、实例表角色、完整 section 键、生产者未提交/在途/完成、
跨 encoder 共享和撤销后的在途寿命；另有场景生命周期/拒绝与驻留检查，覆盖自动失效、原子发布、
静态复用、清空/换世界、代际回退、相同代际内容改写、同帧多列表预算、1024 条淘汰及所有权隔离。
阴影证明检查覆盖新增、编辑、live 缺席、非不透明层拒绝、相同失效集合下的资格变化、取消重录和旧实例表保活。
工程探针不判断世界内白块、闪烁或阴影画质。

### 28.2 FFM 描述符必须与头文件逐项一致

`MCDR_ABI.kt` 里每个 `skipOptional("name", fd(ret, params...))` 的第 0 项是返回类型，其余逐项对应
`MCDR/include/mcdr_abi.h` 的形参。`invokeExact` 做精确类型匹配，差一个参数就抛
`WrongMethodTypeException`；包装层把它折成 `false` / `-1`，表现是功能安静地不执行。

`_port/ffm_signature_check.py` 逐项对拍两者，正常输出 `ffm signatures: 72 binding(s), failed=0`。
改 ABI 时先跑它，再进游戏。


## 28.3 诊断视图：先看覆盖率，再看辐亮度

`[rt] debugView` 的全部取值在 `IllusionConfig.RtDebugView` 里；排查「画面看着像原版」先检查覆盖与辐亮度：

| 视图 | 看到什么 | 结论 |
| --- | --- | --- |
| `COVERAGE` | 白 = G-Buffer 写过，黑 = 没写过 | 大片黑说明重放没覆盖屏幕，追踪自然没有输入 |
| `HIT_DISTANCE` | 灰阶 = 命中距离（64 格归一化），黑 = 打空 | 全黑且覆盖率正常说明射线起点或方向不对 |
| `DIRECT` | 只有追踪出来的辐亮度 | 有值而普通视图像原版，问题在合光之后的上屏 |
| `INDIRECT` | 只有间接项（天空/环境入射） | 直射有、间接没有时画面会显得平，靠它区分缺哪一项 |

返回旧区块时噪点突增或正常合光退回原版，使用分信号有效性视图区分主命中与次级缺口：

| 视图 | 色标 | 诊断边界 |
| --- | --- | --- |
| `PRIMARY_VALIDITY` | 绿：主命中且满足合光覆盖；青：已有主命中但不满足合光覆盖 | 青色可能来自总 invalid 或 G-Buffer 覆盖缺失，结合其它视图判断 |
| `DIRECT_VALIDITY` | 红：直射阴影查询 unknown；深灰：未报告该缺口 | 不表示直射亮度，也不代表历史可信度 |
| `DIFFUSE_VALIDITY` | 红：次级几何 unknown；橙：次级阴影 unknown；黄：两者；深灰：未报告缺口 | 对应本帧实际 diffuse 查询 |
| `SPECULAR_VALIDITY` | 与 diffuse 相同 | 对应本帧实际 specular 查询，未执行的合法零贡献不标缺口 |

四种视图统一优先显示品红的 primary unknown；没有 primary hit 时显示黑色。
状态取自 `gIndirect` 每像素第 4 个原始 uint，bit 0–6 分别为 primary hit、primary unknown、direct unknown、
diffuse geometry/shadow unknown、specular geometry/shadow unknown。RGB 消费继续只读取前三个分量。
追踪自检的常量模式使用独立布局，不用于这些视图。

这些位记录总 invalid 处理前的结果；当前总 invalid 仍会清除主命中标记及光照并触发直通，
分信号视图不会修复该行为，也没有把 unknown 作为当前缺样本传给 NRD/RR。
深灰只表示本帧没有报告对应缺口，不能证明驻留范围之外无几何或 SDK 历史正常。
`_port/rt_signal_validity_check.py` 以受控 trace 结果执行实际积分 HLSL，检查 23 类状态传播与查询需求。
九类确切零贡献情况与可靠阴影基线的辐亮度逐位相等；极小正响应、单通道光源和部分局部可见性仍报告真实缺口。
探针同时检查自发光与环境贡献保留；它不验证硬件场景覆盖、SDK 缺样本恢复或世界内画质。

主表面没有 diffuse 响应时，直射 diffuse 与 diffuse bounce 是有效零贡献，不依赖外部几何。
`RtIntegrate` 分别判断当前材质的 albedo 通道与饱和 metalness：全部 albedo 为零或 metalness 为 1 才跳过这两类查询。
不要按乘积或 epsilon 判断，防止极小非零响应因乘法下溢被省略；单通道正响应同样保留原查询。
主命中、法线、材质、specular 与自发光独立保留；NRD 接收合法零 diffuse，RR 准备颜色仍包含当前 specular 与自发光。
aux 的未查询可见性仅为局部 relief 值，不证明外部无遮挡。该规则只消除无贡献查询引发的假失效；
真实非零 lobe 的缺口、总 invalid 与通用 SDK/合光恢复继续开放。离线完整链入口为 `_port/rt_zero_diffuse_check.py`。

开启 `[rt] invalidationLog`，在正常视图中最多每 250 ms 采样一次输入网格，写入
`<user.dir>/dxrmc/rt_validity.jsonl`。两次 compute 将逐组计数缩为 48 B，复制后使用独立 query snapshot
检查实际生产录制的完成状态；pending 时跳过读回与新采样，取消时丢弃该样本和缓冲，禁止等待或补交命令列表。
20 行合并写入一次，关闭诊断或释放 RT 时写出已完成的余行；在途样本不等待、不落盘。
分组缓冲最多 64 MiB，仅诊断配置开启时分配；此开销不计入正常渲染性能结论。

每行固定保存被采样帧的时间、frame/snapshot/requested snapshot、世界代、scene/live revision、history frame/reset reasons、
相机位置、请求的降噪器/超分枚举、实际 denoise encoding 与 temporal resolve 状态，以及 active/candidate 场景计数。
读取完成时不得改用当前帧元数据。`candidate_built` 是 candidate 的非空 BLAS entry 数量，
不代表整个光照支持域完整；`encoding=0/1/2` 分别表示无降噪结果、NRD 编码结果和线性颜色结果，
不能仅凭请求的枚举断定 SDK 已求值或当前没有逐像素历史拒绝。

| 计数键 | 输入网格上的含义 |
| --- | --- |
| `pixels` / `primary_hit` / `primary_unknown` / `primary_miss` | 实际像素总数及总 invalid 处理前的主查询状态 |
| `direct_unknown` | 主表面直射阴影查询缺口 |
| `diffuse_geometry_unknown` / `diffuse_shadow_unknown` | 当前 diffuse 方向上的次级几何/阴影缺口 |
| `specular_geometry_unknown` / `specular_shadow_unknown` | 当前 specular 方向上的次级几何/阴影缺口 |
| `primary_rejected` | 已知主命中，但 `direct.w` 未保留；可与次级缺口同帧关联 |
| `gbuffer_missing` | 已知且保留的主命中，对应 albedo coverage 不足 |
| `rt_owned` | 已知且保留的主命中，对应 albedo coverage 通过 |

`primary_rejected + gbuffer_missing + rt_owned = primary_hit`。这些是输入网格计数，
不计 SR、jitter 映射和半径淡出后的最终显示像素；它们不是画面质量或 SDK 内部历史的证明。
采样间隔内的短事件可能遗漏，不能据零缺口样本排除未采样帧的异常。
`_port/rt_signal_validity_check.py` 检查实际积分输出的 12 项 GPU 计数，以及非整组边界、缩放 albedo 和多组归约。
`_port/rt_world_geometry_check.py --stats-only` 检查产品采样器的非阻塞读取、帧元数据冻结、取消丢弃、重试与释放。

G-Buffer pass 的视口原点恒为 `(0, 0)`：目标是我们自建的离屏纹理，把主 pass 的 `areaX/areaY` 搬过来会让光栅整体偏移、只画进一块，看上去就是「世界只剩左上角一块」。DEBUG 级每 5 秒记录一次 `rt gbuffer: replay batches=<n> meshlets=<n>`（首帧也记录），用于核对覆盖规模。

进世界实测过的三档（沙漠平地 + 一块方块，`tier=dxr`、`denoise=1`）：

| 视图 | 实测 | 判定 |
| --- | --- | --- |
| `COVERAGE` | 地面与方块是白的，天空是黑的 | 重放覆盖了所有几何，只有天空按定义没覆盖 —— 「进世界看不见」不是覆盖率问题 |
| `COMPOSITE_MARK` | 整屏品红 | 屏幕上的像素确实由合光写出来，上屏这一段通 |
| `SHADOW` | 所有几何同色 | 这一档读 `aux.y`（太阳可见性，见 §9.4）；同色说明太阳可见性全场一致，查阴影要先确认太阳方向与阴影射线的起点偏移 |

`[rt] debugView` 可以**在游戏内热切换**：使用「视频设置 → 光影包 → 光影包设置」选择视图；
TOML 修改也由文件监视线程装入 `D3D12Settings`，不必重启。记录对应事件和视图，最后恢复 `NONE` 验证正常画面。

取证用 `PrintWindow(hwnd, hdc, PW_RENDERFULLCONTENT)` 直接抓窗口，不需要把窗口切到前台：抓之前先确认 `rt frame: dispatch …` 心跳还在走（5 秒一条），心跳停了说明画面已经不再更新，抓到的就是旧帧。

合光成功时打一行 `rt composite: <w>x<h> mode=<n> denoise=<0|1>`；跳过时打 `rt composite: skipped, <原因>`。有前者而屏幕没变，就查上屏，不再查追踪。

`DIRECT` 与 `HIT_DISTANCE` 全黑、`SHADOW` 全场同色而 `COVERAGE` 正常，说明射线一条都没命中：合光对每个像素都走了直通分支（`rt_resolve.hlsl` 里 `direct.w < 0.5 || albedo.a < 0.5`）。此时先用一次「强制命中」判据分辨是追踪没写还是合光没读——把 raygen 末尾的 `direct` / `hitFlag` 换成常量（`direct = float3(0.35, 0.0, 0.35); hitFlag = 1.0;`）后重进世界：

- 整片几何被染上这个常量：追踪写进去了，合光读的也是同一张缓冲，问题在射线或加速结构一侧；
- 画面仍是原版：合光读的不是追踪写的那张缓冲。

这条判据用完必须删掉，否则自检会以 `rt trace: dxr triangle paths maxDiff=1.0` 失败——那条失败本身就是判据生效的证据。确认射线打空后，用两条向下探针二分「几何到底落在哪」：一条从 `g_cameraPos` 向下、一条从 `RtLoadRecord(0, …)` 的段原点向下，命中结果分别写进 R/G 通道。两条都打空且覆盖率正常时，先查实例表的 BLAS 地址与实例变换，再查 `TraceRay` 实际绑定的 AS 视图。
