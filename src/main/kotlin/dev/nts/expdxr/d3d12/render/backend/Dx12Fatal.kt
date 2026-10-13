package dev.nts.expdxr.d3d12.render.backend

import com.mojang.blaze3d.platform.MessageBox
import net.minecraft.CrashReport
import net.minecraft.ReportedException
import net.minecraft.client.Minecraft
import net.minecraft.client.PreferredGraphicsApi
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * Direct3D 12 后端的失败策略（P73）。
 *
 * 背景：vanilla 的后端选择循环（`Minecraft.<init>`，遍历
 * `PreferredGraphicsApi.getBackendsToTry()` 的结果）对每个候选后端 `catch (BackendCreationException)`
 * 后继续试下一个。旧行为里 [PreferredGraphicsApiMixin] 为 DX12 返回 `{DX12, GL, Vulkan}`，于是任何
 * D3D12 失败都会**静默**变成一次 OpenGL 启动 —— 用户看到的是 GL 画面，设置里却仍然写着
 * "Direct3D 12"，除了日志没有任何提示（P69–P72 期间好几次自检失败就是这样"消失"的）。
 *
 * 现在的策略（用户要求，m02176）：
 * 1. [resetPreferredGraphicsApi] 把 `options.txt` 里的 `preferredGraphicsBackend` 还原成 vanilla
 *    默认值 `"default"` 并立刻保存 —— 与 vanilla 自己在"上次启动不干净"时的处理一致
 *    （`Minecraft.java:455-463`）。
 * 2. [crash] 抛出携带自定义 [CrashReport] 的 [ReportedException]。它不是
 *    `BackendCreationException`，所以 vanilla 的循环不会吞掉它：异常一路冒到 `Main.main` 的
 *    `catch (Throwable)`（`Main.java:285-292`），`CrashReport.forThrowable` 会复用我们这份报告
 *    （`CrashReport.java:215-216`），最后由 `Minecraft.crash(...)` 写出 `crash-reports/` 并弹出
 *    崩溃报告界面。失败因此变成一次**可见**的崩溃，而不是一次伪装成正常的降级。
 */
object Dx12Fatal {
    private val LOGGER = LoggerFactory.getLogger("d3d12")

    /**
     * 崩溃报告的 Description（也就是 `ReportedException.getMessage()`，见
     * `ReportedException.java:20-22`）。中英各写一遍，方便用户读、也方便事后 grep 日志。
     */
    private const val TITLE =
        "DXRMC: Direct3D 12 backend failed (Direct3D 12 后端初始化失败；首选图形 API 已还原为 default)"

    /**
     * 关掉致命错误弹窗的系统属性（自动化运行用；见 [showPopup]）。
     * `Illusion\build.gradle` 已加 `-Pdxrmc.noPopup=true` → JVM 系统属性的转发。
     */
    private const val NO_POPUP_PROPERTY = "dxrmc.noPopup"

    /** Win32 `MessageBoxW` 的 uType 位：`MB_OK | MB_ICONERROR | MB_SETFOREGROUND | MB_TOPMOST`。 */
    private const val MB_OK = 0x00000000
    private const val MB_ICONERROR = 0x00000010
    private const val MB_SETFOREGROUND = 0x00010000
    private const val MB_TOPMOST = 0x00040000

    /**
     * 等对话框的最长时间（毫秒）。到点后不再等，照常抛异常写崩溃报告 —— 弹窗只是"让人看见"，
     * 不能变成"没人点就永远不出报告"。
     */
    private const val DIALOG_WAIT_MILLIS = 120_000L

    /**
     * 把 `preferredGraphicsBackend` 还原成 `PreferredGraphicsApi.DEFAULT` 并保存 `options.txt`，
     * 返回一句可直接放进崩溃报告的说明。
     *
     * 这里**绝不向外抛异常**：崩溃本身不能被"保存设置失败"这种次生问题顶掉，所以每一步失败都
     * 只记日志并把原因写进返回值。
     */
    @JvmStatic
    fun resetPreferredGraphicsApi(reason: String): String {
        val minecraft = Minecraft.getInstance()
            ?: return detail("not reset: Minecraft instance is unavailable ($reason)", false)

        val options = minecraft.options
        val current = options.preferredGraphicsBackend().get()
        if (!D3D12GraphicsApi.isDx12(current)) {
            return detail(
                "not reset: options.txt holds \"" + current?.serializedName +
                    "\", which is not DXRMC's entry ($reason)",
                false)
        }

        options.preferredGraphicsBackend().set(PreferredGraphicsApi.DEFAULT)
        return try {
            options.save()
            detail(
                "reset \"" + current.serializedName + "\" -> \"" +
                    PreferredGraphicsApi.DEFAULT.serializedName + "\" and saved to options.txt ($reason)",
                true)
        } catch (t: Throwable) {
            LOGGER.error("[d3d12] preferredGraphicsBackend reset in memory but options.txt could not be saved", t)
            "reset in memory to \"" + PreferredGraphicsApi.DEFAULT.serializedName +
                "\", but saving options.txt failed: " + t
        }
    }

    /**
     * 还原设置，然后抛出带说明的崩溃报告 —— 本函数永不返回。
     *
     * @param what 人类可读的失败描述（进入崩溃报告的 `Failure` 字段）
     * @param cause 原始异常；null 表示没有底层异常（例如 GLFW 直接报错）
     */
    @JvmStatic
    fun crash(what: String, cause: Throwable?): Nothing {
        val resetDetail = try {
            resetPreferredGraphicsApi(what)
        } catch (t: Throwable) {
            LOGGER.error("[d3d12] failed to reset preferredGraphicsBackend", t)
            "reset threw " + t
        }

        LOGGER.error("[d3d12] fatal: {} (no GL/Vulkan fallback by design, P73)", what)
        showPopup(what, resetDetail)
        val report = CrashReport.forThrowable(cause ?: RuntimeException(what), TITLE)
        val category = report.addCategory("DXRMC / Direct3D 12")
        category.setDetail("Failure", what)
        category.setDetail(
            "Fallback",
            "disabled by DXRMC: Direct3D 12 is the only backend offered, so the game does not silently " +
                "continue on OpenGL/Vulkan")
        category.setDetail("preferredGraphicsBackend", resetDetail)
        category.setDetail(
            "Next launch",
            "vanilla's unclean-shutdown protection may force OpenGL once after this crash; to retry " +
                "Direct3D 12, pick it again under Options > Video Settings")
        category.setDetail(
            "Where to look",
            "logs/latest.log lines tagged [d3d12] / [mcdr], and known_bugs.md")
        throw ReportedException(report)
    }

    /**
     * 弹一个**可见的**原生对话框，把失败原因直接摆到用户面前（用户要求，m02777）。
     *
     * 走 vanilla 自己的 [MessageBox]（`com.mojang.blaze3d.platform.MessageBox`，内部是
     * `TinyFileDialogs.tinyfd_messageBox(...)`）—— 这正是 vanilla 在"所有后端都失败"时用的
     * 那条路径（`Minecraft.java:522-526`：`MessageBox.error(errorMsg); throw new WindowInitFailed(...)`），
     * 所以它可以在同一个位置、同一条线程上安全调用：此时还没有窗口，但它自己的实现也不依赖窗口。
     *
     * 它是**阻塞**的（用户点掉才返回），并且和 vanilla 一样在写崩溃报告之前弹出，因此：
     * - 用户不需要去翻日志就能知道发生了什么；
     * - 自动化运行（自检 / 回归）必须能关掉它，否则会一直挂着 —— 用 `dxrmc.noPopup=true`
     *   （`Illusion\build.gradle` 会把 gradle 属性转发成 JVM 系统属性；Loom 的 run config 不会
     *   自动继承 gradle 进程的系统属性）。
     *
     * 任何异常（包括弹窗本身失败）都只记日志：弹窗不能顶掉崩溃报告。
     */
    private fun showPopup(what: String, resetDetail: String) {
        if (System.getProperty(NO_POPUP_PROPERTY) == "true") {
            LOGGER.warn("[d3d12] fatal dialog suppressed (-D{}=true); see the crash report instead",
                NO_POPUP_PROPERTY)
            return
        }

        // P74b：正文用**中英双语**，并且优先直接调 Win32 `user32!MessageBoxW`（见
        // [showNativeMessageBox]）—— 绕开 tinyfd 之后，引号与中文都没有任何限制。
        //
        // 背景（两次踩坑）：`MessageBox.error` 走 tinyfd，而 tinyfd 只要在消息里发现一个引号，
        // 就把**整条消息**替换成字面量 `INVALID MESSAGE WITH QUOTES` 再弹出来（该字符串确实存在于
        // `lwjgl-tinyfd-3.4.1-natives-windows.jar` 的 `lwjgl_tinyfd.dll` 里，UTF-16 字面量，旁边还有
        // `INVALID TITLE WITH QUOTES`）。而 options.txt 的还原说明天然带引号（`reset "d3d12" -> "default"`），
        // 于是用户两次看到的都是那句占位符。tinyfd 只作为**兜底**保留，兜底文本才需要清洗引号。
        val message = buildString {
            append("Direct3D 12 后端启动失败。\n")
            append("Direct3D 12 failed to start.\n\n")
            append("游戏将直接退出，不会静默回退到 OpenGL/Vulkan。\n")
            append("Minecraft will close instead of silently falling back to OpenGL/Vulkan.\n\n")
            append("失败原因 / Failure: ").append(what).append("\n\n")
            append("首选图形 API 已还原为默认值 / preferredGraphicsBackend: ")
            append(resetDetail).append("\n\n")
            append("关闭本窗口后会写出崩溃报告（crash-reports/）。\n")
            append("A crash report is written to crash-reports/ after you close this dialog.\n")
            append("日志行带 [d3d12] 前缀，见 logs/latest.log。\n\n")
            append("注意：下一次启动可能被强制成 OpenGL 一次；请在 选项 > 视频设置 里重新选择 Direct3D 12。\n")
            append("Note: the next launch may force OpenGL once; pick Direct3D 12 again under ")
            append("Options > Video Settings to retry.")
        }
        val fallbackMessage = sanitizeForTinyfd(message)

        // 对话框是**模态阻塞**的（MessageBoxW 在自己线程上跑消息循环）。放到守护线程里等，
        // 这样即使没人在电脑前点它，崩溃报告也一定会写出来（自动化运行还会用 -Ddxrmc.noPopup=true
        // 直接跳过）。守护线程不会阻止 JVM 退出。
        LOGGER.info("[d3d12] showing fatal error dialog (set -D{}=true to suppress)", NO_POPUP_PROPERTY)
        val dialog = Thread({
            try {
                if (!showNativeMessageBox(message, "Minecraft")) {
                    LOGGER.info("[d3d12] falling back to MessageBox.error (tinyfd) for the fatal dialog")
                    MessageBox.error(fallbackMessage)
                }
            } catch (t: Throwable) {
                LOGGER.error("[d3d12] failed to show the fatal error dialog", t)
            }
        }, "dxrmc-fatal-dialog")
        dialog.isDaemon = true
        dialog.start()
        try {
            dialog.join(DIALOG_WAIT_MILLIS)
            if (dialog.isAlive) {
                LOGGER.warn("[d3d12] fatal dialog still open after {} ms; writing the crash report anyway",
                    DIALOG_WAIT_MILLIS)
            }
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 直接调用 Win32 `user32!MessageBoxW`（P74b）。
     *
     * 为什么不用 vanilla 的 `MessageBox.error`：它内部是 tinyfd，而 tinyfd 对消息里的引号有
     * "整条替换成 `INVALID MESSAGE WITH QUOTES`" 的行为（实测踩过两次），并且在 Windows 上
     * 是否按 UTF-8/UTF-16 传递字符串难以确认。直接 FFM 调 `MessageBoxW` 用 **UTF-16LE** 传参：
     * 中文、引号、换行全部原样显示，也不再依赖 tinyfd 的行为。
     *
     * `MessageBoxW` 是模态的（自己跑消息循环），不需要窗口或消息泵 —— 和 vanilla 在
     * `Minecraft.<init>` 里弹窗的时机完全一致。
     *
     * @return true 表示对话框确实弹出来了并已返回（返回码 > 0 = 用户点了"确定"）；
     *         false 表示这条路径不可用（非 Windows、符号查找失败等），调用方应回退到
     *         [MessageBox.error]。
     */
    private fun showNativeMessageBox(text: String, caption: String): Boolean {
        val flags = MB_OK or MB_ICONERROR or MB_SETFOREGROUND or MB_TOPMOST
        return try {
            // Arena 必须在对话框阻塞期间一直存活，所以用函数作用域而不是 confined 临时区。
            Arena.ofShared().use { arena ->
                val lookup = SymbolLookup.libraryLookup("user32", arena)
                val address = lookup.find("MessageBoxW").orElse(null) ?: return false
                val handle = Linker.nativeLinker().downcallHandle(
                    address,
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,   // int  MessageBoxW(...)
                        ValueLayout.ADDRESS,    // HWND hWnd
                        ValueLayout.ADDRESS,    // LPCWSTR lpText
                        ValueLayout.ADDRESS,    // LPCWSTR lpCaption
                        ValueLayout.JAVA_INT))  // UINT uType
                val textSegment = wideString(arena, text)
                val captionSegment = wideString(arena, caption)
                val result = handle.invokeExact(
                    MemorySegment.NULL, textSegment, captionSegment, flags) as Int
                result > 0
            }
        } catch (t: Throwable) {
            LOGGER.warn("[d3d12] direct user32!MessageBoxW failed ({}); will use MessageBox.error",
                t.toString())
            false
        }
    }

    /** 把字符串编码成 NUL 结尾的 UTF-16LE 段（`MessageBoxW` 要的宽字符格式）。 */
    private fun wideString(arena: Arena, s: String): MemorySegment {
        val bytes = (s + '\u0000').toByteArray(StandardCharsets.UTF_16LE)
        val segment = arena.allocate(bytes.size.toLong(), 2L)
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.size)
        return segment
    }

    /**
     * tinyfd 的引号陷阱（m02963 实测）：`tinyfd_messageBox` 只要在消息里发现一个引号，
     * 就把整条消息替换成字面量 `INVALID MESSAGE WITH QUOTES`（标题同理，见
     * `lwjgl_tinyfd.dll` 里的两个字面量）。所以**兜底**用的弹窗文本必须先过这一道：
     * 两种引号都换成反引号（与 NeoForge `FatalErrorReporting` 的做法一致）。
     */
    private fun sanitizeForTinyfd(message: String): String {
        val sanitized = message.replace('"', '`').replace('\'', '`')
        if (sanitized != message) {
            LOGGER.info("[d3d12] fatal dialog fallback text had quotes; replaced with backticks for tinyfd")
        }
        return sanitized
    }

    private fun detail(message: String, reset: Boolean): String {
        if (reset) {
            LOGGER.warn("[d3d12] preferredGraphicsBackend {}", message)
        } else {
            LOGGER.error("[d3d12] preferredGraphicsBackend {}", message)
        }
        return message
    }
}