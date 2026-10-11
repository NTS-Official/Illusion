package dev.nts.expdxr.mixin;

import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.serialization.Codec;
import dev.nts.expdxr.dx12.Dx12Backend;
import dev.nts.expdxr.dx12.Dx12GraphicsApi;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.util.StringRepresentable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns vanilla's three-state graphics API cycle (Default / OpenGL / Vulkan) into a
 * four-state one, with Direct3D 12 appended last - i.e. immediately after Vulkan, so
 * the button reads Default - OpenGL - Vulkan - Direct3D 12.
 *
 * <p>An earlier revision of this class injected DX12 into the DEFAULT and OPENGL
 * branches of {@code getBackendsToTry}, which made Direct3D 12 the de-facto default.
 * That is deliberately gone: {@code getBackendsToTry} is now left exactly as vanilla
 * wrote it unless the selected value is the DXRMC constant, so DEFAULT and OPENGL both
 * keep the stock OpenGL-first order and DX12 is only used when the user picks it.
 *
 * <p>The constant is built rather than declared because the mod compiles against the
 * same enum the game ships, whose {@code $VALUES} array is already baked into
 * {@code <clinit>}. Appending through the constructor invoker means {@code values()}
 * reports four constants, {@code Class.getEnumConstants()} agrees, and {@code CODEC}
 * is rebuilt because {@link StringRepresentable#fromEnum} snapshots the array it is
 * handed instead of reading it lazily.
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {
    /** Rebuilt from {@code values()} so the appended constant can be written to options.txt. */
    @Shadow
    @Final
    @Mutable
    private static Codec<PreferredGraphicsApi> CODEC;

    /**
     * Runs after vanilla has assigned {@code $VALUES} and {@code CODEC}. The constant is
     * created here, not before, because {@code Component.translatable} in its constructor
     * would otherwise hand {@code install} something a later {@code <clinit>} overwrites.
     */
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void dxr_mc$createDx12Constant(CallbackInfo ci) {
        if (Dx12GraphicsApi.constant() != null) {
            return;
        }
        PreferredGraphicsApi dx12 = PreferredGraphicsApiAccessor.dxr_mc$create(
                Dx12GraphicsApi.enumName(),
                Dx12GraphicsApi.ORDINAL,
                Dx12GraphicsApi.SERIALIZED_NAME,
                Dx12GraphicsApi.CAPTION_KEY);
        Dx12GraphicsApi.install(dx12);
        CODEC = StringRepresentable.fromEnum(PreferredGraphicsApi::values);
        Dx12GraphicsApi.logInstalled(PreferredGraphicsApi.values().length);
    }

    /**
     * Publishes the appended constant through {@code values()} - which is what
     * {@code Options} calls for the cycle button's value set and what
     * {@code Class.getEnumConstants()} reflects over. Left alone if the array is not
     * vanilla's three constants, so a future game version cannot be silently mangled.
     */
    @Inject(method = "values", at = @At("RETURN"), cancellable = true)
    private static void dxr_mc$appendDx12Constant(CallbackInfoReturnable<PreferredGraphicsApi[]> cir) {
        PreferredGraphicsApi dx12 = Dx12GraphicsApi.constant();
        PreferredGraphicsApi[] vanilla = cir.getReturnValue();
        if (dx12 == null || vanilla == null || vanilla.length != 3) {
            return;
        }
        for (PreferredGraphicsApi value : vanilla) {
            if (value == dx12) {
                return;
            }
        }
        cir.setReturnValue(new PreferredGraphicsApi[] { vanilla[0], vanilla[1], vanilla[2], dx12 });
    }

    /**
     * Only the DXRMC constant gets its own backend order; every vanilla value falls
     * through to the original implementation, restore-for-restore.
     */
    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void dxr_mc$injectDx12Backend(CallbackInfoReturnable<GpuBackend[]> cir) {
        if (!Dx12GraphicsApi.isDx12((Object) this)) {
            return;
        }
        cir.setReturnValue(new GpuBackend[] { new Dx12Backend(), new GlBackend(), new VulkanBackend() });
    }
}