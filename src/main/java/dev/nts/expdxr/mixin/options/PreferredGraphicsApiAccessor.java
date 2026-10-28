package dev.nts.expdxr.mixin.options;

import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 造一个 {@code PreferredGraphicsApi} 枚举常量。
 *
 * <p>AT 救不了这条：javac 语言层禁止 {@code new} 枚举，{@code Constructor.newInstance} 对枚举直接抛异常。
 * 方法体只会在忘登记 dxr_mc.mixins.json 时被执行到。见 docs/misc/mixins-and-earlywindow.md §2.3</p>
 */
@Mixin(PreferredGraphicsApi.class)
public interface PreferredGraphicsApiAccessor {
    @Invoker("<init>")
    static PreferredGraphicsApi dxr_mc$create(String enumName, int ordinal, String serializedName, String captionKey) {
        throw new AssertionError("Mixin invoker was not transformed");
    }
}
