package io.github.dxegp.mixin.render;

import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionCompiler.class)
public interface SectionCompilerAccessor {
    @Accessor("blockModelSet")
    BlockStateModelSet dxr_mc$blockModelSet();

    @Accessor("fluidModelSet")
    FluidStateModelSet dxr_mc$fluidModelSet();
}
