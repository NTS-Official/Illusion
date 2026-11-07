package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherCompilerMixin {
    @WrapMethod(method = "setCompiler")
    private void dxr_mc$publishCompiler(SectionCompiler compiler, Operation<Void> original) {
        D3D12SectionGeometry.publishCompiler((SectionRenderDispatcher) (Object) this, compiler,
                () -> original.call(compiler));
    }
}
