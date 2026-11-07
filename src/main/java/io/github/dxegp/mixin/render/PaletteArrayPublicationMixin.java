package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.mojang.serialization.DataResult;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtPaletteArrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.stream.LongStream;

@Mixin(PalettedContainer.class)
public abstract class PaletteArrayPublicationMixin<Value> {
    @WrapMethod(method = "pack")
    private PalettedContainerRO.PackedData<Value> dxr_mc$pack(Strategy<Value> strategy,
            Operation<PalettedContainerRO.PackedData<Value>> original) {
        var pack = D3D12SectionGeometry.beginPaletteEncode((PalettedContainer<?>) (Object) this, strategy);
        var result = original.call(strategy);
        D3D12SectionGeometry.finishPalettePack(pack, result);
        return result;
    }

    @WrapMethod(method = "unpack")
    private static <Value> DataResult<PalettedContainer<Value>> dxr_mc$unpack(Strategy<Value> strategy,
            PalettedContainerRO.PackedData<Value> packed, Operation<DataResult<PalettedContainer<Value>>> original) {
        var copy = D3D12SectionGeometry.beginPaletteUnpack(strategy);
        var result = original.call(strategy, packed);
        result.result().ifPresent(palette -> D3D12SectionGeometry.finishPaletteDecode(copy, packed, palette));
        return result;
    }

    @WrapOperation(method = "onResize", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainer$Data;copyFrom:(Lnet/minecraft/world/level/chunk/Palette;Lnet/minecraft/util/BitStorage;)V"))
    private void dxr_mc$copyData(@Coerce Object data, Palette<?> palette, BitStorage storage, Operation<Void> original) {
        var copy = D3D12SectionGeometry.beginPaletteDataCopy(data, palette, storage);
        original.call(data, palette, storage);
        D3D12SectionGeometry.finishPaletteDataCopy(copy, data);
    }

    @WrapOperation(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/BitStorage;getRaw:()[J"))
    private long[] dxr_mc$borrowDecode(BitStorage storage, Operation<long[]> original) {
        try (var access = D3D12RtPaletteArrays.borrow(storage)) {
            return original.call(storage);
        }
    }

    @WrapOperation(method = "read", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;readFixedSizeLongArray:([J)[J"))
    private long[] dxr_mc$decodeArray(FriendlyByteBuf buffer, long[] array, Operation<long[]> original) {
        try (var publication = D3D12SectionGeometry.beginPaletteArrayWrite(array, true)) {
            long[] result = original.call(buffer, array);
            publication.complete();
            return result;
        }
    }

    @WrapOperation(method = "unpack", at = @At(value = "INVOKE", target = "Ljava/util/stream/LongStream;toArray:()[J"))
    private static long[] dxr_mc$unpackArray(LongStream stream, Operation<long[]> original) {
        long[] result = original.call(stream);
        D3D12RtPaletteArrays.unpacked(stream, result);
        return result;
    }
}
