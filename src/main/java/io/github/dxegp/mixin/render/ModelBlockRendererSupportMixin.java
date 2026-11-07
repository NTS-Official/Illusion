package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport;
import io.github.dxegp.experimental.raytracing.D3D12RtBlockExtensions;
import io.github.dxegp.experimental.raytracing.D3D12RtBlockTintInputs;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererSupportMixin {
    @Shadow
    @Final
    private BlockColors blockColors;

    @Shadow
    private boolean tintSourcesInitialized;

    @Unique
    private List<BlockTintSource> dxr_mc$tintInputs;

    @Unique
    private BlockState dxr_mc$tintState;

    @Unique
    private boolean dxr_mc$dynamicTintKnown;

    @Unique
    private boolean dxr_mc$dynamicTintUsed;

    @Unique
    private D3D12RtBlockExtensions.Input dxr_mc$dynamicTintInput;

    @Unique
    private Int2ObjectOpenHashMap<D3D12RtBlockTintInputs.Input> dxr_mc$staticTintInputs;

    @Unique
    private int dxr_mc$currentTintIndex;

    @WrapMethod(method = "getTintColor")
    private int dxr_mc$checkTintCache(BlockAndTintGetter level, BlockState state, BlockPos position,
            int index, Operation<Integer> original) {
        if (!tintSourcesInitialized) {
            dxr_mc$tintInputs = null;
            dxr_mc$dynamicTintKnown = true;
            dxr_mc$dynamicTintUsed = false;
            dxr_mc$dynamicTintInput = null;
            if (dxr_mc$staticTintInputs == null) dxr_mc$staticTintInputs = new Int2ObjectOpenHashMap<>();
            else dxr_mc$staticTintInputs.clear();
        }
        int previousIndex = dxr_mc$currentTintIndex;
        dxr_mc$currentTintIndex = index;
        try {
            return original.call(level, state, position, index);
        } finally {
            D3D12SectionGeometry.recordBlockTintSlot(blockColors, state,
                    dxr_mc$tintState == state ? dxr_mc$tintInputs : null, index, dxr_mc$dynamicTintKnown);
            if (dxr_mc$dynamicTintUsed) {
                D3D12SectionGeometry.recordBlockTintExtensionInput(blockColors, state, dxr_mc$dynamicTintInput);
            }
            D3D12SectionGeometry.recordBlockTintInput(state, dxr_mc$tintInputs, index, level, position,
                    dxr_mc$staticTintInputs == null ? null : dxr_mc$staticTintInputs.get(index));
            dxr_mc$currentTintIndex = previousIndex;
        }
    }

    @WrapOperation(method = "configureTintCache", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/color/block/BlockColors;getTintSources(Lnet/minecraft/world/level/block/state/BlockState;)Ljava/util/List;"))
    private List<BlockTintSource> dxr_mc$captureTintInputs(BlockColors colors, BlockState state,
            Operation<List<BlockTintSource>> original) {
        dxr_mc$tintState = state;
        dxr_mc$tintInputs = original.call(colors, state);
        return dxr_mc$tintInputs;
    }

    @WrapOperation(method = "computeTintColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/color/block/BlockTintSource;colorInWorld(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
    private int dxr_mc$checkTintSource(BlockTintSource source, BlockState state, BlockAndTintGetter level,
            BlockPos position, Operation<Integer> original) {
        D3D12SectionGeometry.recordBlockTintSource(blockColors, source);
        var calculation = D3D12SectionGeometry.beginBlockTintSource(source, state, level, position);
        boolean completed = false;
        try {
            int value = original.call(source, state, level, position);
            completed = true;
            return value;
        } finally {
            var input = D3D12SectionGeometry.finishBlockTintSource(calculation, completed);
            if (dxr_mc$staticTintInputs != null) dxr_mc$staticTintInputs.put(dxr_mc$currentTintIndex, input);
        }
    }

    @WrapOperation(method = "computeTintColor", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/extensions/common/IClientBlockExtensions;collectDynamicTintValues(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lit/unimi/dsi/fastutil/ints/IntList;)V", remap = false))
    private void dxr_mc$checkDynamicTint(IClientBlockExtensions extension, BlockState state,
            BlockAndTintGetter level, BlockPos position, IntList output, Operation<Void> original) {
        dxr_mc$dynamicTintKnown = extension == IClientBlockExtensions.DEFAULT;
        dxr_mc$dynamicTintUsed = true;
        dxr_mc$dynamicTintInput = D3D12SectionGeometry.recordBlockTintExtension(blockColors, state, extension);
        if (!dxr_mc$dynamicTintKnown) D3D12SectionGeometry.recordBlockTintSource(blockColors, null);
        original.call(extension, state, level, position, output);
    }

    @WrapOperation(method = "tesselateBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getOffset(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 dxr_mc$capturePositionOffset(BlockState state, BlockPos position, Operation<Vec3> original) {
        Vec3 offset = original.call(state, position);
        D3D12RtModelSupport.recordOffset(offset);
        return offset;
    }
}
