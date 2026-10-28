package dev.nts.expdxr.d3d12.debug

import com.mojang.blaze3d.platform.MessageBox
import dev.nts.expdxr.misc.settings.D3D12Settings
import dev.nts.expdxr.d3d12.backend.D3D12GraphicsApi
import net.minecraft.CrashReport
import net.minecraft.ReportedException
import net.minecraft.client.Minecraft
import net.minecraft.client.PreferredGraphicsApi
import net.neoforged.fml.loading.FMLPaths
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * DXRMC 的失败策略：复位首选图形 API → 弹窗 → 抛带自定义报告的 [ReportedException]。
 * 抛的**不能**换成 `BackendCreationException`：那样 vanilla 的后端循环会吞掉它，失败就退化成
 * 一次静默的 OpenGL 启动。字段与顺序见 docs/misc/debug-and-crash.md §1.2、§2。
 */
object D3D12Fatal {
    private val LOGGER = LoggerFactory.getLogger("backend")

    /** 报告 description（= `ReportedException.getMessage()`）。纯英文：它同时进 crash-reports 下的报告。 */
    private const val TITLE =
        "DXRMC: the Direct3D 12 backend failed to initialise; the preferred graphics API is back to default"

    /** Win32 `MessageBoxW` 的 uType 位：`MB_OK | MB_ICONERROR | MB_SETFOREGROUND | MB_TOPMOST`。 */
    private const val MB_OK = 0x00000000
    private const val MB_ICONERROR = 0x00000010
    private const val MB_SETFOREGROUND = 0x00010000
    private const val MB_TOPMOST = 0x00040000

    /** 等弹窗的上限：到点不再等，照常写报告 —— 弹窗不能变成"没人点就永远不出报告"。 */
    private const val DIALOG_WAIT_MILLIS = 120_000L

    /**
     * 把 `preferredGraphicsBackend` 复位成 `default` 并保存 `options.txt`，返回一句可放进报告的说明。
     * `startedCleanly = true` 是必须的：否则 vanilla 的"不洁关停"保护会把回退目标改写成 opengl。
     * 本函数**绝不外抛**：崩溃不能被"保存设置失败"这种次生问题顶掉。
     */
    @JvmStatic
    fun resetPreferredGraphicsApi(reason: String): String {
        val minecraft = Minecraft.getInstance()

        val options = minecraft.options
        val current = options.preferredGraphicsBackend().get()
        if (!D3D12GraphicsApi.isDx12(current)) {
            return detail(
                "not reset: options.txt holds \"${current.serializedName}\", which is not DXRMC's entry ($reason)",
                false)
        }

        options.preferredGraphicsBackend().set(PreferredGraphicsApi.DEFAULT)
        options.startedCleanly = true
        return try {
            options.save()
            detail(
                "reset \"${current.serializedName}\" -> \"${PreferredGraphicsApi.DEFAULT.serializedName}\", " +
                    "marked startedCleanly so the vanilla unclean-shutdown guard does not force OPENGL, " +
                    "and saved options.txt ($reason)",
                true)
        } catch (t: Throwable) {
            LOGGER.error("preferredGraphicsBackend reset in memory but options.txt could not be saved", t)
            "reset in memory to \"${PreferredGraphicsApi.DEFAULT.serializedName}\", but saving options.txt failed: ${t}"
        }
    }

    /**
     * `Minecraft` 实例还不存在时的同一件事：直接改 `options.txt`（复位 backend + `startedCleanly:true`）。
     * 只在当前值确实是 `d3d12` 时动手；文件缺失、值不对、读写失败都只返回说明，不抛。
     */
    private fun resetOptionsFileDirectly(reason: String): String {
        val file = FMLPaths.GAMEDIR.get().resolve("options.txt")
        if (!Files.isRegularFile(file)) {
            return detail(
                "not reset: Minecraft instance is unavailable and $file does not exist ($reason)", false)
        }

        return try {
            val text = Files.readString(file, StandardCharsets.UTF_8)
            val backendLine = Regex("(?m)^preferredGraphicsBackend:[^\\r\\n]*")
            val current = backendLine.find(text)?.value
                ?.substringAfter(':')?.trim()?.trim('"')
            if (current != D3D12GraphicsApi.SERIALIZED_NAME) {
                return detail(
                    "not reset: Minecraft instance is unavailable and $file holds \"$current\", " +
                        "which is not DXRMC's entry ($reason)",
                    false)
            }

            val newline = if (text.contains("\r\n")) "\r\n" else "\n"
            val cleanLine = Regex("(?m)^startedCleanly:[^\\r\\n]*")
            var updated = backendLine.replace(
                text, "preferredGraphicsBackend:\"${PreferredGraphicsApi.DEFAULT.serializedName}\"")
            updated = if (cleanLine.containsMatchIn(updated)) {
                cleanLine.replace(updated, "startedCleanly:true")
            } else {
                val separator = if (updated.isEmpty() || updated.endsWith(newline)) "" else newline
                updated + separator + "startedCleanly:true" + newline
            }
            Files.writeString(file, updated, StandardCharsets.UTF_8)
            detail(
                "reset \"$current\" -> \"${PreferredGraphicsApi.DEFAULT.serializedName}\" and marked " +
                    "startedCleanly in $file directly (Minecraft instance was unavailable: $reason)",
                true)
        } catch (t: Throwable) {
            LOGGER.error("failed to reset $file without a Minecraft instance", t)
            "reset failed: Minecraft instance is unavailable and $file could not be rewritten: $t"
        }
    }

    /**
     * 复位设置 → 弹窗 → 抛报告，**永不返回**。
     * @param what 进报告的 `Failure` 字段
     * @param cause null 时临时包一个 `RuntimeException(what)`，让栈指向失败调用点
     */
    @JvmStatic
    fun crash(what: String, cause: Throwable?): Nothing {
        val resetDetail = try {
            resetPreferredGraphicsApi(what)
        } catch (t: Throwable) {
            LOGGER.error("failed to reset preferredGraphicsBackend", t)
            "reset threw ${t}"
        }

        LOGGER.error("fatal: {} (no GL/Vulkan fallback by design)", what)
        showPopup(what, resetDetail)
        val report = CrashReport.forThrowable(cause ?: RuntimeException(what), TITLE)
        val category = report.addCategory("DXRMC / Direct3D 12")
        // 五个字段各自回答什么问题见 docs/misc/debug-and-crash.md §2。
        category.setDetail("Failure", what)
        category.setDetail(
            "Fallback",
            "disabled by DXRMC: Direct3D 12 is the only backend offered, so the game does not silently " +
                "continue on OpenGL/Vulkan")
        category.setDetail("preferredGraphicsBackend", resetDetail)
        category.setDetail(
            "Next launch",
            "preferredGraphicsBackend is reset to default by DXRMC itself and this crash is recorded as a " +
                "handled shutdown, so vanilla's unclean-shutdown guard does not force OpenGL once; to retry " +
                "Direct3D 12, pick it again under Options > Video Settings")
        category.setDetail(
            "Where to look",
            "logs/latest.log lines that mention the Direct3D 12 backend, and docs/misc/debug-and-crash.md")
        throw ReportedException(report)
    }

    /**
     * 阻塞弹窗，且在写报告**之前**（与 vanilla"所有后端都失败"的顺序一致）；弹窗自身异常只记日志。
     * 走原生 `MessageBoxW` 而非 `MessageBox.error`：tinyfd 遇到引号会把整条消息换成
     * `INVALID MESSAGE WITH QUOTES`，而复位说明天然带引号。见 docs/misc/debug-and-crash.md §3.1、§3.4。
     */
    private fun showPopup(what: String, resetDetail: String) {
        if (D3D12Settings.noCrashPopup) {
            LOGGER.warn("fatal dialog suppressed ([debug] noCrashPopup = true); see the crash report instead")
            return
        }

        // 正文中英双语（每条关键词两种语言都能 grep 到）；弹窗路径见 docs/misc/debug-and-crash.md §3.2。
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
            append("日志行见 logs/latest.log。\n\n")
            append("首选图形 API 已复位为 default，下一次启动不会再被改成 opengl。\n")
            append("The fallback target stays at \"default\"; the next launch will not be forced to OpenGL.\n")
        }
        val fallbackMessage = sanitizeForTinyfd(message)

        // 守护线程：没人点也不会拖住 JVM；自动化用 [debug] noCrashPopup 直接跳过。
        LOGGER.info("showing fatal error dialog (set [debug] noCrashPopup = true to suppress)")
        val dialog = Thread({
            try {
                if (!showNativeMessageBox(message, "Minecraft")) {
                    LOGGER.info("falling back to MessageBox.error (tinyfd) for the fatal dialog")
                    MessageBox.error(fallbackMessage)
                }
            } catch (t: Throwable) {
                LOGGER.error("failed to show the fatal error dialog", t)
            }
        }, "dxrmc-fatal-dialog")
        dialog.isDaemon = true
        dialog.start()
        try {
            dialog.join(DIALOG_WAIT_MILLIS)
            if (dialog.isAlive) {
                LOGGER.warn("fatal dialog still open after {} ms; writing the crash report anyway",
                    DIALOG_WAIT_MILLIS)
            }
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * FFM 调 `user32!MessageBoxW`：UTF-16LE 传参，中文/引号/换行原样显示。
     * @return false = 这条路径不可用（非 Windows、符号缺失），调用方回退到 [MessageBox.error]
     */
    private fun showNativeMessageBox(text: String, caption: String): Boolean {
        val flags = MB_OK or MB_ICONERROR or MB_SETFOREGROUND or MB_TOPMOST
        return try {
            // Arena 必须活过阻塞中的对话框：不能用 confined 临时区。
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
            LOGGER.warn("direct user32!MessageBoxW failed ({}); will use MessageBox.error",
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

    /** tinyfd 兜底文本必须先清洗：两种引号都换成反引号，否则整条消息变占位符。 */
    private fun sanitizeForTinyfd(message: String): String {
        val sanitized = message.replace('"', '`').replace('\'', '`')
        if (sanitized != message) {
            LOGGER.info("fatal dialog fallback text had quotes; replaced with backticks for tinyfd")
        }
        return sanitized
    }

    private fun detail(message: String, reset: Boolean): String {
        if (reset) {
            LOGGER.warn("preferredGraphicsBackend {}", message)
        } else {
            LOGGER.error("preferredGraphicsBackend {}", message)
        }
        return message
    }
}