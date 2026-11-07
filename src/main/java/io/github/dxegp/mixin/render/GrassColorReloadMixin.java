package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtColorTables;
import net.minecraft.client.resources.GrassColorReloadListener;
import net.minecraft.world.level.GrassColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GrassColorReloadListener.class)
public abstract class GrassColorReloadMixin {
    @WrapOperation(method = "apply([ILnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/GrassColor;init([I)V"))
    private void dxr_mc$publish(int[] pixels, Operation<Void> original) {
        D3D12RtColorTables.reload(GrassColor.class, pixels, snapshot -> original.call(snapshot));
    }
}

