package io.github.dxegp.experimental.raytracing

import io.github.dxegp.d3d12.render.advanced.mesh.D3D12SectionGeometry
import net.minecraft.client.renderer.chunk.SectionMesh
import net.minecraft.client.renderer.chunk.RenderSectionRegion
import net.minecraft.client.renderer.chunk.SectionCompiler
import net.minecraft.core.SectionPos

interface D3D12SectionCompileState {
    val compileTaskIdentity: Any?
    fun hasPendingCompile(): Boolean
}

interface D3D12SectionTaskState {
    val captureState: D3D12SectionCaptureState
}

interface D3D12SectionRegionState {
    var compileScope: D3D12SectionGeometry.CompileScope?
}

class D3D12SectionCaptureState {
    private var sectionNode = 0L
    private var worldIdentity: Any? = null
    private var epoch = -1
    private var bound = false
    private var compilerResourcesRecorded = false
    private var compilerBlockModels: Any? = null
    private var compilerFluidModels: Any? = null
    private var compilerIdentity: SectionCompiler? = null
    private var cancelled = false
    private var sourceRegion: RenderSectionRegion? = null
    private var compilerInvocation = 0L
    private var sourceManifest: D3D12RtModelSupport.SourceManifest? = null
    var compileScope: D3D12SectionGeometry.CompileScope? = null
        private set
    var producerManifest: D3D12RtModelSupport.Manifest? = null
        private set
    @Volatile
    private var result: SectionMesh? = null
    @Volatile
    var installed: Boolean = false
        private set

    @Synchronized
    fun bind(sectionNode: Long, worldIdentity: Any?, epoch: Int, scope: D3D12SectionGeometry.CompileScope?) {
        check(!bound)
        this.sectionNode = sectionNode
        this.worldIdentity = worldIdentity
        this.epoch = epoch
        compileScope = scope?.takeIf {
            it.sectionNode == sectionNode && it.worldIdentity === worldIdentity && it.epoch == epoch
        }
        bound = true
    }

    @Synchronized
    fun bindSourceRegion(region: RenderSectionRegion) {
        check(bound && !compilerResourcesRecorded && !installed && !cancelled && sourceRegion == null)
        sourceRegion = region
    }

    @Synchronized
    fun captureCompilerResources(blockModels: Any?, fluidModels: Any?) {
        compilerInvocation++
        revokeSourceSupport()
        result = null
        producerManifest = null
        compilerBlockModels = blockModels
        compilerFluidModels = fluidModels
        compilerIdentity = null
        compilerResourcesRecorded = bound && !installed
    }

    @Synchronized
    fun captureCompiler(compiler: SectionCompiler) {
        val inputs = D3D12RtCompilerInputs.describe(compiler)
        captureCompilerResources(inputs?.blockModels, inputs?.fluidModels)
        compilerIdentity = compiler
        compilerResourcesRecorded = compilerResourcesRecorded && inputs != null
    }

    @Synchronized
    fun hasCurrentInputs(): Boolean = !cancelled && compilerResourcesRecorded &&
        D3D12SectionGeometry.isCurrent(compileScope) && compileScope?.resources?.let {
            it.blockModels === compilerBlockModels && it.fluidModels === compilerFluidModels
        } == true && compileScope?.compilerIdentity === compilerIdentity

    fun prepareSourceSupport(position: SectionPos, region: RenderSectionRegion,
                             trusted: Boolean, additionalRenderers: Boolean): Boolean {
        val [scope, invocation] = synchronized(this) {
            compileScope?.takeIf { !installed && sourceRegion === region && hasCurrentInputs() }
                ?.let { it to compilerInvocation }
        } ?: return false
        val target = D3D12SectionGeometry.sourceCapture(scope) ?: return false
        val manifest = D3D12RtModelSupport.sourceSupport(scope, position, region, trusted, additionalRenderers)
        return synchronized(this) {
            if (installed || compileScope !== scope || sourceRegion !== region ||
                compilerInvocation != invocation || !hasCurrentInputs()) return@synchronized false
            D3D12SectionGeometry.publishSourceSupport(target, manifest).also { accepted ->
                sourceManifest = manifest.takeIf { accepted }
            }
        }
    }

    private fun revokeSourceSupport() {
        sourceManifest?.let(D3D12SectionGeometry::revokeSourceSupport)
        sourceManifest = null
    }

    @Synchronized
    fun beginSupportSession(region: RenderSectionRegion, trusted: Boolean,
                            additionalRenderers: Boolean): D3D12RtModelSupport.Session =
        D3D12RtModelSupport.begin(compileScope,
            trusted && !installed && sourceRegion === region && hasCurrentInputs(), additionalRenderers, sourceManifest)

    @Synchronized
    fun cancelSourceSupport() {
        cancelled = true
        sourceRegion = null
        compilerInvocation++
        revokeSourceSupport()
    }

    @Synchronized
    fun captureProducerManifest(manifest: D3D12RtModelSupport.Manifest?) {
        producerManifest = manifest?.takeIf { !installed && it.scope === compileScope && hasCurrentInputs() }
    }

    @Synchronized
    fun capture(mesh: SectionMesh) {
        result = mesh
    }

    @Synchronized
    fun canInstall(mesh: SectionMesh, sectionNode: Long, worldIdentity: Any?, epoch: Int): Boolean =
        bound && !installed && result === mesh && worldIdentity != null &&
            this.sectionNode == sectionNode && this.worldIdentity === worldIdentity && this.epoch == epoch &&
            hasCurrentInputs()

    @Synchronized
    fun observeInstall(mesh: SectionMesh, sectionNode: Long, worldIdentity: Any?, epoch: Int): Boolean {
        if (!canInstall(mesh, sectionNode, worldIdentity, epoch)) return false
        installed = true
        sourceRegion = null
        revokeSourceSupport()
        return true
    }
}
