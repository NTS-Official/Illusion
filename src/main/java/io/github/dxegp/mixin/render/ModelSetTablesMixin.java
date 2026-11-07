package io.github.dxegp.mixin.render;

import io.github.dxegp.experimental.raytracing.D3D12RtModelTables;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin({BlockStateModelSet.class, FluidStateModelSet.class})
public abstract class ModelSetTablesMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static Map<?, ?> D3D12FreezeModelTable(Map<?, ?> models) {
        return D3D12RtModelTables.freeze(models);
    }
}
