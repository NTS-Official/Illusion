package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtColorTables;
import net.minecraft.world.level.GrassColor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GrassColor.class)
public abstract class GrassColorPublicationMixin {
    @WrapOperation(method = "get", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/level/GrassColor;pixels:[I", opcode = Opcodes.GETSTATIC))
    private static int[] dxr_mc$read(Operation<int[]> original) {
        return D3D12RtColorTables.read(GrassColor.class, original::call);
    }

    @WrapOperation(method = "init", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/level/GrassColor;pixels:[I", opcode = Opcodes.PUTSTATIC))
    private static void dxr_mc$publish(int[] pixels, Operation<Void> original) {
        D3D12RtColorTables.write(GrassColor.class, pixels, () -> original.call(pixels));
    }
}

