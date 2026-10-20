package dev.nts.expdxr.mixin;

import com.mojang.serialization.Codec;
import net.minecraft.client.InactivityFpsLimit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 把 {@code InactivityFpsLimit.CODEC} 从 {@code static final} 改成可写。
 *
 * <p>为什么需要：{@code StringRepresentable.fromEnum(values)} 在类初始化时固化了枚举常量表，
 * 运行时往枚举里补成员（见 {@code OptionsMixin} 的 NEVER）之后旧 CODEC 不再认新值，必须换成新
 * Codec；而 {@code public static final} 字段用普通反射 {@code Field.set} 会抛
 * {@code IllegalAccessException}，只有 Mixin 的 {@code @Mutable} 能在字节码层去掉 ACC_FINAL。
 *
 * <p>为什么是 Java 而不是 Kotlin（本仓库其它 mixin 已迁到 Kotlin）：一处 {@code @Shadow} 的
 * {@code static} 字段要求 {@code @JvmField}，而 Kotlin 的 {@code @JvmField} 要么带初始化器、
 * 要么（{@code lateinit}）直接不允许——带初始化器会被并进目标类的 {@code <clinit>}，把真正的
 * CODEC 覆盖成 null。Java 声明没有这个中间态。AGENTS.md 亦明确：mixin 允许 Java。
 *
 * <p>赋值入口在 Kotlin 侧：{@code dev.nts.expdxr.mixin.InactivityFpsLimitCodec.set(codec)}。
 */
@Mixin(InactivityFpsLimit.class)
public class InactivityFpsLimitMixin {
    @Mutable
    @Final
    @Shadow
    private static Codec<InactivityFpsLimit> CODEC;

    /** 枚举常量表本身也是 static final；不改它能写不进新的 NEVER 常量（{@code set} 会抛 UnsupportedOperationException）。 */
    @Mutable
    @Final
    @Shadow
    private static InactivityFpsLimit[] $VALUES;
}
