package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtIdMaps;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import net.minecraft.core.IdMapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(IdMapper.class)
public abstract class IdMapperPublicationMixin<Value> {
    @WrapMethod(method = "addMapping")
    private void dxr_mc$write(Value value, int id, Operation<Void> original) {
        try (var write = D3D12RtIdMaps.writing(this, false)) {
            original.call(value, id);
            write.complete();
        }
    }

    @WrapOperation(method = "addMapping", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;put:(Ljava/lang/Object;I)I"))
    private int dxr_mc$reverse(Reference2IntMap<Value> ids, Value value, int id, Operation<Integer> original) {
        return D3D12RtIdMaps.writeReverse(this, value, id, () -> original.call(ids, value, id));
    }

    @WrapOperation(method = "addMapping", at = @At(value = "INVOKE",
            target = "Ljava/util/List;add:(Ljava/lang/Object;)Z"))
    private boolean dxr_mc$grow(List<Value> values, Value value, Operation<Boolean> original) {
        return D3D12RtIdMaps.append(this, value, () -> original.call(values, value));
    }

    @WrapOperation(method = "addMapping", at = @At(value = "INVOKE",
            target = "Ljava/util/List;set:(ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object dxr_mc$slot(List<Value> values, int id, Value value, Operation<Value> original) {
        return D3D12RtIdMaps.writeSlot(this, id, value, () -> original.call(values, id, value));
    }
}
