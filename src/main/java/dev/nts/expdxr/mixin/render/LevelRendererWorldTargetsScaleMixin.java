package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12RenderScale;
import net.minecraft.client.renderer.LevelRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 世界层辅助渲染目标（半透明 / 物品实体 / 粒子 / 天气 / 云）也按渲染尺寸分配。
 *
 * <p>必须与 `MainTargetRenderScaleMixin` 成对存在：主目标的逻辑尺寸仍是窗口尺寸，只有附件被缩小；
 * 辅助目标若停在窗口尺寸就会比源大，而 `copyDepthFrom` 拿**目标**尺寸当拷贝矩形却从源读，直接抛
 * `Source texture (1920x1000) is not large enough to read a rectangle of 2560x1334 at 0x0`。
 * 不能改用「夹取拷贝矩形」：目标比源大时只会拷左上角，深度与颜色错位。
 * 见 docs/misc/mixins-and-earlywindow.md §3.4
 */
@Mixin(LevelRenderer.class)
public class LevelRendererWorldTargetsScaleMixin {

    /** 只换读取到的值，不动 `this.width/height` 本身；100%（默认）时恒等。 */
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;width:I",
            opcode = Opcodes.GETFIELD), remap = false)
    private int dxr_mc$worldTargetWidth(int original) { return dxr_mc$worldTargetDimension(original); }

    /** 同 `dxr_mc$worldTargetWidth`：辅助目标必须与主目标附件同尺寸，否则 `copyDepthFrom` 越界。 */
    @ModifyExpressionValue(method = "render", at = @At(value = "FIELD",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;height:I",
            opcode = Opcodes.GETFIELD), remap = false)
    private int dxr_mc$worldTargetHeight(int original) { return dxr_mc$worldTargetDimension(original); }

    @Unique private static int dxr_mc$worldTargetDimension(int value) { return D3D12RenderScale.worldLayerSize(value); }
}
