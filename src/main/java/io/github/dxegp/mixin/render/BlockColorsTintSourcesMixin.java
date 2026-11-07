package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtCompilerInputs;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

@Mixin(BlockColors.class)
public abstract class BlockColorsTintSourcesMixin {
    @WrapMethod(method = "register")
    private void dxr_mc$publishTintSources(List<BlockTintSource> sources, Block[] blocks, Operation<Void> original) {
        D3D12SectionGeometry.publishTintSources((BlockColors) (Object) this,
                () -> original.call(D3D12RtCompilerInputs.freezeTintSources(sources), blocks));
    }
}
