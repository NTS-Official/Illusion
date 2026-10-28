package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.misc.settings.D3D12HdrPresentation;
import dev.nts.expdxr.misc.gui.D3D12VideoSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * 只把标题等于 {@code VIDEO} 的那个按钮换成自绘视频设置界面，其余原样返回。
 * 判据 {@link D3D12HdrPresentation#isAvailable()} 只在 D3D12 设备建起来后为真，回退到原版后端时仍走原版界面。
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenVideoMixin {

    @Shadow
    @Final
    private static Component VIDEO;

    @Inject(method = "openScreenButton", at = @At("HEAD"), cancellable = true)
    private void dxrmc$replaceVideoSettingsScreen(
            Component title,
            Supplier<Screen> supplier,
            CallbackInfoReturnable<Button> cir) {
        if (!D3D12HdrPresentation.isAvailable()) {
            return;
        }
        if (!title.getString().equals(VIDEO.getString())) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Screen self = (Screen) (Object) this;

        cir.setReturnValue(Button.builder(title,
                button -> minecraft.setScreenAndShow(new D3D12VideoSettingsScreen(self))).build());
    }
}