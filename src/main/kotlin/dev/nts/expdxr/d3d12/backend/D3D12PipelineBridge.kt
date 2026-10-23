package dev.nts.expdxr.d3d12.backend

import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import java.util.concurrent.ConcurrentHashMap

/**
 * 26.3：backend 的管线编译入口只收到
 * [com.mojang.renderpearl.backend.api.BackendRenderPipeline.CreateInfo]（已打包的 SPIR-V 模块
 * 加渲染状态），拿不到 vanilla 的 [RenderPipeline] —— 而本后端的 GLSL→HLSL 编译链、
 * `shaderDefines` 展开、mesh 地形变体判定都依赖后者。
 *
 * <p>vanilla 每次编译都会先经过
 * `FrontendGpuDevice.compilePipeline(RenderPipeline, ShaderSource, Executor)`，由
 * `FrontendGpuDeviceCompilePipelineMixin` 在这里登记；`D3D12Device.compilePipeline(CreateInfo)`
 * 再按 `CreateInfo.name()`（= `RenderPipeline.getLocation().toString()`）查回。
 *
 * <p>26.3 的 [ShaderSource] 是**按次传入**的（后端 `createDevice` 已经收不到 defaultShaderSource），
 * 所以源也必须一起登记：本后端编译的是 GLSL 源码而不是 `CreateInfo` 里那份 SPIR-V，
 * 光有 [RenderPipeline] 没有源，`pipeline/gui` 这类真实管线在自检/首帧就会拿到 INVALID。
 *
 * <p>并发：登记发生在渲染线程（或管线预热的 worker），查回发生在 `finishCompile()`，
 * 二者不保证同一线程，故用 [ConcurrentHashMap]。
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
