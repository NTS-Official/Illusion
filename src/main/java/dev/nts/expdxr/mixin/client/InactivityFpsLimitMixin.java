package dev.nts.expdxr.mixin.client;

import com.mojang.serialization.Codec;
import net.minecraft.client.InactivityFpsLimit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 让 {@code InactivityFpsLimit.CODEC} 与 {@code $VALUES} 可写，供 {@code FpsLimitCompat} 插入 NEVER。
 *
 * <p>必须是 Java：{@code @Shadow} 的 static 字段在 Kotlin 里只能靠 {@code @JvmField}，带初始化器会被并进
 * 目标类的 {@code <clinit>} 把 CODEC 覆盖成 null。见 docs/misc/mixins-and-earlywindow.md §3.9</p>
 */
@Mixin(InactivityFpsLimit.class)
public class InactivityFpsLimitMixin {
    @Mutable
    @Final
    @Shadow
    private static Codec<InactivityFpsLimit> CODEC;

    /** 不改它写不进 NEVER：VarHandle.set 会抛 UnsupportedOperationException。 */
    @Mutable
    @Final
    @Shadow
    private static InactivityFpsLimit[] $VALUES;
}
