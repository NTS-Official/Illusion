package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12SectionRegionState;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderRegionCache.class)
public abstract class RenderRegionCacheCaptureMixin {
    @Shadow
    @Final
    private Long2ObjectMap<SectionCopy> sectionCopyCache;
    @Unique
    private ClientLevel dxr_mc$level;
    @Unique
    private long dxr_mc$contentRevision;

    @WrapMethod(method = "createRegion")
    private RenderSectionRegion dxr_mc$captureScope(ClientLevel level, long node,
                                                     Operation<RenderSectionRegion> original) {
        D3D12SectionGeometry.CompileScope scope = D3D12SectionGeometry.compileScope(node, level);
        long revision = D3D12SectionGeometry.getContentRevision();
        if (dxr_mc$level != level || dxr_mc$contentRevision != revision) {
            sectionCopyCache.clear();
            dxr_mc$level = level;
            dxr_mc$contentRevision = revision;
        }
        RenderSectionRegion region = original.call(level, node);
        ((D3D12SectionRegionState) region).setCompileScope(scope);
        return region;
    }
}
