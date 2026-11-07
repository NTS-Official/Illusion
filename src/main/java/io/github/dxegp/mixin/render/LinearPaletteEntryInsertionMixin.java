package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtPaletteEntries;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LinearPalette.class)
public abstract class LinearPaletteEntryInsertionMixin<Value> {
    @WrapMethod(method = "idFor")
    private int dxr_mc$insert(Value value, PaletteResize<Value> resize, Operation<Integer> original) {
        try (var insertion = D3D12RtPaletteEntries.insertion((Palette<?>) (Object) this)) {
            int result = original.call(value, resize);
            insertion.complete();
            return result;
        }
    }

    @WrapOperation(method = "idFor", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, ordinal = 2,
            target = "Lnet/minecraft/world/level/chunk/LinearPalette;values:[Ljava/lang/Object;"))
    private Value[] dxr_mc$beginWrite(LinearPalette<Value> entries, Operation<Value[]> original) {
        D3D12RtPaletteEntries.writeInsertion(entries);
        return original.call(entries);
    }
}
