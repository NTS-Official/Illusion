package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(PalettedContainer.class)
public abstract class PaletteStoragePublicationMixin {
    @WrapOperation(method = {"read", "onResize"}, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/level/chunk/PalettedContainer;data:Lnet/minecraft/world/level/chunk/PalettedContainer$Data;"))
    private void dxr_mc$publishStorage(PalettedContainer<?> palette, @Coerce Object data, Operation<Void> original) {
        D3D12SectionGeometry.publishPaletteStorage(palette, () -> original.call(palette, data));
    }
}
