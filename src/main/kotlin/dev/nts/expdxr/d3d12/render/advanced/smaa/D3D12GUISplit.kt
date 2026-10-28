package dev.nts.expdxr.d3d12.render.advanced.smaa

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.render.adapter.D3D12Device
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import dev.nts.expdxr.dxrmc.framegen.FrameGeneration
import dev.nts.expdxr.dxrmc.sr.SuperResolution
import net.minecraft.client.Minecraft
import org.joml.Vector4f

/**
 * The GUI's own window-sized render target: decouples the GUI from the scaled world layer.
 *
 * The GUI's scissor is in *window pixels*, so the GUI must never draw into a render area smaller
 * than the window — vanilla's `RenderPass.enableScissor` throws otherwise. That is why the GUI keeps
 * its window-sized target even when the world-layer blit fails (only a missing target falls back).
 *
 * In hudless mode this target doubles as the frame generation UI layer (premultiplied alpha); see docs/antialiasing/README.md §6.
 */
class D3D12GUIRenderTarget(useStencil: Boolean) :
    RenderTarget("DXRMC GUI", GpuFormat.RGBA8_UNORM,
        if (useStencil) GpuFormat.D32_FLOAT_S8_UINT else GpuFormat.D32_FLOAT)

object D3D12GUISplit {

    /** Window-sized GUI target; created lazily and resized when the window changes. */
    @Volatile
    private var target: D3D12GUIRenderTarget? = null

    /** Whether the world layer was blitted in this frame (only then may the final blit switch source). */
    @Volatile
    private var preparedThisFrame = false

    /** Where the GUI draws this frame; set even when the world-layer blit fails. */
    @Volatile
    private var frameTarget: D3D12GUIRenderTarget? = null

    /** Last reported degradation; logged only on change so it does not spam every frame. */
    @Volatile
    private var lastFailure: String? = null

    /** Creation is attempted once, then rolled back permanently (no per-frame retry, no log spam). */
    @Volatile
    private var givenUp = false

    /** DLSS wrote the upscaled world layer straight into the GUI target: [prepareForGui] must skip its blit. */
    @Volatile
    private var worldLayerDoneThisFrame = false

    /** This frame's SR world layer (window-sized, no UI); [armUiComposition] clears it at the frame boundary. */
    @Volatile
    private var worldLayerThisFrame: D3D12GpuTexture? = null

    /** Whether hudless presentation applies to this frame; updated only after present, in [armUiComposition]. */
    @Volatile
    private var hudlessArmed = false

    /** Last hudless state pushed to native; pushed only on change (native logs a line per push). */
    @Volatile
    private var hudlessPushed = false

    // Last UI texture handle given to native (same texture is pushed once) + "old DLL, no export" warning once.
    private var pushedUiHandle: Long = 0L
    private var warnedNoUiTexture: Boolean = false

    /** See [worldLayerDoneThisFrame]. */
    @JvmStatic
    fun markWorldLayerDone() {
        worldLayerDoneThisFrame = true
    }

    /** Diagnostic read: only meaningful right after [markWorldLayerDone] on the render thread. */
    @JvmStatic
    fun isWorldLayerDoneThisFrame(): Boolean = worldLayerDoneThisFrame

    /** Only needed away from 100 % scale; at 100 % the split is off and nothing changes. */
    @JvmStatic
    fun isActive(): Boolean = D3D12RenderScale.isEnabled() || hudlessArmed

    /**
     * Records this frame's upscaled world layer (= presentation source). Must run after SR wrote it
     * and before [prepareForGui].
     */
    @JvmStatic
    fun markWorldLayer(texture: D3D12GpuTexture) {
        worldLayerThisFrame = texture
        worldLayerDoneThisFrame = true
    }

    /** Whether this frame uses hudless presentation; SR and [prepareForGui] read the same latch. */
    @JvmStatic
    fun isHudlessArmed(): Boolean = hudlessArmed

    /**
     * Frame boundary (after present): decides hudless presentation once and pushes it to native.
     *
     * It must be fixed before the frame starts, because SR is hooked before [prepareForGui] and both
     * have to see the same value. Native's `D3D12FgUiCompositionReady` answers "is the SDK really
     * composing UI now" — not "is hudless on", or the first enable could never happen.
     */
    @JvmStatic
    fun armUiComposition() {
        // This frame's world layer has been presented; SR may not run next frame, so drop the reference.
        worldLayerThisFrame = null
        // DLSS writes its upscaled result straight into the GUI target (pInOutput): there is no separate
        // world layer to hand over, and it is also DLSS-FG's hudless source, so the two must not mix.
        val want = target != null &&
            FrameGeneration.hudlessWanted() &&
            SuperResolution.backendId() != MCDR_ABI.SR_BACKEND_DLSS
        val ready = if (want) MCDR_ABI.D3D12FgUiCompositionReady() else 0
        val able = want && ready == 1
        if (want && !able) {
            noteCompositionNotReady()
        }
        if (able != hudlessPushed && MCDR_ABI.D3D12FgSetUiComposition(able) == 1) {
            hudlessPushed = able
        }
        val next = able && hudlessPushed
        if (next == hudlessArmed) {
            return
        }
        hudlessArmed = next
        Illusion.LOGGER.info(
            "hudless presentation {} - presenting {}, the GUI is {}",
            if (next) "on" else "off",
            if (next) "the world layer only" else "the world + GUI composite",
            if (next) "composed by the frame generation backend" else "drawn by us into the presented image")
    }

    /**
     * Logged when hudless is wanted but native is not composing UI yet (throttled to one line per 10 s).
     */
    private fun noteCompositionNotReady() {
        val now = System.nanoTime()
        if (now - lastNotReadyLogNanos < NOT_READY_LOG_INTERVAL_NANOS) return
        lastNotReadyLogNanos = now
        Illusion.LOGGER.info(
            "hudless presentation wanted, but the frame generation backend is not composing yet " +
                "(fg state={}, backend={}) - keeping the world + GUI composite",
            MCDR_ABI.D3D12FgState(),
            FrameGeneration.activeBackend())
    }

    private var lastNotReadyLogNanos = 0L

    /** Throttle for [noteCompositionNotReady] (10 s). */
    private const val NOT_READY_LOG_INTERVAL_NANOS = 10_000_000_000L

    /**
     * Where the GUI (title-screen panorama included) draws this frame; null = use vanilla's main
     * target. `RenderTarget.resize` asserts the render thread, so every caller must be on it.
     */
    @JvmStatic
    fun targetForFrame(): RenderTarget? {
        val minecraft = try {
            Minecraft.getInstance()
        } catch (t: Throwable) {
            return null
        }
        // Empty frameTarget = the split is off, or prepareForGui never ran this frame. In hudless mode
        // the GUI target is the UI layer, so falling back to the main target is never allowed.
        val current = frameTarget
            ?: if (!hudlessArmed && mainTargetMatchesWindow(minecraft)) return null else ensureTarget()
        val gui = current as? D3D12GUIRenderTarget ?: return null
        // The window may have changed since prepareForGui: the scissor is in window pixels, so the target must match exactly or vanilla throws.
        return try {
            val window = minecraft.window
            if (gui.width != window.width || gui.height != window.height) {
                gui.resize(window.width, window.height)
                logGeometry(minecraft, gui, "resized (late)")
            }
            gui
        } catch (t: Throwable) {
            degrade("late resize failed: $t")
            null
        }
    }

    /**
     * Whether the main target's *actual* attachment size equals the window. Compare textures, not the
     * configured scale: the config flips instantly while the target is only rebuilt on resize.
     * Returns true when the texture cannot be read (then vanilla's own path is fine).
     */
    private fun mainTargetMatchesWindow(minecraft: Minecraft): Boolean {
        val world = (minecraft.gameRenderer.mainRenderTarget().colorTextureView as? D3D12GpuTextureView)
            ?.texture() as? D3D12GpuTexture ?: return true
        val window = minecraft.window
        return world.getWidth(0) == window.width && world.getHeight(0) == window.height
    }

    /**
     * DLSS/DLAA need a window-sized `pInOutput`, so they must not take [targetForFrame]'s "main target
     * already matches the window, no split needed" early-out: idempotent create/fix of the target.
     */
    @JvmStatic
    fun ensureTargetForDlss(): RenderTarget? = try {
        ensureTarget()
    } catch (t: Throwable) {
        degrade("ensureTargetForDlss failed: $t")
        null
    }

    private fun ensureTarget(): RenderTarget? {
        if (givenUp) {
            return null
        }
        val minecraft = Minecraft.getInstance()
        val width = minecraft.window.width
        val height = minecraft.window.height
        if (width <= 0 || height <= 0) {
            return null
        }
        return try {
            val existing = target
            if (existing == null) {
                // The depth format must match the main target's, or the GUI pipeline keeps rebuilding its PSO.
                val created = D3D12GUIRenderTarget(minecraft.gameRenderer.mainRenderTarget().hasDepth())
                created.resize(width, height)
                target = created
                logGeometry(minecraft, created, "active")
                pushUiTextureToFrameGen(created)
                created
            } else {
                if (existing.width != width || existing.height != height) {
                    existing.resize(width, height)
                    logGeometry(minecraft, existing, "resized")
                    // A resize must be re-registered with native: FFX caches the resource description at registration.
                    pushedUiHandle = 0L
                    pushUiTextureToFrameGen(existing)
                }
                existing
            }
        } catch (t: Throwable) {
            givenUp = true
            Illusion.LOGGER.error(
                "could not create the GUI's own render target; forcing render scale back to 100% so the GUI cannot outgrow the render area",
                t)
            // Without a window-sized GUI target the GUI falls back to the main target, whose scissor then goes out of bounds below 100 %.
            D3D12RenderScale.setPercent(100)
            runCatching {
                val mc = Minecraft.getInstance()
                mc.gameRenderer.resize(mc.window.width, mc.window.height)
            }.onFailure { degrade("render scale rollback failed: $it") }
            null
        }
    }

    /**
     * Hands the GUI target's colour texture to frame generation as the UI texture — once per
     * create/resize, not per frame. `Int.MIN_VALUE` = export missing in an old DLL (warned once).
     */
    private fun pushUiTextureToFrameGen(target: RenderTarget) {
        val texture = (target.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture ?: return
        val handle = texture.handle()
        if (handle == pushedUiHandle) {
            return
        }
        val status = try {
            MCDR_ABI.D3D12FgSetUiTexture(handle)
        } catch (t: Throwable) {
            Illusion.LOGGER.warn("could not hand the GUI target to frame generation: {}", t.toString())
            return
        }
        when (status) {
            1 -> {
                pushedUiHandle = handle
                Illusion.LOGGER.info("handed the GUI target ({}) to frame generation as the UI texture", handle)
            }
            Int.MIN_VALUE -> if (!warnedNoUiTexture) {
                warnedNoUiTexture = true
                Illusion.LOGGER.info("this native library has no frame-generation UI texture entry point; UI tagging stays off")
            }
            else -> Illusion.LOGGER.warn("frame generation rejected the GUI target as UI texture (status={})", status)
        }
    }

    /** Diagnostic: both real texture sizes, logged only on create/resize. */
    private fun logGeometry(minecraft: Minecraft, gui: RenderTarget, state: String) {
        val world = (minecraft.gameRenderer.mainRenderTarget().colorTextureView as? D3D12GpuTextureView)
            ?.texture() as? D3D12GpuTexture
        Illusion.LOGGER.info(
            "GUI split {} - world {}x{} ({}%), GUI target {}x{}",
            state,
            world?.getWidth(0) ?: -1, world?.getHeight(0) ?: -1, D3D12RenderScale.percent(),
            gui.width, gui.height)
    }

    /**
     * Called once the world layer is drawn and before the GUI; runs after [D3D12SmaaWorld] so SMAA
     * works at world resolution first.
     */
    @JvmStatic
    fun prepareForGui() {
        // Recomputed every frame: only a blit that really happened this frame may switch the presented source.
        preparedThisFrame = false
        frameTarget = null
        // DLSS already wrote the world layer into this target before the depth clear; read and reset the flag here.
        val worldLayerAlreadyInTarget = worldLayerDoneThisFrame
        worldLayerDoneThisFrame = false
        if (givenUp) {
            return
        }
        val minecraft = Minecraft.getInstance()
        // hudless: this target is the UI layer, so the split stays on even at 100 % scale.
        val hudless = hudlessArmed
        if (!hudless && mainTargetMatchesWindow(minecraft)) {
            // The main target is already window-sized: no split needed, the GUI stays on it.
            return
        }
        val gui = ensureTarget() as? D3D12GUIRenderTarget ?: return degrade("no GUI target")
        // Decided before the blit: a failed blit must never push the GUI back into the world target.
        frameTarget = gui
        // Vanilla clears the main target's depth before the GUI; this is a different target, so clear it here too.
        gui.depthTexture?.let {
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(it, 0.0)
        }
        if (hudless) {
            // Start fully transparent: vanilla's translucent blend then yields premultiplied RGB plus a
            // correct coverage alpha, which is exactly FFX's `USE_PREMUL_ALPHA` convention.
            val ui = (gui.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
                ?: return degrade("GUI target is not a D3D12 texture")
            val encoder = D3D12Device.sharedCommandEncoder() ?: return degrade("no shared command encoder")
            D3D12VariableShadingRate.hardReset(encoder.nativeCtx())
            encoder.clearColorTexture(ui, Vector4f(0.0f, 0.0f, 0.0f, 0.0f))
            // preparedThisFrame stays false: this frame presents the world layer, not this UI layer.
            degrade(null)
            return
        }
        // The world layer is already in this target (DLSS pInOutput); blitting again would overwrite it.
        if (worldLayerAlreadyInTarget) {
            preparedThisFrame = true
            degrade(null)
            return
        }
        val world = (minecraft.gameRenderer.mainRenderTarget().colorTextureView as? D3D12GpuTextureView)
            ?.texture() as? D3D12GpuTexture ?: return degrade("world layer is not a D3D12 texture")
        val destination = (gui.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: return degrade("GUI target is not a D3D12 texture")
        val encoder = D3D12Device.sharedCommandEncoder() ?: return degrade("no shared command encoder")
        // Defensive reset: the GUI shares this command list with the world, and a skipped `scope()` finally would leave text coarsely shaded.
        D3D12VariableShadingRate.hardReset(encoder.nativeCtx())
        // World layer into the GUI target (upscale below 100 %, downscale above), always linear. Only a
        // native-accepted blit switches the final source; a refusal costs one frame of GUI, never a scissor overflow.
        if (!encoder.recordTextureBlit(world, destination)) {
            return degrade("native texture blit refused")
        }
        preparedThisFrame = true
        degrade(null)
    }

    /** Records / clears the degradation state; logs only on change. */
    private fun degrade(reason: String?) {
        if (lastFailure == reason) {
            return
        }
        lastFailure = reason
        if (reason == null) {
            Illusion.LOGGER.info(
                "GUI split healthy again - {}",
                if (hudlessArmed) {
                    "the GUI target carries the UI layer only (the world is presented separately)"
                } else {
                    "the world layer is blitted into the GUI target"
                })
        } else {
            Illusion.LOGGER.warn("GUI split degraded ({}); the GUI keeps drawing into its own window-sized target, this frame just presents the world target", reason)
        }
    }

    /** The target that is actually presented this frame; null = the caller uses the main target. */
    @JvmStatic
    fun replayTarget(): RenderTarget? {
        if (!preparedThisFrame || givenUp || hudlessArmed) {
            return null
        }
        return target
    }

    /**
     * Presentation source: the world layer in hudless mode (or [fallback] if it was already released),
     * our GUI target once the world was blitted into it, otherwise vanilla's [fallback].
     */
    @JvmStatic
    fun presentSourceTexture(fallback: D3D12GpuTexture): D3D12GpuTexture {
        if (!givenUp && hudlessArmed) {
            // A world switch may have released the old world layer: fall back rather than pass a dangling handle.
            val layer = worldLayerThisFrame
            return if (layer != null && !layer.isClosed()) layer else fallback
        }
        if (!preparedThisFrame || givenUp) {
            return fallback
        }
        return (target?.colorTextureView as? D3D12GpuTextureView)?.texture() as? D3D12GpuTexture
            ?: fallback
    }
}
