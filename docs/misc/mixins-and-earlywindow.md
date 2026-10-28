# Mixin 注入点地图与早期窗口

这篇文档讲两件互相咬合的事：

1. 为了让 Direct3D 12 后端跑进原版渲染代码，我们对 26.3 的哪些类、哪些方法、哪一行做了注入，每个点解决什么问题。
2. 从进程启动到第一帧之前那段「还没有渲染后端」的时间里，窗口从哪来 —— 早期窗口（early window）与 FML 兼容层。

只写能从代码确认的东西：描述符、配置键名、颜色值都抄自源码。代码没写清的判断标注「代码中未明确」。

## 1. mixin 在整条链上的位置

### 1.1 两个方向的分工

后端实现本身不需要 mixin。Kotlin 侧实现了 Blaze3D 的 GPU 抽象（`GpuDevice` / `CommandEncoder` / `RenderPass` / …），
原版渲染代码一旦拿到这些接口，后面就是普通的接口调用。

mixin 处理的是接口拿到**之前**、以及接口给不了的东西：

- 原版在调用接口前自己做的决定：渲染目标分配多大、这一帧的主目标是谁、某个 pass 排在哪两条语句之间。
- 只存在于原版方法局部变量里的值（`GuiRenderer#draw` 开头取一次主目标就再没用过）。
- 静态上下文里没人递给你的对象（`GameRenderer#preloadUiShader` 里的 `ResourceManager`）。
- 私有类型（`ShaderManager$PendingResults` 是私有 record，写不进方法描述符，只能包它的构造）。

体量：`Illusion\src\main\java\dev\nts\expdxr\mixin\` 下 **44 个文件、1947 行**。按注解行统计为
53 处 `@Inject`、5 处 `@WrapOperation`、5 处 `@ModifyExpressionValue`、2 处 `@ModifyReturnValue`、
1 处 `@ModifyArg`、2 处 `@ModifyVariable`、4 处 `@Invoker`、2 处 `@Accessor`。最大的是
`options\OptionsMixin.java`（128 行）。

### 1.2 判据：什么必须留在 mixin

| 你需要的 | 为什么只能在 mixin | 例子 |
| --- | --- | --- |
| 某一行的前后顺序 | 顺序错就是黑屏或崩溃，接口层插不进去 | 世界画完、GUI 未画之间要跑 TAA → SMAA → SR（`GameRendererSmaaWorldMixin`） |
| 原版方法的局部变量 | 它不进字段、不出参、也没有 getter | `GuiRenderer#draw` 里的 `mainRenderTarget()` |
| 原版方法的返回值 | 要改结论但不改类型 | `useImprovedTransparency()` 强制 `true` |
| 私有类型 | 描述符写不进 Java 源码 | `ShaderManager$PendingResults` |
| 静态方法里的实例 | 没有 `this`，但那一刻才拿得到 | `preloadUiShader` 的 `ResourceManager` |
| 真正的分配参数 | 分配发生在构造器 / `resize` 内部 | `RenderTarget#createBuffers` 里的 `createTexture` 调用 |

### 1.3 反过来：什么必须留在 Kotlin

| 类型 | 为什么不能放 mixin | 例子 |
| --- | --- | --- |
| 跨帧状态机 | 状态要活过注入点，mixin 类不能随便加字段 | `D3D12GUISplit`、`D3D12RenderScale` |
| 任何 native 调用 | mixin 只做转发，FFM 绑定集中在 `MCDR_ABI`（唯一 native 入口） | `D3D12RenderProf.begin/end` 这类 `@JvmStatic` 中转 |
| 可测的纯计算 | 注入点是「没有单测的代码」 | `scaledForAttachment` 的取整规则 |
| 被多处复用的逻辑 | 注入代码不许引用 `dev.nts.expdxr.mixin.*` 里的类 | `compat\FpsLimitCompat`、`EarlyWindowSurface` |

最后一条不是风格问题：Mixin 会对被注入代码引用的类做 `IllegalClassLoadError` 检查。`FpsLimitCompat` 之所以单独放在
`compat` 包、`EarlyWindowSurface` 之所以放在 `misc\fmlelw_control`，就是为了绕开这个检查。

## 2. 注册：三个文件决定一切

### 2.1 `neoforge.mods.toml`

mixin 配置与 access transformer 都在这一个文件里登记，`build.gradle.kts` 不参与：

```toml
[[mixins]]
config="${mod_id}.mixins.json"

[[accessTransformers]]
file="META-INF/accesstransformer.cfg"
```

`build.gradle.kts` 里没有 `mixin { }` 也没有 `accessTransformers` 配置块，它只负责早期窗口的伴随 jar（见 §5.5）。

### 2.2 `dxr_mc.mixins.json`

| 键 | 值 | 说明 |
| --- | --- | --- |
| `required` | `true` | 配置级硬要求：这批 mixin 必须全部织入 |
| `package` | `dev.nts.expdxr.mixin` | 数组里的名字都相对这个包 |
| `compatibilityLevel` | `JAVA_25` | 与 `jvmTarget = JVM_25`、NeoForge 26.3 的字节码级别一致 |
| `mixins` | `[]` | 通用列表留空，44 条全在 `client` |
| `client` | 44 条 | 目标类（`Window` / `GameRenderer` / `Options` …）只存在于客户端发行版 |
| `injectors.defaultRequire` | `1` | 每条注入默认「必须命中一次」，改名或改结构即启动失败 |
| `overwrites.requireAnnotations` | `true` | 目前没有任何 `@Overwrite`；开着它是禁止将来出现无注解覆写 |

`mixins` 为空、全部塞进 `client`，配置里没有注释解释；从目标类分布看是「服务端没有这些类」，属于 `代码中未明确`。

### 2.3 access transformer：两条，第三条故意不写

`Illusion\src\main\resources\META-INF\accesstransformer.cfg`：

| 条目 | 原可见性 | 谁需要 |
| --- | --- | --- |
| `public net.minecraft.client.Options setGraphicsPresetToCustom()V` | private | 玩家改抗锯齿时把画质预设打成「自定义」 |
| `public net.minecraft.client.gui.screens.options.OptionsSubScreen list` | protected | 视频设置第四栏要往 `OptionsList` 里加控件 |

第三条是 `PreferredGraphicsApi` 的枚举构造器，**故意没有**放进 AT：javac 禁止 `new` 枚举，
`Constructor.newInstance` 对枚举直接抛异常，放宽可见性也救不了 —— 这条只能由
`options\PreferredGraphicsApiAccessor` 的 `@Invoker("<init>")` 承担。

## 3. Mixin 地图

### 3.1 分组总览

| 系统 | 条数 | 它回答的问题 |
| --- | --- | --- |
| 窗口创建与生命周期 | 3 | 谁建窗口，退出时窗口上显示什么 |
| 渲染管线与着色器登记 | 3 | 编译管线时把哪份源码交给后端，着色器失败要不要升级成崩溃 |
| 渲染目标尺寸 | 3 | 百分比渲染比例下谁该缩、谁绝对不能缩 |
| GUI 拆分与目标归属 | 3 | 一帧里 GUI 画到哪个目标 |
| 帧内顺序与数据采集 | 6 | 超分 / 时域数据 / 地形上传在全帧的哪个时刻取 |
| 视频设置与选项 | 10 | 追加后端枚举、追加设置项、持久化 |
| 调试界面 | 4 | F3 的三个数据行，外加一个已停用的准星 mixin |
| 限帧兼容 | 2 | 「永不」这个限帧档从哪来 |
| 阶段计时 | 10 | 每帧各段耗时 |

### 3.2 窗口创建与生命周期（3）

| Mixin | 目标（类#方法） | 注入点 | 目的 |
| --- | --- | --- | --- |
| `core\WindowAdoptCreatedMixin` | `Window#createWindow(GpuBackend,int,int,String)` | `@Inject` HEAD, cancellable | 仅当后端是 `D3D12Backend` 时接管早期 SDL 窗口，游戏不再另建第二个窗口 |
| `core\MinecraftCloseEarlyWindowMixin` | `Minecraft#close` | `@Inject` HEAD, remap=false | 先把手里的 SDL 窗口刷黑，退出时不闪加载屏（详见 §5.7） |
| `render\GlSurfacePresentMixin` | `GlSurface#present` | `@Inject` HEAD, cancellable | `[debug] skipGlSwap` 为真时不做 GL 呈现；父窗口上存在真 SwapBuffers 过的 GL 链会让 RTSS OSD 变 0 像素 |

`WindowAdoptCreatedMixin` 有两条容易误删的分支：句柄走系统属性 `dxrmc.earlywindow.handle`（provider 在 loader 的
early-service 类加载器里，与游戏类加载器不共享静态），接管成功后**立刻清除**，避免后续窗口拿到陈旧句柄；后端不是
D3D12 时**保留**，因为游戏每个后端尝试都会建一次窗口，我们的后端可能第二次才起来。

### 3.3 渲染管线与着色器登记（3）

| Mixin | 目标（类#方法） | 注入点 | 目的 |
| --- | --- | --- | --- |
| `render\FrontendGpuDeviceCompilePipelineMixin` | `FrontendGpuDevice#compilePipeline(RenderPipeline,ShaderSource,Executor)` | `@Inject` HEAD | 把 `ShaderSource` 与管线登记进 `D3D12PipelineBridge`：26.3 的 `CreateInfo` 只有 SPIR-V 与状态，后端还要 GLSL 源、shaderDefines 与 mesh 变体判据 |
| `render\ShaderManagerRequiredFailureMixin` | `ShaderManager#apply(GpuDevice,PendingResults)` | `@WrapOperation` at `@At("NEW") target=java/lang/RuntimeException` | message 以 `Failed to load required shader programs` 开头时打 ERROR 并 `Minecraft#delayCrash`，把静默黑屏升级成崩溃 |
| `render\GameRendererPreloadUiShaderMixin` | `GameRenderer#preloadUiShader`（static） | `@Inject` HEAD | 捕获那一刻的 `ResourceManager` 交给 `D3D12FallbackShaderSource`，供缺失管线回退 |

两个坑写在代码注释里，改动前必读：26.3 的 `preloadUiShader` 是静态方法，**注入处理器也必须是 `static`**，
否则 APPLY 阶段直接 fatal；`ShaderManager$PendingResults` 是私有 record，签名里写不出这个参数类型，
所以 `ShaderManagerRequiredFailureMixin` 只能包 `NEW`，不能包整个 `apply`。

### 3.4 渲染目标尺寸（3）

| Mixin | 目标（类#方法） | 注入点 | 目的 |
| --- | --- | --- | --- |
| `render\MainTargetRenderScaleMixin` | `MainTarget#allocateColorAttachment` / `#allocateDepthAttachment` | `@WrapOperation`（INVOKE `GpuDevice#createTexture`） | 主目标附件按渲染比例分配，但不动 `RenderTarget.width/height` 的逻辑尺寸 |
| `render\RenderTargetCreateBuffersScaleMixin` | `RenderTarget#createBuffers` | 同一 INVOKE，`@WrapOperation` | 覆盖窗口 resize 路径；只对 `(Object) this instanceof MainTarget` 生效 |
| `render\LevelRendererWorldTargetsScaleMixin` | `LevelRenderer#render` | `@ModifyExpressionValue`（FIELD `RenderTarget.width` / `.height`，remap=false）×2 | 世界层辅助目标改用 `worldLayerSize`，与主目标附件同尺寸 |

这三条合起来是一件事：**只有颜色/深度附件的真实分配按比例缩，`RenderTarget` 对外报告的逻辑尺寸一直是窗口尺寸。**

- 不改 `width/height`：`GameRenderer` 每帧比较 `windowRenderState.width != mainRenderTarget.width`，不等就重建主目标；逻辑尺寸一缩，主目标每帧重建。
- 只缩 `MainTarget`：GUI / 模糊等目标用窗口坐标，跟着缩会让 scissor 越界，实测出现过 `RenderArea 1484x773` 配 `scissor 1492x992`。
- 辅助目标必须与主目标附件同尺寸，否则 `copyDepthFrom` 直接抛 `Source texture (1920x1000) is not large enough to read a rectangle of 2560x1334 at 0x0`。

### 3.5 GUI 拆分与目标归属（3）

| Mixin | 目标（类#方法） | 注入点 | 目的 |
| --- | --- | --- | --- |
| `render\GuiRendererTargetMixin` | `GuiRenderer#draw` | `@ModifyExpressionValue`（INVOKE `GameRenderer#mainRenderTarget`） | GUI 画到 `D3D12GUISplit` 的独立目标（`targetForFrame() ?: original`） |
| `render\CubeMapTargetMixin` | `CubeMap#render` | 同一 INVOKE | 标题界面全景图也进 GUI 目标；`CubeMap#render` 在 `GuiRenderer#render` 开头就执行，比 `draw` 换目标更早 |
| `render\GameRendererBlurTargetMixin` | `GameRenderer#processBlurEffect` | `@ModifyExpressionValue`（FIELD `GameRenderer.mainRenderTarget`） | 物品栏 / 暂停菜单的背景模糊作用在 GUI 目标上，而不是主目标 |

### 3.6 帧内顺序与数据采集（6）

| Mixin | 目标（类#方法） | 注入点 | 目的 |
| --- | --- | --- | --- |
| `render\GameRendererSmaaWorldMixin` | `GameRenderer#render` | ① `@Inject` INVOKE `GuiRenderer#render()V` ② `@Inject` INVOKE `CommandEncoder#clearDepthTexture(GpuTexture,D)` shift=BEFORE | ① 世界画完、GUI 未画之间依次 `D3D12TAA` → `D3D12SMAAWorld` → `SuperResolution` → DLSS 提交记账 → `D3D12GUISplit.prepareForGui` ② 清深度前只做 DLSS 的每帧记账 |
| `render\LevelRendererMainPassDepthMixin` | `LevelRenderer#lambda$addMainPass$0` | `@Inject` RETURN, remap=false | 采时域数据、录速度 pass、深度快照、OIT 透射率（唯一无条件入帧图的 pass，且在任何清深度之前） |
| `render\ChunkSectionsToRenderSamplerMixin` | `ChunkSectionsToRender#renderGroup` / `#renderOit` | `@ModifyVariable`（HEAD, argsOnly）×2 | 只给地形专用 sampler 加超分 mip bias，不污染共享 sampler 缓存 |
| `render\GameRendererTransparencyMixin` | `GameRenderer#useImprovedTransparency` | `@Inject` HEAD, cancellable | mesh 地形强制返回 `true`：mesh 路径的线程组顺序不保证，只能走顺序无关的 Wavelet OIT |
| `render\SectionUploadCaptureMixin` | `SectionRenderDispatcher$RenderSection#addSectionBuffersToUberBuffer` | `@Inject` HEAD | 记下每段每层的 indexCount 与缓冲尺寸，供 meshlet 几何构建 |
| `render\DynamicGpuDataCaptureMixin` | `DynamicGpuData#writeChunkSectionCommands`（HEAD/RETURN）、`#writeChunkSectionsInstanced`（HEAD） | `@Inject` ×3 | 抓 `drawIndexedIndirect` 的参数缓冲，mesh 路径按同一份参数重放 |

深度唯一有效的采集点是 `LevelRenderer#lambda$addMainPass$0` 的 RETURN：帧图 main pass 是唯一无条件进入帧图的 pass，
深度在那一刻有效、且在全部 `clearDepthTexture` 之前。**不要改挂 `GameRenderer.render`**（那时深度已清过三次，采样全为
`mean=0.0`），也不要挂 `always_on_top` pass（`hasAnyAlwaysOnTop()` 为假时根本不跑）。
`SectionUploadCaptureMixin` 必须挂 `addSectionBuffersToUberBuffer`，不要改挂 `SectionCompiler.compile`：后者能成功应用，
但回调一次也不触发。

### 3.7 视频设置与选项（10）

| Mixin | 目标（类#方法） | 注入点 | 目的 |
| --- | --- | --- | --- |
| `options\PreferredGraphicsApiMixin` | `PreferredGraphicsApi`（`<clinit>` / `values` / `getBackendsToTry`） | `@Inject` TAIL + `@ModifyReturnValue` ×2 | 往原版枚举里追加 `D3D12` 常量、把 `CODEC` 重建为 `StringRepresentable.fromEnum(values)`、让后端尝试列表只含我们的条目 |
| `options\PreferredGraphicsApiAccessor` | `PreferredGraphicsApi#<init>` | `@Invoker("<init>")`，方法体抛 `AssertionError` | 造枚举常量；AT 救不了枚举构造器，只能这样 |
| `options\OptionsMixin` | `Options#<init>` | `@ModifyArg`（`OptionInstance` 七参构造 index=1）+ `@Inject` at INVOKE `Object.<init>` shift=AFTER + `@Inject` RETURN | 给图形后端选项换 tooltip；安装「永不」限帧档；最后再补一次 NEVER 的专用 tooltip |
| `options\OptionsAntiAliasingMixin` | `Options#load` / `#save` | `@Inject` TAIL | 抗锯齿设置的读写 |
| `options\OptionsRenderOptionsMixin` | `Options#load` / `#save`；`#isRestartRequiredToApplyVideoSettings` | `@Inject` TAIL ×2；`@Inject` RETURN cancellable | 七个非 `Options` 字段的设置项的持久化；HDR 待重启时把重启提示变成 `true` |
| `options\OptionsGraphicsPresetMixin` | `Options#applyGraphicsPreset` | `@Inject` INVOKE `GraphicsPreset#apply` shift=AFTER | 画质预设联动抗锯齿（fast=off、fancy=SMAA、fabulous=TAA、custom 不动） |
| `options\OptionsScreenVideoMixin` | `OptionsScreen#openScreenButton` | `@Inject` HEAD, cancellable | 标题等于 `VIDEO` 且 HDR 可用时，换成自绘的视频设置界面 |
| `options\VideoSettingsScreenOptionsAccessor` | `VideoSettingsScreen#displayOptions` / `#qualityOptions` / `#preferenceOptions` | `@Invoker` ×3 | 取出原版三个私有静态选项数组 |
| `options\OptionInstanceAccessor` | `OptionInstance#tooltip` | `@Accessor` | 读回 tooltip（26.3 的 `caption` / `toString` 已 public，只有 tooltip 还是私有） |
| `options\TooltipAccessor` | `Tooltip#message` | `@Accessor` | 拿未换行的原始文本，换行由自己 `Font.split` |

几条设计约束藏在注释里：

- `@ModifyArg` 包装 `TooltipSupplier` 时必须用**原始类型**：泛型 lambda 会对非图形选项也 emit `checkcast`，
  一次 ClassCastException 就够让游戏起不来。
- `getBackendsToTry` 必须返回**单条目** `new GpuBackend[]{ new D3D12Backend() }`：返回多条目会让
  `BackendCreationException` 被吞掉、静默回退 OpenGL；失败改由 `D3D12Fatal` 重置 `preferredGraphicsBackend` 并崩溃。
- 七个设置项的 `load` / `save` 与 `OptionsAntiAliasingMixin` 分成两个 mixin，各自只改自己的键，互不破坏。

### 3.8 调试界面（4）

| Mixin | 目标（类#方法） | 注入点 | 目的 |
| --- | --- | --- | --- |
| `render\DebugEntrySystemSpecsMixin` | `DebugEntrySystemSpecs#display` | `@Inject` TAIL | 往 `SYSTEM` 组追加适配器 / 显示相关行；非 D3D12 时 `D3D12AdapterInfo.current == null`，返回空列表 |
| `render\DebugEntryTerrainLineMixin` | `DebugEntrySimplePerformanceImpactors#display` | `@WrapOperation`（INVOKE `DebugScreenDisplayer#addLine(String)`） | 在地形行后追加 mesh 徽标，不新增行 |
| `render\DebugEntryFpsMixin` | `DebugEntryFps#display` | `@WrapOperation`（INVOKE `addPriorityLine(String)`） | 帧率行改成 rendered / output 两个数值 |
| `render\DebugCrosshairRendererMixin` | `DebugCrosshairRenderer` | 无（空 mixin） | 3D 准星接管已停用；保留空 mixin 是为了把「为什么停用、恢复条件」留在原位置（删掉不改变行为） |

### 3.9 限帧兼容（2）

| Mixin | 目标（类#方法） | 注入点 | 目的 |
| --- | --- | --- | --- |
| `client\InactivityFpsLimitMixin` | `InactivityFpsLimit` 的 `CODEC` 与 `$VALUES` | `@Mutable @Final @Shadow` | 允许 `FpsLimitCompat` 往枚举里插一个 `NEVER` |
| `client\FpsLimitTrackerMixin` | `FramerateLimitTracker#getFramerateLimit` | `@Inject` HEAD, cancellable | 失焦限帧档为 `NEVER` 时返回 `options.framerateLimit()`，即取消失焦降帧 |

`FpsLimitCompat` 在 `dev.nts.expdxr.compat`（`compat` 包唯一文件），用 `privateLookupIn` 取 4 参构造器
`(String,int,String,String)` 造 `("NEVER", ordinal, "never", "options.inactivityFpsLimit.never")`：描述符必须写满四个参数，
javac 给枚举隐式加了 `name` / `ordinal`，少写就 `NoSuchMethodException`；写字段用普通反射而非 `VarHandle` ——
`@Mutable` 已去掉 final，`VarHandle.set` 反而抛 `UnsupportedOperationException`。

### 3.10 阶段计时（10）

| Mixin | 目标（类#方法） | 段 |
| --- | --- | --- |
| `render\MinecraftRenderFrameProfMixin` | `Minecraft#renderFrame` | `FRAME`（HEAD 还负责 `frameStart` 与 `ImprovedTransparencyLock.ensure()`） |
| `render\GameRendererProfMixin` | `GameRenderer#extract` / `#render` / `#renderLevel` | `EXTRACT` / `RENDER` / `WORLD` |
| `render\GuiRendererProfMixin` | `GuiRenderer#render` | `GUI` |
| `render\LevelRendererProfMixin` | `LevelRenderer#submitFeatures` / `#prepareChunkRenders` / `#compileSections` | `SUBMIT_FEATURES` / `PREPARE_CHUNKS` / `COMPILE_SECTIONS` |
| `render\SectionRenderDispatcherProfMixin` | `SectionRenderDispatcher#uploadTerrainBuffersToGpu` | `UPLOAD_TERRAIN` |
| `render\SectionOcclusionGraphProfMixin` | `SectionOcclusionGraph#update(CameraRenderState,int,ChunkLoadingRenderState)` | `OCCLUSION` |
| `render\FeatureRenderDispatcherProfMixin` | `FeatureRenderDispatcher#prepareFrame` | `PREPARE_FEATURES` |
| `render\FrameGraphBuilderProfMixin` | `FrameGraphBuilder#execute(GraphicsResourceAllocator,FrameGraphBuilder$Inspector)` | `EXECUTE_FRAMEGRAPH`（HEAD 先调 `D3D12ShadingRateImage.prepareFrame()`：此刻主深度还是上一帧最后一笔，正是 SRI 的输入） |
| `render\ChunkSectionsToRenderProfMixin` | `ChunkSectionsToRender#renderGroup` | `RENDER_GROUP` |
| `debug\PreparedFrameProfMixin` | `FeatureRenderDispatcher$PreparedFrame#executeSolid` | `EXEC_FEATURES` |

两条共同约定：HEAD `begin` / RETURN `end`，统一 `remap=false`；用 RETURN 而不是 TAIL，是因为原版方法有提前 return 的路径。
`FrameGraphBuilder#execute` 有两个重载，必须写完整描述符，否则注入点歧义。

## 4. 一帧里的挂点顺序

这张表是 mixin 与后端之间的**时序契约**：后端假设这些时刻的数据已经就绪，顺序一变就会读到上一帧或空数据。

| 顺序 | 挂点 | 那一刻发生什么 | 谁消费 |
| --- | --- | --- | --- |
| 1 | `Minecraft#renderFrame` HEAD | 开 `FRAME` 段、`frameStart`、强制开启改进透明 | `D3D12RenderProf` |
| 2 | `GameRenderer#extract` | 开 `EXTRACT` 段 | 同上 |
| 3 | `FrameGraphBuilder#execute` HEAD | `D3D12ShadingRateImage.prepareFrame()` + 开 `EXECUTE_FRAMEGRAPH` | VRS |
| 4 | `LevelRenderer#render` | 辅助目标改用 `worldLayerSize`；三段计时 | 渲染比例 / 计时 |
| 5 | `LevelRenderer#lambda$addMainPass$0` RETURN | TAA 时域数据、速度 pass、深度快照、OIT 透射率 | TAA / DLSS / 超分掩码 |
| 6 | `ChunkSectionsToRender#renderGroup` | 地形 sampler 加 mip bias、`RENDER_GROUP` 段 | 超分 / 计时 |
| 7 | `GameRenderer#render` INVOKE `GuiRenderer#render` | TAA → SMAA → SR → DLSS 提交记账 → `prepareForGui` | 后处理链 / 帧生成 |
| 8 | `GuiRenderer#draw` / `CubeMap#render` | 换到 GUI 目标 | GUI 拆分 |
| 9 | `GameRenderer#processBlurEffect` | 背景模糊换到 GUI 目标 | GUI 拆分 |
| 10 | `GuiRenderer#render` | 开 `GUI` 段 | 计时 |
| 11 | `Minecraft#close` HEAD | 接管来的 SDL 窗口刷黑 | 退出闪屏 |

第 7 步的顺序是硬约束，为什么必须是这个顺序、以及 SMAA 与 DLSS 为什么各判各的开关，写在抗锯齿与超分两篇文档里；
这里只记一个事实：**它是全帧唯一一个「世界已画完、GUI 还没画」的可注入时刻。**

## 5. 早期窗口（early window）

### 5.1 它解决什么问题

FML 会在游戏窗口存在之前显示一个加载窗口。默认实现走原版 GL 路线，而我们的呈现链要的是「一个 HWND + 自建 D3D12
交换链」；让游戏自己建窗口就会出现两个窗口 —— `WindowAdoptCreatedMixin` 注释里的原话是「Without this the early
loading window and the game window are two different windows」。所以目标是：**我们自己建那个窗口、把加载屏画进去，
然后在 `Window#createWindow` 那一刻把它交出去**，让 D3D12 交换链绑的是同一个 HWND。接管**只对 D3D12 后端生效**：
原版 OpenGL / Vulkan 路径需要自己的 GL attributes / surface flags，必须自己建窗口，那种情况下我们把窗口收走并隐藏。

### 5.2 `ImmediateWindowProvider` 的契约

| 方法 | 我们做什么 | 约束 |
| --- | --- | --- |
| `name()` | 返回 `"dxrmc"` | `fml.toml` 的 `earlyWindowProvider` 就是按这个名字选的 |
| `initialize(ProgramArgs)` | 初始化 SDL、建 854x480 可缩放窗口、建软件 renderer、画第一帧、把句柄写进系统属性 | 任何一步失败都直接 return，句柄留 0 |
| `periodicTick()` | 每 4 tick 重画一次 | **不得 drain SDL 事件**（见 §5.3） |
| `updateProgress(String)` | 空实现 | 说明文字固定（`Initializing DirectX 12 backend...`），因为它描述的是正在初始化的后端 |
| `completeProgress()` | `progressTicks = 50` 后重画 | 进度条只用于「还在加载」的观感 |
| `handOverToMinecraft(Supplier)` | 读 `dxrmc.earlywindow.adopted` 决定交窗口还是丢弃 | 返回固定 `WindowState(0,0,854,480,false,…)` |
| `close()` | 只销毁 renderer，不销毁窗口 | 窗口归游戏所有了 |
| `setMinecraftVersion` / `setNeoForgeVersion` / `crash` / `stopEventPolling()` | 只打日志；`stopEventPolling` 空实现（我们从没启动过事件轮询） | — |
| `displayFatalErrorAndExit(...)` | 打 ERROR 后 `Runtime.getRuntime().halt(1)` | 加载阶段没有可用的崩溃界面 |

### 5.3 三个实现细节（必须这样做）

**用软件 renderer，不用默认 renderer。** 调 `SDL_CreateRenderer(window, "software")`，返回 0 时才退回默认 renderer 并打
一条 WARN —— 软件 renderer 在任何显卡 / 驱动状态下都能画出加载屏，而这一步正好发生在显卡后端还没起来的时候。

**加载屏用 AWT 合成，整帧走 surface → texture，不要建流式纹理。** 素材：`assets\logo_flat_dark.jpg`、Mojangles ASCII 页
（`assets\dxrmc\mojangles_ascii.png`，16×16 格、每格 8×8）；配色：进度条 `0xC46BFF`、轨道 `0x2E3238`、背景纯黑、文字 `0xD8,0xDC,0xE2`；
整帧经 `SDL_CreateSurfaceFrom`（`ARGB8888`）+ `SDL_CreateTextureFromSurface` + `RenderTexture` 上屏 —— 软件 renderer **静默忽略**流式纹理上传。

**不要在 `periodicTick()` 里 drain SDL 事件。** FML 在加载线程上调用 `periodicTick()`，而 `SDL_PollEvent(NULL)` 只要还有
待处理事件就一直返回 `true` —— 清一次事件队列就卡死加载线程。代码里用 NOTE 注释标明这是故意的。

### 5.4 交接协议：两个系统属性

provider 跑在 loader 的 early-service 类加载器里，mixin 跑在游戏类加载器里，**两边不能共享静态字段**。
唯一能共享的就是 JVM 级系统属性：

| 属性 | 写入方 | 读取方 | 语义 |
| --- | --- | --- | --- |
| `dxrmc.earlywindow.handle` | `initialize()` | `WindowAdoptCreatedMixin` | 待接管的窗口句柄；被接管时被 mixin 清除 |
| `dxrmc.earlywindow.adopted` | `WindowAdoptCreatedMixin`（值为 `"1"`） | `handOverToMinecraft` | 「窗口真的被 D3D12 路径拿走了」，读后清除 |

三条不变量：

1. 句柄**用后即清**，后面的窗口不会捡到陈旧句柄。
2. 非 D3D12 后端**不清**句柄 —— 游戏每个后端尝试建一次窗口，我们的后端可能第二次才起来。
3. `handOverToMinecraft` 无论哪条分支都把两个属性清掉。

### 5.5 为什么必须是一个独立 jar，以及安装器在做什么

FML 只在 `mods/` 下的**顶层 `*.jar`** 里找早期服务（`EarlyServiceDiscovery` 按 `.jar` 后缀扫目录），而且**带这类
`META-INF/services` 条目的 jar 会被认领、并从模组发现中排除**。后果很直接：provider 放进模组 jar，模组自己就会从
模组列表里消失。`META-INF/jarjar` 也不行 —— 嵌套 jar 的解压发生在 `ImmediateWindowHandler.load()` 之后。

于是拆成两个 jar：

| 产物 | 内容 | 谁加载 |
| --- | --- | --- |
| `dxr_mc-<ver>.jar` | 正常模组，**排除** `earlywindow` 类与那条 service 文件 | 模组发现 |
| `META-INF/dxrmc/earlywindow.jar`（嵌在上者内部） | 只有 `dev/nts/expdxr/earlywindow/**` + service 文件 + 加载屏图片 | 由安装器解到 `mods/` |

安装器 `EarlyWindowJarInstaller.ensureInstalled()` 在模组构造期跑（`Illusion.init` 第 44 行），做两件事：

1. 把内嵌 jar 写到 `mods\dxrmc-earlywindow.jar`。与磁盘上内容相同就跳过；先写同目录 `.tmp` 再 `Files.move`（半写的 jar
   会让下一次启动失败）；旧 jar 被当前进程占用无法替换时，把旧 jar 移成 `dxrmc-earlywindow.stale` 再把新的放到正式路径。
2. 把 `config\fml.toml` 的 `earlyWindowProvider` 改成 `"dxrmc"`：已有该键就整行替换，没有就追加；`fml.toml` 不存在时不动（不创建）。

两条都生效需要**重启一次**，日志会明说「restart the game once」。内容相同则不写盘，所以正常启动不会每次都改文件。

### 5.6 失败与退回

| 失败点 | 触发条件 | 结果 |
| --- | --- | --- |
| SDL 初始化 / 建窗口失败 | `SDL_Init(SDL_INIT_VIDEO)` 或 `SDL_CreateWindow` 返回 0 | 打 ERROR 后 return，句柄保持 0、属性不写；`WindowAdoptCreatedMixin` 直接放行，原版自己建窗口 |
| 软件 renderer 不可用 | `SDL_CreateRenderer(window,"software")` 返回 0 | 打 WARN，退回默认 renderer，继续 |
| 后端不是 D3D12 | `Window#createWindow` 收到的 backend 不是 `D3D12Backend` | mixin 不接管；交接时打日志「graphics backend is not D3D12 - dropping the early window…」+ `close()` + `SDL_HideWindow` |
| 句柄解析失败 / 为 0 | 属性不是数字，或值为 `"0"` | mixin 不接管，原版建窗口 |
| 纹理 / 表面创建失败 | `SDL_CreateSurfaceFrom` / `SDL_CreateTextureFromSurface` 返回 0 | 只打一次 WARN（`textureFailureLogged` 去重），这一帧不显示，加载继续 |
| 致命加载错误 | `displayFatalErrorAndExit` | 打 ERROR，`Runtime.halt(1)` |

注意「接管成功」这条路径上**故意不销毁 renderer**：销毁它会让窗口黑到游戏第一帧呈现为止，观感上就是闪一下。
最后一帧一直被保留，直到 `Minecraft#close` 时刷黑。

### 5.7 退出时为什么要涂黑

`Minecraft#close` 的顺序是：`windowSurface.close()`（销毁 D3D12 交换链）→ … → `finally` 里才 `window.close()`
（`SDL_DestroyWindow`）。在交换链已经停止呈现、SDL 窗口还没销毁的这一小段里，SDL 会把手里的
**最后一次 present 的那一帧**重新铺到客户区 —— 也就是早期加载屏。所以：

- `MinecraftCloseEarlyWindowMixin` 在 `close` 的 HEAD 调 `EarlyWindowSurface.paintBlack()`，
  用 `SDL_CreateRenderer(handle, "software")` + 黑色 + `SDL_RenderClear` + `RenderPresent` 把最后那帧换成黑色。
- 句柄是一次性的：`paintBlack()` 取出后立刻清零，重复调用什么都不做。
- 没走单窗口路径（句柄为 0）时，这个 mixin 是空操作。
- 刷黑失败只打一条 WARN：`cannot clear the adopted window before shutdown: …`，不阻断关闭。

## 6. 新增一个 mixin 的规范

### 6.1 命名

| 对象 | 规则 | 现有例子 |
| --- | --- | --- |
| 包 | 按注入的系统分：`render` / `options` / `core` / `client` / `debug` | 29 / 10 / 2 / 2 / 1 条 |
| 类名 | 目标类名 + 作用 + `Mixin`；访问器叫 `XxxAccessor` | `GameRendererSmaaWorldMixin`、`OptionInstanceAccessor` |
| 处理器方法 | `dxr_mc$` + 动词短语 | `dxr_mc$adoptEarlyWindow`、`dxr_mc$clearEarlyWindow` |
| 目标写不出名字时 | `@Mixin(targets = "全限定名")` | `SectionUploadCaptureMixin`、`debug\PreparedFrameProfMixin`（目标是内部类） |

`dxr_mc$` 前缀是为了在堆栈、字节码工具与崩溃日志里能一眼看出这是本模组注入的成员，而不是原版方法。

### 6.2 配置登记

- 新 mixin 加到 `dxr_mc.mixins.json` 的 `client` 数组，写相对 `package` 的路径（`render\FooMixin` 写成 `render.FooMixin`）。
- 数组顺序不影响织入顺序；保持现在「按包分组」的排列，便于人工核对。
- 目标类在服务端不存在 → 只能进 `client`，不要往 `mixins` 里放。
- `neoforge.mods.toml` 不用动，`config` 已经声明过整份文件。
- 不要忘了 `required: true` 的语义：数组里多写一条不存在的类会导致启动失败（这正是想要的）。

### 6.3 优先级

44 个 mixin **没有一个**声明 `priority`（默认 1000）。同一目标类上的多个 mixin（`Options` 上有 4 个、
`GameRenderer` 上有 5 个、`LevelRenderer` 上有 3 个）靠「注入点位置 + 被注入方法名」区分，不靠优先级排序。

不要为了抢顺序去加 `priority`：真正的顺序保证来自 `INVOKE` 目标与 `shift`，那是可验证、可 review 的；
`priority` 只影响同一位置的多个回调谁先跑，改了以后没有任何编译期或启动期检查替你兜底。

### 6.4 注入点选择

| 需求 | 用法 | 备注 |
| --- | --- | --- |
| 方法入口做判定 / 改返回值 | `@Inject` at `HEAD` + `cancellable = true` | 取消时务必 `setReturnValue`，否则原版方法带着半初始化状态继续跑 |
| 方法正常结束时采集 | `@Inject` at `RETURN` | 优于 `TAIL`：覆盖提前 return 的路径 |
| 某条语句的前后 | `@Inject` at `@At(value = "INVOKE", target = "…")` + `shift = BEFORE/AFTER` | 最稳的「锚点」，顺序由原版语句决定 |
| 改一个调用的实参 | `@WrapOperation`（INVOKE） | 优于 `@Redirect`：同一调用点有多个 mod 时不互相吞掉 |
| 改一个表达式的结果 | `@ModifyExpressionValue`（INVOKE / FIELD） | 只改值，不改控制流 |
| 改构造器某个参数 | `@ModifyArg`（index=…） | 注意泛型擦除带来的 `checkcast` |
| 改传进方法的变量 | `@ModifyVariable`（`argsOnly = true`） | 地形 sampler 的 mip bias 用这个 |
| 包住一个 `new` | `@WrapOperation` at `@At("NEW")` | 私有类型做参数时唯一选择 |

关于 `remap`：本项目多处写 `remap = false`（lambda 方法名、`@At("NEW")` 的目标、字段 GETFIELD、部分原版方法）。
配置文件里没有 `refmap` 字段，26.3 运行时用的是官方名。哪些点**必须**写、哪些可以不写，代码里没有统一注释
（`代码中未明确`）：新增时按同目录同类注入点照抄，改注入点时顺手验证一遍。

### 6.5 七条硬约束

1. **处理器 `static` 与否必须和目标方法一致。** `GameRenderer#preloadUiShader` 是静态方法，非 static 处理器在 APPLY 阶段 fatal。
2. **mixin 类不能有非 private 的成员方法。** 需要工具方法就放 Kotlin。
3. **注入代码不能引用 `dev.nts.expdxr.mixin.*` 里的类**（`IllegalClassLoadError`）。共用逻辑放 `compat` / `misc` / `d3d12`。
4. **`@Shadow` 只能读已存在的成员；写 final 静态字段需要 `@Mutable @Final @Shadow`，并且用普通反射写**（`VarHandle.set` 会抛 `UnsupportedOperationException`）。
5. **私有 record / 私有内部类只能包 `NEW`**：`ShaderManager$PendingResults` 这类参数类型写不进 Java 源码，`@Inject` 无法声明签名。
6. **描述符要写全。** 内部类带 `$`（`FrameGraphBuilder$Inspector`），有重载就写完整参数表。
7. **不要把 `defaultRequire` 降成 0。** 它现在是 `1`，注入点消失会在启动时炸；降成 0 之后同类问题会变成静默失效。

### 6.6 提交前自检

- [ ] 目标方法有重载？写完整描述符。
- [ ] 目标是 lambda / 私有内部类？用 `targets =` + `remap = false`。
- [ ] 依赖返回值？`cancellable = true`，并且所有分支要么都 `setReturnValue`、要么都不设。
- [ ] 处理器 `static` 与目标方法一致。
- [ ] 新类已进 `client` 数组，且路径相对 `package`。
- [ ] 需要 AT 吗？能不用就不用（枚举构造器那种 AT 也救不了）。
- [ ] 逻辑超过 20 行 → 挪进 Kotlin，mixin 只留调用。
- [ ] 注释写的是「为什么是这个点」，不是「这里做了什么」。

## 7. 文件导航

mixin 侧（前缀 `Illusion\src\main\java\dev\nts\expdxr\mixin\`，逐条清单见 `dxr_mc.mixins.json`）：

| 文件 | 内容 |
| --- | --- |
| `core\WindowAdoptCreatedMixin.java`、`core\MinecraftCloseEarlyWindowMixin.java` | 接管早期 SDL 窗口（仅 D3D12）、退出前刷黑 |
| `render\GameRendererSmaaWorldMixin.java` | 全帧唯一的「世界画完 / GUI 未画」挂点 |
| `render\LevelRendererMainPassDepthMixin.java` | main pass RETURN：时域、速度、深度、透射率 |
| `render\MainTargetRenderScaleMixin.java`、`render\RenderTargetCreateBuffersScaleMixin.java`、`render\LevelRendererWorldTargetsScaleMixin.java` | 渲染比例的三个尺寸点 |
| `render\GuiRendererTargetMixin.java`、`render\CubeMapTargetMixin.java`、`render\GameRendererBlurTargetMixin.java` | GUI 目标归属 |
| `render\FrontendGpuDeviceCompilePipelineMixin.java`、`render\ShaderManagerRequiredFailureMixin.java`、`render\GameRendererPreloadUiShaderMixin.java` | 管线与着色器 |
| `render\SectionUploadCaptureMixin.java`、`render\DynamicGpuDataCaptureMixin.java` | 地形数据采集 |
| `render\*ProfMixin.java`（9 个）、`debug\PreparedFrameProfMixin.java` | 阶段计时 |
| `options\OptionsMixin.java`、`options\OptionsRenderOptionsMixin.java`、`options\OptionsAntiAliasingMixin.java`、`options\OptionsGraphicsPresetMixin.java` | `Options` 上的四处注入 |
| `options\PreferredGraphicsApiMixin.java`、`options\PreferredGraphicsApiAccessor.java` | 往原版枚举里追加 D3D12 |
| `options\OptionsScreenVideoMixin.java`、`options\VideoSettingsScreenOptionsAccessor.java` | 自绘视频设置界面 |
| `client\InactivityFpsLimitMixin.java`、`client\FpsLimitTrackerMixin.java`、`render\DebugCrosshairRendererMixin.java` | 「永不」限帧档；已停用的准星空壳 |

早期窗口与兼容层：

| 文件 | 内容 |
| --- | --- |
| `Illusion\src\main\java\dev\nts\expdxr\earlywindow\DxrmcEarlyWindowProvider.java` | 独立 jar 里的 provider：SDL 窗口 + AWT 加载屏 + 交接 |
| `Illusion\src\main\kotlin\dev\nts\expdxr\misc\fmlelw_control\EarlyWindowJarInstaller.kt` | 解出 `mods\dxrmc-earlywindow.jar`、改 `fml.toml` |
| `Illusion\src\main\kotlin\dev\nts\expdxr\misc\fmlelw_control\EarlyWindowSurface.kt` | 记住 / 刷黑被接管的窗口句柄 |
| `Illusion\src\main\java\dev\nts\expdxr\compat\FpsLimitCompat.java` | 反射安装 `InactivityFpsLimit.NEVER`（`compat` 包唯一文件） |
| `Illusion\src\main\resources\dxr_mc.mixins.json`、`...\META-INF\accesstransformer.cfg` | 44 条注册；两条 AT |
| `Illusion\src\main\resources\META-INF\services\net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider`、`...\neoforge.mods.toml` | provider 的 service 声明；`[[mixins]]` 与 `[[accessTransformers]]` 登记 |
| `Illusion\build.gradle.kts` | `earlyWindowJar` 任务、主 jar 的排除与嵌入、`jarALL` |

被 mixin 调用的 Kotlin 侧（前缀 `Illusion\src\main\kotlin\dev\nts\expdxr\`）：

| 文件 | 内容 |
| --- | --- |
| `d3d12\render\adapter\D3D12RenderProf.kt` | 段计时，供 10 个 `*ProfMixin` 静态调用 |
| `d3d12\render\advanced\vrs\D3D12RenderScale.kt`、`D3D12ShadingRateImage.kt` | 附件尺寸换算 / 世界层尺寸；每帧 SRI 准备 |
| `d3d12\render\advanced\smaa\D3D12GuiSplit.kt`、`...\smaa\D3D12SmaaWorld.kt`、`...\taa\D3D12Taa.kt` | GUI 目标拆分与本帧目标选择；SMAA 世界层；TAA 时域数据 |
| `d3d12\backend\D3D12PipelineBridge.kt`、`render\shader\D3D12FallbackShaderSource.kt` | 管线与 `ShaderSource` 登记；缺失管线的回退源码 |
| `d3d12\render\advanced\mesh\D3D12SectionGeometry.kt`、`D3D12MeshDrawCapture.kt` | 地形段几何与间接绘制捕获 |
| `d3d12\render\advanced\terrain\D3D12TerrainSampler.kt` | 地形 sampler 包装（mip bias） |
| `d3d12\debug\D3D12DebugInfo.kt`、`D3D12FrameStats.kt` | F3 数据行的来源 |
| `d3d12\backend\D3D12Backend.kt`、`misc\settings\D3D12Settings.kt` | 判定「是不是我们的后端」；`skipGlSwap` / `meshTerrain` 等开关 |
| `dxrmc\sr\SuperResolution.kt`、`dxrmc\sr\D3D12SrMask.kt` | 超分后端选择与掩码 |

## 8. 文档维护

改这一层时，请同步更新本文的三类内容：

1. **§3 的表格**：新增或删除 mixin 时，行必须跟着变 —— 表里留一条已删除的 mixin 比没有表更糟。
   同时更新 `dxr_mc.mixins.json` 的条数与 §3.1 的总览（现在是 3/3/3/3/6/10/4/2/10 = 44）。
2. **§4 的时序**：注入点的相对顺序是后端的假设。移动一个挂点时（尤其是第 5、7 步），
   先确认 `D3D12TAA` / DLSS / `D3D12GUISplit` 的消费时机，再改这张表。
3. **§5 的交接协议**：`dxrmc.earlywindow.handle` / `.adopted` 两个属性、`mods\dxrmc-earlywindow.jar`
   的路径与 `fml.toml` 的键名都是硬编码字符串。改任何一个，provider、mixin、安装器三处要同时改，
   并且这里要写清楚新值。

写作约定（与 `Illusion\docs\` 其他文档一致）：

- 只写能从代码确认的事实，具体到描述符、键名、颜色值与行数。
- 代码没写清的地方直接写「代码中未明确」，不要用推测填空。
- 不出现工单号、会话编号；不引用仓库根目录的过程性文档（它们在发布包里不存在）。
- 结论要能反查：新增段落时，如果一句话对应不上任何代码位置，就不要写。

本文与 `shaders\README.md`（管线编译链）、`antialiasing\README.md` 与 `superresolution\README.md`（世界层挂点）、
`misc\settingscreen\README.md`（设置项持久化）有交叉，交叉处只保留接口约定，细节以各篇为准。
