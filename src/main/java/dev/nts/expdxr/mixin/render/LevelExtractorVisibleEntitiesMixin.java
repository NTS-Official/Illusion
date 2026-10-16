package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.render.advanced.taa.D3D12ObjectMotion;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * P76b（用户 m05600）：「路径 B」——逐物体运动快照的**可见实体**采集点。
 *
 * <p>为什么需要它：{@code RegisterRenderStateModifiersEvent}（NeoForge 公共 API）在实机上实测
 * <b>一次回调都没来</b>（{@code fired=0}），而速度 pass 的 GPU 管线本身是好的。该 API 的注册链路
 * 已逐行核对过（{@code RenderStateExtensions.ENTITY} + {@code ENTITY_CACHE} 的
 * {@code isAssignableFrom} 反向查找，{@code EntityRenderer.class} 作为键在原理上能覆盖全部
 * renderer），所以拿不到回调的成因要么是事件时序、要么是那个测试场景里确实没有可见实体 ——
 * 两者都不该继续靠猜。这个 mixin 绕开整条回调链路，直接在原版的可见实体遍历里采集。
 *
 * <p><b>挂点必须在 {@code isEntityVisible} 之后。</b>第一版把 {@code @Inject} 放在
 * {@code extractVisibleEntities} 的 HEAD，结果拿到的
 * {@code ClientLevel.entitiesForRendering()} 是<b>整个客户端世界</b>的实体表（实测 300+ 个），
 * 而真正会被渲染的只有通过 {@code isEntityVisible} 的那几个（实测 1 个）。那样会把它当成
 * 300 个"可见物体"写进速度缓冲，既超容量又让着色器用错误的 AABB 覆盖大量像素。
 *
 * <p>所以改成注入到 {@code this.extractEntity(entity, partialEntity)} 那一行的调用点：此刻
 * {@code entity} 已经通过可见性过滤、{@code partialEntity} 是原版刚按
 * {@code tickRateManager.isEntityFrozen(entity)} 算出来的那个值。每个可见实体调一次。
 *
 * <p>{@code require = 1}（{@code dxr_mc.mixins.json} 的 {@code defaultRequire}）保证方法名或
 * 局部变量表一旦随版本变化，启动就<b>直接失败</b>，不会静默失效。
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorVisibleEntitiesMixin {

    // P76b：挂点在 extractEntity 的 HEAD —— 它只被 extractVisibleEntities 在通过可见性判断后调用
    // （LevelExtractor.java:251），所以语义与原挂点一致，但处理器只要声明目标方法自己的两个参数，
    // 不需要 LocalCapture（LocalCapture 按 LocalVariableTable 顺序逐个映射，漏一个就会把
    // cameraPos(Vec3) 当成 Entity 静默塞进 harvest）。
    @Inject(
        method = "extractEntity",
        at = @At("HEAD"),
        remap = false
    )
    private void dxr_mc$harvestVisibleEntity(Entity entity, float partialTickTime, CallbackInfo ci) {
        D3D12ObjectMotion.harvest(entity, partialTickTime);
    }
}
