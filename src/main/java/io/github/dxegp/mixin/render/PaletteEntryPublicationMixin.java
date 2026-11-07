package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.spongepowered.asm.mixin.Mixin;

@Mixin({SingleValuePalette.class, LinearPalette.class, HashMapPalette.class})
public abstract class PaletteEntryPublicationMixin<Value> {
    @WrapMethod(method = "read")
    private void dxr_mc$read(FriendlyByteBuf buffer, IdMap<Value> registry, Operation<Void> original) {
        try (var publication = D3D12SectionGeometry.beginPaletteEntryDecode((Palette<?>) (Object) this, registry)) {
            original.call(buffer, registry);
            publication.complete();
        }
    }

    @WrapMethod(method = "copy")
    private Palette<Value> dxr_mc$copy(Operation<Palette<Value>> original) {
        var copy = D3D12SectionGeometry.beginPaletteEntryCopy((Palette<?>) (Object) this);
        Palette<Value> result = original.call();
        D3D12SectionGeometry.finishPaletteEntryCopy(copy, result);
        return result;
    }
}
