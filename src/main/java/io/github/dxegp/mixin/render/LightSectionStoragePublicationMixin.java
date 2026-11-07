package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry;
import io.github.dxegp.experimental.raytracing.D3D12RtModelSupport;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LayerLightSectionStorage.class)
public abstract class LightSectionStoragePublicationMixin {
    @Shadow
    @Final
    protected Long2ObjectMap<DataLayer> queuedSections;

    @Inject(method = "getDataLayerData(J)Lnet/minecraft/world/level/chunk/DataLayer;", at = @At("HEAD"))
    private void dxr_mc$recordLayerRead(long sectionNode, CallbackInfoReturnable<DataLayer> callback) {
        D3D12RtModelSupport.recordLightLayerRead((LayerLightSectionStorage<?>) (Object) this, sectionNode);
    }

    @Inject(method = "<init>", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;queuedSections:Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"))
    private void dxr_mc$ownQueue(CallbackInfo callback) {
        D3D12SectionGeometry.ownLightQueue((LayerLightSectionStorage<?>) (Object) this, queuedSections);
    }

    @WrapOperation(method = {"queueSectionData", "markNewInconsistencies"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;"))
    private Object dxr_mc$putQueue(Long2ObjectMap<DataLayer> queue, long sectionNode, Object layer,
                                  Operation<Object> original) {
        try (var publication = D3D12SectionGeometry.beginLightQueueMutation((LayerLightSectionStorage<?>) (Object) this, sectionNode)) {
            Object result = original.call(queue, sectionNode, layer);
            if (publication != null) {
                publication.complete();
            }
            return result;
        }
    }

    @WrapOperation(method = {"queueSectionData", "markNewInconsistencies"}, at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;remove(J)Ljava/lang/Object;"))
    private Object dxr_mc$removeQueue(Long2ObjectMap<DataLayer> queue, long sectionNode, Operation<Object> original) {
        try (var publication = D3D12SectionGeometry.beginLightQueueMutation((LayerLightSectionStorage<?>) (Object) this, sectionNode)) {
            Object result = original.call(queue, sectionNode);
            if (publication != null) {
                publication.complete();
            }
            return result;
        }
    }

    @WrapOperation(method = "markNewInconsistencies", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectIterator;remove()V"))
    private void dxr_mc$consumeQueue(ObjectIterator<?> iterator, Operation<Void> original, @Local(index = 4) long sectionNode) {
        try (var publication = D3D12SectionGeometry.beginLightQueueMutation((LayerLightSectionStorage<?>) (Object) this, sectionNode)) {
            original.call(iterator);
            if (publication != null) {
                publication.complete();
            }
        }
    }

    @WrapOperation(method = "swapSectionMap", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;visibleSectionData:Lnet/minecraft/world/level/lighting/DataLayerStorageMap;"))
    private void dxr_mc$publishLightMap(LayerLightSectionStorage<?> storage, DataLayerStorageMap<?> next,
                                       Operation<Void> original) {
        D3D12SectionGeometry.publishLightMap(storage, next, () -> original.call(storage, next));
    }
}
