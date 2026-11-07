package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.fluid.CustomFluidRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerSupportMixin {
    @Shadow
    @Final
    private boolean cutoutLeaves;

    @WrapOperation(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
    private void dxr_mc$captureModelSupport(ModelBlockRenderer renderer, BlockQuadOutput output,
            float originX, float originY, float originZ, BlockAndTintGetter level, BlockPos position,
            BlockState state, BlockStateModel model, long seed, Operation<Void> original) {
        try (D3D12RtModelSupport.Placement placement = D3D12RtModelSupport.beginModel(
                model, originX, originY, originZ, ModelBlockRenderer.forceOpaque(cutoutLeaves, state),
                !state.hasOffsetFunction())) {
            original.call(renderer, output, originX, originY, originZ, level, position, state, model, seed);
        }
    }

    @WrapOperation(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
    private void dxr_mc$captureFluidSupport(FluidRenderer renderer, BlockAndTintGetter level,
            BlockPos position, FluidRenderer.Output output, BlockState state, FluidState fluid,
            Operation<Void> original) {
        D3D12RtModelSupport.recordFluid(renderer, level, position, state, fluid);
        original.call(renderer, level, position, output, state, fluid);
    }

    @WrapOperation(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/fluid/CustomFluidRenderer;renderFluid(Lnet/minecraft/client/renderer/block/FluidRenderer;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean dxr_mc$captureCustomFluidSupport(CustomFluidRenderer custom, FluidRenderer renderer,
            FluidState fluid, BlockAndTintGetter level, BlockPos position, FluidRenderer.Output output,
            BlockState state, Operation<Boolean> original) {
        D3D12RtModelSupport.recordFluid();
        return original.call(custom, renderer, fluid, level, position, output, state);
    }
}
