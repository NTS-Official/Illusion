package dev.nts.expdxr.mixin.options;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 取出 {@code VideoSettingsScreen} 的三个私有静态选项数组，按自绘界面重新分组；控件仍由原版生成。 */
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
