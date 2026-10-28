# 网格着色器与可变着色率（mesh shader / meshlet · VRS · 渲染比例）

本文整理 D3D12 后端里两块相对独立的子系统：

- **网格着色器管线**：地形（不透明、镂空、OIT 半透明）如何从原版的分段上传数据切出 meshlet，
  再由放大着色器（AS）剔除、网格着色器（MS）展开成三角形。
- **可变着色率与渲染比例**：视频设置里的 VRS 选项、屏幕空间着色率图，以及按百分比缩放主渲染目标。

写法以「从源码确认的事实」为准：所有数值都取自代码常量。凡是源码里没有写死的取舍理由、
或者要看 native 侧才能回答的事情，一律标注 **代码中未明确**，不做推测。

两套机制在代码里**没有共享开关**，但共享同一个「地形批」的入口，互相影响的地方集中在第 6 节。

---

## 1. 能力与开关总表

| 机制 | 需要什么 | 由谁判定 | 缺失时 |
| --- | --- | --- | --- |
| mesh 管线 | FL 12_2 + mesh shader tier ≥ 1 + DXC 可用 | `D3D12MeshShader.isSupported()` | 地形批整批不画，记 ERROR（**没有** IMR 回退） |
| AS 剔除 | mesh 管线 + `[mesh] terrainCull` + VS 里能解析出相机 uniform + 没有 `dxrmc/meshlet_no_as` 标记文件 | `D3D12MeshletShader.resolveCullUniforms`、`tryMeshletDraw` | 回退到「不剔除」的 meshlet 版本 |
| 逐图元距离档位 | 视频设置 VRS = AUTO 且 native 预检通过 | `D3D12VariableShadingRate.push()` | 恒 1X1 |
| 屏幕空间着色率图（Tier 2） | 设备支持 SRI + `[vrs] sri` + 主深度可读 | `D3D12ShadingRateImage.prepareFrame()` | 退回逐图元距离档位 |
| 渲染比例 | D3D12 后端正在驱动渲染 | `D3D12RenderScale.isAvailable()` | 比例只记录不生效，warn 一次 |

三条互相独立的「谁负责什么」边界：

1. mesh 管线决定**谁来产生三角形**（顶点/索引拉取换成 AS + MS 展开）。
2. VRS 决定**每个图元或每个屏幕 tile 用多少着色样本**（1X1 / 1X2 / 2X2）。
3. 渲染比例决定**渲染目标本身多大**，与 VRS 是乘法关系。

---

## 2. 网格着色器管线

### 2.1 数据从哪来：段上传时采集

- 采集点是 `SectionRenderDispatcher$RenderSection.addSectionBuffersToUberBuffer` 的 HEAD
  （mixin `SectionUploadCaptureMixin`）。原版每次把一段某一层（solid / cutout / translucent）
  写进共享的 Uber 缓冲时，这里顺手记一条。
- 采集内容按层：`indexCount`、`vertexBytes`、`indexBytes`，以及切好的 meshlet 列表。
- **不缓存 GPU 句柄**：Uber 缓冲会扩容和整理，句柄每批都要重新从派发路径拿。
- 注册表按 section key 存，`levelIdentity` 变化（切世界、重连）时整体清空并递增 `epoch`。
- meshlet 切分时机由 `[mesh] terrain` 决定：
  - 打开时 `shadowLimit = Int.MAX_VALUE`，**每次上传都切**（派发需要全量段表）。
  - 关闭时只切前 64 次（`MESHLET_SHADOW_UPLOADS = 64`），仅用于「shadow」统计，默认路径零成本。
- 切分过程整段 `catch` 掉（`catch (ignored: Throwable)`）：meshlet 是旁路数据，
  任何异常都**不允许**影响原版的上传主流程。

### 2.2 meshlet 怎么切（`D3D12MeshletBuilder`）

纯 CPU 函数，不碰 GPU：输入一段顶点字节，输出 meshlet 描述符数组。

- 顶点布局固定为 `DefaultVertexFormat.BLOCK`，`VERTEX_STRIDE = 28`：
  - Position `RGB32_FLOAT` @0（12 B）
  - Color `RGBA8_UNORM` @12（4 B）
  - UV0 `RG32_FLOAT` @16（8 B）
  - UV2 `RG16_SINT` @24（4 B）
  - **没有法线**。
- 一个 meshlet = `QUADS_PER_MESHLET = 16` 个四边形 = `16 × 4 = 64` 顶点 = `32` 三角形。
  描述符 `MESHLET_DESC_BYTES = 32`。为什么是 16 个 quad：**代码中未明确**。
- 法线靠绕序算：`cross(p1 - p0, p2 - p0)`；长度 < `1e-6` 视为退化。
- 方向掩码按「面朝哪个轴」分 7 类：
  - `DIR_POS_X = 1 shl 0` … `DIR_NEG_Z = 1 shl 5`（六个纯轴方向）
  - `DIR_NON_AXIS = 1 shl 6`（交叉面、双面材质：草、花、斜面 —— 这类不能背面剔除）
  - 判据 `AXIS_DOMINANCE = 0.85f`：最大分量的绝对值 ≥ `0.85 × |n|` 才算纯轴对齐，否则算 `NON_AXIS`。
- 包围盒量化：`QUANTIZE = 15.9375f`，把 `[0, 16]` 映射到 `[0, 255]`
  （`16 × 15.9375 = 255`，正好塞进 uint8）；解码就是 `q / 15.9375`。
- 打包（8 个 uint32 = 32 B）：
  - `packedMin` = `minXYZ | quadCount << 24`
  - `packedMax` = `maxXYZ | directionMask << 24`
  - 然后依次是 `sectionId`、`localVertexOffset`、`firstQuad`、3 个 0 填充。
  - 注释里的理由：两条描述符正好占一条 64 B cache line；最初设想的 16 B 装不下。
- `isPure = Integer.bitCount(directionMask) == 1`：单方向 meshlet 占比是切分质量的主要指标
  （按朝向分桶后，理论上大部分应为纯方向）。
- `build()` 在 `stride < 28` 或四边形数为 0 时返回 null，调用方必须把它当「切不出」处理。

### 2.3 绘制捕获：把 MDI 列表和段对上（`D3D12MeshDrawCapture`）

原版地形走的是多绘制间接（MDI）命令，参数缓冲在 UPLOAD 堆里，CPU 读不回来
（实测报 `mapBuffer: buffer lacks MAP_READ`），所以只能在调用侧捕获。

- 每条间接参数是 `DynamicGpuData.IndexedDraw(indexCount, instanceCount, firstIndex, baseVertex, baseInstance)`，
  20 B，对应 `D3D12_INDIRECT_ARGUMENT_TYPE_DRAW_INDEXED`。
- 每个段附带 `ChunkSectionInfo(x, y, z, visibility)`，来自 slot 1 的 `CHUNK_DATA_INSTANCED`
  实例流（16 B / 段，`stepRate = 1`）。
- 捕获结果按顶点切片偏移（`slice.offset`）索引；一批里存 `drawCount × 4` 个 int
  （indexCount / firstIndex / baseVertex / baseInstance）。
- **每帧清空**。曾经用过「超过 64 条只留最新 offset」的启发式，会连别的图层的批一起删掉，
  结果日志里出现 `no captured MDI list for offset 0/2920/4620`、地形整片不画。
- 段的键用 `SectionPos.asLong(blockToSectionCoord(x/y/z))`，但写进段表的是**方块坐标**
  （`chunkX/Y/Z` 直接参与距离计算）。用方块坐标当键时实测 369 段只命中 1 段。
- `buildTables()` 的契约：任一段缺 meshlet、或缺这一层，**整批返回 null**。
  没有 IMR 回退，部分派发只会画出缺块的画面，不如整批跳过并留证据。

### 2.4 顶点解码代码生成（`D3D12MeshVertexDecode`）

MS 的顶点属性不是从 IA 阶段来的，得把 spvc（SPIRV-Cross）翻译出来的 VS 输入结构自己填。

- `parseInputFields()` 找 `struct SPIRV_Cross_Input`，逐行匹配 `类型 名字 : 语义;`。
- `parseGpuFormat()` 拆 `GpuFormat` 名字：分量数、位宽、种类。
  - 分量数取「字母个数」和「`R8G8B8A8` 这类分组数」的较大者，再压到 4。
    按字母数算是为了不把 `float3` 解析成 4 分量（会直接让 DXC 编译失败）。
  - 位宽取名字里第一个数字，缺省 32；种类取最后一段（`_UNORM`、`_SINT`、`FLOAT`…）。
- 支持的读取函数：FLOAT、UNORM 8/16/32、SNORM 8/16（**SNORM 32 不支持**）、
  SINT 8/16/32、UINT 8/16/32。
- 不支持的格式退化成 `vin.X = (type)0;` 并留一行注释，不让整条管线编译失败。
- 生成的取址是 `vertexIndex * <stride>u + <offset>u`，即**逐顶点线性寻址**，
  这正是「不需要索引缓冲」的前提。
- `ChunkPosition` / `ChunkVisibility` 不走这里：它们不在顶点流里，见下一节。

### 2.5 MS / AS 生成（`D3D12MeshletShader`）

`build(vsHlsl, format, stride = 28, cull = false): String?`：返回 null 表示「这次生成失败，
调用方回退」。常量：`THREADS = 64`、`MAX_PRIMITIVES = 32`、`VERTEX_STRIDE = 28`。

前置要求：VS 里必须能找到 `SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)`
这个入口（spvc 的产物固定叫 `main`，与 `ms_main` 冲突，所以改名 `vs_main`）。

字段取值优先级：

1. 名字能在 `VertexFormat` 里找到 → 走顶点解码（2.4）。
2. 名字含 `ChunkPosition` → `int3(s.chunkX, s.chunkY, s.chunkZ)`（来自实例流）。
3. 名字含 `ChunkVisibility` → `s.visibility`（float，供原版着色用）。
4. 都不匹配 → `(type)0`，并留注释。

#### 2.5.1 放大着色器（AS）

- `[numthreads(32, 1, 1)]`：每 32 个线程处理 32 个 meshlet（一个线程一个）。
- 载荷 `struct MeshPayload { uint slot[32]; }`，128 B；配 `groupshared` 的计数与数组。
- 线程对应关系 `local = gid.x * 32 + tid.x`，只有 `local < gMeshletCount` 才继续判。
- `cullOn = gCullEnabled != 0`：常量走 b8，切换剔除不用换 PSO
  （这也让「不剔除」成为同一条 PSO 上的 A/B，只需要改上传的常量）。
- 背面掩码（带 1 个方块余量，避免贴面被误剔）：
  - `mn.x < 1.0` → `1`；`mx.x > -1.0` → `2`
  - `mn.y < 1.0` → `4`；`mx.y > -1.0` → `8`
  - `mn.z < 1.0` → `16`；`mx.z > -1.0` → `32`
  - 与 meshlet 的 `directionMask`（低 6 位）按位与非 0 即「这个方向可能可见」。
  - `DIR_NON_AXIS = 64` **单独放行**：漏掉它会在开 `terrainCull` 时让平原上的草和花整片消失。
- 视锥剔除：用段盒子的中心与半径
  - `radius = length((mx - mn) * 0.5)`
  - 中心换到相机相对坐标后过一次 `ModelViewMat`、再一次 `ProjMat`。
  - `W = clip.w`：reversed-Z 下 `m23 = -1`，相机前方的 `w` 为正。
    历史上这里写成 `-clip.w`，导致 64 格外整片被剔。
  - 侧边界用 `radius * length(p0 ± p3)` 量级近似，近边界用 `radius * length(p3.xyz)`。
  - `slack = 64.0`：源码注释明确说 8 太小、会剔掉地平线远处的 meshlet，**不要再调小**。
    代价是远处的剔除收益会打折。
- 存活者用 `InterlockedAdd` 抢一个槽位写进 `groupshared`，最后
  `DispatchMesh(gPayloadCount, 1, 1, gPayload)` —— 空载荷也是合法的「零线程组」派发。

#### 2.5.2 网格着色器（MS）

- `[numthreads(64, 1, 1)] [outputtopology("triangle")]`，输出 64 个顶点、32 个三角形。
- meshlet 编号：没有 AS 时就是 `gid.x`；有 AS 时是 `payload.slot[gid.x]`。
- 解析描述符：`quadCount = (packedMin >> 24) & 0xFF`，
  `vertexCount = quadCount * 4`，`primCount = quadCount * 2`。
- `SetMeshOutputCounts(vertexCount, primCount)` 必须在统一控制流里调用（不是分支内）。
- 顶点索引 = `s.vertexBase + m.localVertexOffset + vid`，
  其中 `s.vertexBase` 是相对**当前绑定的顶点切片**的偏移。
- 三角形用算术索引生成，**完全不读索引缓冲**：
  - `tris[vid*2]     = (baseV, baseV + 1, baseV + 2)`
  - `tris[vid*2 + 1] = (baseV, baseV + 2, baseV + 3)`
  - `baseV = vid * 4`
- 该算术来自原版地形几何的固定绕序假设：段的四边形按 4 顶点一块排列。
  代码里没有对「非地形几何」做这条假设的兜底 —— 所以 mesh 变体只用于地形批（见 2.7）。

#### 2.5.3 资源布局

| 名字 | 类型 | 内容 |
| --- | --- | --- |
| `t14` | `ByteAddressBuffer` | 顶点流，28 B / 顶点 |
| `t15` | `ByteAddressBuffer` | 段表，32 B / 段 |
| `t16` | `ByteAddressBuffer` | meshlet 表，32 B / 条 |
| `b8` | cbuffer | `MeshletGlobals` |

- `SectionDesc`（32 B）：`vertexBase`、`quadBase`、`meshletBase`、`meshletCount`、
  `chunkX`、`chunkY`、`chunkZ`、`visibility`。
  `vertexBase` = 该段在批里的 `baseVertex`（相对顶点切片），`quadBase = baseVertex / 4`。
- `MeshletDesc`（32 B）：`packedMin`、`packedMax`、`sectionId`、`localVertexOffset`
  （= `firstQuad * 4`）、`firstQuad`、3 个填充。
- `MeshletGlobals`：`uint gMeshletCount; uint gCullEnabled; uint pad1; uint pad2; float4 gVrsBand;`
  - `gVrsBand.x` = 近带外沿，`y` = 中带外沿，`z` = 远带速率代码，`w` = 启用位。
  - 四个分量都由 Java 侧每批现算上传（见 3.2），因此改配置能下一帧生效。

#### 2.5.4 矩阵与常量约定

- spvc 产出的 VS 是 `row_major` + `mul(float4, M)`（行向量在左）。
  MS 里必须沿用同一约定：**不要**写 `mul(M, v)`，否则几何整体错位。
- `resolveCullUniforms()` 要求 VS 里同时有 `CameraBlockPos`、`CameraOffset`、
  `ModelViewMat`（`float4x4`）、`ProjMat`（`float4x4`）：缺任何一个就返回 null，
  调用方改为生成**不剔除**的版本。绝不生成「剔除判据全为 0」的 AS —— 那会把整个地形剔没。
- uniform 名字用前缀正则匹配：spvc 可能给名字加前缀，精确匹配会静默漏掉。

### 2.6 管线创建与三级回退（`D3D12Device.createMeshVariant`）

- 顶点格式取 `pipeline.vertexFormatBindings.firstOrNull()`。
- 剔除开关：`pipeline.isCull && !flipY && !location.contains("animate_sprite")`
  （flipY 与精灵动画的绕序特殊，不能交给背面剔除）。
- 像素着色器仍用原版翻译好的 HLSL，编 `ps_6_0`；MS 编 `ms_6_9`、AS 编 `as_6_9`
  （mesh 阶段要求 SM 6.9）。
- 根签名 `D3D12CreateMeshRootSignature(bindings, 1)`，1 表示带 mesh 阶段。
- PSO 描述：`MCDRMeshPipelineDesc().shaders(ms, as, ps)`、逐 color target 描述、
  `depth(GpuFormat.D32_FLOAT.ordinal, writeDepth, depthTest)`、`topology(cull, polygonMode)`。

回退梯子（越往下越保守，每一级都留一条 WARN）：

| 序 | 触发条件 | 处理 | 结果 |
| --- | --- | --- | --- |
| 1 | `build(cull = true)` 返回 null | 重建 `cull = false` | 无剔除 meshlet，仍走 AS 派发路径 |
| 2 | MS/AS 编译失败 | 同上，重建不剔除版 | 无剔除 |
| 3 | 只有 AS 的 PSO 建失败 | 重试不带 AS 的 PSO | MS 直接用 `gid.x` 编号 meshlet |
| 4 | MS 彻底失败 | 记 WARN，meshlet 句柄置 0 | 地形批整批跳过（见 2.7） |

三级回退的实际效果：设备只要支持 mesh tier 1，就先保住「地形还能画」；
只有 MS 编译与 PSO 创建都失败时才会整批不画。

### 2.7 派发（`D3D12RenderPassBackend.drawIndexedIndirect` / `tryMeshletDraw`）

- 地形识别是**按管线名白名单**：`isTerrain(location) = location.contains("terrain") && !location.contains("shadow")`。
  名字错了整条通路会静默空转，所以每个新名字第一次出现时会打一行
  `MDI terrain candidate '…' terrain=… rate=…`。
- 层映射：`solid_terrain → SOLID`、`cutout_terrain → CUTOUT`、`translucent` / `oit_ → TRANSLUCENT`。
  地形**全部**（含 OIT 半透明）只走 meshlet，没有 IMR 回退；
  半透明能这么做的前提是强制打开的 improved transparency（Wavelet OIT）——
  OIT 与顺序无关，所以线程组顺序不影响结果。
- 地形批整个包在 VRS 的作用域里：`scope(ctx, location) { tryMeshletDraw(...) }`（见 3.4）。
- `tryMeshletDraw` 的前置条件（任一不满足就记一条去重的 INFO，然后返回 false）：
  mesh 派发开关、已绑定管线、存在 mesh 变体、能按参数偏移找到捕获的 MDI 批、
  `drawCount` 一致、层名可识别、slot 0 顶点缓冲存在、顶点切片偏移已知、段表 / meshlet 表完整。
- 剔除开关：`cullOn = !Files.exists(<工作目录>/dxrmc/meshlet_no_as)`。
  这是一个同会话 A/B 用的标记文件，只改 b8 里的常量，不换 PSO。
- 上传：段表与 meshlet 表走 staging（256 B 对齐），globals 也是 256 B
  （`gMeshletCount`、`gCullEnabled`、`gVrsBand` 四个 float）。
- 绑定顺序有讲究：先 `D3D12SetPipeline(meshletPipeline)`，再 `pushDescriptors` ——
  设管线会重设根签名，之前推的根描述符会失效。
- 派发量：有 AS 时 `dispatchCount = (meshletCount + 31) / 32`（AS 是 32 线程/组），
  没有 AS 时就是 `meshletCount`（MS 一个线程组一个 meshlet）。
- 派发完恢复 IMR 管线状态；按层累计 `gMeshletSolid / gMeshletCutout / gMeshletTranslucent`，
  每 2 秒打一行 `meshlet dispatch: draws=… meshlets=… layer=… totals[…]`。
- 失败（`!drawn`）会打 ERROR：`terrain batch not drawn (mesh path unavailable): …`
  —— 这是「地形不见了」的第一现场，比静默 fallback 更有用。
- 启动时若 `[mesh] terrain` 为真但 `D3D12MeshShader.isSupported()` 为假，
  后端创建阶段就会告警（此时地形必然不画）。

### 2.8 性能上换到了什么

- **省掉索引缓冲带宽**：三角形由线程组 ID + 算术索引生成，顶点按 `vertexBase + localVertexOffset` 线性取。
- **一个线程组 32 三角形**（64 线程 → 每线程 2 个三角形），比传统 IA 的拉取粒度更大。
- **AS 换掉视锥外的 MS 与光栅化**：代价是一次额外派发与 128 B 组内载荷；
  `slack = 64` 是为了不误剔，会牺牲一部分剔除率。
- **段表每批现传**：两条 32 B 描述符 + 段表，量很小，换来「不需要在 GPU 上维护持久缓冲」。

---

## 3. 可变着色率

### 3.1 选项只有 OFF / AUTO

- 入口在 vanilla 视频设置的第四组「Illusion 设置」里，是一个循环按钮。
- 档位只有两档：**OFF** 与 **AUTO**（早期的手动档位已经删除，只剩枚举名兼容）。
- 持久化到 `options.txt` 的 `dxrMcShadingRate`，存的是枚举名。
  读取时：精确匹配枚举名；名字以 `RATE_` 开头（旧的手动档位）当 AUTO；其余当 OFF。
- `isEnabled() = 档位 == AUTO && !nativeRefused`；
  `isAvailable() = D3D12AdapterInfo.current != null`（非 D3D12 后端时按钮置灰）。
- `push()` 会把「AUTO 要求的最高速率」（2X2）推给 native 做能力预检：
  - 返回 1：接受。
  - 返回 0：`nativeRefused = true`，此后恒 1X1（一次性闩锁，不再重试）。
  - 返回 `Int.MIN_VALUE`：DLL 没有该导出（老版 native）。
  - 其他：记录错误。
- 速率代码与名字的对应（`d3d12.h` 枚举值）：
  0x0 = 1X1、0x1 = 1X2、0x4 = 2X1、0x5 = 2X2、0x6 = 2X4、0x9 = 4X2、0xA = 4X4。
  实际只会用到 1X1 / 1X2 / 2X2 三种。

### 3.2 逐图元距离档位（唯一的「逐物体」控制）

粒度为**图元**：MS 给每个三角形写 `SV_ShadingRate`。

- 生成条件：`D3D12MeshletShader` 成功解析出相机 uniform（`cu != null`）。
  缺 uniform 时 MS 不写 `SV_ShadingRate`，组合器取 MIN，粗档被压回 1X1 —— 这是安全的失败方向。
- 定档用的是**段盒子**（16³ 方块）而不是 meshlet 盒子：
  - `vrsBase = chunk - CameraBlockPos + CameraOffset`
  - `vrsDistance = max(0, length(段中心) - length(半对角))`（即到段的最近距离下界）
  - 同一段内所有 meshlet 共用一个档位。
  - 理由（源码注释）：如果按 meshlet 盒子各自判，同一个段里会同时出现 1X2 与 2X2 的细碎拼块，
    TAA 夹紧之后就是一片闪烁；整段同档则边界只出现在段与段的交界。
- 三个区间（`gVrsBand` 来自 `D3D12VariableShadingRate.msBand()`）：
  - `d < near`：1X1（`near` 默认 32 方块，范围 0..512）
  - `near ≤ d < far`：1X2（`far` 默认 96 方块，范围 0..2048）
  - `d ≥ far`：远带档位（1X2 或 2X2，取决于下面的 cap）
- cap 由超分状态决定：`terrainRateCap() = if (超分开启) 1X2 else 2X2`。
  58% 的输入比例下再叠 2X2，等于只剩约 1/4 的着色样本，
  实测「VRS 关掉平稳、开启后全屏跳」就是这么来的；100% 比例仍允许 2X2。
- 白名单：只有 `solid_terrain` 可能变粗。
  `shadow` 与不含 `solid_terrain` 的一律 -1（不变）。
  `cutout_terrain`（叶子、栅栏的镂空）与 `translucent` / `oit_` 一律 1X1 ——
  粗像素会把相邻的 alpha 混进同一样本，叶子边缘直接破洞。
- 两个距离带都是**每批次现读配置**（`IllusionConfig.SPEC.isLoaded` 守卫，
  未加载时用常量 32.0 / 96.0 兜底），所以改 toml 后下一帧就生效。

### 3.3 屏幕空间着色率图（Tier 2）

逐图元的距离带有个天生缺点：段与段的交界是一条几何上的硬边，缺少屏幕内容的信息时无法避开
地平线。Tier 2 的着色率图就是那个「按屏幕内容修正」的环节。

- 一张图像，每个 tile = `TILE = 16` × 16 像素，
  网格尺寸 `(width + 15) / 16` × `(height + 15) / 16`。
- 每帧一张 compute 的判据（`cs_6_8`，`[numthreads(8, 8, 1)]`，一个线程一个 tile）：
  - 读 tile 四角的深度：`rel = (dmax - dmin) / max(dmin, 1e-4)`
  - `extreme`：`dmin ≤ 1e-5`、`dmax ≥ 0.99999`（近/远平面）或出现 NaN
  - `edge`：`rel > edgePermille × 0.001`（默认 `EDGE_PERMILLE = 50`，即 5% 相对深度差）
  - `moving`：运动矢量长度 > `MOTION_PIXELS`（默认 8 像素/帧）
  - 默认速率 `2X2`；`extreme || edge || moving` 任一成立 → 1X1。
  - 也就是说：**只有既平坦又静止的 tile 才允许变粗**。
- 强制全细（`gPad` bit0）：每个 tile 都写 1X1 并且立即返回。
  触发源：进出世界（登录/退出事件）、相机跳变（一帧位移 > 8.0 方块或转角 > 30.0 度）、
  资源刚重建、上一次 compute 失败。
- 时间钳制（`gPad` bit1）：`rate = min(rate, 上一帧同 tile 的档位)`。
  枚举值按面积单调递增，取 min 就是「不比上一帧更粗」，
  用来消除静态镜头下边缘的呼吸感；代价是一张额外的 ping-pong 图像。
- 两张图像 ping-pong（`images[2]`，各自一张 SRV，`views[2]`）：
  上一帧的图既作为 `t2` 读入，当前帧写另一张；写完后 `historyValid = !forceFine`，
  一旦强制全细就作废历史。
- 深度来源：**复制**主深度到一张自己持有的快照纹理（必须用同一格式 `D32_FLOAT`；
  换 `R32_FLOAT` 会直接 `format mismatch`）。HLSL 里按 `Texture2D<float>` + `Load` 读。
  - 复制发生在每帧清除之前，此时主深度还是上一帧的最后一笔 —— 这正是着色率图想要的「上一帧画面」。
- 运动矢量来自速度通道（`D3D12Velocity.previousMotionView()`）。
  通道没开（既没 SMAA/TAA 也没 DLSS）时退化成只看深度：`motionPixels = Int.MAX_VALUE`，
  并把深度视图当第二张 SRV 绑上去（仍然满足着色器签名）。
- 每帧命令序列：开命令列表 → 复制深度 → 一次 compute 派发
  （组数 `(tilesX + 7) / 8, (tilesY + 7) / 8`）→ 提交 → 交换写指针。
- 挂点：帧图构建的 execute 开头（`@Synchronized`）。以下任一情况直接返回：
  失败闩锁已置、VRS 非 AUTO、`[vrs] sri` 关、主深度不是可读的 GPU 纹理。
- 失败策略：任何一步失败都置 `failed = true`（**永久自关**），
  之后只剩逐图元距离档位 —— 与速度通道的处理一致：宁可少省，不要画面坏掉。

### 3.4 组合器与作用域

D3D12 的着色率由两级组合器合成：stage0 处理基率与逐图元（`SV_ShadingRate`），
stage1 处理上一步结果与着色率图。枚举值：`PASSTHROUGH = 0`、`MIN = 2`（取更细的一档）。

| 着色率图是否在场 | stage0 | stage1 | 效果 |
| --- | --- | --- | --- |
| 不在场 | MIN | PASSTHROUGH | 只用逐图元距离档位，且与基率取更保守者 |
| 在场 | PASSTHROUGH | MIN | 逐图元档位不再参与，由图像与基率决定 |

在场时把 stage0 切到 PASSTHROUGH 的原因（源码注释 + 实测）：不切的话，
32–96 方块那一圈 1X2 会盖住图像，用户看到的是地平线一条边缘在闪。
启动时会推一次并打 `VRS combiners = MIN / PASSTHROUGH.`。

- 组合器必须在 `SetShadingRateNow` **之前**设置：`RSSetShadingRate` 把基率与两个组合器
  一起写进命令列表状态。设置函数是幂等的（缓存上一次的值）。
- 作用域 `scope(ctx, location, body)`：
  - `rate <= 0` → 直接执行 `body()`，AUTO 之外零 native 调用。
  - 否则：设组合器 → `D3D12SetShadingRateNow(ctx, rate)` 成功才继续 →
    同一条命令列表上 `bind` 当前着色率图 → 执行 `body()` →
    `finally` 解绑图像 + `SetShadingRateNow(ctx, 1X1)`。
  - 计数 `scopedBatches / scopedImages / scopedRestores` 用于 F3 与回归对比：
    三者应当同增，缺一即为泄漏。
- `hardReset(ctx)`：GUI 开始绘制前防御性复位（解绑图像 + 恢复 1X1），
  只在 VRS 非 OFF 时调用。界面不参与粗档位，避免 UI 文字被 2X2 糊掉。

---

## 4. 渲染比例

渲染比例是**视频设置里的滑条**（不再读模组的 toml），持久化在 `options.txt` 的两个键：
`dxrMcRenderScale`、DLSS 模式专用的 `dxrMcRenderScaleDlss`。

| 模式 | 最小 | 最大 | 步进 | 默认 | 档数 |
| --- | --- | --- | --- | --- | --- |
| 普通 | 25% | 400% | 25% | 100% | 16 |
| DLSS | 25% | 100% | 1% | 58% | 76 |

- **记账尺寸不变**：主目标的逻辑宽高（以及 GUI 的正交投影、每帧的尺寸比对）仍然按窗口算，
  只有真实的颜色/深度附件按百分比缩放（构造与 resize 两处 mixin）。
  渲染链的输出尺寸取自纹理本身（渲染通道的 `outputWidth/height`、blit 的 viewport/scissor），
  呈现时用线性采样器拉伸到 back buffer（独立的一次 texture→texture blit）。
- `appliedPercent`：附件**实际**按哪个比例建的。世界层的辅助目标（半透明、粒子、天气、云、
  后处理内部目标）必须按 `appliedPercent` 而不是滑条值分配，否则帧中间会出现
  「主目标 640×360、世界层 290×163」这种不一致，OIT 的深度范围 pass 会直接抛
  `Depth texture: size does not match expected attachment size`。
- 重建主目标**只能**用 `Minecraft.schedule`，不能用 `execute`：后者在渲染线程内联执行，
  会把本帧仍在使用的深度纹理关掉（`IllegalStateException: Depth texture is closed`）。
  重建就是拿窗口尺寸再调一次 `gameRenderer.resize`（它会无条件 destroy + create 附件）。
- `scaled(value)`：比例 100% 或 `value <= 0` 原样返回；后端不是 D3D12 时
  **只 warn 一次**并原样返回（GL 呈现按窗口尺寸读主目标会报 `Source texture … is not large enough`）。
- 放大（> 100%）时附件维度用设备的 `maxTextureSize` 封顶；
  放大只做线性采样，没有盒式降采样，所以超采样档会偏软、边缘有轻微闪烁。
- DLAA 锁：锁定时比例恒 100，且所有入口（滑条、程序化设置、DLSS 切换）都必须经过同一个
  `setPercent` 才能生效；切换 DLSS 模式时若正被锁，不能把用户记忆值写成 100。
- 超分门面在「需要 100% 自愈」时用 `setPercentAtFrameBoundary()`（内部就是 `schedule`），
  避免在渲染线程中途改尺寸。

### 4.1 地形 mip bias（渲染比例 → 采样质量）

地形图集是**低分辨率渲染 + 上采样**最容易露馅的地方：比例一降，屏幕像素对应的图集纹素变少，
mip 会选得偏高（更模糊）。

- 公式：`bias = log2(renderScale) - 0.5`，夹到 `[-16, 0]`（只往清晰一侧偏，不会更糊）。
- 生效条件：
  - 超分必须处于开启状态；
  - `scale >= 1.0` 且只有 DLSS（不是 DLAA）时才需要偏置；
  - `bias == 0` 时**原样返回原采样器对象**（保持身份不变，原版 sampler 缓存与生命周期照旧）。
- 缓存键：`(addressModeU/V, minFilter, magFilter, maxAnisotropy, maxLod, bias)`；
  被替换掉的旧采样器进 `retired` 列表，不当场 close（上一帧的命令列表可能还在用），
  由设备侧统一 `release()`。
- 只挂地形（`ChunkSectionsToRender`）的采样器参数，**不能改全局采样器缓存** ——
  否则 GUI、实体、字体一起变糊。

---

## 5. 失效与回退一览

| 位置 | 失效表现 | 回退 |
| --- | --- | --- |
| mesh 能力查询 | `isSupported()` 为假 | 地形整批不画 + ERROR（无 IMR 回退） |
| AS 生成 | 缺 camera uniform / 生成失败 | 不剔除的 meshlet 版本 |
| AS PSO | 创建失败 | 不带 AS 的 MS（`gid.x` 编号） |
| MS PSO | 创建失败 | mesh 句柄 0 → 地形整批不画 + ERROR |
| 段表 / meshlet 表 | 任一段缺数据 | 整批返回 null（不部分派发） |
| VRS 预检 | native 拒绝 | 恒 1X1（一次性闩锁） |
| 着色率图 | 任一步失败 | 永久自关，退回逐图元距离档位 |
| 着色率图 | 相机跳变 / 进出世界 | 强制全细一帧，历史作废 |
| 渲染比例 | 后端不是 D3D12 | warn 一次，比例不生效 |
| 地形采样器 | bias 为 0 | 返回原采样器对象 |

---

## 6. 耦合点（两块机制在哪里互相影响）

1. **mesh 的逐图元档位是 VRS 侧算出来的。** MS 自己不读配置，`gVrsBand` 的四个 float
   来自 `D3D12VariableShadingRate.msBand()`，每批次上传。VRS 为 OFF 或 native 拒绝时启用位为 0，
   逐图元档位随之失效 —— 但 `SV_ShadingRate` 能否写入仍由「能否解析出相机 uniform」单独控制，
   两个条件相互独立。
2. **图像在场时逐图元档位被主动放弃。** 3.4 的 stage0 = PASSTHROUGH 意味着「图像优先于距离带」。
   所以开 `[vrs] sri` 之后，`terrainNearBlocks` / `terrainFarBlocks` 不再是唯一的档位来源。
3. **渲染比例与 VRS 是乘法关系，且都作用于屏幕像素。** 比例降到 58% 时屏幕像素约剩 1/3，
   再叠 2X2 就等于每 2×2 个（已经变少的）像素只算一次着色 ——
   这就是 `terrainRateCap()` 在超分开启时把上限压到 1X2 的原因。
4. **着色率图的网格随渲染比例变化。** tile 数由渲染目标的宽高算
   （`(w + 15) / 16`），所以改比例会重建图像与深度快照，历史需作废（走强制全细）。
5. **地形 mip bias 只跟着渲染比例走，与 VRS 无关。** 调 VRS 不会顺带改变采样清晰度。

---

## 7. 观测与取证

- F3 调试行（VRS 条目）：`VRS: OFF (no RSSetShadingRate) | caps …`、
  `VRS: AUTO unsupported by this device (staying 1X1)`、
  `sri 512x288@2560x1440 imgs=… clamp=on <cap>` 或 `<cap> near=32 far=96`，
  末尾带 `batches=… restores=…`。`imgs` / `restores` 不跟 `batches` 同增就是作用域泄漏。
- 关键日志：
  - `mesh support=… reason=…`（能力查询，带 native 给的原因字符串，缓冲 512 B）
  - `MDI terrain candidate '…' terrain=… rate=…`（名字白名单取证）
  - `meshlet dispatch: draws=… meshlets=… layer=… totals[…]`（每 2 秒）
  - `terrain batch not drawn (mesh path unavailable): …`（ERROR，地形不见的第一现场）
  - `section upload #…` / `meshlet shadow: …`（段采集与 `pure` 占比）
  - `main render target attachments <w>x<h> -> <w>x<h> (renderScale=<n>%)`
  - `VRS combiners = MIN / PASSTHROUGH.`（启动时推一次）
- 诊断开关：`[debug] meshDiagnostics`（NONE / CLEAR / PASS）做一次性回读隔离测试，
  用来区分「回读路径坏了」与「这一趟根本没落到纹理上」；`[debug] selfTestMesh`
  做完整的 DXC 编译 + mesh PSO + DispatchMesh + 回读自检；
  `[debug] selfTestCompute` 是着色率图依赖的 compute 路径自检。

---

## 8. 配置项

以下都在 `config/dxr_mc-client.toml`。

| 表 | 键 | 默认 | 含义 |
| --- | --- | --- | --- |
| mesh | `terrain` | `true` | 地形走 mesh 管线（唯一路径）。需 12_2 / tier 1；关闭后地形不画，只用于隔离回归 |
| mesh | `terrainCull` | `false` | 用 AS 剔除 meshlet（6 向背面掩码 + 视锥）；需 `terrain` 为真，无 mesh tier 时忽略 |
| vrs | `terrainNearBlocks` | `32.0`（0..512） | 比这更近的段恒 1X1 |
| vrs | `terrainFarBlocks` | `96.0`（0..2048） | 远带的起点，应明显大于近带（约取视距一半可让 2X2 边缘避开地平线） |
| vrs | `sri` | `true` | 每帧生成 Tier 2 着色率图；关闭后只剩逐图元距离带 |
| vrs | `sriEdgePermille` | `50`（0..1000） | 判为边的深度对比阈值，1000 = 关闭该项 |
| vrs | `sriMotionPixels` | `8`（0..256） | 判为运动的像素阈值，仅在速度通道开启时生效 |
| vrs | `sriTemporalClamp` | `true` | 不允许比上一帧更粗；代价是一张额外 ping-pong 图 |
| debug | `meshDiagnostics` | `NONE` | `CLEAR` / `PASS` 一次性回读隔离测试 |
| debug | `selfTestMesh` | `false` | mesh 自检（在启动路径之外） |
| debug | `selfTestCompute` | `false` | compute 自检（着色率图的地基） |

另外两项在**视频设置**里，不写进 toml：渲染比例滑条（`options.txt` 的 `dxrMcRenderScale`
/ `dxrMcRenderScaleDlss`）与 VRS 档位（`options.txt` 的 `dxrMcShadingRate`）。

---

## 9. 文件导航

路径相对于 `Illusion\src\main\kotlin\dev\nts\expdxr\`。

| 文件 | 一句话职责 |
| --- | --- |
| `d3d12\render\advanced\mesh\D3D12MeshShader.kt` | 唯一的能力查询出口：12_2 + mesh tier ≥ 1 + DXC 是否齐备 |
| `d3d12\render\advanced\mesh\D3D12MeshletBuilder.kt` | CPU 侧切 meshlet：量化 AABB、方向掩码、32 B 打包 |
| `d3d12\render\advanced\mesh\D3D12MeshletShader.kt` | 生成 MS/AS 的 HLSL 文本（AS 剔除、算术索引、可选的逐图元档位） |
| `d3d12\render\advanced\mesh\D3D12MeshDrawCapture.kt` | 捕获原版每帧的 MDI 段列表，拼出段表与 meshlet 表 |
| `d3d12\render\advanced\mesh\D3D12MeshVertexDecode.kt` | 把 VS 输入结构逐字段翻译成 ByteAddressBuffer 解码代码 |
| `d3d12\render\advanced\mesh\D3D12SectionGeometry.kt` | 段几何注册表：段上传时采集并切 meshlet（切分失败静默） |
| `d3d12\render\advanced\terrain\D3D12TerrainSampler.kt` | 地形图集专用 mip LOD bias（随渲染比例与超分状态） |
| `d3d12\render\advanced\vrs\D3D12VariableShadingRate.kt` | VRS 选项、距离带、作用域、组合器、F3 文本、options.txt 持久化 |
| `d3d12\render\advanced\vrs\D3D12ShadingRateImage.kt` | Tier 2 着色率图：16×16 tile 的逐帧 compute、ping-pong、失败闩锁 |
| `d3d12\render\advanced\vrs\D3D12RenderScale.kt` | 渲染比例滑条、附件尺寸换算、主目标重建、`options.txt` 两个键 |
| `d3d12\backend\D3D12RenderPassBackend.kt` | 地形识别、VRS 作用域包裹、meshlet 派发与前置条件检查 |
| `d3d12\render\adapter\D3D12Device.kt` | mesh 变体创建与三级回退；地形采样器 `release()` 调用点 |
| `d3d12\render\adapter\D3D12AdapterInfo.kt` | 设备能力字段：`shadingRateTier` / `shadingRateTileSize` / `additionalShadingRates` |
| `misc\settings\D3D12Settings.kt` | 把 `IllusionConfig` 解析成 `meshTerrain` / `meshTerrainCull` 等属性 |
| `IllusionConfig.kt` | 配置定义与注释（`[mesh]` / `[vrs]` / `[debug]` 三张表） |
| `misc\gui\D3D12VideoSettingsContent.kt` | 视频设置第四组里注册渲染比例与 VRS 两个控件 |
| `d3d12\debug\D3D12VrsDebugEntry.kt` | 把 VRS 那一行挂进 F3 |

---

## 10. 文档维护

- 本文的事实来源只有上面列出的源码，**不要**在这里引入设计草案、工单编号或仓库外的资料；
  需要解释「为什么」而源码没写清楚时，就写「代码中未明确」，不要补故事。
- 改动下列任一处请同步本文：
  - `D3D12MeshletBuilder` 的 `QUADS_PER_MESHLET` / `VERTEX_STRIDE` / 方向位定义 / 量化系数；
  - `D3D12MeshletShader` 的 `THREADS` / `MAX_PRIMITIVES` / `slack` / 载荷大小 / 描述符布局；
  - `D3D12VariableShadingRate` 的档位枚举、`terrainRateCap()`、白名单与组合器切换条件；
  - `D3D12ShadingRateImage` 的 `TILE`、`EDGE_PERMILLE`、`MOTION_PIXELS`、跳变阈值；
  - `D3D12RenderScale` 的比例梯度、`appliedPercent` 语义、DLAA 锁；
  - 两张配置表的默认值与范围（第 8 节）。
- 新增数值时请写「常量名 + 值 + 单位」，只写值会在下一次改动后变成谣言。
- 回退梯子（2.6）与失效表（第 5 节）是最容易过期的两处：改了任一级 fallback 都要同时改两张表。
- 若某个结论来自实机观察而不是代码（例如「2X2 叠 58% 会全屏跳」），
  请标明是实测结论，别让它读起来像常量推导。
