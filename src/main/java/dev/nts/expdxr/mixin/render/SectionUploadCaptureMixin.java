package dev.nts.expdxr.mixin.render;

import com.mojang.blaze3d.vertex.MeshData;
import dev.nts.expdxr.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * 在 section 网格**上传到 UberBuffer 的那一刻**采集几何元信息。
 *
 * <p>不能挂 `SectionCompiler.compile`：那个混入能应用，但回调一次也不触发。
 * 这里同时能拿到 section 坐标、层、`CompiledSectionMesh` 与两个 ByteBuffer 的字节数。
 * 只在 HEAD 读实参、不改状态；不缓存 GPU 句柄（UberBuffer slice 会随整理变化）。
 * 见 docs/misc/mixins-and-earlywindow.md §3.6
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public abstract class SectionUploadCaptureMixin {

    @Shadow
    public abstract long getSectionNode();

    @Inject(method = "addSectionBuffersToUberBuffer", at = @At("HEAD"))
    private void dxr_mc$captureUpload(ChunkSectionLayer layer, CompiledSectionMesh mesh,
                                      ByteBuffer vertexBuffer, ByteBuffer indexBuffer,
                                      CallbackInfoReturnable<Boolean> cir) {
        Level level = Minecraft.getInstance().level;
        D3D12SectionGeometry.captureUpload(
                getSectionNode(),
                layer.name(),
                mesh.isEmpty(layer) ? 0 : mesh.getSectionDraw(layer).indexCount(),
                vertexBuffer == null ? 0 : vertexBuffer.remaining(),
                indexBuffer == null ? 0 : indexBuffer.remaining(),
                level == null ? 0 : System.identityHashCode(level),
                vertexBuffer);
    }
}