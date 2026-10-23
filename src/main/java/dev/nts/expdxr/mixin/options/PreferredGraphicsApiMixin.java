package dev.nts.expdxr.mixin.options;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.serialization.Codec;
import dev.nts.expdxr.d3d12.debug.D3D12Fatal;
import dev.nts.expdxr.d3d12.backend.D3D12Backend;
import dev.nts.expdxr.d3d12.backend.D3D12GraphicsApi;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.util.StringRepresentable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    @Shadow @Final @Mutable public static Codec<PreferredGraphicsApi> CODEC;

    /**
     * Runs after vanilla has assigned {@code $VALUES} and {@code CODEC}. The constant is
     * created here, not before, because {@code Component.translatable} in its constructor
     * would otherwise hand {@code install} something a later {@code <clinit>} overwrites.
     */
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void dxr_mc$createDx12Constant(CallbackInfo ci) {
        if (D3D12GraphicsApi.constant() != null) return;

        PreferredGraphicsApi dx12 = PreferredGraphicsApiAccessor.dxr_mc$create(
                D3D12GraphicsApi.enumName(),
                D3D12GraphicsApi.ORDINAL,
                D3D12GraphicsApi.SERIALIZED_NAME,
                D3D12GraphicsApi.CAPTION_KEY);
        D3D12GraphicsApi.install(dx12);
        CODEC = StringRepresentable.fromEnum(PreferredGraphicsApi::values);
        D3D12GraphicsApi.logInstalled(PreferredGraphicsApi.values().length);
    }

    /**
     * Publishes the appended constant through {@code values()} - which is what
     * {@code Options} calls for the cycle button's value set and what
     * {@code Class.getEnumConstants()} reflects over. Left alone if the array is not
     * vanilla's three constants, so a future game version cannot be silently mangled.
     */
    @ModifyReturnValue(method = "values", at = @At("RETURN"))
    private static PreferredGraphicsApi[] dxr_mc$appendDx12Constant(PreferredGraphicsApi[] vanilla) {
        PreferredGraphicsApi d3d12 = D3D12GraphicsApi.constant();
        if (d3d12 == null || vanilla == null || vanilla.length != 3) return vanilla;

        for (PreferredGraphicsApi value : vanilla) {
            if (value == d3d12) {
                return vanilla;
            }
        }

        return new PreferredGraphicsApi[] { vanilla[0], vanilla[1], vanilla[2], d3d12 };
    }

    /**
     * Only the DXRMC constant gets its own backend list; every vanilla value falls
     * through to the original implementation, restore-for-restore.
     *
     * <p>DXRMC deliberately returns a <b>single</b> backend (P73). Minecraft's selection
     * loop swallows {@code BackendCreationException} and moves on to the next entry, so
     * the previous {@code {DX12, GL, Vulkan}} order turned every Direct3D 12 failure
     * into a silent OpenGL launch - the user watched an OpenGL frame while the setting
     * still read "Direct3D 12". With one entry there is nothing left to fall back to;
     * the failure is handled by
     * {@link D3D12Fatal}, which resets
     * {@code preferredGraphicsBackend} to {@code default} and crashes the game with an
     * explanatory crash report.
     */
    @ModifyReturnValue(method = "getBackendsToTry", at = @At("RETURN"))
    private GpuBackend[] dxr_mc$injectDx12Backend(GpuBackend[] vanilla) {
        if (!D3D12GraphicsApi.isDx12(this)) return vanilla;

        return new GpuBackend[] { new D3D12Backend() };
    }
}