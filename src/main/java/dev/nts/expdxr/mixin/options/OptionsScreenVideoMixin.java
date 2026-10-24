package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.D3D12HdrPresentation;
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
 * D3D12 后端下把"视频设置..."按钮换成自绘界面。
 *
 * <p>替换点在 {@code OptionsScreen.openScreenButton(title, supplier)}：原版用同一个工厂造"视频设置"、
 * "音乐与声音"、"语言"等按钮，只有 title 与 {@code VIDEO} 相同的那个被换掉，其余原样返回。
 *
 * <p>判据是 {@link D3D12HdrPresentation#isAvailable()}：它只在 D3D12 设备真的建起来之后为真，所以
 * 玩家选了 D3D12 却回退到原版后端时仍然走原版界面。
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