package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.shader.D3D12FallbackShaderSource;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P30：把 {@code GameRenderer.preloadUiShader(ResourceManager)} 收到的 provider 交给
 * {@link D3D12FallbackShaderSource}。
 *
 * <p>26.3 起 {@code Minecraft.loadCriticalShaders()} 传入的是 {@code this.resourceManager}
 * （{@link ResourceManager} 继承 {@link net.minecraft.server.packs.resources.ResourceProvider}，
 * 所以下游 {@code setProvider} 的签名不用动），且在 {@code LoadingOverlay} 被挂上之前，
 * 所以到第一次绘制 mojang_logo 时该 provider 必定已经捕获完毕。
 *
 * <p>这里只读取一个方法参数，不改变 vanilla 的任何行为。
 *
 * <p>注意处理函数必须是 **static**：26.3 的 {@code preloadUiShader} 本身就是 static
 * （26.2 是实例方法，Minecraft 直接调 {@code this.gameRenderer.preloadUiShader(...)}），
 * Mixin 不允许非 static 回调注入 static 方法，会在 APPLY 阶段直接 fatal。
 */
@Mixin(GameRenderer.class)
public class GameRendererPreloadUiShaderMixin {
    @Inject(method = "preloadUiShader", at = @At("HEAD"))
    private static void dxrmc$CaptureVanillaShaderProvider(ResourceManager resourceManager, CallbackInfo ci) {
        D3D12FallbackShaderSource.setProvider(resourceManager);
    }
}