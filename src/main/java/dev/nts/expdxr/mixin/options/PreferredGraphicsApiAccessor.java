package dev.nts.expdxr.mixin.options;

import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code PreferredGraphicsApi} 的枚举构造器是包私有的。
 *
 * <p>这一条**不能**用 access transformer 代替，两条路都堵死：
 * <ul>
 *   <li>javac 在语言层面禁止 {@code new} 一个枚举（{@code 错误: 无法实例化枚举类}），
 *       即使 AT 把构造器放宽成 {@code public} 也一样编译不过；</li>
 *   <li>{@link java.lang.reflect.Constructor#newInstance} 对枚举直接抛
 *       {@code IllegalArgumentException: Cannot reflectively create enum objects}。</li>
 * </ul>
 * 所以这里保留 {@code @Invoker("<init>")} —— 由 Mixin 在转换期生成真实调用，
 * 方法体只在"忘了登记到 dxr_mc.mixins.json"时才会被执行到。
 *
 * <p>其余两个纯放宽访问的 interface（{@code OptionsAccessor}、{@code OptionsSubScreenAccessor}）
 * 已改成 {@code META-INF/accesstransformer.cfg} 里的条目。
 */
@Mixin(PreferredGraphicsApi.class)
public interface PreferredGraphicsApiAccessor {
    @Invoker("<init>")
    static PreferredGraphicsApi dxr_mc$create(String enumName, int ordinal, String serializedName, String captionKey) {
        throw new AssertionError("Mixin invoker was not transformed");
    }
}
