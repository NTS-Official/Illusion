package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.shader.D3D12FallbackShaderSource;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 `GameRenderer.preloadUiShader(ResourceManager)` 收到的 provider 交给 `D3D12FallbackShaderSource`。
 *
 * <p>**处理函数必须是 `static`**：目标方法本身是 static，Mixin 不允许非 static 回调注入 static
 * 方法，会在 APPLY 阶段直接 fatal。调用时 `LoadingOverlay` 还没挂上，所以首次绘制 mojang_logo
 * 之前 provider 必定已捕获。见 docs/misc/mixins-and-earlywindow.md §3.3
 */
@Mixin(GameRenderer.class)
public class GameRendererPreloadUiShaderMixin {
    @Inject(method = "preloadUiShader", at = @At("HEAD"))
    private static void dxrmc$CaptureVanillaShaderProvider(ResourceManager resourceManager, CallbackInfo ci) {
        D3D12FallbackShaderSource.setProvider(resourceManager);
    }
}