package dev.nts.expdxr.mixin;

import dev.nts.expdxr.compat.FpsLimitCompat;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 {@code Options} 构造时给 {@code InactivityFpsLimit} 补一个 "NEVER" 常量，
 * 并把它挂到原有的 {@code inactivityFpsLimit} 选项上（含专用 tooltip）。
 *
 * <p>Java 版移植自 whynotkeepyourfpsup 的 Kotlin mixin；枚举补值的具体机制见
 * {@link FpsLimitCompat}。
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Unique
    private boolean dxrmc$fpsNeverInstalled;

    @Shadow
    public abstract OptionInstance<InactivityFpsLimit> inactivityFpsLimit();

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/lang/Object;<init>()V", shift = At.Shift.AFTER))
    private void dxrmc$injectNeverEnum(CallbackInfo ci) {
        if (this.dxrmc$fpsNeverInstalled) {
            return;
        }
        this.dxrmc$fpsNeverInstalled = true;
        FpsLimitCompat.installNever();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dxrmc$fixInactivityTooltip(CallbackInfo ci) {
        InactivityFpsLimit never = FpsLimitCompat.never();
        if (never == null) {
            return;
        }
        OptionInstance<InactivityFpsLimit> option = this.inactivityFpsLimit();
        try {
            java.lang.reflect.Field tooltipField = OptionInstance.class.getDeclaredField("tooltip");
            tooltipField.setAccessible(true);
            @SuppressWarnings("unchecked")
            OptionInstance.TooltipSupplier<InactivityFpsLimit> original =
                (OptionInstance.TooltipSupplier<InactivityFpsLimit>) tooltipField.get(option);
            OptionInstance.TooltipSupplier<InactivityFpsLimit> wrapped = value ->
                FpsLimitCompat.isNever(value)
                    ? Tooltip.create(Component.translatable("options.inactivityFpsLimit.never.tooltip"))
                    : original.apply(value);
            tooltipField.set(option, wrapped);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("dxr_mc: failed to patch inactivity fps tooltip", e);
        }
    }
}
