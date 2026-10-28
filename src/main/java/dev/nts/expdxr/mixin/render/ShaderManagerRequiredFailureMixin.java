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
 * <p>原版那条 `RuntimeException` 发生在资源重载线程上，被 `SimpleReloadInstance` 记进日志就结束、
 * 渲染线程继续跑；但 `apply` 已经把管线缓存丢掉，玩家看到的是一整块黑屏。
 *
 * <p>必须包 `NEW` 而不是改方法签名：`apply` 的参数类型 `ShaderManager$PendingResults` 是私有内部
 * record，Java 侧写不出来（用 `Object` 会被 Mixin 判为 invalid signature）。
 * 见 docs/misc/mixins-and-earlywindow.md §3.7
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
            LOGGER.error("A required render pipeline failed to load and no pipeline cache is left for the render thread, which can only end in a black screen, so this is escalated to a crash."
                + " The usual cause is that the build output is missing its shader resources:"
                + " delete Illusion\\build\\resources\\main and re-run processResources.\n{}", message);
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                minecraft.delayCrash(CrashReport.forThrowable(thrown, "Loading required render pipelines"));
            }
        }
        return thrown;
    }
}