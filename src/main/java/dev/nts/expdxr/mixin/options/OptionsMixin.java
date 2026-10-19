package dev.nts.expdxr.mixin.options;

import dev.nts.expdxr.d3d12.backend.D3D12GraphicsApi;
import java.util.ArrayList;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Gives the appended Direct3D 12 entry the same "Experimental" treatment vanilla gives
 * Vulkan.
 *
 * <p>Vanilla builds the graphics-API tooltip inline inside {@code Options.<init>} and
 * only appends {@code options.graphicsApi.tooltip.vulkan} when the value is
 * {@code VULKAN}; there is no hook to extend that branch, and {@code Tooltip} exposes no
 * way to read the lines back out of an existing instance. So the supplier handed to the
 * shared {@code OptionInstance} constructor is wrapped: every value other than the DXRMC
 * constant is forwarded untouched, and the DXRMC constant gets a tooltip rebuilt with
 * the same restart-notice / description / experimental-note structure vanilla uses.
 *
 * <p>The injection point is the seven-parameter constructor because that is the overload
 * the graphics options uses. <b>Many other options share it</b>, so the wrapper is deliberately
 * erased (raw {@code TooltipSupplier}, {@code Object} parameter): a {@code TooltipSupplier<T>}
 * lambda would emit a {@code checkcast} on the incoming value and blow up with a
 * {@code ClassCastException} the first time a non-graphics options such as render distance
 * asked for its tooltip.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Final @Shadow private static Component TOOLTIP_NEEDS_RESTART;

    @Final @Shadow private static Component GRAPHICS_API_TOOLTIP;

    @Shadow private PreferredGraphicsApi preferredGraphicsBackendFromStartup;

    @ModifyArg(
            method = "<init>", at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/OptionInstance;" +
                            "<init>(Ljava/lang/String;" +
                            "Lnet/minecraft/client/OptionInstance$TooltipSupplier;" +
                            "Lnet/minecraft/client/OptionInstance$CaptionBasedToString;" +
                            "Lnet/minecraft/client/OptionInstance$ValueSet;" +
                            "Lcom/mojang/serialization/Codec;" +
                            "Ljava/lang/Object;" +
                            "Lnet/minecraft/client/OptionInstance$ValueUpdateListener;)V"),
            index = 1)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private OptionInstance.TooltipSupplier dxr_mc$D3D12Tooltip(OptionInstance.TooltipSupplier original) {
        return (Object value) -> {
            if (!D3D12GraphicsApi.isDx12(value)) return original.apply(value);

            ArrayList<Component> lines = new ArrayList<>();

            if (value != this.preferredGraphicsBackendFromStartup) {
                lines.add(TOOLTIP_NEEDS_RESTART);
                lines.add(CommonComponents.EMPTY);
            }

            lines.add(GRAPHICS_API_TOOLTIP);
            lines.add(CommonComponents.EMPTY);
            lines.add(D3D12GraphicsApi.dx12TooltipLine());

            return Tooltip.create(CommonComponents.joinLines(lines));
        };
    }
}