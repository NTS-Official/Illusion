package dev.nts.expdxr.d3d12.backend

import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import java.util.concurrent.ConcurrentHashMap

/**
 * 管线编译入参桥：backend 只收到 [CreateInfo]（打包好的 SPIR-V + 状态），拿不到 vanilla 的
 * [RenderPipeline] 与按次传入的 [ShaderSource]，而 GLSL→HLSL 链、`shaderDefines` 展开、
 * mesh 地形变体判定都依赖它们 —— 所以先由 `FrontendGpuDeviceCompilePipelineMixin` 登记，
 * `D3D12Device.compilePipeline(CreateInfo)` 再按 `CreateInfo.name()` 查回。
 *
 * 并发：登记在渲染线程/预热 worker，查回在 `finishCompile()`，不保证同线程 → 必须用 [ConcurrentHashMap]。
 */
object D3D12PipelineBridge {
    private class Entry(val pipeline: RenderPipeline, val source: ShaderSource)

    private val entries = ConcurrentHashMap<String, Entry>()

    @JvmStatic
    fun register(pipeline: RenderPipeline, source: ShaderSource) {
        entries[pipeline.location.toString()] = Entry(pipeline, source)
    }

    @JvmStatic
    fun lookup(name: String): RenderPipeline? {
        return entries[name]?.pipeline
    }

    @JvmStatic
    fun lookupSource(name: String): ShaderSource? {
        return entries[name]?.source
    }
}
