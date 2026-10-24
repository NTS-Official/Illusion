package dev.nts.expdxr.d3d12.backend

import com.mojang.blaze3d.platform.InputConstants
import dev.nts.expdxr.Illusion.Companion.MODID
import dev.nts.expdxr.IllusionConfig
import dev.nts.expdxr.misc.D3D12Settings
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
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
 * PIX timing capture driven by a key press (`[debug] pixTimingCapture`, default F9).
 *
 * PIX's UI cannot write this file for us: its continuous ("counters") recording keeps everything
 * inside the PIX session until the user saves it, and the timing capture that PIX itself starts
 * writes to `%TEMP%` under a name it picks. A capture that has to land under the game directory,
 * with a name and a length the player chose, has to be started from inside the process -
 * PIXBeginCapture/PIXEndCapture, which the native layer calls on threads of its own.
 *
 * Hence the key binding instead of an automatic capture: the player decides when the interesting
 * content is on screen, every press starts a fresh file, pressing again stops early, and the frame
 * budget is read from the config at the moment of the press, so changing it does not need a
 * restart. A capture is only started while `[debug] pixTimingCapture` is on, so the feature stays
 * dormant unless it is asked for.
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

    /**
     * P199 诊断键：循环 DLSS/DLAA 的抖动符号档位（0..3），用来现场 A/B 静止抖动。
     *
     * <p>临时按键 —— 符号定下来后连同 [DeepLearningSuperSampling.cycleJitterSignMode] 一起删。
     */
    private val JITTER_KEY = KeyMapping(
        "key.dxr_mc.jitter_sign",
        InputConstants.Type.KEYBOARD,
        InputConstants.KEY_F7,
        CATEGORY,
    )

    /** Highest index tried for `run/pix/dxrmc-capture-N.wpix` before giving up. */
    private const val MAX_CAPTURE_INDEX = 999

    /**
     * Chat lines. Their arguments arrive as already styled components, so a translation only has to
     * order them; the wording is free to differ per language.
     */
    private const val TRANSLATION_PREFIX = "chat.dxr_mc.pix_capture.prefix"
    private const val TRANSLATION_START = "chat.dxr_mc.pix_capture.start"
    private const val TRANSLATION_STOP = "chat.dxr_mc.pix_capture.stop"

    /**
     * Failure lines, also always shown: a capture that silently refuses to start looks exactly like
     * a key that does nothing, which is what the first on-machine test ran into. A refusal by PIX
     * reports the HRESULT and nothing else: the code is what identifies the problem (0x80070005 is
     * an unelevated process, 0x800700B7 a capture session left alive by an earlier run), while a
     * sentence about it would be a guess.
     */
    private const val TRANSLATION_FAIL_HRESULT = "chat.dxr_mc.pix_capture.fail.hresult"
    private const val TRANSLATION_FAIL_RECORDING = "chat.dxr_mc.pix_capture.fail.recording"
    private const val TRANSLATION_FAIL_MISSING = "chat.dxr_mc.pix_capture.fail.missing"

    private var recording = false
    private var frames = 0
    private var frameLimit = 0
    private var capturePath: Path? = null
    private var startedNanos = 0L

    /** Why a capture ended; each cause is its own translation key, so the chat line follows the language. */
    private enum class StopCause(val translationKey: String) {
        LIMIT("chat.dxr_mc.pix_capture.cause.limit"),
        MANUAL("chat.dxr_mc.pix_capture.cause.manual"),
    }

    /**
     * Registers the key binding and the per-tick handler. Called from [dev.nts.expdxr.IllusionClient]
     * with the mod event bus (the key binding has to be registered during mod loading, or the
     * controls screen has no entry for it).
     */
    fun register(modEventBus: IEventBus) {
        modEventBus.addListener<RegisterKeyMappingsEvent> { event ->
            event.registerCategory(CATEGORY)
            event.register(KEY)
            event.register(JITTER_KEY)
        }
        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> { tick() }
    }

    private fun tick() {
        while (KEY.consumeClick()) {
            toggle()
        }
        while (JITTER_KEY.consumeClick()) {
            val mode = dev.nts.expdxr.dxrmc.nvidia.dlss.DeepLearningSuperSampling.cycleJitterSignMode()
            val text = "[dxrmc] DLSS jitter sign mode=$mode"
            LOGGER.info(text)
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal(text))
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
                "[dxrmc] PIX capture key pressed, but [debug] pixTimingCapture is off - nothing started",
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
                    "[dxrmc] PIX capture started: {} ({} frames, press {} again to stop early)",
                    path,
                    limit,
                    KEY.translatedKeyMessage.string,
                )
            }

            0 -> {
                LOGGER.warn("[dxrmc] PIX capture: PIX is already recording; nothing started")
                postFailure(TRANSLATION_FAIL_RECORDING)
            }

            Int.MIN_VALUE -> {
                LOGGER.warn("[dxrmc] PIX capture: the loaded MCDR.dll has no mcdrStartPixTimingCapture")
                postFailure(TRANSLATION_FAIL_MISSING)
            }

            else -> {
                LOGGER.error("[dxrmc] PIX capture FAILED to start (HRESULT 0x%08X)".format(Locale.ROOT, status))
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
                    "[dxrmc] PIX capture stopped ({}), {} frames in {} s: {} - PIX writes the file on its own thread",
                    cause.name.lowercase(Locale.ROOT),
                    capturedFrames,
                    String.format(Locale.ROOT, "%.1f", seconds),
                    path,
                )
            }

            0 -> LOGGER.warn("[dxrmc] PIX capture: stop requested but nothing was recording")

            else -> {
                LOGGER.error("[dxrmc] PIX capture: stop FAILED (HRESULT 0x%08X)".format(Locale.ROOT, status))
                postHresult(status)
            }
        }
        frames = 0
    }

    /**
     * Only `[DXEGP/PIX Capture]` is purple - the tag itself, not the sentence after it.
     *
     * <p>A style set on a parent is inherited by everything appended to it, so the tag has to be a
     * sibling of the message rather than its parent. Hence [line]: an unstyled root that carries the
     * purple tag and then the parts, each part keeping its own colour.
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

    /**
     * Puts one line into the chat, always: the capture state has to be visible in game even when the
     * log is not being watched, and the user explicitly did not want a switch to silence it.
     */
    private fun postChat(message: Component) {
        try {
            Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(message)
        } catch (t: Throwable) {
            LOGGER.warn("[dxrmc] PIX capture: cannot post the capture state to chat: {}", t.toString())
        }
    }

    /** A refusal, in red: silence here looks like a dead key. */
    private fun postFailure(key: String) {
        postChat(line(Component.translatable(key).withStyle(ChatFormatting.RED)))
    }

    /** PIX's own refusal, in red, with the HRESULT as the whole explanation. */
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

    /**
     * `pixCaptureFrames` as it stands right now: the point of the key binding is that the length of
     * the next capture can be tuned without restarting the game.
     */
    private fun configuredFrames(): Int {
        val configured = try {
            IllusionConfig.PIX_CAPTURE_FRAMES.get()
        } catch (t: Throwable) {
            D3D12Settings.pixCaptureFrames
        }
        return configured.coerceAtLeast(1)
    }

    /**
     * `run/pix/dxrmc-capture-<n>.wpix`, numbered off what the directory already holds.
     *
     * <p>The number comes from the directory, never from a counter kept in this session: only the
     * directory knows what earlier launches wrote, and reusing a number would overwrite a capture
     * that is still wanted. Every `dxrmc-capture-*` entry counts, not just the `.wpix` — a capture
     * that PIX never finished writing leaves `-wal`/`-shm` companions behind, and those belong to
     * that number too. So the next file is always one past the highest index seen on disk.
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
                    "[dxrmc] PIX capture: run/pix already holds captures up to number {}; remove some first",
                    highest,
                )
                return null
            }
            directory.resolve("dxrmc-capture-$next.wpix")
        } catch (t: Throwable) {
            LOGGER.error("[dxrmc] PIX capture: cannot prepare run/pix: {}", t.toString())
            null
        }
    }
}