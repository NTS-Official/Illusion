package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SingleValuePalette.class)
public abstract class SinglePaletteEntryInsertionMixin<Value> {
    @WrapOperation(method = "idFor", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/level/chunk/SingleValuePalette;value:Ljava/lang/Object;"))
    private void dxr_mc$write(SingleValuePalette<Value> entries, Value value, Operation<Void> original) {
        D3D12SectionGeometry.publishSinglePaletteEntry(entries, value, () -> original.call(entries, value));
    }
}
