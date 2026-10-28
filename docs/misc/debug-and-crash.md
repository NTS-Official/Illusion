# 崩溃处理、诊断与日志

本文只描述**代码里确实存在**的行为：崩溃路径、崩溃报告字段、弹窗策略、日志通道与诊断开关。
凡是代码没有定义清楚的地方都写成「代码中未明确」，不做推测式补全。

涉及两个语言侧：

- JVM（Kotlin）侧：`Illusion\src\main\kotlin\dev\nts\expdxr\`，负责崩溃报告与弹窗。
- native 侧：`MCDR\src\`，负责 SEH 级取证、原生日志行与设备状态查询。

## 1. 崩溃路径全景

### 1.1 四条路径总表

| 路径 | 触发点（代码位置） | 谁写报告 | 谁弹窗 | 进程如何退出 |
| --- | --- | --- | --- | --- |
| Java 异常（后端初始化/自检失败） | `d3d12/backend/D3D12Backend.kt` 的 `createWindow` / `createDevice` → `d3d12/debug/D3D12Fatal.kt:crash` | `D3D12Fatal`：自定义 `CrashReport` + `ReportedException`，最终落到 `crash-reports/` | `D3D12Fatal.showPopup`（原生 `MessageBoxW`） | `ReportedException` 逃出原版候选后端循环，被 `Main.main` 的 `catch (Throwable)` 接住 → 原版崩溃流程 |
| Java 异常（运行期任意异常） | 渲染/资源调用内部，例如 `d3d12/render/core/D3D12GpuBuffer.kt` 的 `mapFailureMessage` | 原版 `CrashReport.forThrowable`（DXRMC 不介入） | 无（原版崩溃界面是游戏内 GUI） | 原版崩溃流程；退出码代码中未明确 |
| native 访问冲突 / 未处理 SEH | `MCDR\src\core\d3d12_device.cpp` 的 `dx12CrashFilter` | **不写文件**，只往 stderr 打 `FATAL` 行 | 无 | 过滤器返回 `EXCEPTION_EXECUTE_HANDLER`，由 Windows 按未处理异常终止进程；代码未设置退出码，实际退出码即异常码（如访问冲突 `0xC0000005`） |
| 设备被移除 / 复位 | `MCDR\src\core\d3d12_surface.cpp` 的 `presentSurface`、`MCDR\src\core\d3d12_device.cpp` 的 `deviceRemoved` / `logDeviceStatus`，以及 JVM 侧 `D3D12GpuBuffer` | 不专门写报告；只有后续任意一个会失败的调用把它变成上表第二行 | 无 | 不主动退出，见 1.4 |
| 卡死（watchdog） | `d3d12/debug/D3D12ExitWatchdog.kt` 的 `dumpAndExit` | 不写崩溃报告；把全部线程栈写 stderr 并追加到 `%TEMP%\backend-java.log` | 无 | `System.exit(1)` |

补充一条不在这四条里的：**native 库缺失/符号绑定失败**。`d3d12/extern/MCDR_ABI.kt` 把失败记进
`LOAD_FAILURE`，之后任何 `requireLoaded()` 都抛 `IllegalStateException("MCDR native library is unavailable: …")`，
于是它走上表第一行——被 `createDevice` 的 `catch (t: Throwable)` 接住，交给 `D3D12Fatal.crash`。

### 1.2 Java 异常：为什么是「崩溃」而不是「降级」

原版 `Minecraft` 的后端选择循环对每个候选后端只 `catch (BackendCreationException)` 然后继续试下一个。
D3D12 的失败一旦落进这一层，就会静默变成一次 OpenGL 启动：用户看到 GL 画面、设置里仍写着「Direct3D 12」，
除日志外没有任何提示。因此本后端的失败策略（见 `D3D12Fatal` 的类注释）是：

1. `resetPreferredGraphicsApi(reason)` 把 `options.txt` 的 `preferredGraphicsBackend` 还原成原版默认值
   `"default"` 并立刻保存——与原版自己处理「上次启动不干净」的方式一致。
2. 同时把 `options.startedCleanly` 置为 `true`。理由：DXRMC 的失败路径**按设计**直接崩游戏，下一次启动
   必然被判成非正常退出，而原版那条保护会把 `graphicsApi != OPENGL` 改写成 `OPENGL`；不标记的话回退目标
   就从 `default` 变成 `opengl`，玩家会看到「图形 API 变成了 OpenGL」。标记之后回退目标停在 `default`。
3. `crash(what, cause)` 抛 `ReportedException`（**不是** `BackendCreationException`），所以后端循环不会吞它，
   异常一路冒到 `Main.main` 的 `catch (Throwable)`；`CrashReport.forThrowable` 会复用我们这份报告，
   最后 `Minecraft.crash(...)` 写出 `crash-reports/` 并弹出崩溃报告界面。

`crash` 的调用点只有三处，都在 `D3D12Backend.kt`：`createWindow` 里 `SDL_CreateWindow` 返回 0、
`[debug] failDevice` 打开的故意失败钩子、以及 `createDevice` 末尾的 `catch (t: Throwable)`。
进入 `catch` 前会先 `device.close()`（自检阶段的 catch 里），并打两条 ERROR：
`D3D12 self-test failed`（带栈）与 `D3D12 failure is fatal by design: no GL/Vulkan fallback, …`。

`resetPreferredGraphicsApi` 有两条实现，取决于 `Minecraft` 实例是否已经存在：

- 有实例：改 `Options` 内存值 → `save()`。当前值不是 `d3d12` 时**不动手**，只返回说明。
- 无实例（设备创建早于 `Minecraft` 构造完成）：`resetOptionsFileDirectly` 直接读写
  `FMLPaths.GAMEDIR/options.txt`，用正则替换 `preferredGraphicsBackend:` 行并补齐 `startedCleanly:true`。

两条路径都**绝不向外抛异常**：每一步失败只记日志并把原因写进返回字符串——崩溃本身不能被「保存设置失败」
这种次生问题顶掉。

### 1.3 native 访问冲突：只有 stderr，没有文件

`installCrashHandlerOnce()` 在建设备时调用一次，做两件事：把本模块的 PDB 预热进内存
（`prepareCrashSymbols`，目的是让过滤器里只查表、不碰文件 I/O），然后
`SetUnhandledExceptionFilter(&dx12CrashFilter)`。所以**只有建过设备之后**这条取证才存在。

`dx12CrashFilter` 在 SEH 回调里只做同步 `fprintf(stderr, …)` + `fflush`，逐行如下（实际格式串）：

```text
FATAL native exception code=0x%08X addr=%p tid=%lu module=%s
FATAL context: pendingDeletes=%lld openList=%d flushInProgress=%ld deletingObj=%p deletingIndex=%ld deletingTotal=%ld
FATAL deletingObj: kind=%d descSlot=%d resource=%p sourceTexture=%p mappedPtr=%p size=%lld
FATAL deviceRemovedReason=0x%08X
FATAL backtrace: %u frames
  [%02u] <本模块：文件:行>  |  [%02u] <别的模块>+0x%llx
```

为什么是这几行：`code`/`addr`/`module` 定位「谁炸的」；`pendingDeletes`/`openList`/`flushInProgress` 与
`deletingObj` 那组描述「延迟释放队列正在干什么」——D3D12 里最典型的一类崩溃就是对象在本模块的延迟删除
flush 过程中被别处释放或提前使用；`deviceRemovedReason` 区分「我们写错了」与「设备早被驱动移除」；
backtrace 里本模块的帧带 `文件:行`（PDB 已预热），别的模块只给 `模块+偏移`。

这条路径**不弹窗、不写 `crash-reports/`**：取证只能靠 stderr（以及把 stderr 重定向出来的启动器日志）。
它也不走 `emitLogLine`，因此**不受 `logLevel` 过滤、不进 `nativeLogFile` 镜像**——崩溃旁路刻意保持最小依赖。

### 1.4 设备被移除（`DXGI_ERROR_DEVICE_REMOVED` = `0x887A0005`）

native 侧 `deviceRemoved()` 的实现是一句纯查询（不同步 GPU）：

```cpp
if (!gCtx.device) return true;
return gCtx.device->GetDeviceRemovedReason() != S_OK;
```

它被用在 `presentSurface` 里给帧生成 / DLSS-G / FFX 的调用加守卫（设备已死就不再提交这些工作）。
`presentSurface` 对返回值的处理是：

- `DXGI_STATUS_OCCLUDED` / `DXGI_STATUS_MODE_CHANGED` → 记 INFO，标 `suboptimal`；
- `FAILED(hr)` → 记 ERROR `presentSurface: FAILED <hr>`，若是 `DXGI_ERROR_DEVICE_REMOVED` /
  `DXGI_ERROR_DEVICE_RESET` 再调 `logDeviceStatus("presentSurface: FAILED")`；标 `suboptimal`；
- 成功 → 只在 DEBUG 打 `presentSurface: ok (syncInterval=%u)`（默认级别下静默，因为 stderr/文件写在帧关键路径上）。

`logDeviceStatus` → `deviceStatusText()` 拼出「`GetDeviceRemovedReason=<hrText>`」加上 InfoQueue **最近 24 条**
消息描述，用一条 ERROR 打出。未开调试层时 InfoQueue 为空，这行就只剩 reason 一项——这正是它的价值：
把「只是一个 `0x887A0005`」变成「`0x887A0005` + 驱动/调试层说为什么」。

JVM 侧真正把设备移除**变成可见结论**的是 `D3D12GpuBuffer`：

- `isDeviceLost(native)` 认三个 HRESULT（`887A0005` 移除 / `887A0006` 挂起 / `887A0007` 复位）及其英文名；
- `reportDeviceLostOnce(detail)` 只在**第一次**打一行（`deviceLostReported` 从此不复位），正文第一句就是
  「设备已被移除，从这一行起每个 GPU 调用都会失败，第一个 `Map` 的 buffer 不是原因」；
- `mapFailureMessage` 把 `native: <mcdrLastError 原文>`、handle、`size`、`closed`、`offset`、`length`、读写标志
  一起塞进异常消息，判定为设备移除时再追加「这不是这个 buffer 的问题」的整段说明。

所以设备被移除的**结局**是：画面先坏（present 失败、日志各记一行），然后第一个 CPU 侧会失败的调用抛出异常，
变成一次普通的原版崩溃报告；如果没有任何调用抛出（例如只是持续 present 失败），进程既不会退出、也不会被
watchdog 杀掉（watchdog 只看 `Render thread` 在不在），只能由用户强杀。这是当前设计的**已知真空**。

### 1.5 卡死：`D3D12ExitWatchdog`

在 `IllusionClient` 的 `init` 里 `D3D12ExitWatchdog.start()`（mod 加载期启动，客户端专用）。

- 线程名 `backend-exit-watchdog`，`isDaemon = true`。
- 每 `POLL_MS = 5_000` ms 扫一次 `Thread.getAllStackTraces()`，看有没有名字恰为 `Render thread` 的线程。
- **先见到过** `Render thread` 才开始判退出（`sawRenderThread`），避免慢启动被误判成退出卡死。
- 一旦该线程消失，再等 `GRACE_MS = 15_000` ms 复核；仍不在就 `dumpAndExit()`。
- `dumpAndExit` 把全部线程的 `name [state] daemon=` 与栈帧打到 stderr，并**追加**写
  `%TEMP%\backend-java.log`（`System.getProperty("java.io.tmpdir")`，失败就只留 stderr），最后 `System.exit(1)`。

要复现的典型场景：原版已经关窗口、`Render thread` 已经结束，而某个非守护工作线程还卡在我们后端的 fence
等待里，JVM 于是永远不死，启动器（作为父进程）看起来像「完全冻住」。watchdog 的意义就是让启动器能收回，
同时留下卡在哪一帧的证据。

一处文案与常量的小出入：dump 头行写「after 20s」，实际是 `5 s 轮询 + 15 s 宽限`（最坏 20 s、最好 15 s）。

## 2. 崩溃报告结构

`D3D12Fatal.crash(what, cause)` 生成的报告只有三类内容：**标题**（`CrashReport` 的 description）、
一个分类 `DXRMC / Direct3D 12`、以及该分类下的五个 detail 字段。

| 字段 | 值从哪来 | 为什么 D3D12 问题非要它 |
| --- | --- | --- |
| description（标题） | 常量 `TITLE`：`DXRMC: the Direct3D 12 backend failed to initialise; the preferred graphics API is back to default` | 报告列表/首屏就能看出「不是随便哪个 mod 崩了」，并顺带告知设置已被复位 |
| `Failure` | `crash` 的 `what` 形参：`SDL_CreateWindow failed for DX12 (video driver: …)`、`DX12 self-test failed: <异常>`、`simulated Direct3D 12 device failure ([debug] failDevice = true)` | 具体失败点。自检失败时 `what` 里带的是异常 `toString()`，因此包含原生返回码 |
| `Fallback` | 固定文案：`disabled by DXRMC: Direct3D 12 is the only backend offered, so the game does not silently continue on OpenGL/Vulkan` | 直接堵住「为什么不自动换 GL」这个最常见疑问，也说明这是**策略**不是缺陷 |
| `preferredGraphicsBackend` | 复位函数的返回值，形如 `reset "d3d12" -> "default", marked startedCleanly …, and saved options.txt (…)` | 下一次启动到底会试哪个后端、为什么不会再被改成 opengl |
| `Next launch` | 固定文案：设置已被 DXRMC 自己复位、本次崩溃按「已处理关停」记录，所以原版的不洁关停保护不会强推 OpenGL；要再试 D3D12 请去「选项 → 视频设置」重新选 | 避免用户以为「DXRMC 自己关掉了 D3D12」 |
| `Where to look` | 指向 `logs/latest.log` 里提到本后端的那几行，以及仓库里维护的已知问题记录（该文件不在发布仓库内） | 把「看哪里」写进报告，省掉一轮往返 |

生成顺序固定：先复位设置 → 记 `fatal: <what> (no GL/Vulkan fallback by design)`（ERROR，logger 名 `backend`）
→ 弹窗（阻塞，见第 3 节）→ `CrashReport.forThrowable(cause ?: RuntimeException(what), TITLE)` → 填五个 detail
→ `throw ReportedException(report)`。**弹窗在写报告之前**，与原版「所有后端都失败」时的处理顺序一致。

底层异常为 `null` 时（例如 SDL 直接报错、没有 Throwable 可传）会临时包一个 `RuntimeException(what)`，
保证报告里的栈至少指向失败调用点。

## 3. 弹窗策略

### 3.1 为什么是 Win32 `MessageBoxW`，不是原版 `MessageBox.error`

原版 `com.mojang.blaze3d.platform.MessageBox.error` 内部是 `TinyFileDialogs.tinyfd_messageBox`，
而 tinyfd 在消息里**发现一个引号**（单引号或双引号都算）时，会把**整条消息**替换成字面量
`INVALID MESSAGE WITH QUOTES`（标题同理是 `INVALID TITLE WITH QUOTES`）。这两个字符串确实在
`lwjgl-tinyfd` 的 natives DLL 里。而复位说明天然带引号（`reset "d3d12" -> "default"`），
于是用户看到的就是那句占位符。

因此实现直接用 FFM 调 `user32!MessageBoxW`：

- `Linker.nativeLinker().downcallHandle(..., FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT))`，
  文本与标题都是 `UTF-16LE` + NUL（`wideString`），因此中文、引号、换行全部原样显示。
- `uType = MB_OK | MB_ICONERROR | MB_SETFOREGROUND | MB_TOPMOST`
  （`0x00000000 | 0x00000010 | 0x00010000 | 0x00040000`），标题固定 `Minecraft`。
- 用 `Arena.ofShared()`：对话框阻塞期间 Arena 必须一直活着，所以不能用 `ofConfined` 的临时区。
- 失败（非 Windows、符号找不到、调用抛异常）就**回退**到 `MessageBox.error(fallbackMessage)`，
  并且兜底文本先过 `sanitizeForTinyfd`：单双引号都换成反引号（与 NeoForge `FatalErrorReporting` 的做法一致）。

`MessageBoxW` 是模态的、自己跑消息循环，不需要窗口或消息泵，因此能用在「窗口还没建 / 已经没了」的时机。

### 3.2 为什么正文中英双语

面向中文玩家与英文工单两条通道：正文每一段都是「中文一行 + 英文一行」，关键词（`Direct3D 12`、
`OpenGL/Vulkan`、`crash-reports/`、`logs/latest.log`）在两种语言里都能被 grep 到。正文五段：

1. 后端启动失败 / failed to start；
2. 游戏将直接退出，不会静默回退 / will close instead of silently falling back；
3. `失败原因 / Failure: <what>`；
4. `首选图形 API 已还原为默认值 / preferredGraphicsBackend: <复位说明>`；
5. 关掉本窗口后会写出 `crash-reports/` 的报告，日志在 `logs/latest.log`，且下一次启动不会被改成 opengl。

注意标题（`TITLE`）是纯英文常量，只有弹窗正文是双语的——因为标题同时充当 `CrashReport` 的 description，
放进 `crash-reports/*.txt` 时保持英文便于对照。

### 3.3 线程、超时与「不能卡住」

弹窗在独立守护线程 `dxrmc-fatal-dialog` 上跑，主线程 `join(DIALOG_WAIT_MILLIS = 120_000)`。

- 没人点也不会永远不出报告：超时后记一条 WARN `fatal dialog still open after 120000 ms; writing the crash report anyway`，
  照常抛异常写报告。
- 守护线程不会阻止 JVM 退出（用户直接关掉对话框/结束进程也不会被它拖住）。
- 弹窗自身抛异常只记日志，绝不顶掉崩溃报告。

### 3.4 自动化运行怎么关

配置键：`config/dxr_mc-client.toml` → `[debug] noCrashPopup = true`。

- 生效点：`D3D12Fatal.showPopup` 首行判断 `D3D12Settings.noCrashPopup`，为真时记一条
  `fatal dialog suppressed ([debug] noCrashPopup = true); see the crash report instead`（WARN）后直接返回，
  报告与 `ReportedException` 照旧。
- 已弃用的逃生口（不作为唯一入口）：系统属性 `-Ddxrmc.noPopup=true`；Gradle 侧的 `-Pdxrmc.noPopup`
  转发成它。`D3D12Settings` 里这两条是「或」的关系。
- 与自动化自检配套：`[debug] failDevice` / `selfTest*` 故意让后端失败时，如果不关弹窗，无人值守的
  CI / 回归就会停在一个等点击的模态框上。

## 4. 日志体系

### 4.1 唯一出口：`misc/IllusionLogger.kt`

JVM 侧只有这一个门面，所有地方都不得直接 `println` / `System.err.println`：

| 成员 | 语义 | 门控 |
| --- | --- | --- |
| `debugMessage(String)` | 高频诊断打印 | `debugEnabled()`：`D3D12_LOG_VERBOSE=1` 或 `logLevel >= DEBUG` |
| `debugFormat(fmt, vararg)` | 同上，`String.format` 风格 | 同上 |
| `infoMessage(String)` | 一次性状态报告（「把哪个设置应用成了什么」） | 不按 mod 级别过滤，交给 log4j 级别 |
| `warnMessage(String)` | 「出事了但引擎继续跑」 | 同上 |
| `warnFormat(fmt, vararg)` | 同上 | 同上 |
| `level()` / `debugEnabled()` | 查询当前级别 | — |
| `applyOpt(level, nativeVerbose, shutdownTrace, nativeLogFile)` | 真正落地：改 log4j 级别 + 推给 native | — |
| `applyFromConfig()` / `applyDefaults()` | 从 TOML / 内置默认值应用 | — |

`OWN_LOGGERS = ["dev.nts.expdxr", "backend", "dxr_mc-temporal", "dxr_mc-velocity", "dxr_mc"]`：
Minecraft 自带的 log4j2 配置里没有这些名字，不显式 `Configurator.setLevel` 的话它们会继承 root
（INFO，开发环境下是 DEBUG），玩家配置的级别就形同虚设。`dev.nts.expdxr` 是包级 logger，
覆盖 `LogUtils.getLogger()` 派生的类名 logger，不必逐个枚举。

启动时的级别来源优先级：`D3D12_LOG_VERBOSE=1`（开发逃生口，覆盖配置文件）> `[logging] logLevel` > 内置默认
`ERROR`。生效时打一条 INFO banner `log level = <level> (native <n>, source: config/dxr_mc-client.toml)`；
同样的 banner 只打一次（`lastBanner`），因为两个 `@Mod` 入口类都会应用一次默认值。

### 4.2 级别映射：JVM ↔ native 0..3

`enum class IllusionLogLevel(val nativeLevel: Int)`：

| 配置/枚举 | log4j 级别 | native 整数 | native 通道 |
| --- | --- | --- | --- |
| `ERROR`（默认） | `ERROR` | `0` | `dbgLogError` |
| `WARN` | `WARN` | `1` | `dbgLog` / `dbgLogWarn` |
| `INFO` | `INFO` | `2` | `dbgLogInfo` / `dbgLogProgress` |
| `DEBUG` | `DEBUG` | `3` | `dbgLogDebug` |
| `TRACE` | `TRACE` | `3`（与 DEBUG 同档，native 没有第 4 档） | `dbgLogDebug` |

native 侧 `gLogLevel` 被钳制在 `0..3`，各通道门槛在 `d3d12_device.cpp` 里写死：
`dbgLogError` 恒输出（断言 `gLogLevel < 0` 永不成立）、`dbgLogWarn` / `dbgLog` 需 `>= 1`、`dbgLogInfo` 需 `>= 2`、
`dbgLogDebug` 需 `>= 3`。`dbgLogProgress` 走 INFO 标注，但**是否无视级别恒定输出**由
`[logging] shutdownTrace`（默认 `true`）经 `mcdrSetShutdownTraceAlways` 决定——关停卡死时那几行是唯一线索。

推送路径：`IllusionLogger.applyOpt` → `MCDR_ABI.D3D12SetLogLevel(clamped 0..3)`；`[logging] nativeVerbose = true`
时直接推 `3`（「native 永远 DEBUG、Java 侧不动」是排查 DLL 噪音的常用手法，所以它和 `logLevel` 是两个键）。
`D3D12SetLogLevel` 在旧 DLL 上没有 `mcdrSetLogLevel` 时回退成 `D3D12SetLogVerbose(level >= 2)`。

### 4.3 native 行前缀：只有 `formatLogPrefix` 一处生成

```cpp
int formatLogPrefix(char* out, size_t outSize, const char* level, const char* logger) {
    SYSTEMTIME st{};
    GetLocalTime(&st);
    return std::snprintf(out, outSize, "[%02u%02u月%04u %02u:%02u:%02u.%03u] [thread-%lu/%s] [%s/]: ",
                         day, month, year, hour, minute, second, milliseconds,
                         GetCurrentThreadId(), level, logger);
}
```

实际一行长这样：

```text
[1510月2026 15:29:44.942] [thread-12345/WARN] [dxr_mc/]: presentSurface: FAILED DXGI_ERROR_DEVICE_REMOVED
```

要点：

- 时间块**逐字对齐 JVM 侧 log4j 的 pattern**（`ddMM月yyyy HH:mm:ss.SSS`，所以是「日 月 年」而不是「年月日」），
  这样两路输出放在同一个控制台里能按时间戳直接对齐；用 `GetLocalTime` 而不是 QPC 毫秒，因为 JVM 行里没有
  「进程运行到第几毫秒」这个量。
- `level` 是 `ERROR` / `WARN` / `INFO` 这类文字，**前缀里有级别，正文里不许再写级别或标签**。
- logger 名在 `emitLogLine` 里写死为 `dxr_mc`，所有 native 行都是 `[dxr_mc/]:`。
- 单行出口 `emitLogLine` 用一个 2304 字节的栈缓冲拼「前缀 + 正文 + `\n`」，同时写 stderr（每行 `fflush`）
  与可选的镜像文件；前缀都放不下时**整行丢弃**，不写半行。

### 4.4 日志镜像文件

`[logging] nativeLogFile`（默认空串 = 只走 stderr）→ `IllusionLogger` → `MCDR_ABI.D3D12SetLogFile(path)`
→ native `setLogFile`。native 侧 `mirrorLogToFile` 的规则：

- 路径优先级：配置路径 `gConfiguredLogPath` > 已弃用的环境变量 `DX12_NATIVE_LOG_FILE`（仅在配置为空时生效）
  > 都没有就不写文件。
- 以 `"a"` 追加打开，每行 `fprintf` 后立刻 `fflush`——进程被强杀也不丢。
- `setLogFile("")` 关闭镜像并释放句柄；换路径同样先关旧句柄，下一次写日志时按新路径重开。
- 相对路径按游戏目录解析（native 的 `fopen` 用当前工作目录）。

存在的理由很具体：Prism 这类启动器只把 log4j 的输出写进 `logs/latest.log`，**子进程的 stdout/stderr 不落盘**，
于是「用户端出问题却拿不到 native 行」成了常态。设了这条镜像，`latest.log` 里没有的那部分 native 行就在这里。

另外两处落盘：

- watchdog 的线程栈 → `%TEMP%\backend-java.log`（追加写，best effort）。
- 崩溃旁路的 `FATAL` 行**只走 stderr**，不进镜像（见 1.3）。

### 4.5 日志规范（JVM 与 native 同一条约定）

- **英文、精简、一句话说清「发生了什么 + 关键数值」**。不写感叹号，不写 `WARN:` / `ERROR:` 这类文字级别
  （级别已由通道给出）。
- **正文不得再带前缀标签**：`[dxrmc]`、`[dxr_mc]`、`[backend]`、`[backend-java]`、`[dx12]`、`[mcdr]`、`[SR]`
  这类标签一律不写——结构化前缀已经给出 logger 名与级别。
- **工单号只留在代码注释里**，消息正文里不出现。
- native 前缀只由 `formatLogPrefix()` 生成；JVM 出口只有 `IllusionLogger`；`MCDR_ABI` 里 FFM 调用失败也走
  `IllusionLogger.warnMessage`。
- 裸 `fprintf(stderr, …)` 只留给**崩溃旁路**与 JVM 自检，且行首写 `FATAL ` 而不是 `[dx12] `。

### 4.6 现存的不一致（复核时优先看）

代码里还有几处与上面的规范不完全对齐，列在这里是为了不要把它们当范本：

1. `D3D12GpuBuffer.reportDeviceLostOnce` 用裸 `System.err.println`，且**没有前缀、没有级别**。它是有意绕开
   logger 的（设备已死时要保证这一行落盘），但代价是绕开了所有级别配置与镜像文件。
2. `D3D12ExitWatchdog.dumpAndExit` 同样用 `System.err.println`，这是刻意的：卡死时 log4j 可能已经不工作。
3. `D3D12GpuBuffer.close` 里有一段临时取证打印（`GpuBuffer.close label=… size=…`），只对 Screenshot 类 buffer
   和前 4 次特定用途的关闭生效，属于临时手段而非日志规范。
4. 环境变量名在注释与代码之间不一致：`IllusionLogger` 实际读的是 `D3D12_LOG_VERBOSE`，而
   `IllusionConfig` 与该类自己的注释里写的是 `DX12_LOG_VERBOSE`。只有前者生效。
5. `D3D12ExitWatchdog` 的 dump 头行写「after 20s」，实际是 5 s 轮询 + 15 s 宽限。

## 5. 诊断开关清单

全部在 `config/dxr_mc-client.toml`，由 `IllusionConfig.kt` 定义、`D3D12Settings` 解析成 `Snapshot`。
需要 native 在建设备前就知道的值通过专用 ABI 或 `D3D12SetEnvironment` 推进去（配置是唯一入口）。

### 5.1 `[logging]`

| 键 | 默认 | 作用 | 开销 / 注意 |
| --- | --- | --- | --- |
| `logLevel` | `ERROR` | 本 mod 全部输出（Java + native）的级别 | `DEBUG`/`TRACE` 会打出着色器源、PSO/pipeline dump、逐帧 tracing，日志量极大 |
| `nativeVerbose` | `false` | 只把 DLL 推到 DEBUG，Java 侧级别不变 | 用于单独二分 DLL 噪音；与 `logLevel` 互不影响 |
| `shutdownTrace` | `true` | 关停路径的 `destroySurface`/`destroyDevice` 阶段行**无视级别**输出 | 这些行是 INFO，关掉后默认 ERROR 级别就看不见——退出卡死时也就没有线索 |
| `nativeLogFile` | `""` | 每条 native 行同时追加到该文件 | 每行 `fflush`，路径按游戏目录解析；空 = 只走 stderr |

### 5.2 `[debug]`

| 键 | 默认 | 作用 | 开销 / 注意 |
| --- | --- | --- | --- |
| `pixCapture` | `false` | 启动时加载 PIX 的抓帧层（`WinPixGpuCapturer.dll`），PIX 才能对本进程抓 GPU 帧 | 必须在**第一个 D3D12 设备创建前**加载，所以 `gameRestart()`；PIX 要开着才能真正保存 |
| `pixDirectory` | `""` | PIX 安装目录或其中某个 DLL 的路径 | 空 = 自动检测（先找 MCDR.dll 旁边，再找 `%ProgramFiles%\Microsoft PIX\<版本>`）；`gameRestart()` |
| `pixTimingCapture` | `false` | 启用 F9（`选项 → 控制 → DXRMC`）的 PIX **timing** 抓帧 | 按下才抓，帧数在按下瞬间读配置，**不用重启**；ETW 会话要求当前用户能创建（PIX 文档建议加进 `Performance Log Users` 组，不需要提权） |
| `pixCaptureFrames` | `600`（60..20000） | 一次按键抓多少帧 | 60 fps 下 600 帧约 10 秒，开内存事件后文件可达数百 MB |
| `debugLayer` | `false` | 带 D3D12 调试层建设备 | 逐调用校验，帧率代价大；**Agility SDK 的 D3D12Core 生效时会被忽略**（inbox 校验层对不上）；`gameRestart()` |
| `profiling` | `false` | 逐帧/逐批次计时（对应已弃用的 `DX12_PROF=1` 环境变量）：记录、PSO 绑定、pass 提交的 CPU 毫秒 | 每批几次计时读取；`gameRestart()` 类的一次性读取 |
| `dumpHlsl` | `false` | 把每次翻译出的 HLSL 落盘到游戏目录下 `dxrmc/hlsl/` | 最多 60 对；stage 行为异常时是唯一的现场 |
| `skipGlSwap` | `false` | 让残留的 GL surface `present()` 变空操作 | 只在 `[renderer] glContext = true` 时才有意义 |
| `asyncRenderThread` | `true` | 独立渲染线程（acquire/present/fence 等待离主线程） | 关掉是**诊断手段**：怀疑渲染线程与录制线程抢同一个堆时用来隔离；`gameRestart()` |
| `temporalChain` | `false` | 强制打开时域链（jitter、投影注入、velocity attachment） | 玩法上不需要（选 TAA/DLAA/DLSS 会自动开）；没有 AA 时观察链路用 |
| `temporalJitter` | `true` | 是否把亚像素 jitter 注入投影矩阵 | 关掉可分离「jitter 错了」与「矩阵错了」 |
| `selfTestSurface` | `false` | 在真实窗口上建/呈现/销毁一条一次性交换链 | **默认关**：RTSS/Afterburner 会把 OSD 绑到第一条被 Present 的链上，自检链会把它抢走一整个会话；`gameRestart()` |
| `selfTestMesh` | `false` | mesh shader 自检（DXC 现场编译 → mesh PSO → DispatchMesh → 回读） | 现场编译 HLSL，不放进启动关键路径；`gameRestart()` |
| `selfTestCompute` | `false` | compute 自检（`cs_6_8` → compute PSO → Dispatch → 回读），同时驱动着色率图像（R8_UINT SRI）自检 | 同上；`gameRestart()` |
| `meshDiagnostics` | `NONE` | 一次性 mesh 回读隔离：`CLEAR` 只写平色再回读，`PASS` 先跑完整 mesh pass | 区分「回读通路坏了」与「pass 没落到纹理上」；只在取证时开 |
| `failDevice` | `false` | 故意让建设备第一步就失败 | 端到端验证失败策略（不回退 + 复位设置 + 带说明的崩溃报告）；配合 `noCrashPopup` 用；**不要用来玩游戏** |
| `noCrashPopup` | `false` | 抑制致命错误对话框，只写崩溃报告 | 自动化运行必备（对话框是模态的，会一直等一个不会到来的点击） |

另外三组**每次启动都会跑**、没有开关的 Java 侧自检：`selfTestJavaResources`、`selfTestCommandLayer`、
`selfTestPipelines`（在 `D3D12Backend.createDevice` 里无条件调用）。任何一组抛异常都会关设备并走
`D3D12Fatal.crash("DX12 self-test failed: …")`——所以「什么都没改却启动崩溃」时先看这三组。

### 5.3 自动化入口（不是功能开关）

构建脚本里的 `-Pdxrmc.*` 只用于自动化跑测试，按项目约定**不是功能入口**：

- `-Pdxrmc.selfTest=fg-on@20,fg-off@400` → 系统属性 `dxrmc.selfTest`，由
  `dxrmc/framegen/FrameGenerationSelfTest.install()` 在 tick 上按时间点开关帧生成；没设属性时只做一次
  `System.getProperty` 就返回，不注册任何监听器。
- `-Pdxrmc.noPopup` → 系统属性 `dxrmc.noPopup`，等价于 `[debug] noCrashPopup = true` 的已弃用逃生口。

### 5.4 设备状态取证（无开关）

- `logDeviceStatus(tag)`：`GetDeviceRemovedReason` + InfoQueue 最近 24 条，打一条 ERROR。调用点：
  `presentSurface` 失败且 `hr` 是 `DXGI_ERROR_DEVICE_REMOVED` / `DXGI_ERROR_DEVICE_RESET`。
- `dumpInfoQueueMessages()`：InfoQueue 最近 16 条，按 severity 分级到 `dbgLogError` / `dbgLogWarn` / `dbgLogInfo`
  （`CORRUPTION`/`ERROR` 走 ERROR，`WARNING` 走 WARN，其余走 INFO），然后 `ClearStoredMessages`。
- `deviceRemoved()`：纯查询（不同步 GPU），给帧生成 / DLSS-G / FFX 的调用加守卫。

三者都依赖调试层消息才有内容；`debugLayer = false`（默认）时 InfoQueue 为空，只剩 `GetDeviceRemovedReason`。

## 6. 文件导航

JVM 侧（相对 `Illusion\src\main\kotlin\dev\nts\expdxr\`）：

| 路径 | 一句话职责 |
| --- | --- |
| `d3d12/debug/D3D12Fatal.kt` | 后端失败策略：复位首选图形 API、原生弹窗、自定义崩溃报告，抛 `ReportedException` |
| `d3d12/debug/D3D12ExitWatchdog.kt` | 退出卡死看守：`Render thread` 消失 15 s 后 dump 全线程栈并 `System.exit(1)` |
| `d3d12/debug/D3D12DebugInfo.kt` | F3 `system_specs` 组追加的分辨率/显存/后端行，以及地形行的 `with mesh` 后缀 |
| `d3d12/debug/D3D12FrameStats.kt` | F3 帧率行的 `rendered/output` 双数值与 `(FG n.nx)` 倍率（1 s 窗口 + 指数平滑） |
| `d3d12/debug/D3D12VrsDebugEntry.kt` | 独立的 F3 debug entry（`dxr_mc:vrs`），VRS 诊断行可由玩家在 F3+F6 里开关 |
| `misc/IllusionLogger.kt` | JVM 侧唯一日志出口 + 级别枚举 + 把级别/关停标记/镜像文件路径推给 native |
| `misc/settings/D3D12Settings.kt` | 把配置解析成不可变 `Snapshot`，并用 ABI/环境变量推给 native（建设备前就要的值） |
| `d3d12/backend/D3D12Backend.kt` | 后端入口：窗口/设备创建、三组无条件自检、失败时调 `D3D12Fatal.crash` |
| `d3d12/extern/MCDR_ABI.kt` | FFM 绑定；`LOAD_FAILURE`/`isAvailable()` 与日志/诊断相关导出（SetLogLevel、SetLogFile、SetPIX…） |
| `d3d12/render/core/D3D12GpuBuffer.kt` | 识别设备移除（三个 HRESULT）、一次性报告、把 `mcdrLastError` 原文带进异常 |
| `d3d12/render/core/D3D12GpuSurface.kt` | `present()`：blit/呈现推进、帧统计计数、`DXGI_STATUS_OCCLUDED` 等的 JVM 侧表现 |
| `d3d12/backend/D3D12PixCaptureController.kt` | F9 键位与 PIX timing 抓帧的启停/帧数预算（配置在按下瞬间读） |
| `dxrmc/framegen/FrameGenerationSelfTest.kt` | 只给自动化用的 tick 钩子（`dxrmc.selfTest` 属性驱动） |
| `IllusionConfig.kt` | `[logging]` / `[debug]` 等全部配置项的定义与中英文注释（配置界面文案来源） |
| `IllusionClient.kt` | 客户端入口：应用默认日志级别、启动 watchdog、注册 PIX 键位与自检钩子 |

native 侧（相对仓库根）：

| 路径 | 一句话职责 |
| --- | --- |
| `MCDR\src\core\d3d12_device.cpp` | `gLogLevel` / `formatLogPrefix` / `emitLogLine` / `dbgLog*` / 镜像文件 / SEH `dx12CrashFilter` / `deviceStatusText` / `deviceRemoved` |
| `MCDR\src\core\d3d12_surface.cpp` | `presentSurface` 的 HRESULT 分支、`DXGI_ERROR_DEVICE_REMOVED` 时调 `logDeviceStatus` |
| `MCDR\src\c_abi\abi_common.cpp` | 公共错误状态与字符串写出（`mcdrLastError` 的读写），供 JVM 侧把 native 原文带进异常/日志 |

## 7. 文档维护

- 本文的事实来源是代码，不是设计意图。**改代码后请同步改这里**，尤其是：`formatLogPrefix` 的格式串、
  `IllusionLogLevel.nativeLevel` 的映射、`D3D12Fatal` 的五个 detail 字段、`[debug]`/`[logging]` 的键名与默认值、
  watchdog 的 `POLL_MS` / `GRACE_MS`。
- 新增诊断开关时按固定顺序落地：`IllusionConfig` 字段 → `D3D12Settings.Snapshot` 构造参数 + 属性 + `build()`
  读取 → 调用点改成读属性；三处缺一处就是「配置写了但不生效」。需要 native 在建设备前知道的，加专用 ABI
  导出，不要发明新的环境变量。玩家能按到的，注册 `KeyMapping`；配置界面文案同步
  `assets/dxr_mc/lang` 下的 `en_us.json` 与 `zh_cn.json`（键名 `dxr_mc.configuration.<section>.<key>`）。
- 新增崩溃路径时，先决定三件事：**谁写报告**、**谁弹窗**、**进程怎么退出**，然后回来补第 1 节的总表。
  已确认没有的东西（例如「设备移除 → 主动崩游戏」）不要写成已有。
- 上面 4.6 列的不一致如果要修，请一次修干净并删掉对应条目；不要在这里保留过期描述。
