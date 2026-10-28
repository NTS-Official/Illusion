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
 * 在原版图形后端枚举尾部（Vulkan 之后）追加 D3D12，使循环按钮变成四项。
 *
 * <p>常量必须用 {@code @Invoker("<init>")} 现造，造完必须重建 {@code CODEC} ——
 * {@code StringRepresentable.fromEnum} 快照的是当时那个数组。未选中 D3D12 时不改原版行为。
 * 见 docs/misc/mixins-and-earlywindow.md §3.7</p>
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {
    @Shadow @Final @Mutable public static Codec<PreferredGraphicsApi> CODEC;

    /** 必须在 {@code <clinit>} TAIL：早于原版赋值会被后续 {@code <clinit>} 覆盖。 */
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

    /** 让 {@code values()} / {@code getEnumConstants()} 看到第四个常量；数组不是原版三个时不碰。 */
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
     * 必须返回**单条目** {@code {D3D12Backend}}：原版选中循环会吞掉 {@code BackendCreationException} 继续试下一个，
     * 多条目就等于 DX12 失败后静默回退 OpenGL。失败改由 {@link D3D12Fatal} 重置设置并崩溃。
     */
    @ModifyReturnValue(method = "getBackendsToTry", at = @At("RETURN"))
    private GpuBackend[] dxr_mc$injectDx12Backend(GpuBackend[] vanilla) {
        if (!D3D12GraphicsApi.isDx12(this)) return vanilla;

        return new GpuBackend[] { new D3D12Backend() };
    }
}