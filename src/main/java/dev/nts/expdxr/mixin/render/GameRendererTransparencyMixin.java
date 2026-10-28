package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.misc.settings.D3D12Settings;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 开 mesh 地形时强制走 improved transparency（Wavelet OIT）。
 *
 * <p>必须：经典半透明依赖排序索引缓冲，而 mesh 路径的线程组执行顺序不保证 —— 两者只能二选一；
 * OIT 顺序无关，三段都能直接吃 meshlet 派发。只把开关抬成 true，线框调试（F3 + G）仍按原逻辑走。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererTransparencyMixin {

    @Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
    private void dxr_mc$forceOitForMeshTerrain(CallbackInfoReturnable<Boolean> cir) {
        if (D3D12Settings.INSTANCE.getMeshTerrain()) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
