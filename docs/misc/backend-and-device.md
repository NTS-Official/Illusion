# D3D12 后端与设备 / 资源层

本文说明 Blaze3D 的 GPU 抽象（`GpuBackend` / `GpuDevice` / `CommandEncoder` / `RenderPass` / 资源四件套）
在 D3D12 上是怎么落地的：对象由谁持有、在哪一刻释放、内存怎么分块复用、哪些数值一旦改动就会立刻出错。

范围：`Illusion\src\main\kotlin\dev\nts\expdxr\d3d12\` 下的 `backend\`、`render\adapter\`、`render\core\`
三个包，以及它们调用的 C ABI（`MCDR.dll`）。**不展开**：GLSL→SPIR-V→HLSL→DXIL 的编译细节、
呈现与后处理管线（SMAA/TAA/VRS/DLSS）、meshlet 几何构建算法、配置系统。

本文所有数值与阈值都取自代码；代码没有写清楚的地方会显式标「代码中未明确」。

---

## 1. 三条不变量

先记住这三条，后面大部分设计都是它们的推论。

1. **JVM 侧只做翻译与记账，GPU 状态全在 native。**
   Kotlin 里没有一个 `ID3D12*` 指针，只有 `Long` 句柄；**没有公开的 barrier API**，资源状态由 native
   在每个使用点（render pass 附件、拷贝、绑定顶点/索引缓冲、写描述符）自动 transition。
   应用层不需要、也没有办法声明资源状态。
2. **一个设备一个共享编码器。**
   `D3D12Device.createCommandEncoder()` 永远返回同一个 `D3D12CommandEncoderBackend` 实例，整帧的
   所有 pass 与帧中后处理都录进同一条命令列表。临时需求（例如截图回读）走
   `constructor()`（`device == null`）另建一条短命命令列表。
3. **失败即崩溃，不回退。**
   任何自检失败、管线创建失败都走 `D3D12Fatal.crash(...)`，抛出的是 `ReportedException` 而不是
   `BackendCreationException`，所以原版「试用下一个候选后端」的循环不会把它吞掉。同时把
   `preferredGraphicsBackend` 重置为默认值，下次启动不会再选中 D3D12。

为什么是这个形状：

- Blaze3D 的三层抽象（设备 → 命令编码器 → render pass）与 D3D12 的对应物不是一一对应：D3D12 的
  render pass 只是「一组 RTV/DSV + viewport/scissor + 资源状态转换」，所以 `D3D12RenderPassBackend`
  在 JVM 侧几乎只是录制转发，真正的状态机在 native。
- 26.3 的前端只把 `BackendRenderPipeline.CreateInfo` 交给后端，**拿不到 `RenderPipeline` 对象**，
  而 GLSL→HLSL 需要管线定义（shader 路径、defines、绑定布局）。于是有 `D3D12PipelineBridge`
  这张按 `pipeline.location` 索引的旁路表。这是契约缺口带来的补丁，不是设计偏好。
- 前端同样不暴露 `GpuDevice` 的 backend 实例（`CommandEncoder.backend()` 是 `protected`），而帧中
  后处理要在同一帧、同一条命令列表里追加 pass，所以设备把当前帧的编码器挂成伴生静态字段
  （`D3D12Device.sharedCommandEncoder()`）。

---

## 2. 对象模型

### 2.1 全景图

```
Blaze3D 接口层
  GpuBackend ── D3D12Backend        getName / loadLibrary / createWindow / createDevice
      │
      │ createDevice()
      ▼
  GpuDeviceBackend ── D3D12Device   deviceInfo · timestampCtx · glslCompiler
      ├── pipelineCache      : IdentityHashMap<RenderPipeline, D3D12CompiledRenderPipeline>
      ├── pipelineCacheFlipY : HashMap<FlipYKey, D3D12CompiledRenderPipeline>   ← 值语义键
      ├── shaderCache        : HashMap<ShaderCompilationKey, D3D12IntermediaryShaderModule>
      ├── sharedCommandEncoder ─────► 全设备唯一，createCommandEncoder() 每次返回它
      └── 资源工厂：createBuffer / createTexture / createTextureView / createSampler /
                    createTimestampQueryPool / createSurface / compilePipeline
      │
      ▼
  CommandEncoderBackend ── D3D12CommandEncoderBackend
      ├── ctx : Long                   native CommandContext*
      ├── transientMemory              UBO ring(3×4096B) + staging 池(3 块，512KB 起)
      ├── currentRenderPass ────────► D3D12RenderPassBackend    每次 createRenderPass 新建
      ├── pendingSubmit                异步渲染线程握手中（多帧飞行）
      └── companion GLOBAL_CALLBACKS   设备级围栏回调队列
      │
      ├── createRenderPass() ───────► D3D12RenderPassBackend
      │        renderArea · outputWidth/Height · hasDepth · colorTargetHandle · flipY
      │        uniforms: Map<String, Any> · pendingVertexBuffers · pipeline
      │
      └── 资源：JVM 侧只持有 Long 句柄，真正的 D3D12 对象在 native
               D3D12GpuBuffer              ──► native Buffer（UPLOAD / READBACK / DEFAULT）
               D3D12GpuTexture             ──► native Texture（views 引用计数）
                 └ D3D12GpuTextureView     ──► native View（baseMip + levelCount）
               D3D12GpuSampler             ──► native Sampler
               D3D12GpuQueryPool           ──► native QueryPool（读取阻塞）
               D3D12GpuSurface             ──► native SwapChain（3 个 back buffer）
               D3D12CompiledRenderPipeline ──► PSO（+ meshletPso / lineQuadPso）

native 侧（MCDR.dll 进程内单例，JVM 看不到）
  ID3D12Device · ID3D12CommandQueue · ID3D12Fence(queueFence) + queueFenceValue
  drawHeap           shader-visible CBV_SRV_UAV，4 段 ring，每段 155648 槽
  srvHeap 262144 · rtvHeap 2048 · frameRtvHeap 256 · dsvHeap 256 · samplerHeap 2048
  gPendingDeletes    延迟删除队列，按围栏值结算
  每 encoder 一个 CommandContext{ allocator, commandList, listOpen, fenceValue, drawHeapSlotBase }
  可选：异步渲染线程 + 分区描述符分配器 + bundle 录制池
```

### 2.2 持有关系与释放责任

| 对象 | 由谁创建 | 谁长期持有 | 谁负责销毁 | 实际释放时机 |
| --- | --- | --- | --- | --- |
| `D3D12Backend` | 模组启动时注册进 Blaze3D | Blaze3D 的后端注册表 | 进程级，无需销毁 | 进程退出 |
| `D3D12Device` | `D3D12Backend.createDevice` | 前端 `GpuDevice` | `D3D12Device.close()` | 窗口销毁之后、进程退出之前 |
| 共享 encoder | `D3D12Device.createCommandEncoder`（首次） | `D3D12Device` | `D3D12Device.close()` 显式调 `D3D12DestroyCommandEncoder` | 设备关闭时 |
| 一次性 encoder | 各处 `new D3D12CommandEncoderBackend()` | 调用方 | `close()`（内部会 `D3D12DestroyCommandEncoder`） | 用完即关 |
| `D3D12RenderPassBackend` | encoder 的 `createRenderPass` | encoder 的 `currentRenderPass` | `submitRenderPass()` 或 encoder `close()` 兜底 EndPass | 每个 pass 结束 |
| `D3D12GpuBuffer` | device / `D3D12TransientMemory` | 调用方（transient 的由 frame 列表持有） | `close()` → `ID3D12DestroyResource` | 排进 `gPendingDeletes`，围栏通过后真正 `delete` |
| `D3D12GpuTexture` | device | 调用方 | `close()`；**views > 0 时只置关闭标志** | 最后一个 view `close()` 之后 |
| `D3D12GpuTextureView` | device | 调用方 | `close()` → `removeViews()` + 销毁视图句柄 | 同上（延迟到 GPU 空闲） |
| `D3D12CompiledRenderPipeline` | `D3D12Device.compilePipeline` | `pipelineCache` / `pipelineCacheFlipY` | `clearPipelineCache()` 或资源重载 | 缓存清理时（含 meshlet/宽线变体） |
| `D3D12GpuSampler` | device | 调用方 | `close()` | 立即 / 延迟删除 |
| `D3D12GpuQueryPool` | device | 调用方 | `close()` | 延迟删除 |
| `D3D12GpuSurface` | device `createSurface` | 前端窗口 | `close()` | 窗口销毁时（耗时会被记录，慢会表现为窗口灰几秒） |
| `D3D12AsyncContext` | 设备 `init` 里的首次 `get()` | 单例 | `shutdown()`（先 `awaitTermination(2s)`） | 设备关闭时 |

一句话记法：**设备持有编码器与管线缓存；编码器持有瞬时内存与当前 pass；资源归调用方（前端）**。
transient 里靠 frame 列表持有的那些缓冲是唯一「不由前端持有」的例外。

### 2.3 不是对象的东西

以下状态只在 native 里，但它们决定了 JVM 侧的写法：

- **`drawHeap`**：shader-visible 的 CBV/SRV/UAV 堆，按帧段轮转。JVM 侧看不到它，只能通过
  `D3D12PushDescriptors` 往里写；`SetDescriptorHeaps` 在 begin 与每次 push 时由 native 重绑。
- **`queueFence` + `queueFenceValue`**：整条队列一个围栏、一个单调递增值。JVM 侧拿到的 `GpuFence`
  只是「当前值 + 1」的 token，见 §3.7。
- **`gPendingDeletes`**：所有销毁请求的中转站，见 §3.6。
- **`gOpenListCount`**：当前有多少条命令列表处于打开状态；延迟删除用它判断「有没有在飞的引用」。

---

## 3. 生命周期

### 3.1 初始化顺序（顺序错了就是崩溃或静默失效）

1. 模组构造：注册 `D3D12Backend`，读取配置（`D3D12Settings`），把需要 native 在**设备创建前**就
   知道的值推过去 —— 第一次 `pushToNative()` 实际上被**静默丢掉**，因为 ABI 句柄要等 `D3D12Device`
   构造时才加载，此时还是 `null`。这一条不写下来一定会被误认为「配置没生效」。
2. `D3D12Backend.createDevice`：
   1. `[debug] failDevice = true` 时先崩，用来验证崩溃路径本身；
   2. `pushToNative()`（第一次，通常无效）；
   3. `D3D12Device(EMBEDDED_SHADER_SOURCE)`，构造里依次：
      `ID3D12CreateDevice` 探针 → `GetTimestampFrequency`（为 0 时按 1.0 处理并 warn）→
      解析 `ID3D12GetAdapterInfo` 的扁平 JSON 成 `D3D12AdapterInfo` → 组装 `DeviceInfo` →
      `D3D12InitAsyncRenderer(1)`（若开了异步渲染线程，**必须在 `createCommandEncoder` 之前**）→
      `D3D12AsyncContext.init()`（分区描述符堆 + bundle 池 + worker 池；任一失败自动退回单线程录制）；
   4. `pushToNative()` 第二次 —— 那些 native 只在创建期读一次的开关（例如帧生成模式）靠这一次才到得了；
   5. 自检链；
   6. `clearPipelineCache()` 后返回设备。
3. 自检链（顺序固定，后面的依赖前面的）：`selfTestJavaResources` → `selfTestCommandLayer` →
   `selfTestPipelines` → 可选 `selfTestMesh` / `selfTestCompute` + `selfTestShadingRate` /
   `selfTestMeshDiag` / `selfTestSurface`。任一步抛异常都会 `device.close()` 后再抛，不会留下半初始化
   设备。
4. **自检结束后必须清空管线缓存**：自检用的是内嵌 GLSL（只覆盖 `core/gui` 与
   `core/position_tex_color`），不清缓存的话真实 shader 永远命中这些假条目，屏幕是紫色。
5. 创建窗口后 `createSurface(hwnd)` → `configure(w, h, presentMode)`（内部 `D3D12ConfigureSurface`，
   失败抛 `SurfaceException`）→ 第一次 `acquireNextTexture()` 只是登记「下帧要拿」，见 §3.2。
6. 第一次 `createCommandEncoder()`：native 侧建 CommandContext 并**立刻** `D3D12BeginCommandList`
   （begin 幂等：`listOpen == 1` 时跳过 Reset）。

### 3.2 每帧流程

```
acquireNextTexture()      只置 acquireDeferred；真正的 D3D12AcquireSurface 推迟到 blit 前
      │
createRenderPass(desc)    D3D12BeginRenderPass：附件 transition → RTV/DSV 描述符 → OMSetRenderTargets
      │                   → viewport/scissor → 可选 clear
      │                   （每个附件的 RTV 绑的是 desc 里那层 mip，不是恒绑 mip0）
setPipeline / setVertexBuffer / setIndexBuffer / setUniform* / setScissor
      │
draw*                     首次绘制前 pushDescriptors()：把本 pass 的绑定写进本帧 drawHeap 段
      │
submitRenderPass()        D3D12EndRenderPass
      │
blitFromTexture(tex)      ensureAcquired()（此处才 D3D12AcquireSurface，把等上一帧 Present 的时间
      │                   与本帧录制重叠）→ recordVelocityPass() → D3D12BlitSurface
      │
encoder.submit()          同步：D3D12Submit → transientMemory.rotate() → 跑围栏回调
      │                   异步：等 "recording ready" → 交棒渲染线程 → pendingSubmit（下帧结算）
      ▼
surface.present()         D3D12Temporal.onFramePresented() → blitSkipped 时直接返回
                          → D3D12PresentSurface → 帧统计 → D3D12GUISplit.armUiComposition()
```

两个容易忽略的点：

- **blit 与当帧绘制必须在同一次提交里**（blit 是录进共享 encoder 的），且 `present` 一定在提交之后。
  前端具体在哪一行调 `submit` 属于前端实现，后端只依赖上面的偏序。
- **`blitSkipped` 是防叠帧的闸门**：本帧没有写过 back buffer 就不 Present，否则会出现「同一张 back
  buffer 被连续呈现」的重影（叠加 UI、OSD 时尤其明显）。

### 3.3 一帧的两种提交模式

同步模式（`asyncRenderThread` 关闭时，也是异步握手超时后的降级路径）：

```
submit() → settlePendingSubmit() → D3D12BeginCommandList（幂等）→ D3D12Submit
        → transientMemory.rotate() → 执行本帧攒下的回调
```

异步渲染线程模式（`asyncRenderThread = true`）：

```
主线程 submit()  → D3D12AsyncRenderBeginFrame → 等渲染线程就绪（阻塞，5s 超时）
                → D3D12AsyncSendCommandsReady → pendingSubmit = true（不阻塞，下一帧再结算）
渲染线程        → Close + ExecuteCommandLists + Present
```

约束与降级（都在代码里明确写了）：

- **只有帧编码器允许进异步管线。** 一次性编码器如果走异步，渲染线程会替它 Close + Execute + Signal，
  置 `gAsyncOwnsPresent` 并自己 Present —— 结果是提前 Present、双 Present，还顺手推走帧编码器的
  围栏 token。所以 `submit()` 的判定是 `device != null && D3D12AsyncRenderBeginFrame(ctx)`。
- **allocator Reset 与 begin 只能由主线程在帧开始做**，渲染线程只允许 Close + Execute + Present。
  反过来的话主线程已录制的整帧命令会被丢弃，back buffer 永不写入 → 窗口狂闪。
- 握手超时（5s）或 `D3D12AsyncRenderBeginFrame` 失败 → 降级同步路径，并 `D3D12ClearAsyncRenderCtx()`
  清掉渲染线程上的上下文，避免下一帧错位。
- `ensureListOpen()` **先结算 `pendingSubmit` 再读 `listOpen`**。顺序颠倒会在多帧飞行时读到渲染线程
  尚未 Close 的中间态，主线程于是往一条正在被关闭的列表里写命令（数据竞争）。

### 3.4 资源销毁时机

| 场景 | 路径 | 说明 |
| --- | --- | --- |
| 普通资源 `close()` | Kotlin → `ID3D12DestroyResource(handle)` | 句柄立刻置 0（幂等），真正 `delete` 走延迟删除 |
| 纹理还有 view | `D3D12GpuTexture.close()` 只看计数 | 计数归零的那次 `removeViews()` 才真正发起销毁 |
| 管线 | `D3D12CompiledRenderPipeline.close()` | **先取走 handle 再归零**，然后 DestroyPipeline |
| 管线缓存 | `D3D12Device.clearPipelineCache()` | 逐条 close 后清空三张缓存；资源重载时前端也会逐条 close |
| 瞬时缓冲 | `transientMemory` 的 frame 列表 | `rotate()` 时淘汰超过 2 帧（`FRAMES_IN_FLIGHT`）的 frame |
| 交换链 | `D3D12GpuSurface.close()` | 窗口销毁时；耗时被打点 |
| 设备 | `D3D12Device.close()` | 见 §3.8 的固定顺序 |

### 3.5 纹理与视图的引用计数

`D3D12GpuTexture` 维护 `views` 计数：`D3D12GpuTextureView` 构造时 `addViews()`，`close()` 时
`removeViews()`。`D3D12GpuTexture.close()` 只在计数为 0 时销毁底层资源；计数为负会抛
`"Too many views removed from texture"`。

为什么必须有它：D3D12 允许先删视图句柄而保留资源，但**如果资源先被删，尚存的 view 就是野句柄**；
而前端关纹理与关视图的先后顺序完全不受后端控制（例如图集重建时先 close 纹理再 close 视图）。
用计数把「资源存活」与「视图存活」解耦，是最小的安全实现。

### 3.6 延迟删除（pending deletes）为什么存在

D3D12 的设计前提是「应用自己保证资源在 GPU 用完之前不被释放」。而 JVM 侧有两个无法控制的释放来源：

1. **GC / 引用消失的时机不可控**：资源是普通 Kotlin 对象，`close()` 可能发生在任意帧、甚至帧中间。
2. **资源重载是批量、突发、且发生在录制中途的**：改语言、改资源包都会让前端一次性 close 掉大量
   纹理与管线，而此时命令列表可能刚引用过它们、还没提交。

所以 native 侧把「销毁」拆成两步：

```
destroyObject(obj)     →  gPendingDeletes.push_back({ obj, requiredFence })
flushPendingDeletes()  →  对每条：requiredFence <= queueFence->GetCompletedValue() 才真正 delete
```

细节（`MCDR\src\core\d3d12_device.cpp`）：

- `requiredFence` 是「最后一次可能引用该对象的提交」的围栏值。**无法确定时**用哨兵
  `kFenceBlockedByOpenList`：等 `gOpenListCount == 0`（没有任何打开的命令列表）后，统一按当前的
  `queueFenceValue` 结算。这覆盖了「对象被录进一条还没提交的列表」这个最危险的情况。
- 释放时顺带回收描述符槽位（SRV / sampler 槽位进 free list），否则长跑必然耗尽。
- **缓冲不直接 delete，先进 free list**：非 READBACK 的缓冲进 96 MiB 上限的池
  （`kMaxFreeBufferBytes`，`d3d12_device.cpp:114`），后续同容量请求直接复用；只有池满或 READBACK
  堆才真正 `delete`。
- **READBACK 缓冲刻意不入池**：它们基本都是一次性读回（典型：每秒一次的截图缓冲，1.6 MB），
  复用要求尺寸完全一致，实际几乎不命中；进池只会让 96 MiB 上限被这种大块占满，此后每块都新分配，
  进程私有字节线性上涨。
- 重入保护：`gPdInProgress` 用 `InterlockedExchange` 抢占，并发/重入调用直接跳过并记一条日志。
- 清理点：提交/呈现路径、以及「没有打开的命令列表」的时机都会调一次；设备销毁时用
  `force = true` 无条件全放（此时已保证没有在飞工作）。

### 3.7 围栏：per-command-list 的 token

`createFence()` 返回的 `GpuFence` **不是**队列级信号量，而是「本命令列表当前围栏值 + 1」的 token：

```kotlin
val token = MCDR_ABI.D3D12GetFenceValue(ctx) + 1
```

`awaitCompletion(timeoutMs)` 转成 `D3D12WaitForFence(ctx, token, timeoutMs * 1_000_000L)`，
溢出钳到 `Long.MAX_VALUE`；结果缓存，第二次调用不再等待。`close()` 只置 `completed = true`，
不释放任何 native 对象。

为什么不能用设备级的 `queueFenceValue`：一次性编码器的提交也会推进全局序列，而
`StagedVertexBuffer.endFrame` / `MappableRingBuffer.rotate` 这类调用是在帧**中间**取 token 的。
用全局序列会让 token 提前「已完成」，缓冲提前回收 —— 表现为随机的画面错位，且极难复现。

### 3.8 设备关闭的固定顺序

```
D3D12TerrainSampler.release()
共享 encoder.close()  →  MCDR_ABI.D3D12DestroyCommandEncoder(nativeHandle)
clearPipelineCache()
glslCompiler.close()
D3D12AsyncContext.shutdown()  →  awaitTermination(2s)  →  D3D12DestroyAsyncRenderer()
D3D12DestroyDevice()          →  必须趁 dxgi.dll 还活着
D3D12AdapterInfo.current = null
```

两处不能改：

- **共享编码器要显式 `D3D12DestroyCommandEncoder`**。`close()` 对共享编码器刻意**不**调 destroy
  （否则 `CubeMap.render()` 内部的 close 会把整帧的 ctx 销毁掉），销毁责任上收到设备。
- **必须显式 `D3D12DestroyDevice`**。留给 `DLL_PROCESS_DETACH` 会让 `ComPtr::Release()` 在
  `dxgi.dll` detach 之后执行，触发原生异常，退出码 2170（表现为「关游戏时崩一下」）。

---

## 4. 内存策略

### 4.1 瞬时内存的三层

`D3D12TransientMemory` 镜像官方 `VulkanTransientMemory` 的接口，但实现完全不同 —— Vulkan 那边靠
`VmaAllocation`，D3D12 这边靠三种手写分配器：

| 分配器 | 用途 | 容量 | 生命周期 |
| --- | --- | --- | --- |
| UBO ring | 每帧 `setUniform` 的常量缓冲 | 3 × 4096 B 常驻缓冲 | 归 ring 自己，随 `close()` 释放，**永不进 frame 列表** |
| staging 块 | 上传源（UPLOAD 堆） | 512 KB 起，超过 8 MB 不入池 | 3 个池，每池上限 4 MB |
| frame 列表 | 无法复用的 GPU 缓冲 | — | 超过 2 帧（`FRAMES_IN_FLIGHT`）后 close |

关键常量：

```
FRAMES_IN_FLIGHT        = 2        // 帧列表保留几帧
UBO_RING_COUNT          = 3        // 与帧列表解耦，3 个槽轮转
UBO_RING_BLOCK_SIZE     = 4096L    // 单个 UBO ring 缓冲大小
STAGING_BLOCK_SIZE      = 512 KiB  // 一个 staging 块的初始大小
MIN_BLOCK_ALIGNMENT     = 512L     // D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT
STAGING_POOL_COUNT      = 3
MAX_POOL_BYTES_PER_POOL = 4 MiB
MAX_POOLED_BLOCK_SIZE   = 8 MiB
MAX_STAGING_MAP_RETRIES = 1
```

### 4.2 分块与回收

staging 块是 **bump 分配器**（只向前推进，不回收块内单块）：

```
allocateInStagingBlock(size, alignment):
    align  = alignment.coerceAtLeast(512)          // 纹理上传要求 512 的倍数
    offset = roundUp(used, align)
    if (offset + size > block.size()) { 换块；offset = 0 }
```

换块时旧块进 `retiredPooledStagingBlocks`，`rotate()` 里的 `recycleStagingBlocks()` 决定它的去向：
放回池（总字节 ≤ 4 MB）或进 frame 列表（等 2 帧后 close）。池号 `(idx + 1) % 3`，所以一个块被复用
时已经过了 3 帧 > 2 帧，GPU 侧早执行完了。

为什么是 512 而不是 256：纹理上传走 `PLACED_FOOTPRINT`，`Offset` 必须是
`D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT`（512）的倍数；用 256 对齐会让 `CopyTextureRegion` 的
校验失败或内容错位 —— 后者更难查，因为不报错。

为什么 UBO ring 不能进 frame 列表：ring 是常驻缓冲，一旦进了 frame 列表，第 3 次 `rotate()` 就会把它
close 掉，之后 `D3D12MapBuffer` 返回 null → `IllegalStateException`。这是「看起来只是内存管理的写法」
而实际会让第 3 帧必崩的典型。

### 4.3 `allocateCpu` 为什么用堆缓冲

`allocateCpu` 用 `ByteBuffer.allocate(...)`（Java 堆），**不是** `allocateDirect`：

- 这个函数每帧被调用数百到上千次（顶点数据装配、UBO 拼装）。
- 直接缓冲的原生内存只能靠 `Cleaner` 回收，在「每帧分配、引用很快消失」的模式下回收速度跟不上分配；
  实测会让进程私有字节以 900 MB/min 量级线性上涨。
- 堆缓冲由 GC 管理，代价是要在 `writeToBuffer` 时做一次拷贝 —— 这次拷贝本来就要做（进 staging 块）。

默认字节序与原行为一致（BIG_ENDIAN）；**只有 `D3D12GpuBuffer.map` 返回的视图被强制改成
LITTLE_ENDIAN**，见 §4.6。

### 4.4 上传路径

```
writeToBuffer(dst, data)
    D3D12Temporal.interceptWrite(dst, data)   // 顺带抓逐帧数据，见下
    ensureListOpen()
    transientMemory.uploadStaging(data, 1, USAGE_COPY_SRC)   // 写进 staging 块，返回块内切片
    D3D12CopyBuffer(ctx, staging, 0, dst, 0, total)

copyBufferToTexture(...)   // 必须先 ensureListOpen()，块分配改造后不再顺带录制命令
    skipBytes = (sourceX + sourceY * sourceWidth) * dst.format.blockSize()
```

`interceptWrite` 是 D3D12 后端独有的旁路：它按目标缓冲的 `label()` 与长度识别两个特定的 UBO
（关卡投影矩阵 64 B、相机 56 B），把这一帧的原始字节复制一份给时间性抖动与相机追踪用。之所以必须
在这里抓，是因为这两类 UBO 位于 `UNIFORM | COPY_DST` 堆、**不可 map**，而这是唯一能拿到「本帧新鲜
字节」的时刻。

**不做 NaN 检测/替换**：早期版本会把非有限浮点批量替换成 0，结果把顶点数据也覆写了（顶点缓冲同样
走 `writeToBuffer`），表现为整屏黑或几何塌陷。现在与官方 `VulkanCommandEncoder` 行为对齐，只记录
不修改。

### 4.5 读回路径

- `copyTextureToBuffer(tex, dst, offset, callback, mip)`：目标必须是 READBACK 堆缓冲
  （`USAGE_MAP_READ`），回调进 `GLOBAL_CALLBACKS`。`device == null`（一次性编码器）时立刻
  `submit()`，随后同步跑回调。
- `D3D12GpuQueryPool` 的读取是**阻塞**的：native 把时间戳 resolve 到 readback 缓冲、提交、等围栏，
  然后返回。它不适合放在热路径上按帧调用。
- 截图路径的历史教训见 §7 第 6 条。

### 4.6 mapped 内存的约束

| 约束 | 说明 |
| --- | --- |
| 只有 UPLOAD / READBACK 堆可 map | native 的 `mapBuffer` 有五类失败都返回 0：句柄无效、缺 `MAP_WRITE`、偏移越界、堆类型不对、`Map()` 失败 |
| DEFAULT 堆不可 map | 「持久映射」在 D3D12 里只对 UPLOAD 堆成立；DEFAULT 堆想写只能走 copy |
| 长度上限 | `length <= Int.MAX_VALUE`（`MemorySegment` 的 size 是 long，但 map 出来的是 `ByteBuffer`） |
| 字节序 | `map()` 内强制 `LITTLE_ENDIAN`。`NewDirectByteBuffer` 默认是 **BIG_ENDIAN**，不改的话所有 UBO 都是坏的（矩阵全垃圾、颜色目标全黑） |
| 必须 unmap | `MappedView.close()` → `D3D12UnmapBuffer`；忘记关会在下次 `Map` 同一资源时撞上 D3D12 的限制 |
| 关闭后不可用 | `map` 前 `check(!isClosed())`；ring 槽位被 close 时抛 `"allocateGpuMapped: ring buffer slot N is already closed"` |
| 256 字节 CBV 对齐 | 任何作为 CBV 的偏移（含 meshlet 的 globals）必须 `% 256 == 0`，见 §5 |

---

## 5. 硬约束清单（照代码抄的数值）

| # | 约束 | 数值 / 规则 | 违反后果 | 出处 |
| --- | --- | --- | --- | --- |
| 1 | CBV 地址对齐 | **256 字节**（`D3D12_CONSTANT_BUFFER_DATA_PLACEMENT_ALIGNMENT`） | `D3D12PushDescriptors` 失败，抛 `dx12PushDescriptors failed: …`；这是该失败最常见的原因 | `D3D12RenderPassBackend.pushDescriptors` |
| 2 | 纹理上传偏移对齐 | **512 字节**（`D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT`） | `CopyTextureRegion` 校验失败或像素错位 | `D3D12TransientMemory.MIN_BLOCK_ALIGNMENT` |
| 3 | meshlet globals 偏移 | 必须 `% 256 == 0`，否则该批跳过（`meshlet globals offset … is not 256-aligned`） | 地形静默少画 | `D3D12RenderPassBackend.tryMeshletDraw` |
| 4 | `drawHeap` 容量 | 每帧段 `131072 + 24576 = 155648` 槽，**4 段**，共 **622592** 槽（shader-visible 上限 1,000,000） | 段内耗尽 → `draw descriptor heap exhausted` | `d3d12_device.cpp:192-199` |
| 5 | 帧段号 | `drawHeapSlotBase = (fenceValue % 4) * 155648` | 槽位算错会覆盖别的帧的描述符 | `d3d12_device.cpp:4000` |
| 6 | 其余描述符堆 | SRV 262144 · RTV 2048 · 帧 RTV 256 · DSV 256 · sampler 2048 | 溢出直接创建失败 | `d3d12_device.cpp:192-196` |
| 7 | 异步 worker 分区 | 每分区 **24576** 槽（8 worker ⇒ 3072 槽/帧/worker） | 并行 bundle 分配失败 → 回退串行 | `kAsyncWorkerSlotsPerSection` |
| 8 | 单 bundle 上限 | 绘制数 ≤ **512**（`SERIAL_BUNDLE_MAX_DRAWS`），SRV 槽位需求 ≤ **2048** | 超限就升级为真并行或回退串行 | `D3D12RenderPassBackend` |
| 9 | 并行最小批量 | 少于 **24** 次绘制不走并行（`PARALLEL_MIN_DRAWS`） | 调度开销大于收益 | 同上 |
| 10 | 描述符分配 count | 分配器把 `count == 0` 视为失败，调用方必须 `coerceAtLeast(1)` | 无 SRV 的批次被误判为分配失败 | 同上 |
| 11 | barrier 规则 | JVM 侧**没有** barrier API；唯一例外是异步 bundle 的预过渡（`AsyncPrepareCBVBuffers` → `VERTEX_AND_CONSTANT_BUFFER`、`AsyncPrepareIndexBuffers` → `INDEX_BUFFER`、`AsyncPrepareTextureViews` → `SHADER_RESOURCE`），全部在**主列表**做 | bundle 内出现 `ResourceBarrier` 是非法的（D3D12 明确禁止） | `MCDR_ABI.kt` |
| 12 | 命令列表线程 | 一条命令列表只能由创建它的线程录制；异步渲染线程只 Close/Execute/Present | 数据竞争、整帧命令被丢弃、窗口狂闪 | `D3D12CommandEncoderBackend.submit` |
| 13 | `frameSlot` 读取时机 | 必须在命令列表 begin **之后**（`D3D12AsyncCurrentFrameSlot`） | 读到上一帧的段号，描述符写错段 | `D3D12AsyncContext` |
| 14 | 围栏回调线程 | native 的监视线程；**不得抛异常**（异常逃出 upcall 会终止 JVM） | 进程级崩溃，没有 Java 栈 | `D3D12FenceCallback` |
| 15 | RTV 的 mip | 每个附件按 `colorMips[i]` 绑真实 mip，不能恒绑 mip0 | 图集 mip 链永远不写入 | `createRenderPass` |
| 16 | clear flag | `0 = LOAD`，`1 = CLEAR`；`clearColors` 长度 = 附件数 × 4 | 该清的没清 / 该保留的被抹 | `D3D12BeginRenderPass` |
| 17 | 索引格式 | `D3D12SetIndexBuffer(ctx, buf, indexType)`：`0 = R16_UINT`，`1 = R32_UINT` | 大网格索引错位 | `MCDR_ABI` |
| 18 | flipY 判定 | 附件 0 纹理 `usage == 13`，即 `COPY_DST + TEXTURE_BINDING + RENDER_ATTACHMENT`（1+4+8）；**绝不能是 15**（再多一个 `COPY_SRC`） | 主 pass 被误判成图集 pass → 图标镜像、裁剪区错乱 | `createRenderPass` |
| 19 | 顶点缓冲槽位 | 绑定时若 `vertexSize == 0` 会挂起，`setPipeline` 后按管线布局落实；布局里也没有就丢弃 | 该批次缺顶点流、几何消失 | `setVertexBuffer` |
| 20 | 宽线 | D3D12 的 `LINELIST` 恒为 1 px；宽线靠额外的 `TRIANGLES` 变体 PSO | 没建变体时选中框只剩 1 px | `D3D12Device.compilePipeline` |
| 21 | 交换链缓冲数 | **3**（`kSurfaceBufferCount`，对齐官方 `minImageCount = max(3, min)`） | 与官方约定不符（后果代码中未明确） | `mcdr_device.cppm:1499` |
| 22 | 描述符堆重绑 | native 在 begin 与每次 push 时都 `SetDescriptorHeaps({drawHeap})` | 只绑一次会在帧中途丢堆 | `d3d12_device.cpp` |
| 23 | 异步 allocator ring | 4 帧轮转（`kFramesInFlight = 4`），与 `fenceValue % 4` 同步 | 复用未完成的 allocator → 命令列表语义损坏 | `d3d12_async_executor.cppm:40` |
| 24 | `pushConstants` | 无根常量路径，直接忽略并只 warn 一次 | 依赖它的 shader 拿到脏常量 | `D3D12RenderPassBackend.pushConstants` |

---

## 6. 与原版 Blaze3D 的契约

### 6.1 必须实现的接口面

| 接口 | 本后端的实现 | 备注 |
| --- | --- | --- |
| `GpuBackend` | `getName` / `loadLibrary` / `unloadLibrary` / `createWindow` / `createDevice` | 后两个是空实现：`d3d12.dll` 是系统组件，不需要预加载 |
| `GpuDeviceBackend` | `createCommandEncoder` / `createTexture`×2 / `createTextureView` / `createBuffer`×2 / `createSampler` / `createSurface` / `createTimestampQueryPool` / `getTimestampCalibrationOffset` / `getLastDebugMessages` / `isDebuggingEnabled` / `getDeviceInfo` / `close` | `getTimestampCalibrationOffset` 返回 0（时间戳与呈现同源） |
| `CommandEncoderBackend` | `createRenderPass` / `submitRenderPass` / `clearColorTexture` / `clearColorAndDepthTextures`×2 / `clearDepthTexture` / `clearStencilTexture` / `writeToBuffer` / `copyToBuffer` / `writeToTexture` / `copyBufferToTexture` / `copyTextureToBuffer` / `copyTextureToTexture` / `createFence` / `writeTimestamp` / `submit` / `close` | `clearStencilTexture` 在 26.3 已从接口移除，仍保留实现 |
| `RenderPassBackend` | `setPipeline` / `setVertexBuffer` / `setIndexBuffer` / `setUniform` / `setUniformByName` / `pushDescriptors` / `setViewport` / `setScissor` / `disableScissor` / `draw` / `drawIndexed` / `drawIndirect` / `drawIndexedIndirect` / `multiDrawIndexed`×2 / `multiDraw` / `multiDrawDirectSeparate` / `pushConstants` / `close` | `multiDrawIndexed(firstIndexOffsets…)` 与 `multiDrawDirectSeparate` 直接抛 `UnsupportedOperationException` |
| 资源 | `GpuBuffer`（`map`/`slice`/`close`）、`GpuTexture`（`addViews`/`removeViews`/尺寸查询）、`GpuTextureView`、`GpuSampler`、`GpuQueryPool`、`GpuFence`、`GpuSurface` | 26.3 起 `GpuBuffer` 必须继承 `BaseGpuBuffer`，前端 `validateDraw` 会强转 |

### 6.2 与 OpenGL / Vulkan 语义不同的地方

| 语义 | 本后端 | 差异 |
| --- | --- | --- |
| NDC 深度 | `isZZeroToOne = true`（0..1），深度格式固定 `D32_FLOAT` | GL 是 -1..1；沿用反向 Z 的比较函数 |
| 描述符 | 没有 descriptor set 对象；每次绘制前 `pushDescriptors` 写进本帧瞬时堆 | Vulkan 是 set + binding；这里等价于 push descriptors |
| 资源状态 | 应用层完全不声明，native 自动 transition | Vulkan 必须写 barrier；这是最大的写法差异 |
| uniform 索引 | `setUniform(index)` 的 index 是 `CreateInfo.uniforms` 的**位置**，不是 `flattenUniforms` 顺序 | 顺序不同是常态，按名字回退会静默绑错 |
| 顶点缓冲绑定顺序 | 可以先绑 buffer 再设管线（`pendingVertexBuffers`），stride 从管线推 | Vulkan 里 stride 随绑定给出 |
| 宽线 | 1 px；宽线由三角形变体模拟 | GL/Vulkan 有 `lineWidth` |
| 裁剪原点 | `flipY` 为假时 scissor 的 Y 需要翻转；为真时直通 | GL 左下原点，D3D12 左上原点 |
| push constants | 忽略（只 warn） | Vulkan 有根常量等价物 |
| 呈现 | 先 blit 到 back buffer 再 Present；`acquire` 延迟到 blit 前 | 不是「直接渲染到 back buffer」 |
| 围栏 | `GpuFence` 是命令列表内 token，超时返回 false | Vulkan 是 `VkSemaphore`/`VkFence` 对象 |
| 纹理视图 | 视图带 `baseMip`，RTV 绑真实 mip | GL 没有 mip 级视图概念 |

### 6.3 哪里会 assert / 抛异常

失败一律是异常而不是 `assert`（Kotlin/JVM 侧），消息即诊断信息：

| 场景 | 异常 / 消息 |
| --- | --- |
| 原生对象创建返回 0 | `IllegalStateException("dx12CreateBuffer returned a null handle")` 等同族消息 |
| 纹理/视图已关闭 | `IllegalStateException("Texture '…' is closed")`、`"Buffer … is closed"` |
| 管线无效 | `IllegalStateException("Pipeline … is not valid (shader compilation failed)")` |
| 绑管线失败 | `IllegalStateException("dx12SetPipeline failed for …")` |
| 推描述符失败 | `IllegalStateException("dx12PushDescriptors failed: …")` |
| 缺绑定 | `IllegalStateException("Uniform '…' was not set before core")`、`"Texture '…' was not bound before core"` |
| 拷贝时列表已关 | native `"dx12CopyBufferToTexture: no open command list"` |
| staging 块不可映射 | 先 WARN 一次并换块重试；超限抛 `IllegalStateException("staging block … could not be mapped at …")` |
| UBO ring 槽位已关闭 | `IllegalStateException("allocateGpuMapped: ring buffer slot N is already closed")` |
| 上传零字节 | `IllegalArgumentException("Cannot upload zero bytes")` |
| 视图计数为负 | `IllegalStateException("Too many views removed from texture")` |
| 查询越界 | `IndexOutOfBoundsException("Query range … exceeds pool size …")` |
| surface 配置/获取失败 | `SurfaceException("Failed to configure DX12 surface to WxH")`、`"Failed to acquire DX12 back buffer (deferred)"` |
| 不支持的绘制重载 | `UnsupportedOperationException("… is not supported by the D3D12 backend")` |
| 自检失败 | `D3D12Fatal.crash("DX12 self-test failed: $t")` → `ReportedException` |

唯一「硬失败但先不抛」的地方是自检与 `D3D12Fatal`：它们需要先 `device.close()` 再把异常交给崩溃
报告，顺序反了会留下半初始化的设备。

---

## 7. 常见坑与历史决策

1. **为什么没有 GL/Vulkan 回退。**
   早期版本让自检失败后抛 `BackendCreationException`，结果被原版「尝试下一个候选后端」的逻辑吞掉，
   游戏以 GL 启动，玩家以为 D3D12 生效但画面完全不对。现在抛 `ReportedException` 并重置
   `preferredGraphicsBackend`：要么 D3D12 能跑，要么带着一份崩溃报告退出。

2. **为什么编码器是共享单例。**
   曾经每帧 `new` 一个编码器：绘制记在 A 上，帧末提交的是新建的空 B，于是空命令列表被提交，
   back buffer 里是上一帧的垃圾 —— 表现为窗口狂闪。官方 `VulkanDevice` 也是构造期建一个、之后复用。

3. **为什么 `pipelineCacheFlipY` 的键是值语义。**
   误用 `IdentityHashMap` 时，每次传入的 `FlipYKey` 都是新对象 → 永远 miss → flipY 管线每帧重编译，
   直接表现为低帧率。主缓存用 `IdentityHashMap` 是**对的**（`RenderPipeline` 是同一实例），
   两者键语义相反，改的时候不要顺手统一。

4. **为什么 `getOrCompilePipeline` 命中后还要 `isValid()`。**
   资源重载（改语言、开高对比度）会让前端 close 掉所有 `CompiledRenderPipeline`，但缓存条目还在。
   早期用 `computeIfAbsent` 原样返回，于是 `setPipeline` 抛「Pipeline … is not valid」，第二次切高
   对比度必崩。

5. **`close()` 里先取走 handle 再归零。**
   26.3 前端会逐条 `close()` 管线，而 native 的 `DestroyPipeline` 不是幂等的 —— 重复销毁同一个指针
   会触发原生 double free，退出码 `0xC0000374`（堆损坏）。先置零再销毁，第二次调用就是空操作。

6. **截图为什么曾经每秒泄漏 1.6 MB。**
   两个独立的 bug 叠加：① 围栏回调队列原来是**每个编码器一份**，而原版截图只
   `createCommandEncoder().copyTextureToBuffer(..., callback, 0)` 从不提交这个编码器，回调永不执行；
   ② 抽干队列的写法是 `val run = pendingCallbacks` 之后紧跟 `clear()` —— 别名之后 `run` 就是那个
   空列表，循环体永远不跑。现在回调是**设备级全局队列**，任何一次提交都会抽干它。

7. **为什么 staging 块映射失败后就丢弃。**
   一块「已经证明不可映射」的 UPLOAD 堆缓冲再放回池里，只会把失败传播到后续几十帧。现在的做法是
   WARN 一次（带块句柄、容量、偏移、长度、异常原文），`close()` 它 —— 由于 `close()` 走延迟删除，
   立即释放不会打断在飞的命令列表 —— 然后换新块重试。早期这里是直接让整帧崩在
   `dx12MapBuffer returned null`。

8. **为什么 `uploadStaging` 不再给每次上传建目标缓冲。**
   旧实现每次上传建两个缓冲（UPLOAD staging + DEFAULT 目标），而 `writeToBuffer` 每帧几十次，
   于是每帧上百次 `ID3D12CreateBuffer`，采样占渲染线程约 16%。现在目标是「块内切片 + 一次
   `CopyBuffer`」，缓冲创建次数降到常数级。

9. **为什么 `copyBufferToTexture` 里要显式 `ensureListOpen()`。**
   块分配改造后，分配 staging 不再顺带录制命令。帧外（资源重载）调用时若不自查，就会命中
   `dx12CopyBufferToTexture: no open command list`，表现为白屏 + 五颜六色闪烁。

10. **为什么 `pushDescriptors` 之前必须先设管线。**
    绑定表是「条目序号 → register 序号」，由管线布局决定；没有管线时只能记日志跳过（SKIP），
    否则写进去的 register 编号毫无意义。

11. **为什么 meshlet 要先换 PSO 再 `pushDescriptors`。**
    native 的 `SetGraphicsRootSignature` 会清掉已绑的根描述符表。顺序反了，mesh shader 拿到的是
    空表或上一管线的表，读到的全是垃圾 —— 表现为地形整片消失，而不是报错。

12. **为什么放弃 DXGI 独占全屏。**
    `SetFullscreenState` 会卡死 `Present`、并锁住 Alt+Tab。独占交给 SDL 切显示模式，交换链始终是
    窗口化的 flip 模型。

13. **为什么 `acquireNextTexture` 是延迟的。**
    同步获取意味着在帧首等「上一帧的 Present 完成」（实测 0.13–1.7 ms）。现在只登记意图，真正
    `D3D12AcquireSurface` 推到 blit 之前，把这段等待与本帧的录制重叠。

14. **为什么自检里「先用普通 API 清一次色」的步骤不能省。**
    自检代码的注释记录了实测：同一条命令列表里，如果**第一个动作**就是 render pass 清屏，之后回读
    到的仍是 (0,0,0,0)；先调一次普通的 `D3D12ClearColorTexture` 再开 pass，就能读到正确颜色。
    这是实测得到的顺序依赖，机制在代码中未明确 —— 保留这段顺序，别「优化」掉。

15. **为什么 `#include` 不从 `ShaderSource.getInclude` 拿。**
    那份缓存返回的指针只在原版 shaderc 的 include 回调期间有效，之后读到的是已释放内存，尾部会混进
    替换字符（`U+FFFD`），shaderc 报 `unexpected token`。现在直接从 `ResourceManager` 读
    `shaders/include/<path>`，读不到才回退，并丢弃含替换字符的结果。

---

## 8. 文件导航

路径相对 `Illusion\src\main\kotlin\dev\nts\expdxr\`。

| 文件 | 一句话职责 |
| --- | --- |
| `d3d12\backend\D3D12Backend.kt` | 后端入口：建窗口、建设备、跑自检链、失败即崩 |
| `d3d12\backend\D3D12CommandEncoderBackend.kt` | 命令列表生命周期、清屏/拷贝、帧提交（同步与异步）、帧中后处理入口 |
| `d3d12\backend\D3D12RenderPassBackend.kt` | pass 录制：绑定表、顶点/索引缓冲、scissor、绘制、并行 bundle、meshlet 派发 |
| `d3d12\backend\D3D12CompiledRenderPipeline.kt` | 已编译管线：PSO 句柄、meshlet 与宽线变体、绑定条目推导 |
| `d3d12\backend\D3D12PipelineBridge.kt` | 按 `pipeline.location` 找回 `RenderPipeline` 与 `ShaderSource` 的旁路表 |
| `d3d12\backend\D3D12BindGroupEntry.kt` | 绑定条目 → HLSL register 序号的映射（CBV/SRV/texel buffer） |
| `d3d12\backend\D3D12FenceCallback.kt` | 全库唯一的 FFM upcall 接口（围栏回调） |
| `d3d12\backend\D3D12GraphicsApi.kt` | 给 `PreferredGraphicsApi` 追加 D3D12 选项 |
| `d3d12\backend\D3D12PixCaptureController.kt` | F9 触发 PIX 定时捕获 |
| `d3d12\debug\D3D12Fatal.kt` | 致命错误出口：写报告并抛 `ReportedException`，绕过候选后端回退 |
| `d3d12\render\advanced\smaa\D3D12GuiSplit.kt` | GUI/世界层拆分：决定 blit 源纹理，present 后重置合成状态 |
| `d3d12\render\advanced\taa\D3D12Temporal.kt` | 时间性 jitter：拦截 UBO 写入、逐帧滚动偏移 |
| `d3d12\render\adapter\D3D12Device.kt` | 设备门面：资源工厂、管线缓存、着色器编译、关闭顺序 |
| `d3d12\render\adapter\D3D12AdapterInfo.kt` | 适配器信息（手写 JSON 解析）与 F3 显示数据源 |
| `d3d12\render\adapter\D3D12RenderProf.kt` | 阶段计时（每 300 帧一行），供 Java mixin 静态调用 |
| `d3d12\render\core\D3D12TransientMemory.kt` | 瞬时内存：UBO ring、staging 块分配器、帧列表与回收 |
| `d3d12\render\core\D3D12GpuBuffer.kt` | 缓冲对象与 `map()`（含字节序修正与设备丢失识别） |
| `d3d12\render\core\D3D12GpuTexture.kt` | 纹理对象与视图引用计数 |
| `d3d12\render\core\D3D12GpuTextureView.kt` | 纹理视图（带 `baseMip`） |
| `d3d12\render\core\D3D12GpuSurface.kt` | 交换链：配置、延迟获取、blit、present |
| `d3d12\render\core\D3D12GpuQueryPool.kt` | 时间戳查询池（读取是阻塞的） |
| `d3d12\render\core\D3D12AsyncContext.kt` | 异步录制上下文：分区描述符、bundle 池、worker 池 |
| `d3d12\render\shader\D3D12GpuSampler.kt` | 采样器对象 |
| `d3d12\render\shader\D3D12ShaderCompiler.kt` | GLSL→SPIR-V→HLSL→DXIL 的编译入口 |
| `d3d12\render\shader\D3D12IntermediaryShaderModule.kt` | 中间着色器模块：缓存已翻译的 HLSL 并给顶点属性分配槽位 |
| `d3d12\extern\MCDR_ABI.kt` | 全部 C ABI 绑定（FFM downcall + 唯一 upcall 存根） |
| `d3d12\extern\MCDR_PipelineDesc.kt` | 图形/计算管线描述的 native 侧布局 |
| `d3d12\extern\MCDR_MeshPipelineDesc.kt` | mesh 管线描述的 native 侧布局 |
| `d3d12\extern\MCDR_Layout.kt` | 结构体字段偏移与 ABI 签名登记 |

---

## 9. 文档维护

改这一层时，请同步更新本文的三类内容：

1. **数值**：§5 的表格与 §4.1 的常量直接抄自代码。改动 `D3D12TransientMemory` 的块大小、`drawHeap`
   的分段、描述符堆容量、对齐要求时，先改代码再改表 —— 表里留旧数值比没有表更糟。
2. **顺序**：§3.1 的初始化顺序、§3.8 的关闭顺序、§3.2 的每帧偏序都是「改了就出事」的清单。新增
   一个需要在设备创建前推给 native 的配置项时，记得它在 `pushToNative` 的第几次之后才生效
   （§3.1 第 1 条）。
3. **接口面**：§6.1 的表格对应 26.3 的 `GpuBackend` / `GpuDeviceBackend` / `CommandEncoderBackend` /
   `RenderPassBackend`。升级 Minecraft 版本后先核对这张表，再动实现。

写作约定（与仓库内其他设计文档一致）：

- 只写**能从代码确认**的事实，具体到数值、枚举名、配置键名。
- 代码没写清楚的地方直接写「代码中未明确」，不要用推测填空。
- 不出现工单号、会话编号；不引用仓库根目录的过程性文档（它们在发布包里不存在）。
- 结论要能反查：新增段落时，如果一句话对应不上任何代码位置，就不要写。

本文覆盖的是「后端与设备 / 资源层」。管线编译链、呈现与后处理、meshlet 几何构建各有独立文档，
交叉处只保留接口约定。
