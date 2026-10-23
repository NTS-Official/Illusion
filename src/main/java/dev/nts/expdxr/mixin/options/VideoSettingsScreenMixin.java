package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.D3D12HdrPresentation;
import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate;
import dev.nts.expdxr.dxrmc.nvidia.dlss.DeepLearningSuperSampling;
import dev.nts.expdxr.dxrmc.nvidia.reflex.NvidiaReflex;
import java.util.Arrays;

import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts DXRMC's rendering options into the vanilla video-settings screen.
 *
 * <p>Vanilla assembles that screen from three static arrays -
 * {@code displayOptions}, {@code qualityOptions} and {@code preferenceOptions} -
 * so the anti-aliasing entry is appended to the quality array and the HDR entry (P198) to the
 * display array, which places each one at the end of its section, right after vanilla's own
 * options. Appending to the array rather than injecting a widget into {@code addOptions} keeps
 * vanilla's two-per-row layout intact.
 *
 * <p>Both arrays are extended with {@code @Inject(..., at = RETURN) + CallbackInfoReturnable}
 * rather than {@code @ModifyReturnValue}: NeoForge's bundled MixinExtras rejects the natural
 * handler shape here (it wants the original return value <em>before</em> the captured arguments -
 * see the run46 log), and plain Mixin has no such ambiguity.
 *
 * <p>The render scale and the VRS shading rate (migrated out of the mod's own
 * {@code [render]} config section, m10294) are appended at the very end of
 * {@code addOptions} instead, with a fourth section header of their own - the same shape the
 * three vanilla sections have ({@code addHeader} + {@code addSmall}). Only {@code addOptions}
 * builds the list at runtime, so that is the one place where a fourth section can be added
 * without touching vanilla's arrays. {@code OptionsSubScreen.addContents} assigns
 * {@code this.list} before calling {@code addOptions}, so the list is already there at TAIL.
 *
 * <p>P108b（用户 m10920）: the DLSS switch ({@link DeepLearningSuperSampling}) joins that section on a
 * <em>row of its own</em> - {@code addSmall(option, null)} yields a single-widget row - so it
 * no longer borrows a slot from the anti-aliasing cycle button. It deliberately stays out of
 * vanilla's graphics presets, like the other two entries of this section.
 *
 * <p>P198（用户请求）: every option this mod adds is <b>hidden</b> - not merely greyed out - when the
 * live device is not D3D12 ({@link D3D12HdrPresentation#isAvailable()} is the shared probe).
 * Both array taps therefore leave the vanilla array untouched, and {@code addOptions} does not
 * even create the section header. Greying them out was the old behaviour; it still left a dead
 * "Illusion Settings" section on OpenGL/Vulkan players' screens.
 *
 * <p><b>DLSS and anti-aliasing are mutually exclusive (user m05600).</b> DLSS upscales with its
 * own temporal anti-aliasing, so running our AA under it only blurs the input it then has to
 * reconstruct. While DLSS is on, {@code D3D12AntiAliasing} pins the mode to OFF and the
 * anti-aliasing button is greyed out on top of the usual availability check; turning DLSS off
 * restores whatever the player had selected before.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Unique private int dxr_mc$renderScaleSynced = Integer.MIN_VALUE;
    @Unique private int dxr_mc$antiAliasingSynced = Integer.MIN_VALUE;
    
    @Unique private final Component ILLUSION_HEADER = Component.translatable("options.video.illusion.header").withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD);
    // HDR (P198) - 追加到原版"显示"页末尾
    @Inject(method = "displayOptions", at = @At("RETURN"), cancellable = true)
    private static void dxr_mc$addHdr(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        if (!D3D12HdrPresentation.isAvailable()) {
            return;
        }
        OptionInstance<?>[] vanilla = cir.getReturnValue();
        OptionInstance<?>[] extended = Arrays.copyOf(vanilla, vanilla.length + 1);
        extended[vanilla.length] = D3D12HdrPresentation.option();
        cir.setReturnValue(extended);
    }

    @Inject(method = "qualityOptions", at = @At("RETURN"), cancellable = true)
    private static void dxr_mc$addAntiAliasing(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        if (!D3D12HdrPresentation.isAvailable()) {
            return;
        }
        OptionInstance<?>[] vanilla = cir.getReturnValue();
        OptionInstance<?>[] extended = Arrays.copyOf(vanilla, vanilla.length + 1);
        extended[vanilla.length] = D3D12AntiAliasing.option();
        cir.setReturnValue(extended);
    }

    // Illusion settings
    @Inject(method = "addOptions", at = @At("TAIL"))
    private void dxr_mc$addIllusionSection(CallbackInfo ci) {
        OptionsList list = ((OptionsSubScreen) (Object) this).list;
        if (list == null) return;
        // 非 D3D12 后端：整块设置不出现（不是灰掉）。
        if (!D3D12HdrPresentation.isAvailable()) return;

        // Header
        list.addHeader(ILLUSION_HEADER);

        // Render scaling
        list.addBig(D3D12RenderScale.option());

        // VRS & DLSS
        list.addSmall(D3D12VariableShadingRate.option(), DeepLearningSuperSampling.option());
        list.addSmall(DeepLearningSuperSampling.presetOption(), DeepLearningSuperSampling.qualityOption());

        // Reflex
        list.addBig(NvidiaReflex.option());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void dxr_mc$updateDxrMcButtons(CallbackInfo ci) {
        OptionsList list = ((OptionsSubScreen) (Object) this).list;
        if (list == null) return;
        if (!D3D12HdrPresentation.isAvailable()) return;

        // Update scaling value when applying DLSS/DLAA options change.
        int scaleGeneration = D3D12RenderScale.syncGeneration();
        if (scaleGeneration != dxr_mc$renderScaleSynced) {
            dxr_mc$renderScaleSynced = scaleGeneration;
            list.resetOption(D3D12RenderScale.option());
            D3D12RenderScale.logSliderResync();
        }

        // Same for anti-aliasing: the graphics preset, the DLSS lock and the restore-from-
        // options.txt path all write the value through OptionInstance.set, which only fires the
        // value listener - the CycleButton caches its own label until somebody calls resetOption.
        // Vanilla's GraphicsPreset.set does that for the options it knows; ours is not one of them.
        int aaMode = D3D12AntiAliasing.current();
        if (aaMode != dxr_mc$antiAliasingSynced) {
            dxr_mc$antiAliasingSynced = aaMode;
            list.resetOption(D3D12AntiAliasing.option());
        }

        dxr_mc$setButtonActive(list, D3D12AntiAliasing.option(),
                D3D12AntiAliasing.isAvailable() && !D3D12AntiAliasing.isLockedByDlss());

        // Lock scaling option when DLAA enabled
        dxr_mc$setButtonActive(list, D3D12RenderScale.option(),
                D3D12RenderScale.isAvailable() && !D3D12RenderScale.isLockedByDlaa());
        dxr_mc$setButtonActive(list, D3D12VariableShadingRate.option(), D3D12VariableShadingRate.isAvailable());

        // Lock one(DLAA/DLSS) when another is enabled
        boolean dlaaOwnsDlss = DeepLearningSuperSampling.isDlaaActive();
        dxr_mc$setButtonActive(list, DeepLearningSuperSampling.option(),
                DeepLearningSuperSampling.isAvailable() && !dlaaOwnsDlss);
        dxr_mc$setButtonActive(list, DeepLearningSuperSampling.qualityOption(),
                DeepLearningSuperSampling.isAvailable() && !dlaaOwnsDlss);
        dxr_mc$setButtonActive(list, DeepLearningSuperSampling.presetOption(),
                DeepLearningSuperSampling.isAvailable() && !dlaaOwnsDlss);

        // Reflex runtime check, lock if lost
        dxr_mc$setButtonActive(list, NvidiaReflex.option(), NvidiaReflex.isAvailable());
    }

    @Unique
    private void dxr_mc$setButtonActive(OptionsList list, OptionInstance<?> option, boolean active) {
        AbstractWidget button = list.findOption(option);
        if (button != null) button.active = active;
    }
}
