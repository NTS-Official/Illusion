package dev.nts.expdxr.mixin.options;

import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the compiler-generated enum constructor so that
 * {@link PreferredGraphicsApiMixin} can append a fourth constant at runtime.
 *
 * <p>The source-level constructor is {@code PreferredGraphicsApi(String serializedName,
 * String key)}, but at the bytecode level every enum constructor carries the synthetic
 * {@code (String name, int ordinal)} prefix, so the invoker has to declare all four
 * parameters in that exact order.
 */
@Mixin(PreferredGraphicsApi.class)
public interface PreferredGraphicsApiAccessor {
    @Invoker("<init>")
    static PreferredGraphicsApi dxr_mc$create(String name, int ordinal, String serializedName, String key) {
        throw new AssertionError("Mixin invoker was not transformed");
    }
}