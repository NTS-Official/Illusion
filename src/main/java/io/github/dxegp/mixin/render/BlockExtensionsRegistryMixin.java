package io.github.dxegp.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.dxegp.experimental.raytracing.D3D12RtBlockExtensions;
import java.util.Map;
import net.neoforged.neoforge.client.extensions.common.ClientExtensionsManager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ClientExtensionsManager.class, remap = false)
public abstract class BlockExtensionsRegistryMixin {
    @Shadow
    @Final
    @Mutable
    private static Map<Object, Object> BLOCK_EXTENSIONS;

    @WrapOperation(method = "<clinit>", at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
            target = "Lnet/neoforged/neoforge/client/extensions/common/ClientExtensionsManager;BLOCK_EXTENSIONS:Ljava/util/Map;"))
    private static void dxr_mc$ownBlockExtensions(Map<Object, Object> extensions, Operation<Void> original) {
        original.call(D3D12RtBlockExtensions.ownRegistry(extensions));
    }
}
