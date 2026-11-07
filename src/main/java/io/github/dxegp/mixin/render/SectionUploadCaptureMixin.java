package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12SectionCompileState;
import io.github.dxegp.experimental.raytracing.D3D12SectionTaskState;
import io.github.dxegp.experimental.raytracing.D3D12SectionCaptureState;
import io.github.dxegp.experimental.raytracing.D3D12SectionRegionState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.List;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public abstract class SectionUploadCaptureMixin implements D3D12SectionCompileState {

    @Unique
    private volatile SectionRenderDispatcher.RenderSection.SectionTask dxr_mc$compileTask;

    @Override
    public Object getCompileTaskIdentity() {
        return dxr_mc$compileTask;
    }

    @Override
    public boolean hasPendingCompile() {
        SectionRenderDispatcher.RenderSection.SectionTask task = dxr_mc$compileTask;
        return task != null && !((SectionCompileTaskAccessor) task).dxr_mc$isCancelled().get()
                && !((D3D12SectionTaskState) task).getCaptureState().getInstalled();
    }

    @Inject(method = "createCompileTask(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask;",
            at = @At("RETURN"))
    private void dxr_mc$trackCompileTask(
            RenderSectionRegion region, List<?> additionalRenderers,
            CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> callback) {
        synchronized (this) {
            Level level = Minecraft.getInstance().level;
            D3D12SectionGeometry.observeLevel(level);
            SectionRenderDispatcher.RenderSection.SectionTask task = callback.getReturnValue();
            D3D12SectionCaptureState state = ((D3D12SectionTaskState) task).getCaptureState();
            state.bind(getSectionNode(), level, D3D12SectionGeometry.INSTANCE.epoch(),
                    ((D3D12SectionRegionState) region).getCompileScope());
            state.bindSourceRegion(region);
            dxr_mc$compileTask = task;
        }
    }

    @Shadow
    public abstract long getSectionNode();

    @Shadow
    public abstract SectionMesh getSectionMesh();

    @Inject(method = "addSectionBuffersToUberBuffer", at = @At("HEAD"))
    private void dxr_mc$captureUpload(ChunkSectionLayer layer, CompiledSectionMesh mesh,
                                      ByteBuffer vertexBuffer, ByteBuffer indexBuffer,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (vertexBuffer == null && mesh == getSectionMesh()) {
            return;
        }
        Level level = Minecraft.getInstance().level;
        SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
        int indexCount = draw == null ? 0 : draw.indexCount();
        D3D12SectionGeometry.IndexSource source = draw != null && draw.hasCustomIndexBuffer()
                ? D3D12SectionGeometry.IndexSource.CUSTOM_SLICE
                : D3D12SectionGeometry.IndexSource.SEQUENTIAL_QUADS;
        int indexStride = source == D3D12SectionGeometry.IndexSource.CUSTOM_SLICE ? draw.indexType().bytes : 0;
        D3D12SectionGeometry.stageUpload(
                getSectionNode(),
                mesh,
                layer.name(),
                indexCount,
                level,
                vertexBuffer,
                source,
                indexStride,
                indexBuffer);
    }

    @WrapMethod(method = "setSectionMesh")
    private SectionMesh dxr_mc$commitUpload(SectionMesh mesh, Operation<SectionMesh> original) {
        synchronized (this) {
            SectionRenderDispatcher.RenderSection.SectionTask task = dxr_mc$compileTask;
            if (task == null || ((SectionCompileTaskAccessor) task).dxr_mc$isCancelled().get()) {
                return mesh;
            }
            D3D12SectionCaptureState state = ((D3D12SectionTaskState) task).getCaptureState();
            Level level = Minecraft.getInstance().level;
            int epoch = D3D12SectionGeometry.INSTANCE.epoch();
            long node = getSectionNode();
            if (!state.canInstall(mesh, node, level, epoch)) {
                if (!state.hasCurrentInputs()) {
                    dxr_mc$retryCompile(task, level, node, epoch);
                }
                return mesh;
            }
            SectionMesh previous = original.call(mesh);
            if (getSectionMesh() == mesh && Minecraft.getInstance().level == level
                    && D3D12SectionGeometry.commitCurrentUpload(node, mesh, level, epoch,
                            state.getCompileScope(), state.getProducerManifest())) {
                state.observeInstall(mesh, node, level, epoch);
            }
            if (!state.getInstalled() && !state.hasCurrentInputs()) {
                dxr_mc$retryCompile(task, level, node, epoch);
            }
            return previous;
        }
    }

    @Unique
    private void dxr_mc$retryCompile(SectionRenderDispatcher.RenderSection.SectionTask task,
                                       Level level, long node, int epoch) {
        if (level == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            synchronized (this) {
                if (client.level != level || D3D12SectionGeometry.INSTANCE.epoch() != epoch
                        || getSectionNode() != node || dxr_mc$compileTask != task
                        || ((SectionCompileTaskAccessor) task).dxr_mc$isCancelled().get()
                        || ((D3D12SectionTaskState) task).getCaptureState().getInstalled()) {
                    return;
                }
            }
            client.levelExtractor.setSectionDirty(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
        });
    }

    @Inject(method = "releaseSectionMesh", at = @At("HEAD"))
    private void dxr_mc$discardUpload(SectionMesh mesh, CallbackInfo ci) {
        D3D12SectionGeometry.discardUpload(mesh);
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void dxr_mc$removeSection(CallbackInfo ci) {
        synchronized (this) {
            dxr_mc$compileTask = null;
            D3D12SectionGeometry.removeSection(getSectionNode());
        }
    }
}
