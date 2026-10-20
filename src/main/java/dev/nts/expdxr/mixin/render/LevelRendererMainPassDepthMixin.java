package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12TAA;
import dev.nts.expdxr.dxrmc.nvidia.dlss.DeepLearningSuperSampling;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P108x（用户 m11686）：DLSS **深度快照**的挂点 —— 本帧世界几何刚画完、任何一次
 * `clearDepthTexture` 之前的那一刻。
 *
 * <p>反编译证据（`javap -p -c` 26.2 的 client jar，完整转储在
 * `_port\javap_gamerenderer.txt` / `_port\javap_levelrenderer.txt`）：
 *
 * <p>26.2 的关卡渲染是**帧图**：`LevelRenderer.render` 只 add pass、`frame.execute(…)` 时才真正录制。
 * 清主目标深度一共三处，按执行序：
 * <ol>
 *   <li>`lambda$addAlwaysOnTopPass$0` 的 INVOKE 偏移 <b>52</b>（源码
 *       `_port\mcsrc\net\minecraft\client\renderer\LevelRenderer.java:571`）；</li>
 *   <li>`GameRenderer.renderLevel` 的 INVOKE 偏移 <b>499</b>（源码
 *       `_port\mcsrc\net\minecraft\client\renderer\GameRenderer.java:578`，手部之前）；</li>
 *   <li>`GameRenderer.render(DeltaTracker, boolean)` 的 INVOKE 偏移 <b>356</b>（源码
 *       `...GameRenderer.java:443`，`GuiRenderer.render()` 在偏移 387）。</li>
 * </ol>
 *
 * <p>`GameRendererSmaaWorldMixin` 的两个 `@Inject` 都落在 (3) 那个方法里，所以"清深度之前"的旧挂点
 * 其实已经排在第 (1)(2) 次之后 —— 实测 `DXRMC_P108X_DUMP=1` 的
 * `P108x: dump jvm-depth-before-snapshot` 在那里恒为 `mean=0.0/0.0/0.0/0.0`。
 *
 * <p>**挂点选择的实测过程**（都是 native `DXRMC_P108X_DUMP=1` 的一次性读数，
 * `_port\p108x_native_*.log`）：
 * <ul>
 *   <li>always_on_top 的 pass 体开头：**一次都没跑到** —— 该 pass 只在
 *       `FeatureRenderDispatcher.PreparedFrame.hasAnyAlwaysOnTop()` 为真时才被
 *       `addAlwaysOnTopPass` 加进帧图（源码 :557），实测那一趟为假，深度快照一次都没发生。</li>
 *   <li>`GameRenderer.renderLevel` 的 RETURN（源码 :597，帧图已经执行完）：读到
 *       `1920x1000 fmt=40 mean=0.0/0.0/0.0/0.0` —— 世界深度已经没了。</li>
 *   <li>**这里**（帧图 main pass 的 RETURN）：读到 `640x360 fmt=40
 *       mean=14.6/16.4/13.5/6.9 max=255/254/255/57` 与
 *       `1920x1000` 同量级的非零值 —— 主目标深度里正是本帧的世界几何。</li>
 * </ul>
 *
 * <p>帧图执行序：clear（清色+深度）→ sky → **main（不透明 + 实体 + 半透明 + 粒子）** →
 * clouds → weather → 透明链 → always_on_top（若加了：清深度 + 实体置顶）→ 手部（清深度 + 手）→
 * `GameRenderer.render` 清深度 → GUI。挂在 main pass 的 **RETURN**（`lambda$addMainPass$0` 唯一的
 * `return` 在偏移 327，紧跟 `featureFrame.executeTranslucentAfterTerrain()`）因此同时满足：
 * 世界几何已画完、且排在所有清深度之前；`frame.addPass("main")` 在 `LevelRenderer.addMainPass`
 * 源码 :394 是**无条件**执行的，不像 always_on_top 有前置条件。
 *
 * <p>P76c（用户 m07942「走动时仍然有拖影，只有dlss开才会带来拖影」）：**运动矢量的速度 pass 也挪到这里**。
 * 原先它挂在 `GameRendererSmaaWorldMixin` 的 `dxr_mc$dlssBeforeDepthClear`，而那处按上面的执行序已经排在
 * (1)(2) 两次清深度**之后** —— 实测 `mean=0.0/0.0/0.0/0.0`，即速度 pass 拿到的深度**整张是 0**。
 * `D3D12Velocity` 的像素着色器对 `depth <= 0.0 || depth >= 1.0` 直接 `return float4(0,0,0,0)`，
 * 于是运动矢量纹理**恒为零**，DLSS 只能把整帧当静止画面做时间累积 —— 拖影只随运动出现、只在 DLSS
 * 开启时出现（此前所有 mv 侧的改动因此都是空转）。
 * 深度快照与速度 pass 现在共用这一个挂点：世界几何已画完（深度有效）、且排在所有清深度之前。
 *
 * <p>`require = 1`（`dxr_mc.mixins.json` 的 `defaultRequire`）保证：这个 lambda 名字一旦随版本
 * 变化而找不到，启动就会**直接失败**，不会静默失效。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMainPassDepthMixin {
    /**
     * {@code addMainPass} 的 pass 体（javac 合成方法 {@code lambda$addMainPass$0}）的 RETURN。
     *
     * <p>先录速度 pass（吃这一刻的主目标深度），再交给 native 做深度快照 —— 两者读的是同一份资源，
     * 顺序只是为了日志里"速度 pass 先有读数"。
     */
    @Inject(method = "lambda$addMainPass$0", at = @At("RETURN"), remap = false)
    private void dxr_mc$dlssDepthSnapshotAfterMainPass(CallbackInfo ci) {
        // P77：只开 TAA（DLSS 关着）时运动矢量要从这里补录 —— captureMotionVectors 在 DLSS 关闭时
        // 第一行就返回，不补这一行 TAA 就永远拿不到运动矢量。
        D3D12TAA.captureTemporalData();
        DeepLearningSuperSampling.captureMotionVectors();
        DeepLearningSuperSampling.captureDepthSnapshot();
    }
}