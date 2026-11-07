package io.github.dxegp.mixin.render;

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12SectionRegionState;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionCaptureMixin implements D3D12SectionRegionState {
    @Unique
    private D3D12SectionGeometry.CompileScope dxr_mc$compileScope;

    @Override
    public D3D12SectionGeometry.CompileScope getCompileScope() {
        return dxr_mc$compileScope;
    }

    @Override
    public void setCompileScope(D3D12SectionGeometry.CompileScope scope) {
        dxr_mc$compileScope = scope;
    }
}
