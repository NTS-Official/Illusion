package dev.nts.expdxr.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 必需着色器管线编译失败时，把"静默黑屏"升级成真正的崩溃。
 *
 * <p>原版 {@code ShaderManager.apply} 在必需管线缺失时 `throw new RuntimeException("Failed to load
 * required shader programs: …")`。这个异常发生在资源重载线程上，{@code SimpleReloadInstance}
 * 把它记进日志就结束、渲染线程继续跑；但 {@code apply} 已经把管线缓存丢掉，于是玩家看到的是一整块
 * 黑屏，日志里只有一条埋在 reload 栈里的 stack trace。2026-10-25 实测：21:06:40 报错，之后
 * {@code COMPILE OK} / P106 每帧继续打到 21:07:03 用户手动关窗口 —— 既没崩也没画面。
 *
 * <p>注入点是那句 {@code new RuntimeException(message)}（`@WrapOperation` + `@At("NEW")`），
 * 因为 {@code apply} 的参数类型 `ShaderManager$PendingResults` 是私有内部 record，Java 侧无法在
 * 方法签名里引用它（用 `Object` 会被 Mixin 判为 invalid signature）。这里不改变 vanilla 的抛异常
 * 行为，只在构造出这条异常时额外把崩溃报告交给 {@link Minecraft#delayCrash}：下一帧就是崩溃屏幕，
 * 日志里也留一条带修法的 ERROR。
 *
 * <p>装饰性内容（例如天空方块）应当用 {@code registerOptionalPipeline} 注册，失败只打 WARN，
 * 与本 mixin 无关。
 */
@Mixin(ShaderManager.class)
public class ShaderManagerRequiredFailureMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("dxrmc/pipelines");

    private static final String REQUIRED_PREFIX = "Failed to load required shader programs";

    @WrapOperation(
        method = "apply(Lcom/mojang/renderpearl/api/device/GpuDevice;"
            + "Lnet/minecraft/client/renderer/ShaderManager$PendingResults;)V",
        at = @At(value = "NEW", target = "java/lang/RuntimeException"))
    private static RuntimeException dxr_mc$crashOnRequiredPipelineFailure(String message,
        Operation<RuntimeException> original) {
        RuntimeException thrown = original.call(message);
        if (message != null && message.startsWith(REQUIRED_PREFIX)) {
            LOGGER.error("[dxrmc] 必需渲染管线加载失败，渲染线程已经没有可用的管线缓存（继续跑只会黑屏），"
                + "因此升级为崩溃。最常见的成因是构建产物里缺着色器资源："
                + "删掉 Illusion\\build\\resources\\main 再跑 processResources。\n{}", message);
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                minecraft.delayCrash(CrashReport.forThrowable(thrown, "Loading required render pipelines"));
            }
        }
        return thrown;
    }
}