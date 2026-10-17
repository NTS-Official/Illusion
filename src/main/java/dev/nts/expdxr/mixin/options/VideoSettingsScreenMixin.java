package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.render.advanced.D3D12AntiAliasing;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate;
import dev.nts.expdxr.dxrmc.dlss.DeepLearningSuperSampling;
import java.util.Arrays;

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
 * so the anti-aliasing entry is appended to the quality array, which places it at the end
 * of the quality section, right after vanilla's own texture/mipmap/anisotropy
 * options. Appending to the array rather than injecting a widget into
 * {@code addOptions} keeps vanilla's two-per-row layout intact.
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
 * <p>Like vanilla's anisotropy options, all four buttons are greyed out whenever the
 * setting cannot do anything - here, whenever the game is not rendering through
 * the D3D12 backend.
 *
 * <p><b>DLSS and anti-aliasing are mutually exclusive (user m05600).</b> DLSS upscales with its
 * own temporal anti-aliasing, so running our AA under it only blurs the input it then has to
 * reconstruct. While DLSS is on, {@code D3D12AntiAliasing} pins the mode to OFF and the
 * anti-aliasing button is greyed out on top of the usual availability check; turning DLSS off
 * restores whatever the player had selected before.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    /**
     * P76i（用户 m10601）：上一次把渲染缩放滑条对齐到生效值时看到的
     * {@link D3D12RenderScale#syncGeneration()}。{@code MIN_VALUE} 让第一帧必定对齐一次。
     */
    @Unique
    private int dxr_mc$renderScaleSynced = Integer.MIN_VALUE;

    @Inject(method = "qualityOptions", at = @At("RETURN"), cancellable = true)
    private static void dxr_mc$addAntiAliasing(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] vanilla = cir.getReturnValue();
        OptionInstance<?>[] extended = Arrays.copyOf(vanilla, vanilla.length + 1);
        extended[vanilla.length] = D3D12AntiAliasing.option();
        cir.setReturnValue(extended);
    }

    /**
     * 第四栏" Illusion 设置"，排在原版三组（显示 / 画质 / 偏好）之后。
     *
     * <p>这一栏**故意不参与原版画质预设**：{@code OptionsGraphicsPresetMixin} 只把抗锯齿映射进
     * fast/fancy/fabulous，renderScale 与 shadingRate 既不被预设改写，也不把预设打成"自定义"
     * （玩家调它们时预设保持不变）。这正是把它们单开一栏的原因——原版预设管的是"画质档位"，
     * 这一栏管的是 DXRMC 自己的渲染分辨率与着色粒度。要改这个约定请先问清楚。
     */
    @Inject(method = "addOptions", at = @At("TAIL"))
    private void dxr_mc$addIllusionSection(CallbackInfo ci) {
        OptionsList list = ((OptionsSubScreen) (Object) this).list;
        if (list == null) {
            return;
        }
        list.addHeader(Component.translatable("options.video.illusion.header"));
        list.addBig(D3D12RenderScale.option());
        list.addSmall(D3D12VariableShadingRate.option(), DeepLearningSuperSampling.option());
        list.addSmall(DeepLearningSuperSampling.presetOption(), DeepLearningSuperSampling.qualityOption());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void dxr_mc$updateDxrMcButtons(CallbackInfo ci) {
        OptionsList list = ((OptionsSubScreen) (Object) this).list;
        if (list == null) {
            return;
        }
        // P76i（用户 m10601）：切 DLSS 开关 / 进出行 DLAA 会同时改缩放的**取值范围**与**生效值**，
        // 而 vanilla 的滑条只在构造和松手时对齐 `OptionInstance.get()`；`OptionInstance.set` 在
        // 值没变时更是连回调都不来。滑条于是停在旧位置，标题又拿"旧位置 + 新范围"算 —— 显示的数字
        // 可以和真正生效的值毫无关系（DLAA 把缩放钉在 100%，滑条却按 DLSS 的 50..90 读成 50%）。
        // 这里每 tick 比对一次代号，变了就借 vanilla 的公共入口重新同步（内部即 `resetValue()`）。
        int scaleGeneration = D3D12RenderScale.syncGeneration();
        if (scaleGeneration != dxr_mc$renderScaleSynced) {
            dxr_mc$renderScaleSynced = scaleGeneration;
            list.resetOption(D3D12RenderScale.option());
            D3D12RenderScale.logSliderResync();
        }
        dxr_mc$setButtonActive(list, D3D12AntiAliasing.option(),
                D3D12AntiAliasing.isAvailable() && !D3D12AntiAliasing.isLockedByDlss());
        // P108aa（用户 m11796）：DLAA 要求 render == target，缩放缓条在 DLAA 期间置灰（值钉在 100）。
        dxr_mc$setButtonActive(list, D3D12RenderScale.option(),
                D3D12RenderScale.isAvailable() && !D3D12RenderScale.isLockedByDlaa());
        dxr_mc$setButtonActive(list, D3D12VariableShadingRate.option(), D3D12VariableShadingRate.isAvailable());
        // P108aa：抗锯齿 = DLAA 时 DLSS 由抗锯齿选项接管，这两个按钮置灰（tooltip 里已说明）。
        boolean dlaaOwnsDlss = DeepLearningSuperSampling.isDlaaActive();
        dxr_mc$setButtonActive(list, DeepLearningSuperSampling.option(),
                DeepLearningSuperSampling.isAvailable() && !dlaaOwnsDlss);
        dxr_mc$setButtonActive(list, DeepLearningSuperSampling.qualityOption(),
                DeepLearningSuperSampling.isAvailable() && !dlaaOwnsDlss);
        dxr_mc$setButtonActive(list, DeepLearningSuperSampling.presetOption(),
                DeepLearningSuperSampling.isAvailable() && !dlaaOwnsDlss);
    }

    @Unique
    private void dxr_mc$setButtonActive(OptionsList list, OptionInstance<?> option, boolean active) {
        AbstractWidget button = list.findOption(option);
        if (button != null) {
            button.active = active;
        }
    }
}
