package dev.nts.expdxr.d3d12.backend

import com.mojang.blaze3d.platform.InputConstants
import dev.nts.expdxr.Illusion.Companion.MODID
import dev.nts.expdxr.IllusionConfig
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.misc.settings.D3D12Settings
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.common.NeoForge
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 按键驱动的 PIX timing capture（`[debug] pixTimingCapture`，默认 F9）：PIX 自己的计时捕获只写
 * `%TEMP%` 且文件名由它决定，要落到游戏目录、用玩家选的名字与长度，只能进程内起
 * PIXBeginCapture/PIXEndCapture（native 在自己的线程上调）。
 *
 * 每次按下开新文件，再按一次提前结束；帧预算在按下那一刻读配置，改配置不用重启。开关关闭时功能休眠。
 * 见 docs/misc/backend-and-device.md。
 */
internal object D3D12PixCaptureController {
    private val LOGGER = LoggerFactory.getLogger("dxrmc")

    /** Translation key of the key binding; the label of its category lives under `key.category`. */
    const val KEY_NAME: String = "key.dxr_mc.pix_capture"

    private val CATEGORY = KeyMapping.Category(Identifier.fromNamespaceAndPath(MODID, "debug"))

    /** `Ctrl`-free single key on purpose: captures are started while flying around, not in a menu. */
    private val KEY = KeyMapping(
        KEY_NAME,
        InputConstants.Type.KEYBOARD,
        InputConstants.KEY_F9,
        CATEGORY,
    )

    /** Highest index tried for `run/pix/dxrmc-capture-N.wpix` before giving up. */
    private const val MAX_CAPTURE_INDEX = 999

    /** chat 行翻译键：参数是已带样式的 component，翻译只负责排序。 */
    private const val TRANSLATION_PREFIX = "chat.dxr_mc.pix_capture.prefix"
    private const val TRANSLATION_START = "chat.dxr_mc.pix_capture.start"
    private const val TRANSLATION_STOP = "chat.dxr_mc.pix_capture.stop"

    /**
     * 失败行也必须显示：capture 静默拒绝启动与「按键没反应」无法区分。PIX 拒绝时只给 HRESULT，
     * 码本身就能定位问题（0x80070005 = 进程未提权，0x800700B7 = 上一次会话还活着）。
     */
    private const val TRANSLATION_FAIL_HRESULT = "chat.dxr_mc.pix_capture.fail.hresult"
    private const val TRANSLATION_FAIL_RECORDING = "chat.dxr_mc.pix_capture.fail.recording"
    private const val TRANSLATION_FAIL_MISSING = "chat.dxr_mc.pix_capture.fail.missing"

    private var recording = false
    private var frames = 0
    private var frameLimit = 0
    private var capturePath: Path? = null
    private var startedNanos = 0L

    /** 结束原因；每种原因一个翻译键。 */
    private enum class StopCause(val translationKey: String) {
        LIMIT("chat.dxr_mc.pix_capture.cause.limit"),
        MANUAL("chat.dxr_mc.pix_capture.cause.manual"),
    }

    /** 注册按键绑定与每 tick 处理器：必须在模组加载期注册，否则「控制」界面里没有这一项。 */
    fun register(modEventBus: IEventBus) {
        modEventBus.addListener<RegisterKeyMappingsEvent> { event ->
            event.registerCategory(CATEGORY)
            event.register(KEY)
        }
        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> { tick() }
    }

    private fun tick() {
        while (KEY.consumeClick()) {
            toggle()
        }
        if (recording && ++frames >= frameLimit) {
            stop(StopCause.LIMIT)
        }
    }

    private fun toggle() {
        if (recording) {
            stop(StopCause.MANUAL)
            return
        }
        if (!D3D12Settings.pixTimingCapture) {
            LOGGER.info(
                "PIX capture key pressed, but [debug] pixTimingCapture is off - nothing started",
            )
            return
        }
        start()
    }

    private fun start() {
        val path = nextCapturePath() ?: return
        val limit = configuredFrames()
        when (val status = MCDR_ABI.D3D12StartPIXTimingCapture(path.toString())) {
            1 -> {
                recording = true
                frames = 0
                frameLimit = limit
                capturePath = path
                startedNanos = System.nanoTime()
                postChat(
                    line(
                        Component.translatable(
                            TRANSLATION_START,
                            Component.literal(path.fileName.toString()).withStyle(ChatFormatting.YELLOW),
                        ),
                    ),
                )
                LOGGER.info(
                    "PIX capture started: {} ({} frames, press {} again to stop early)",
                    path,
                    limit,
                    KEY.translatedKeyMessage.string,
                )
            }

            0 -> {
                LOGGER.warn("PIX capture: PIX is already recording; nothing started")
                postFailure(TRANSLATION_FAIL_RECORDING)
            }

            Int.MIN_VALUE -> {
                LOGGER.warn("PIX capture: the loaded MCDR.dll has no mcdrStartPixTimingCapture")
                postFailure(TRANSLATION_FAIL_MISSING)
            }

            else -> {
                LOGGER.error("PIX capture FAILED to start (HRESULT 0x%08X)".format(Locale.ROOT, status))
                postHresult(status)
            }
        }
    }

    private fun stop(cause: StopCause) {
        recording = false
        val path = capturePath
        capturePath = null
        val capturedFrames = frames
        val seconds = if (startedNanos == 0L) {
            0.0
        } else {
            (System.nanoTime() - startedNanos) / 1_000_000_000.0
        }
        startedNanos = 0L
        when (val status = MCDR_ABI.D3D12StopPIXTimingCapture()) {
            1 -> {
                postChat(stopMessage(path, capturedFrames, seconds, cause))
                LOGGER.info(
                    "PIX capture stopped ({}), {} frames in {} s: {} - PIX writes the file on its own thread",
                    cause.name.lowercase(Locale.ROOT),
                    capturedFrames,
                    String.format(Locale.ROOT, "%.1f", seconds),
                    path,
                )
            }

            0 -> LOGGER.warn("PIX capture: stop requested but nothing was recording")
            else -> {
                LOGGER.error("PIX capture: stop FAILED (HRESULT 0x%08X)".format(Locale.ROOT, status))
                postHresult(status)
            }
        }
        frames = 0
    }

    /**
     * 只有 `[DXEGP/PIX Capture]` 这个标签是紫色，后面的句子不是：样式会被子节点继承，
     * 所以标签必须是消息的**兄弟**而不是父节点（见 [line]）。
     */
    private fun tag(): MutableComponent =
        Component.translatable(TRANSLATION_PREFIX).withStyle(ChatFormatting.LIGHT_PURPLE)

    private fun line(vararg parts: Component): Component {
        val root = Component.empty()
        root.append(tag())
        for (part in parts) {
            root.append(part)
        }
        return root
    }

    private fun stopMessage(path: Path?, capturedFrames: Int, seconds: Double, cause: StopCause): Component {
        val secondsText = String.format(Locale.ROOT, "%.1f", seconds)
        return line(
            Component.translatable(
                TRANSLATION_STOP,
                Component.literal(path?.fileName?.toString() ?: "?").withStyle(ChatFormatting.YELLOW),
                Component.literal(capturedFrames.toString()).withStyle(ChatFormatting.GREEN),
                Component.literal(secondsText).withStyle(ChatFormatting.GREEN),
                Component.translatable(cause.translationKey).withStyle(ChatFormatting.YELLOW),
            ),
        )
    }

    /** 无条件写一行到 chat：capture 状态必须在游戏里可见，即使没人看日志。 */
    private fun postChat(message: Component) {
        try {
            Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(message)
        } catch (t: Throwable) {
            LOGGER.warn("PIX capture: cannot post the capture state to chat: {}", t.toString())
        }
    }

    /** 红色拒绝行：这里静默就像按键坏了。 */
    private fun postFailure(key: String) {
        postChat(line(Component.translatable(key).withStyle(ChatFormatting.RED)))
    }

    /** PIX 的拒绝，红色，HRESULT 就是全部解释。 */
    private fun postHresult(status: Int) {
        val code = "0x%08X".format(Locale.ROOT, status)
        postChat(
            line(
                Component.translatable(
                    TRANSLATION_FAIL_HRESULT,
                    Component.literal(code).withStyle(ChatFormatting.YELLOW),
                ).withStyle(ChatFormatting.RED),
            ),
        )
    }

    /** 现读 `pixCaptureFrames`：按键绑定的意义就是下一次 capture 的长度不用重启就能调。 */
    private fun configuredFrames(): Int {
        val configured = try {
            IllusionConfig.PIX_CAPTURE_FRAMES.get()
        } catch (t: Throwable) {
            D3D12Settings.pixCaptureFrames
        }
        return configured.coerceAtLeast(1)
    }

    /**
     * `run/pix/dxrmc-capture-<n>.wpix`，序号取自目录**已有什么**，不能用本会话的计数器：只有目录知道
     * 之前几次启动写了什么，重用序号会覆盖还想要的 capture。`dxrmc-capture-*` 全部计入，不只 `.wpix`
     * —— PIX 没写完的 capture 会留下 `-wal`/`-shm` 伴生文件，它们同样占用那个序号。
     */
    private fun nextCapturePath(): Path? {
        return try {
            val directory = FMLPaths.GAMEDIR.get().resolve("pix")
            Files.createDirectories(directory)
            var highest = 0
            Files.newDirectoryStream(directory, "dxrmc-capture-*").use { stream ->
                for (entry in stream) {
                    val digits = entry.fileName.toString()
                        .removePrefix("dxrmc-capture-")
                        .takeWhile { it.isDigit() }
                    val index = digits.toIntOrNull() ?: continue
                    if (index > highest) {
                        highest = index
                    }
                }
            }
            val next = highest + 1
            if (next > MAX_CAPTURE_INDEX) {
                LOGGER.error(
                    "PIX capture: run/pix already holds captures up to number {}; remove some first",
                    highest,
                )
                return null
            }
            directory.resolve("dxrmc-capture-$next.wpix")
        } catch (t: Throwable) {
            LOGGER.error("PIX capture: cannot prepare run/pix: {}", t.toString())
            null
        }
    }
}
