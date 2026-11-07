package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtIdMaps;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Iterator;

@Mixin(MappedRegistry.class)
public abstract class RegistryIdMapPublicationMixin<Value> {
    @WrapMethod(method = "register(ILnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder$Reference;")
    private Holder.Reference<Value> dxr_mc$register(int id, ResourceKey<Value> key, Value value,
            RegistrationInfo info, Operation<Holder.Reference<Value>> original) {
        try (var write = D3D12RtIdMaps.writing(this, false)) {
            Holder.Reference<Value> result = original.call(id, key, value, info);
            write.complete();
            return result;
        }
    }

    @WrapMethod(method = "registerIdMapping")
    private void dxr_mc$map(ResourceKey<Value> key, int id, Operation<Void> original) {
        try (var write = D3D12RtIdMaps.writing(this, false)) {
            original.call(key, id);
            write.complete();
        }
    }

    @WrapMethod(method = "clear")
    private void dxr_mc$clear(boolean full, Operation<Void> original) {
        try (var write = D3D12RtIdMaps.writing(this, true)) {
            original.call(full);
            write.complete();
        }
    }

    @WrapMethod(method = "iterator")
    private Iterator<Value> dxr_mc$iterator(Operation<Iterator<Value>> original) {
        return D3D12RtIdMaps.iterator((MappedRegistry<Value>) (Object) this, original.call());
    }

    @WrapOperation(method = {"register(ILnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder$Reference;", "registerIdMapping"},
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectList;add:(Ljava/lang/Object;)Z"))
    private boolean dxr_mc$append(ObjectList<Holder.Reference<Value>> values, Holder.Reference<Value> value, Operation<Boolean> original) {
        return D3D12RtIdMaps.append(this, value, () -> original.call(values, value));
    }

    @WrapOperation(method = "registerIdMapping", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectList;set:(ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object dxr_mc$slot(ObjectList<Holder.Reference<Value>> values, int id, Holder.Reference<Value> value,
            Operation<Holder.Reference<Value>> original) {
        return D3D12RtIdMaps.writeSlot(this, id, value, () -> original.call(values, id, value));
    }

    @WrapOperation(method = {"register(ILnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder$Reference;", "registerIdMapping"},
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;put:(Ljava/lang/Object;I)I"))
    private int dxr_mc$reverse(Reference2IntMap<Value> ids, Value value, int id, Operation<Integer> original) {
        return D3D12RtIdMaps.writeReverse(this, value, id, () -> original.call(ids, value, id));
    }

    @WrapOperation(method = "clear", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/ObjectList;clear:()V"))
    private void dxr_mc$values(ObjectList<?> values, Operation<Void> original) {
        D3D12RtIdMaps.clear(this, () -> original.call(values));
    }

    @WrapOperation(method = "clear", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;clear:()V"))
    private void dxr_mc$ids(Reference2IntMap<?> ids, Operation<Void> original) {
        D3D12RtIdMaps.clear(this, () -> original.call(ids));
    }
}
