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
 * P125（mesh Step 2）：在 section 网格**上传到 UberBuffer 的那一刻**采集几何元信息。
 *
 * <p>为什么不用 `SectionCompiler.compile`：本机实测该混入能成功应用，但回调一次也不触发
 * （`CompileTask.doTask` 的探针每次都打，`compile` 的回调为 0 —— 见 known_bugs P125）。
 * `addSectionBuffersToUberBuffer` 是同一批数据进入 GPU 前的必经之路，而且这里**同时**能拿到：
 * section 坐标（`getSectionNode()`）、层（`ChunkSectionLayer`）、`CompiledSectionMesh`
 * （`getSectionDraw(layer).indexCount()`）、顶点/索引 ByteBuffer 的字节数 ——
 * 正是计划 Step 2 要的"MeshData 元信息 + 上传生命周期关联"。
 *
 * <p>只在 HEAD 读取，不改任何状态；不缓存 GPU 句柄（UberBuffer slice 会随整理变化）。
 * 注意：混入类不能有非 private 成员方法，否则 Mixin 抛 `contains non-private method(s)` 崩游戏。
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