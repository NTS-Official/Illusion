package dev.nts.expdxr.mixin.options;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 26.3 把视频设置的三个选项数组留在 {@code VideoSettingsScreen} 的私有静态方法里
 * （{@code displayOptions} / {@code qualityOptions} / {@code preferenceOptions}）。
 *
 * <p>自绘的 D3D12 视频设置界面要按自己的分组重排这些选项，但复制一份构造逻辑会在原版改签名时
 * 悄悄失效，所以这里只把数组取出来，滑块与循环按钮仍然由原版生成。
 */
@Mixin(VideoSettingsScreen.class)
public interface VideoSettingsScreenOptionsAccessor {

    @Invoker("displayOptions")
    static OptionInstance<?>[] dxrmcDisplayOptions(Options options) {
        throw new AssertionError("mixin should have replaced VideoSettingsScreen.displayOptions");
    }

    @Invoker("qualityOptions")
    static OptionInstance<?>[] dxrmcQualityOptions(Options options) {
        throw new AssertionError("mixin should have replaced VideoSettingsScreen.qualityOptions");
    }

    @Invoker("preferenceOptions")
    static OptionInstance<?>[] dxrmcPreferenceOptions(Options options) {
        throw new AssertionError("mixin should have replaced VideoSettingsScreen.preferenceOptions");
    }
}
