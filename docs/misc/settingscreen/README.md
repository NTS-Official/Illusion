# 设置界面与配置系统

本文说明 Illusion（模组 id `dxr_mc`）的设置如何从定义走到界面：配置项定义在 `IllusionConfig`，
解析成不可变快照在 `D3D12Settings`，最后由自绘的视频设置界面读属性、写 `OptionInstance`。只写
从代码能确认的事实；代码没有明确的，直接注明「代码中未明确」。

---

## 1. 全景：两条持久化通道 + 一条翻译通道

设置数据分成两条互不相干的持久化通道，这是理解整个系统的前提：

| 通道 | 落盘位置 | 谁保存 | 内容 |
| --- | --- | --- | --- |
| NeoForge 配置 | `config/dxr_mc-client.toml` | `ModConfigSpec`（`IllusionConfig.SPEC`） | 后端/渲染器行为开关：录制线程模型、调试层、PIX、HDR 模式、超分后端、VRS 距离带…… |
| 原版选项 | `options.txt` | `Options.save()` | 视频设置里的 `OptionInstance`：渲染缩放、抗锯齿、DLSS/FSR/XeSS 开关、Reflex、HDR 呈现 |

第三条是纯文案通道：`assets/dxr_mc/lang/en_us.json` 与 `zh_cn.json`。

```
config/dxr_mc-client.toml
        │  ModConfigEvent.Loading / Reloading
        ▼
IllusionConfig（定义 + 默认值 + comment + translation）
        │  读取
        ▼
D3D12Settings（Snapshot：解析、缓存、推给 native）
        │  val xxx get() = snapshot().xxx
        ▼
D3D12VideoSettingsScreen / D3D12VideoSettingsContent / D3D12Widgets
        │  stage → commit → OptionInstance.set → options.save()
        ▼
options.txt + native（专用 ABI 函数）
```

视频设置项为什么不放进 toml：它们必须是原版 `OptionInstance` 才能复用原版的取值集合与格式化
键（如 `options.percent_value`）；但它们不是 `Options` 的字段，原版 `processDumpedOptions` 遍历
看不到，所以由 `OptionsRenderOptionsMixin` 在 `Options.load`/`save` 的 `TAIL` 上按 `键:值` 行
手工读写同一个文件。「应用」按钮只做「写回 OptionInstance + `options.save()`」，与 toml 无关。

---

## 2. `IllusionConfig`：定义层

### 2.1 注册

`Illusion.kt` 的 `init` 里 `modContainer.registerConfig(ModConfig.Type.CLIENT, IllusionConfig.SPEC)`
——客户端类型决定文件名 `config/dxr_mc-client.toml`。字段在文件顶部只做类型声明，真正的定义集中
在同一个 `init` 块里按节 `push`/`pop`。文件顶部另有全部枚举（`CommandBundleMode`、
`DlssImplementation`、`DlssFrameGeneration`、`SrBackend`、`HdrMode`、`RtMode`、`MeshDiagnostics`），
因为 `defineEnum` 需要类型；枚举常量上的 KDoc 是「这个取值意味着什么」的第一现场。

### 2.2 九节

| 节 | 键前缀 | 管什么 | 生效粒度 |
| --- | --- | --- | --- |
| `[logging]` | `dxr_mc.configuration.logging` | 日志级别、native 详细日志、关停追踪、落盘 | 重载即可 |
| `[renderer]` | `…renderer` | GL 上下文逃生口、异步录制、命令 bundle、HDR、交换链、锐化 | 混合：多数要重启 |
| `[dlss]` | `…dlss` | DLSS 实现、输出交接、Reflex 限帧、帧生成 | 实现选择要重启 |
| `[sr]` | `…sr` | 超分后端（NONE/DLSS/FSR/XeSS）、锐度、响应遮罩 | 下一帧 |
| `[rt]` | `…rt` | 光追可见性分层 | 下一帧 |
| `[shaderpack]` | `…shaderpack` | 启用的光影包（空串＝无光影） | 下次打开光追 |
| `[mesh]` | `…mesh` | mesh 地形与 amplification 剔除 | 代码中未明确 |
| `[vrs]` | `…vrs` | 逐图元距离带、着色率图像参数 | 每个地形批次 |
| `[debug]` | `…debug` | PIX、调试层、自检、HLSL 落盘、崩溃弹窗 | 多数要重启 |

节注释是粗粒度说明，**逐项以字段自己的 `comment` 与 `gameRestart()` 为准**：`[renderer]` 的节
注释写「改一个值需要重启游戏」，但 `hdr`、`allowTearing`、`presentChildWindow` 三项都没有
`gameRestart()`，注释里写的是「下一次创建交换链时生效」。

### 2.3 字段三件套

```kotlin
PIX_CAPTURE_FRAMES = BUILDER
    .comment(
        "How many frames one key-press capture records (read when the key is pressed).",
        "600 frames is about ten seconds at 60 fps, which is already a large file."
    )
    .translation("dxr_mc.configuration.debug.pixCaptureFrames")
    .defineInRange("pixCaptureFrames", 600, 60, 20000)
```

- `comment(...)`：多行英文，回答「为什么存在、代价是什么、何时生效」，是唯一写「为什么」的地方；
  界面文案只解释「是什么」。
- `translation(...)`：`dxr_mc.configuration.<节>.<键>`。
- `define*` 的最后一个参数是出厂默认值。
- `.gameRestart()`：只加在「创建期就要读到」的项上，它只影响配置界面那句重启提示，不拦运行时
  （见 §5）。字段名 `UPPER_SNAKE`，TOML 键与翻译键小驼峰。

枚举取值另有 `dxr_mc.configuration.<节>.<键>.<VALUE>` 键，后缀与枚举常量全大写一致。

### 2.4 默认值就是出厂值

改默认值等于改所有新用户的初始体验，也会改变旧来源兼容层的判定（§3.7）。必须同步三处：
`define*` 的默认值、`Snapshot` 的 fallback、`build()` 里 `config(fallback)` 的实参。

### 2.5 语言文件

```
dxr_mc.configuration.<section>                     // 节标题
dxr_mc.configuration.<section>.<key>               // 字段说明
dxr_mc.configuration.<section>.<key>.<ENUM_VALUE>  // 枚举取值名
```

`en_us.json` 与 `zh_cn.json` 必须同时加。缺键不报错，配置界面直接显示原始键名，只能靠肉眼发现。
`debug`/`logging` 各有一个 `.button` 后缀的键，代码中未明确绑定位置，新增选项不必管它。

---

## 3. `D3D12Settings`：解析、缓存与推送

### 3.1 职责

`misc/settings/D3D12Settings.kt` 是「渲染开关的唯一解析处」：把配置读出来做范围收敛与旧来源
兜底，存进不可变 `Snapshot` 用 `@Volatile` 发布，再把 native 必须在设备创建前知道的值通过专用
ABI 推过去。

### 3.2 Snapshot 的形状

```kotlin
private class Snapshot(
    val glContext: Boolean,
    val asyncCommandLists: Boolean,
    val asyncWorkers: Int,
    // ……每个开关一个 val
)

@Volatile private var snapshot: Snapshot? = null
@Volatile private var pushed: String? = null

val asyncWorkers: Int get() = snapshot().asyncWorkers
```

构造参数就是全部开关，**新增开关先加构造参数**，编译器会提示漏项。对外一律只读属性
（`D3D12Settings.meshTerrain`），不是函数。`snapshot()` 懒构建（`snapshot ?: build()`）；
`reload()` 把 `snapshot` 与 `pushed` 置空，下次读属性时重建。

### 3.3 为什么快照

`build()` 的注释给了理由：渲染器在逐帧路径上读这些开关，而 `ModConfigSpec.get()` 带校验与转换
并不便宜；渲染线程与录制 worker 又并发读，不可变对象 + `@Volatile` 发布是最省事的安全做法。

### 3.4 早读不缓存

```kotlin
fun <T> config(fallback: T, read: () -> T): T =
    if (!configAvailable) fallback
    else try { read() } catch (t: Throwable) { configAvailable = false; fallback }
```

模组构造、DLL 初始化时 `config/dxr_mc-client.toml` 可能还没读进来，`get()` 会抛。处理方式是
**返回出厂默认值、且本次不写回 `snapshot`**（`configAvailable` 置 false）；若此时缓存，整个会话
就被冻在默认值上。含义：能早读就早读，但早读失败不能污染缓存，也不要另建「启动时读一次」的
静态字段绕开这套兜底。

### 3.5 重载与推送

```
ModConfigEvent.Loading   → IllusionLogger.applyFromConfig(); D3D12Settings.reload(); pushToNative()
ModConfigEvent.Reloading → 同上
D3D12Backend 设备建好后   → pushToNative()（补推）
```

推两次是必要的：配置加载时 ABI 句柄可能还是 `null`，那次推送被静默丢掉；设备建好后必须补推，
否则「配置写了但 native 不知道」。`pushToNative()` 幂等——用 `buildString` 拼出要推的值与上次
成功的字符串比较，相同直接返回，因此可以随时重复调用。

### 3.6 两类推法

| 推法 | 用于 | 例子 |
| --- | --- | --- |
| `MCDR_ABI.D3D12SetEnvironment(name, value/null)` | 旧功能兼容层 | `DX12_DEBUG_LAYER`、`DX12_PROF`、`MCDR_DLSS_IMPL`、`MCDR_STREAMLINE`、`DX12_NATIVE_LOG_FILE` |
| 专用 ABI 函数 | 所有新功能 | `D3D12SetPIXDirectory`、`D3D12SetReflexFrameLimit`、`D3D12SetHdrMode`、`D3D12SetSwapchainOptions`、`D3D12SetRaytracingMode`、`D3D12SetDlssgMode` |

`D3D12SetDlssgMode(mode, frames)` 在动态模式下强制只生成 1 张——这是 ABI 约束，不是界面逻辑。

### 3.7 旧来源兼容层

几乎每个布尔开关后面都挂着一个旧来源判定：

```kotlin
glContext = config(false) { IllusionConfig.GL_CONTEXT.get() } ||
    System.getenv("DXR_MC_GL_CONTEXT") == "1" ||
    System.getProperty("dxrmc.noApi") == "false" ||
    File("gl_context.flag").exists(),
```

两条规则缺一不可：**只在配置仍是默认值时才看旧来源**（例如 `asyncWorkers` 只在配置等于 8 时
才允许 `DX12_ASYNC_WORKERS` 覆盖），否则玩家在 toml 里的选择会被环境变量无声压掉；**每个 key
只 warn 一次**（`legacy(...)` + 并发 `warned` 集合），日志里说明该来源已弃用。

历史清单：`DXR_MC_GL_CONTEXT`、`gl_context.flag`、`dxrmc.noApi`、`DX12_ASYNC`、
`backend.async`、`DX12_ASYNC_WORKERS`、`backend.async.workers`、`DX12_BUNDLE_MODE`、
`backend.bundleMode`、`dxrmc.sharpen`、`MCDR_DLSS_IMPL`、`MCDR_STREAMLINE`、
`MCDR_REFLEX_FRAME_LIMIT_US`、`dxrmc.dlssOutput`、`DX12_DEBUG_LAYER`、`DX12_PROF`、
`D3D12_LOG_VERBOSE`、`DXR_MC_SKIP_GL_SWAP`、`DX12_NO_ASYNC`、`dxrmc.temporal`、
`dxrmc.temporal.jitter`、`dxrmc.selfTest*`、`dxrmc.failDevice`、`dxrmc.noPopup`、
`DX12_MESH_DIAG`、`DX12_NATIVE_LOG_FILE`。

---

## 4. 生效时机：三级（外加界面一种）

| 级别 | 读取点 | 例子 | 改完怎么生效 |
| --- | --- | --- | --- |
| 逐次直读 | 每次用到都读 `IllusionConfig`，不经快照 | VRS 距离带（每地形批次）、PIX 捕获帧数（按键时）、日志级别 | 下一次用到 |
| 快照级 | `D3D12Settings.snapshot()` | 锐化强度、帧生成倍率、光追模式、超分后端 | 配置重载事件 → 下次读属性 |
| 创建级 | 设备/交换链创建时消费一次 | 调试层、GL 上下文、异步录制、DLSS 实现、HDR、交换链选项 | 必须重启（或重建交换链） |

界面侧的 `OptionInstance` 是第四种：commit 之后立刻 `set()`，回调直接改状态/推 native，所以
「点应用就生效」；落盘由 `options.save()` 负责，与 toml 无关。

---

## 5. 需要重启 vs 必须热切换

### 5.1 判据

一句话：**读取发生在使用那一刻就不需要重启；读取发生在设备/交换链创建之前就必须重启。**
`.gameRestart()` 标的是后者，不是「这个值重要」。

### 5.2 两张清单

必须重启（带 `.gameRestart()`）：

| 键 | 为什么 |
| --- | --- |
| `[renderer] glContext` | 窗口创建方式（`GLFW_NO_API` 还是真 GL 上下文）在建窗口时定死 |
| `[renderer] asyncCommandLists` / `asyncWorkers` / `commandBundles` | 录制线程池在渲染器启动时建立 |
| `[dlss] implementation` | DLSS 后端在第一次 evaluate 前选定 |
| `[debug] debugLayer` | 调试层是设备创建参数 |
| `[debug] asyncRenderThread` | 专用渲染线程在启动时创建 |
| `[debug] pixCapture` / `pixDirectory` | PIX 捕获层必须在第一个 D3D12 设备之前加载 |
| `[debug] selfTest*` / `failDevice` | 启动路径上执行一次的自检 |
| `[debug] meshDiagnostics` | 一次性隔离测试，设备建好后不再读 |

不需要重启：

| 键 | 生效点 |
| --- | --- |
| `[renderer] hdr`、`allowTearing`、`presentChildWindow` | 下一次创建交换链（resize/切全屏也会重建） |
| `[renderer] sharpenAmount`、`taaSharpenAmount` | 下一帧的后处理参数 |
| `[dlss] frameGeneration`、`frameGenerationFrames`、`reflexFrameLimitUs` | 立即推给 native，下一帧 |
| `[sr] backend`、`sharpness`、`mask` | 下一帧；切后端会等 GPU 一次 |
| `[rt] mode` | 下一帧 |
| `[vrs] *` | 每个地形批次重读 |
| `[debug] profiling`、`dumpHlsl`、`skipGlSwap`、`temporalChain`、`temporalJitter`、`noCrashPopup` | 下一个使用点 |
| `[debug] pixTimingCapture`、`pixCaptureFrames` | 按下捕获热键那一刻 |
| `[logging] *` | 下一次日志输出 |

`[mesh] terrain` / `terrainCull` 的生效时机代码中未明确：注释只说关掉之后地形不再绘制、没有
mesh tier 时被忽略，没写是管线创建时读还是每帧读。

### 5.3 为什么禁止用环境变量当功能入口

1. **看不见**：`[debug] pixDirectory` 的注释写着「这里刻意没有环境变量：只以环境变量存在的
   开关，等于没人找得到的开关」。
2. **改起来等于重启**：环境变量在进程启动时固定，改一次要重开游戏，热切换在物理上就不可能。
3. **没有地方联动**：配置项能被界面读取、能在缺依赖时置灰并说明原因、能在两个值互斥时收敛；
   环境变量做不到任何一条。

所以新功能的顺序固定：先有 `IllusionConfig` 字段，再有 `Snapshot`，最后才是调用点。旧读点只
作为兼容层存在，不得扩展，也不得出现在新功能的实现里。

---

## 6. 界面：从屏幕到控件

### 6.1 入口

`OptionsScreenVideoMixin` 只替换「视频设置…」一个按钮：`openScreenButton` 的 `HEAD` 判断标题是否
等于原版 `VIDEO`，且 `D3D12HdrPresentation.isAvailable()` 为真，才 `setScreenAndShow` 自绘界面，
否则原样返回。判据是「设备真的建起来了」而不是配置里的首选后端，所以玩家选了 D3D12 却回退到
原版后端时，仍进原版界面。

### 6.2 屏幕骨架与布局

`D3D12VideoSettingsScreen` 是原版 `Screen`，标题 `dxr_mc.video.title`，内容全部自绘：左侧竖排
页签、右侧内容区（右边界外挂右侧滚动条，见 §6.9）、底部动作按钮。它不把控件注册进 `Screen.renderables`，而是在 `extractRenderState`
里按自己的顺序遍历、自己控制裁剪。

| 常量 | 值 | 用途 |
| --- | --- | --- |
| `WIDTH_SHARE_OF_MONITOR` / `FALLBACK_MAX_WIDTH` | 0.809 / 2071 | 目标宽度 = 显示器宽度 × 比例；找不到显示器时的宽度 |
| `MIN_WIDTH` / `MIN_CONTENT_WIDTH` / `MIN_EXPANDED_WIDTH` | 360 / 220 / 330 | 窗口下限 / 内容区下限 / 低于它滑条行强制折叠 |
| `SIDEBAR_X` / `OUTER_MARGIN` / `GAP` / `TAB_GAP` | 8 / 8 / 8 / 4 | 边距与间距 |
| `HINT_LINE` | 12 | 底部常驻提示行高度（「需要重启」画在这里） |
| `D3D12TabWidget.WIDTH` / `HEIGHT` | 120 / 25 | 页签 |
| `D3D12Widget.DEFAULT_HEIGHT` | 21 | 一行设置的高度 |
| `D3D12OptionWidget.SLIDER_LINE` | 12 | 折叠展开后多出的第二行 |
| `D3D12ScrollBarWidget.WIDTH` / `HIT_PADDING` | 4 / 2 | 右侧滚动条的可见宽度与两侧命中放宽 |
| `SCROLLBAR_OFFSET` / `SCROLLBAR_NESTED_OFFSET` | 6 / 2 | 滚动条距内容区右边界的外扩距离；有二级子页时向内收缩 |
| `HINT_COLUMN_OFFSET` | 14 | 右侧提示块为滚动条让出的整列宽度（`SCROLLBAR_OFFSET + WIDTH + 4`） |

`optimalWidth()` 把「显示器宽度 × 0.809 ÷ GUI 缩放」夹到 `[MIN_WIDTH, 当前宽度]`；下限写成
`MIN_WIDTH.coerceAtMost(width)` 而不是直接 `MIN_WIDTH`，否则窗口比 360 还窄时 `coerceIn` 的
下界大于上界会抛异常。内容区四边在 `init()` 算一次：左让页签、上让标题、右留外边距、下同时让
开底部按钮与那条**常驻预留**的提示行——不预留的话，出现「需要重启」时最后一行会被按钮压住。

### 6.3 页面与条目

`D3D12VideoSettingsContent` 只描述「有哪些页、每页哪些行」，不碰布局、滚动、绘制与输入。

| 页 | 页签键 | 内容 |
| --- | --- | --- |
| Display | `dxr_mc.video.tab.display` | 独占全屏分辨率模式、全屏、独占全屏、垂直同步、HDR 呈现、界面尺寸、最大帧率、降低帧率、亮度、图形 API |
| Quality & Performance | `…tab.quality` | 画质预设、原版 quality 数组、抗锯齿 |
| Preferences | `…tab.preferences` | 原版 preference 数组 |
| Illusion Settings | `…tab.illusion` | 顶部固定：渲染缩放、VRS；三个二级子页 NVIDIA / AMD / Intel，每页是「状态块 + 该厂商设置 + 帧生成开关」；页尾接超分共享项 |
| Shader packs | `…tab.shaderpack` | 当前包名、打开包列表（独立屏，见 §6.10）、光影包设置（独立屏，见 §6.11）、打开包目录、一句目录约定 |

光影包必须自成**一页**：把它塞进别的页的顶部固定区会连带把二级页签与内容区一起压下去，
而顶部固定区按设计不参与滚动（§6.5），压下去就再也滚不回来。

原版三个选项数组经 `VideoSettingsScreenOptionsAccessor` 的 `@Invoker` 取出，不复制构造逻辑——
复制一份会在原版改签名时悄悄失效。显示页按原版数组下标重排（0 最大帧率、1 垂直同步、2 降低
帧率、3 界面尺寸、4 全屏、5 独占全屏、6 亮度、7 图形 API）。「独占全屏分辨率模式」不在 `Options`
里，是原版内联的滑条，这里重建一份：取值 `-1..模式数-1`，`-1` 表示跟随当前桌面模式，写入
`window.setPreferredFullscreenVideoMode(...)`。

条目是四个类型之一：`Heading(text)`（分组标题，只画字、不参与悬停，空文本＝留白行）、
`Note(text, warning)`（只读说明，解释「这项为什么现在不可用」，必须看起来不可交互）、
`Setting(option)`（真正的一行控件，持有原版 `OptionInstance`）、
`Action(label, onClick)`（整行按钮，点开一个子界面；回调参数是当前屏，子界面把它当父级）。
`pages()` 每次打开都重算，因为条目依赖运行时状态；**不要在静态初始化时缓存页**，否则第一次打开的状态会被永久沿用。

#### 6.3.1 嵌套子页

一页里还要再分组时，给 `D3D12VideoSettingsPage` 传 `subPages`。页自己的行仍用 `entries`，固定在
子页签**上方**、不参与滚动；`trailingEntries` 接在子页内容**下方**、跟着滚动：

```kotlin
D3D12VideoSettingsPage(
    translatable("dxr_mc.video.tab.illusion"),
    entries = listOf(renderScaleRow, vrsRow),               // 页签上方，固定
    subPages = listOf(
        D3D12VideoSettingsSubPage(Component.literal("NVIDIA"), nvidiaRows),
        D3D12VideoSettingsSubPage(Component.literal("AMD"), amdRows),
        D3D12VideoSettingsSubPage(Component.literal("Intel"), intelRows),
    ),
    trailingEntries = sharedRows,                           // 子页内容之后
)
```

- 子页标题横着排在 `contentTop + 上方 entries 的高度 + GAP`；宽度 = 标题像素宽 + 两侧内边距
  （12 → 排不下压到 4 → 再排不下按内容区截断）。
- 只有子页内容与 `trailingEntries` 滚动、裁剪；上方 `entries` 固定不动。滚轮同样只在这块区域
  （页签下方、`rowsTop()` 起）生效 —— 指针停在固定行或页签上时，滚轮不带动子页内容；行裁剪、
  命中判定与悬停提示也以 `rowsTop()` 为分界。
- 每个页签各自记住自己选中的子页，切走再切回不会跳回第一组；同一页内只挪页签位置、不重建控件。
- 子页标题不参与滚动、不弹提示；行照旧滚动与裁剪。
- 页签列表只在 `buildRows()` 里跟着页走：走「本页没有二级子页」那条分支时必须 `clearSubTabs()`：
  `subTabs.clear()` 并把 `subTabsPage = -1`（迫使下次重建）。页签不属于任何一页，切到没有子页的
  页面后没人清就会继续画在 `tabY` 上，压住新页面的第一行 —— 看起来像"三个按钮粘在界面上"。

### 6.4 控件种类

| 控件 | 外观 | 行为 |
| --- | --- | --- |
| `D3D12TitleWidget` / `D3D12HeadingWidget` | 顶部标题条 / ACCENT 紫分组标题 | 不交互 |
| `D3D12NoteWidget` | 灰色小字，`warning` 时红色 | 不交互 |
| `D3D12TabWidget` | 120×25，选中时左侧 1px 紫条 | 点击切页并播 UI 音 |
| `D3D12SubTabWidget` | 18 高，宽度随标题文本，选中时下缘 1px 紫条 | 点击切子页并播 UI 音 |
| `D3D12OptionWidget` | 开关 / 滑条 / 枚举 / 折叠行 | 见下 |
| `D3D12ButtonWidget` | 深底白字，可淡入淡出 | 应用 / 撤销 / 完成 |
| `D3D12ScrollBarWidget` | 4 宽半透明轨道 + 主题色滑块 | 拖动滑块或点轨道滚动；用过滚轮后自动淡出（§6.9） |

`D3D12OptionWidget` 一行容纳三种外观，判定顺序不能改：`isSwitch = option.get() is Boolean`
**必须最先判**（26.3 的布尔取值集合同时实现了 `SliderableValueSet`，先判滑条会把开关画成没有
取值的滑轨）；然后是折叠、滑条（`SliderableValueSet`）、枚举（`OptionInstance.Enum` **或**
`CycleableValueSet`——只认前者会漏掉 HDR、Reflex 这种自定义集合，表现为点了完全没反应）。

### 6.5 暂存、应用、撤销

```
点击 → stage(value)     → 控件画 pendingValue，并显示「有改动」
应用 → commitPending()  → OptionInstance.set() → options.save()
撤销 → clearPending()   → 回到 OptionInstance 的当前值
关闭 → clearPending()   → 回父界面（关闭不等于应用）
```

`hasChanges()` 决定底部按钮：有改动时是「应用 / 撤销」，无改动时「应用」变成「完成」（点它等于
关闭），撤销隐藏。切页或窗口变化会重建行列表，重建时用 `pendingOrNull()` 收集、`stageExternal()`
回挂，未应用的改动不会丢。

画质预设行有额外动作：`set()` 只写取值，原版靠 `Options.applyGraphicsPreset` 触发
`GraphicsPreset.apply`，自绘界面绕过了那条路径，所以 `apply()` 里在提交之后显式调用
`options.applyGraphicsPreset(options.graphicsPreset().get())`，否则预设选了不会真正套用。

### 6.6 启用/禁用（灰化）规则

灰化由 `D3D12VideoSettingsContent.lockReasonFor(option)` 统一裁决：返回 `null` 可改，返回文案
则锁定，这段文案同时充当该行的悬浮提示。屏幕把它包成 `readOnlyProvider` / `lockReasonProvider`
传给控件；控件只读时不高亮、不画手柄、点击直接返回 `false`。

| 控件 | 条件 | 文案键 |
| --- | --- | --- |
| 渲染缩放 | DLSS 上采样中，且超分后端是 DLSS | `lock.renderscale_dlss` |
| 抗锯齿 | 同上 | `lock.aa_dlss` |
| DLSS 开关 | 帧生成开着 / 抗锯齿是 DLAA / 后端是 FSR、XeSS | `lock.dlss_framegen` / `lock.dlss_dlaa` / `lock.sr_other_backend` |
| FSR、XeSS 开关 | 帧生成开着 / 另一家已选中 | `lock.sr_framegen` / `lock.sr_exclusive` |
| FSR、XeSS 画质 | 帧生成开着 / 不是当前后端 | `lock.sr_framegen` / `lock.sr_not_active` |
| 超分遮罩 | 当前后端不支持遮罩 | `lock.sr_mask_unsupported` |
| DLSS 画质 | 抗锯齿是 DLAA | `lock.dlss_dlaa` |

文案键的完整前缀是 `dxr_mc.video.`。三条易踩规则：

1. **后端为 NONE 时不能锁 DLSS 开关**，否则死锁：没选后端 → DLSS 置灰 → 选不了后端。
2. **帧生成厂商开关一律不锁**：可用数为 0 的实验后端正是要让人点开试，失败时由 native 回滚并
   在状态行说明。
3. **锁必须给原因**，只置灰不解释，玩家只会以为界面坏了。

取值过滤是另一套机制：`allowedValuesProvider(option)` 返回 **lambda**（每次询问重算，不是快照），
目前只有 Reflex 用到——帧生成开着时把 `Off` 从循环里摘掉。控件循环取值前先过滤。

### 6.7 提示与「需要重启」

提示有两种版式：右侧剩余空间 ≥ `MIN_HINT_WIDTH` 时用固定块（内容「标题 / 空行 / 描述」+ 顶部
1px 强调线），否则改成跟随鼠标的悬浮提示。固定块从 `contentRight + HINT_COLUMN_OFFSET` 起画，
把右侧滚动条那一列整列让开；算「右侧剩余空间」时也已经扣掉这一列，否则固定块会压在滚动条上。
固定块把锁定原因并进同一个框（两块半透明面板叠在一起更黑更难读），悬浮式把锁定原因另画在鼠标上方并加表头。
提示只在内容区可视范围内、且整行都落在视口里时才画。

「需要重启」是底部常驻行的内容：`D3D12HdrPresentation.isRestartPending()` 或原版
`isRestartRequiredToApplyVideoSettings()` 为真时，画红色 `options.restartRequired`。

### 6.8 滚动与裁剪

行总高超过内容区才滚动：`maxScroll = 行尾 - contentTop - 内容区高度`，滚动量 `roundToInt()` 后
整体偏移行位置；`maxScroll > 0` 时对内容区 `enableScissor`，滚出视口的行不画，滚轮每格 12 像素。
滚轮只在指针落在滚动区内时生效（页签上方的固定行与页签本身不滚），指针压在右侧滚动条上也算
滚动区。滚动量只在 `moveTo(...)` 里被设置（重排时执行一次夹取，不引入新值）：夹到
`[0, maxScroll]` 后立刻重排行位置，滚轮与滚动条拖动走同一条路径。点击先给页签与动作按钮，
再给上方的固定行与滚动区里的行，最后给滚动条——它在内容区右侧之外，与行不重叠。只有 `mouseY`
在内容区内才算点到行。

### 6.9 右侧滚动条

`D3D12ScrollBarWidget` 提供不用滚轮就能上下滑动的入口，挂在屏幕之外不占行：

| 项 | 规则 |
| --- | --- |
| 横坐标 | `contentRight + SCROLLBAR_OFFSET`；当前页有二级子页时改成 `contentRight + SCROLLBAR_NESTED_OFFSET`（向内收缩）：它滚的是子页内容，位置要能看出这一层。窗口窄到内容区右边界贴着画面时夹到 `width - 4`，否则整条落在画面外 |
| 纵向范围 | `rowsTop()` 到 `contentBottom`，与滚动区、裁剪区完全一致；有二级子页时自然从子页签下方开始 |
| 宽度 | 可见 4 像素（`D3D12ScrollBarWidget.WIDTH`），轨道与滑块都画满这 4 像素，靠颜色区分 |
| 命中区 | 左右各放宽 `HIT_PADDING` = 2，共 8 像素宽：按 4 像素去抓一条线太费劲。命中区不越进内容区（二级子页时左边界正好落在 `contentRight`） |
| 滑块长度 | 可见高度 ÷ 内容总高 × 轨道高度，最短 `MIN_THUMB` = 18；内容放得下（`maxScroll == 0`）就整条不画 |
| 颜色 | 轨道半透明白、滑块 `ACCENT`，悬停或拖动时用 `blend(ACCENT, WHITE, …)` 提亮（0.15 秒） |
| 拖动 | 按住滑块 1:1 跟手拖动；点轨道是「滑块中心跳到指针」，不翻页。拖动中不隐藏，松手后照常按滚轮计时淡出 |
| 按键判定 | 只认左键，写 `InputConstants.MOUSE_BUTTON_LEFT`。原版输入栈已换成 SDL 编号（左 1 / 中 2 / 右 3），按 GLFW 的 0/1/2 判会让控件静默失灵：按下不响应，看起来就像拖不动 |

自动隐藏：

```
从未用过滚轮            → 常显（否则鼠标玩家找不到入口）
滚轮滚动过页面          → 记录时间，1 秒没有再滚就淡出
滚轮再滚                → 立刻淡入
指针停在条上 / 正在拖动 → 不隐藏
淡入淡出时长             → 0.3 秒
```

计时用 `System.nanoTime()` 自己算每帧步长，不用 `partialTick`：后者在一帧内会重复取值，停顿
菜单里也不推进，动画会抖或卡在半透明。透明度为 0 时既不画也不接受点击（此时把指针移上去会让它
淡回来，再点即可）。自动隐藏状态是屏幕级的，切页不重置。

「用过滚轮」的判据是滚轮真的滚动了内容（`maxScroll > 0` 且在滚动区内），不是页面上任意一次滚轮。

---

### 6.10 子页面：光影包选择

「视频设置 → 光影包」页里的 `Action` 条目「光影包…」，点开 `D3D12ShaderPackScreen`
（`D3D12VideoSettingsScreen` 当父级，`onClose()` 就回它上面那屏）。这一屏是**独立 `Screen`**，
复用 `D3D12WidgetRenderer` 与 `D3D12ButtonWidget`，但布局照抄 Iris 的包选择页而不是设置页骨架。

| 元素 | 数值 |
| --- | --- |
| 标题 / 副标题 | 居中，`y = 8` / `y = 21`，副标题灰色斜体 |
| 列表 | 顶 `32`、底 `height - 94`；行高 20；行宽 `min(308, width - 50)`，水平居中 |
| 行文字 | 居中在行内；悬停加粗并加一层按钮底色 |
| 已应用行 / 不可用行 | `0xFFFFF263` 黄 / `0xFFA2A2A2` 灰 |
| 说明面板 | 宽 314，底边 `height - 4`，高度 `max(50, 18 + 行数 * 10)`；悬停 10 帧后才淡入 |
| 第一行按钮 | `y = height - 51`、152×20：打开文件夹 / 重新扫描 |
| 第二行按钮 | `y = height - 27`、100×20：取消（`width/2 - 154`）、应用（`width/2 - 50`）、完成（`width/2 + 54`） |

行为规则：

- 「无光影」永远是第 0 行，其余是 `D3D12ShaderPacks.all()` 的顺序（内置在前、用户包在后）。
- 选中只是改 `selection`；**应用**才写配置（`D3D12ShaderPacks.apply`），并把 `[rt] backend`
  一起改掉。「取消」把选择回退到已应用的那一行；「完成」先应用、成功才关界面（应用失败时留在
  这一屏并显示原因，否则玩家点完不知道到底改没改）。
- 不可用的包（缺入口着色器）在说明面板里列出缺哪个文件，点「应用」只出一条提示、不写配置。
- 滚轮只在列表区域内生效，越过列表就不滚。
- 换包之后已有的 compute 管线不会原地换，提示文案里写明「重新打开光追后生效」，规则见 [rt 文档](../../rt/README.md) 的 11.4 节。

### 6.11 子页面：光影包设置

「视频设置 → 光影包」页里的 `Action` 条目「光影包设置」，点开 `D3D12ShaderPackConfigScreen`。
控件不是写死的：行 = 包在 `shaders/configs.json` 里声明的 `attributes`，按 `group` 分段。
声明格式与取值/落盘规则见 [rt 文档](../../rt/README.md) 的 11.6 节。

| 元素 | 数值 |
| --- | --- |
| 标题 / 副标题 | 居中，`y = 8` / `y = 21`；副标题带包名，灰色斜体 |
| 列表 | 顶 `32`、底 `height - 35`；行宽 `min(320, width - 50)`，水平居中 |
| 分组标题行 | 高 16，文字用强调色 |
| 设置项行 | 高 20：左侧名称，右侧当前值，区间项在行内画一条轨道 |
| 底部按钮 | `y = height - 27`、100×20：「完成」（只关界面，改动即时生效） |

行为规则：

- 点行即改：布尔翻转、枚举循环到下一个取值、区间项按点击位置吸附取值（拖动时连续改）。
- 每次改动立刻落盘（包的 `settings.json`，带 `key` 的再写 TOML），不等「完成」，因为这类开关是
  画质/调试用途，玩家要边看边调；没有「应用」按钮，也就没有需要撤销的暂存态。
- 包没有 `configs.json` 时只显示一行说明，不显示任何开关。
- 滚轮只在列表区域内生效；内容超过列表高度时才裁剪。

## 7. 新增一个选项

### 7.1 情形 A：纯后端开关（住 toml）

1. `IllusionConfig` 加字段（顶部声明类型）＋ `init` 里对应节加 `.comment(...)` +
   `.translation("dxr_mc.configuration.<节>.<键>")` + `.define*`；需要重启才加 `.gameRestart()`。
2. `Snapshot` 加构造参数与只读属性，`build()` 里用 `config(fallback) { IllusionConfig.XXX.get() }`
   读，fallback 必须等于出厂默认值。
3. 需要 native 在设备创建前知道：加专用 ABI 函数（与 `mcdr_abi.h` 同步）、在 `pushToNative()` 里
   推、并纳入那个 `pushed` 拼接字符串，否则幂等比较会漏掉它。
4. 改调用点：把读环境变量/常量/硬编码的地方改成读 `D3D12Settings.xxx`。漏了就是「配置写了但
   不生效」。
5. 语言文件：`en_us.json` 与 `zh_cn.json` 各加 `dxr_mc.configuration.<节>.<键>`；枚举再各加一组
   `...<键>.<VALUE>`。
6. 验证：`cd Illusion; .\gradlew.bat compileKotlin compileJava`；跑客户端确认配置界面显示文案
   而不是键名。

### 7.2 情形 B：视频设置项（住 `options.txt`）

1. 建 `OptionInstance(captionKey, tooltipSupplier, valueToString, valueSet, default, callback)`。
   `valueSet` 三选一：`OptionInstance.Enum`、自定义 `CycleableValueSet`（需要自己的显示名/校验）、
   `SliderableValueSet`（需要 `toSliderValue` / `fromSliderValue` / `next` / `previous`）。
2. 决定显示名格式：循环按钮由原版 `CycleButton` 自动加「标题: 值」前缀，所以 `valueToString`
   只返回值；滑条没有这个前缀，得自己用 `options.percent_value` 之类格式化。
3. 持久化：若不是 `Options` 字段，在 `OptionsRenderOptionsMixin` 的 `load`/`save` `TAIL` 加一行，
   并实现 `load(file)`/`save(file)`（按 `键:值` 行读写，save 先删同键旧行再追加）。`load` 里要
   处理「`Options.load` 期间 `set` 不触发回调」，通常手动补一次推 native。
4. 加进页面：在 `D3D12VideoSettingsContent` 对应页的 `entries` 里加 `Setting(option)`，需要解释
   就加 `Note(text, warning)`。
5. 可用性判据用 `D3D12AdapterInfo.current != null`（设备真的建起来），不要用配置里的首选后端。
6. 灰化联动：在 `lockReasonFor` 加条件与文案；需要过滤取值就加 `allowedValuesProvider`。
7. 语言文件：`options.<名字>`、`options.<名字>.tooltip`、每个取值的 `options.<名字>.<value>`；
   `en_us` / `zh_cn` 同步。
8. 验证：改值 → 关界面 → 重进确认值还在；重启确认 `options.txt` 多了一行。

---

## 8. 细节与约束

### 8.1 滑条取整

原版 `OptionInstance.IntRange` 的 `fromSliderValue` 落在任意整数上，25% 步进的滑条不能直接用。
渲染缩放的取值集合做了三件事：`fromSliderValue` 先线性映射再**吸附到步进倍数**
（`((v + step/2) / step) * step`，整数除法向下取整）后夹到首尾；`next`/`previous` 也走一档步进
（否则原版 `current ± 1` 会被吸附回原值，键盘左右键像没反应）；`toSliderValue` 首尾贴 0 与 1、
中间按 `(v + 0.5)` 映射，保证每档等宽。

控件不额外取整：它 `stage(fromSliderValue(fraction))`，取整责任在取值集合里。新增滑条照抄这个
分工，不要在控件里再 round 一次。

### 8.2 滑条点击分区

滑条右半边按位置设值，左半边是「退一档」（`previous`）。不要两边都按比例设值，否则点一下标题
就会把取值拉到 0。折叠展开后的整条滑条只按位置，不退档。

### 8.3 折叠行

宽度不够时一行改成折叠样式（`▾/▸`），判据同时看三件事：标题加取值的自然宽度超过行宽、滑条的
取值在中线之前放不下、行宽小于 `MIN_EXPANDED_WIDTH`。窗口拉宽后要清掉残留展开态，否则会出现
「明明放得下却还折叠」。折叠行展开后滑条铺在第二行整条宽度上，行高要额外加 `SLIDER_LINE`，
否则会和下一行重叠。

### 8.4 取值文本

原版换算函数返回「标题: 值」，控件直接画会变成「标题：标题：值」，所以剥前缀；剥的时候要同时
认 ASCII 冒号与中文全角冒号，否则中文下剥不掉；剥完为空就保留原文，避免只剩空串。

### 8.5 HDR / SDR 切换

交换链的格式与色彩空间是**建链参数**，改了 HDR 模式不影响在用的交换链：要么等下一次创建
（resize、切全屏），要么重启。因此 tooltip 必须写明「下次创建交换链才生效」；「是否需要重启」
以 native 记录的**实际建链模式**（`D3D12HdrAppliedMode`）为准而不是自己记的状态——改完又改回去
提示会自己消失，还没建过链（返回 `-1`）时一律不提示。三档编号就是下发给 native 的编号
（`auto/on/off` = 0/1/2），不要重排；强制 ON 在 SDR 显示器上会过曝，这是刻意的管线测试路径。

### 8.6 窗口重建与渲染目标

渲染缩放改变会重建主渲染目标，这是选项回调自己的动作，界面只负责 `set()`，不要自己调重建。
DLAA 要求渲染分辨率等于目标分辨率，所以选中 DLAA 时缩放强制 100 并锁定；实现方式是让取值集合
无论怎么拖都返回 100，而不是隐藏滑条——原版控件是建界面时按取值集合造好的，隐藏要走列表重建，
工程里没有干净入口。

### 8.7 只读行的视觉

只读行不能只把字变灰：必须不高亮、不响应悬停、不画手柄，否则玩家仍会去点；`Note` 行同理，要
看起来像说明而不是按钮。

### 8.8 每帧强制的锁

「改进的透明」在 D3D12 下被锁成常开：mesh 管线只保留了 OIT 半透明路径，关掉之后没有任何能把
半透明地形正确合成的路径。实现是每帧 `ensure()` 纠一次（图形预设、外部写回等任何路径改回
false 都会在下一帧被拉回），不是只在首帧设一次——只设一次会留下可被绕过的锁。首帧还没有 level
时选项回调可能抛异常，代码降级成 debug 日志、下一帧再试。

`ensure()` 的注释说「界面打开时也会调用 enforce」，但当前代码里没有这个调用点，`lockReason()`
与其语言键也没有被任何界面代码使用（见 §9 待复核）。

---

## 9. 文件导航

路径相对 `Illusion\src\main\kotlin\io\github\dxegp\`；最后一列标注不在该根下的真实位置。

| 文件 | 一句话职责 |
| --- | --- |
| `IllusionConfig.kt` | 全部 TOML 选项的定义、默认值、注释与翻译键，以及文件顶部的枚举 |
| `misc\settings\D3D12Settings.kt` | 把配置解析成不可变 `Snapshot`，处理旧来源兼容层，把关键值推给 native |
| `misc\settings\D3D12HdrPresentation.kt` | HDR 呈现选项（`options.hdr`）、`options.txt` 读写、重启判定 |
| `misc\settings\D3D12VRSSettings.kt` | VRS 的两个玩家可见取值（关/自动）与取值翻译键 |
| `misc\settings\ImprovedTransparencyLock.kt` | D3D12 下把「改进的透明」强制常开并给出原因 |
| `misc\gui\D3D12VideoSettingsScreen.kt` | 视频设置屏幕：布局、页签、滚动、绘制、输入、应用/撤销 |
| `misc\gui\D3D12VideoSettingsContent.kt` | 四个页面的内容、条目类型、灰化联动与取值过滤 |
| `misc\gui\D3D12ShaderPackScreen.kt` | 光影包子页面：包列表、说明面板、应用/取消/完成（见 §6.10） |
| `dxrmc\raytracing\D3D12ShaderPacks.kt` | 光影包的发现、元数据与 `[shaderpack] active` 的读写入点 |
| `misc\gui\D3D12ShaderPackConfigScreen.kt` | 光影包设置子页面：按包声明的 `attributes` 出控件（见 §6.11） |
| `dxrmc\raytracing\D3D12PackConfig.kt` | 读包内 `shaders/configs.json`，读写包的 `settings.json` 与它绑的 TOML 键 |
| `misc\gui\D3D12Widget.kt` | 控件基类、`WidgetRenderer`（提取器的 alpha/scissor 封装）、调色板 |
| `misc\gui\D3D12Widgets.kt` | 标题/分组/说明/页签/二级页签/设置行/按钮/滚动条八种控件 |
| `d3d12\render\advanced\vrs\D3D12RenderScale.kt` | 渲染缩放选项、25% 步进取值集合、DLAA 锁定 |
| `d3d12\render\advanced\D3D12AntiAliasing.kt` | 抗锯齿选项（原版取值集合上的 `OptionInstance`） |
| `dxrmc\superresolution\dlss\NvidiaReflex.kt` | Reflex 三档选项、`options.txt` 读写、与帧生成的互锁 |
| `…FrameGeneration`、`…SuperResolution` | 帧生成/超分的后端选择与状态行，向界面提供实时状态 |
| `Illusion\src\main\java\…\mixin\options\OptionsScreenVideoMixin.java` | 把原版「视频设置…」按钮换成自绘界面 |
| `…\mixin\options\OptionsRenderOptionsMixin.java` | 在 `Options.load`/`save` 上读写视频设置项的 `options.txt` 行，并补一票重启提示 |
| `…\mixin\options\VideoSettingsScreenOptionsAccessor.java` | 取出原版三个私有选项数组 |
| `…\mixin\options\OptionInstanceAccessor.java`、`TooltipAccessor.java` | 读 `OptionInstance` 的 tooltip（Kotlin 侧看不到织入接口） |
| `…\mixin\render\MinecraftRenderFrameProfMixin.java` | 每帧调用「改进的透明」的强制开启 |
| `Illusion\src\main\resources\assets\dxr_mc\lang\en_us.json`、`zh_cn.json` | 全部界面与配置文案，两份必须同步 |

### 待复核（代码里有不一致，本文未当成结论）

1. **HDR 有两个入口**：`[renderer] hdr`（toml，经 `D3D12Settings.pushToNative()` 调
   `D3D12SetHdrMode`）与 `options.hdr`（`D3D12HdrPresentation.push()` 也调同一 ABI）。两者写同一
   native 状态，谁最后写谁生效；是刻意双入口还是历史遗留，代码中未说明。
2. **默认值两处不一致**：`[dlss] implementation` 出厂默认 `STREAMLINE`，`Snapshot` fallback 是
   `NGX`；`[renderer] presentChildWindow` 出厂默认 `true`，fallback 是 `false`。fallback 只在
   「配置还没读进来」时生效，可能是刻意保守值，但没有注释说明。
3. **`ImprovedTransparencyLock.lockReason()` 与其语言键没有调用点**，实际只有每帧 `ensure()` 在
   强制开启，玩家看不到锁定原因；KDoc 里「界面打开时调用」的说法与现状不符。

---

## 10. 文档维护

- **只写当下**：不写设计史、方案对比与问题编号，也不引用仓库外的排障文档。
- **改代码时同步改本文**：新增/删除选项、改默认值、改生效时机、改灰化条件、改布局常量，都在
  本文有对应段落。§5.2 的两张清单和 §6.6 的矩阵最容易过期。
- **不要写死行号**：文件会漂移，用文件名 + 符号名（属性名、函数名、键名）定位。
- **不确定就写「代码中未明确」**：不要补一个听起来合理的解释。§5.2 与 §9 的待复核就是这样来的，
  读者能据此去问作者而不是被误导。
- **语言文件同步是硬要求**：只加 `en_us` 会在中文环境显示键名，反之亦然；改完两份都过一遍
  `ConvertFrom-Json`，确认没有多余逗号或重复键。







