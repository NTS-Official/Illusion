package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.shader.D3D12FallbackShaderSource;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P30：把 {@code GameRenderer.preloadUiShader(ResourceProvider)} 收到的 provider 交给
 * {@link D3D12FallbackShaderSource}。
 *
 * <p>{@code Minecraft.loadCriticalShaders()}（Minecraft.java:801-803）调用的是
 * {@code this.gameRenderer.preloadUiShader(this.vanillaPackResources.asProvider())}，
 * 且在 {@code LoadingOverlay} 被挂上之前（Minecraft.java:671 早于 :687），
 * 所以到第一次绘制 mojang_logo 时该 provider 必定已经捕获完毕。
 *
 * <p>这里只读取一个方法参数，不改变 vanilla 的任何行为。
 */
@Mixin(GameRenderer.class)
public class GameRendererPreloadUiShaderMixin {

    @Inject(method = "preloadUiShader", at = @At("HEAD"))
    private void gl4dx12$captureVanillaShaderProvider(ResourceProvider resourceProvider, CallbackInfo ci) {
        D3D12FallbackShaderSource.setProvider(resourceProvider);
    }
}