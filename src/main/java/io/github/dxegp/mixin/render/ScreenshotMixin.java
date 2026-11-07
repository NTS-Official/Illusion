package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import io.github.dxegp.d3d12.render.core.D3D12GpuTexture;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

@Mixin(Screenshot.class)
public class ScreenshotMixin {
    @WrapOperation(method = "lambda$takeScreenshot$1", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/NativeImage;setPixelABGR(III)V"), remap = false)
    private static void dxr_mc$ScreenshotRow(NativeImage image, int column, int row, int color,
            Operation<Void> original, GpuBuffer buffer, int height, int downscale, int width,
            GpuTexture texture, Consumer<NativeImage> callback) {
        original.call(image, column,
                texture instanceof D3D12GpuTexture ? image.getHeight() - 1 - row : row, color);
    }
}
