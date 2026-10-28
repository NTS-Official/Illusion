package dev.nts.expdxr.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.util.StringRepresentable;

/**
 * 运行期往 {@code InactivityFpsLimit} 里补一个 NEVER 常量。
 *
 * <p>构造器描述符必须写满四参 {@code (String,int,String,String)} —— name / ordinal 由 javac 隐式加入。
 * 写字段用普通反射而非 VarHandle。这个类必须在 mixin 包之外：被注入的代码不能引用
 * {@code dev.nts.expdxr.mixin.*}（{@code IllegalClassLoadError}）。见 docs/misc/mixins-and-earlywindow.md §3.9</p>
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
            // 描述符是 (String,int,String,String)：少写就 NoSuchMethodException。
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

    /** 反射写 static 字段：{@code @Mutable} 已去掉 final，VarHandle 那条路会抛 {@code UnsupportedOperationException}。 */
    private static void writeStatic(String name, Object value)
        throws ReflectiveOperationException {
        Field field = InactivityFpsLimit.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
