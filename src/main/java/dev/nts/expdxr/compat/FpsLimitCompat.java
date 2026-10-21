package dev.nts.expdxr.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.util.StringRepresentable;

/**
 * "Inactivity FPS limit: Never" 的实现核心（Java 版）。
 *
 * <p>原实现来自独立模组 whynotkeepyourfpsup（Fabric + Kotlin mixin，用 ClassTweaker 验证了
 * Kotlin mixin 可行性）。移植到 NeoForge 时改成 Java：本仓库的 mixin 允许 Java，而 Kotlin 在
 * 这里有两处硬伤——{@code @Shadow} 的 static 字段要求 {@code @JvmField}，而 Kotlin 的
 * {@code @JvmField} 必须带初始化器（{@code lateinit} 不允许），初始化器会被并进目标类的
 * {@code <clinit>} 把 CODEC 写成 null。
 *
 * <p>做法与原来一致，只是换成 Java 语法：
 * <ol>
 *   <li>用 {@code privateLookupIn} 拿到 {@code InactivityFpsLimit} 的私有构造器，造一个
 *       "NEVER" 实例（枚举常量在运行期补不出来，只能这样造）；</li>
 *   <li>把它塞进 {@code $VALUES}——{@code privateLookupIn} 的 lookupClass 就是该枚举类，
 *       按 JLS 允许写入本类的 final 字段，所以 {@code findStaticVarHandle} 能写；</li>
 *   <li>把 {@code CODEC} 换成含新值的 {@code fromEnum} 结果（字段本身由
 *       {@code InactivityFpsLimitMixin} 的 {@code @Mutable} 去了 final，这里用反射写）。</li>
 * </ol>
 *
 * <p>这个类必须在 mixin 包之外：Mixin 的 {@code IllegalClassLoadError} 禁止被注入的代码引用
 * {@code dev.nts.expdxr.mixin.*} 下的类。
 */
public final class FpsLimitCompat {
    private static volatile InactivityFpsLimit never;

    private FpsLimitCompat() {
    }

    public static InactivityFpsLimit never() {
        return never;
    }

    public static boolean isNever(InactivityFpsLimit value) {
        return value != null && value == never;
    }

    /** 幂等：Options 每构造一次都会走到，但只需要补一次枚举常量。 */
    public static synchronized void installNever() {
        if (never != null) {
            return;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                InactivityFpsLimit.class, MethodHandles.lookup());
            // 枚举构造器在字节码里是 `(String name, int ordinal, String serializedName, String key)`：
            // 源码上只声明了两个参数，但 javac 会把枚举的 name/ordinal 作为前两个隐式参数加进去
            // （javap 的源码视图不显示它们，-p 看 descriptor 才是
            // `(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V`）。
            // MethodType 必须用这个完整的 4 参描述符，否则 findConstructor 直接抛 NoSuchMethodException。
            MethodHandle ctor = lookup.findConstructor(InactivityFpsLimit.class, MethodType.methodType(
                void.class, String.class, int.class, String.class, String.class));

            InactivityFpsLimit[] current = InactivityFpsLimit.values();
            InactivityFpsLimit created = (InactivityFpsLimit) ctor.invoke(
                "NEVER", current.length, "never", "options.inactivityFpsLimit.never");

            InactivityFpsLimit[] extended = Arrays.copyOf(current, current.length + 1);
            extended[current.length] = created;
            writeStatic("$VALUES", extended);

            never = created;
            writeStatic("CODEC", StringRepresentable.fromEnum(() -> extended));
        } catch (Throwable t) {
            throw new IllegalStateException("dxr_mc: failed to install InactivityFpsLimit.NEVER", t);
        }
    }

    /**
     * 写目标类的 static 字段。
     *
     * <p>字段的 ACC_FINAL 由 {@code InactivityFpsLimitMixin} 的 {@code @Mutable} 去掉，
     * 因此这里用普通反射即可 —— VarHandle 那条路会抛
     * {@code UnsupportedOperationException: set}（final 字段不可写）。
     */
    private static void writeStatic(String name, Object value)
        throws ReflectiveOperationException {
        Field field = InactivityFpsLimit.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
