@file:Suppress("unused", "UNCHECKED_CAST", "FunctionName", "LocalVariableName", "RedundantVisibilityModifier", "MemberVisibilityCanBePrivate", "KotlinRedundantDiagnosticSuppress", "USELESS_CAST", "SENSELESS_COMPARISON")

package io.github.dxegp.d3d12.extern

import io.github.dxegp.d3d12.backend.D3D12FenceCallback
import io.github.dxegp.d3d12.extern.MCDR_ABI.MH_createsampler
import io.github.dxegp.d3d12.extern.MCDR_ABI.skip
import io.github.dxegp.d3d12.extern.MCDR_ABI.verifyABI
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Java FFM binding for MCDR; see docs/misc/ffm-abi.md (contract, arena rules, failure modes).
 * Handles are plain `long`, 0 = missing; every handle is linked once and must be invoked with a
 * descriptor that matches its native prototype exactly (see [MethodHandle.invokeExact]).
 */
object MCDR_ABI {
    /** [logging] logLevel gates every per-frame diagnostic print. */
    val LOG_VERBOSE: Boolean
        get() = IllusionLogger.debugEnabled()
    /** [debug] profiling; separate from LOG_VERBOSE, which skews timing. */
    val PROF: Boolean
        get() = D3D12Settings.profiling || LOG_VERBOSE
    private const val LIB_NAME = "MCDR.dll"
    private const val EXTRACT_DIR = "dxrmc"
    /** vendor/manifest.txt lists the SDK runtimes staged by `stageVendorLibs`; a JAR directory cannot be enumerated, so the list must never be hard-coded here. XeSS + D3D12Core + DXC ship, the NVIDIA/FSR runtimes never do (licence). */
    private const val VENDOR_MANIFEST = "/vendor/manifest.txt"
    /** Owns the loaded library and every upcall stub; lives for the process. */
    private val ARENA: Arena = Arena.ofShared()
    private val LINKER: Linker = Linker.nativeLinker()
    private var LIB: SymbolLookup? = null
    private var LOAD_FAILURE: Throwable? = null
    // Method handles are nullable so a failed load leaves them null instead of throwing
    // ExceptionInInitializerError — requireLoaded() turns that into a readable message.
    private var MH_abiversion: MethodHandle? = null
    private var MH_abilayout: MethodHandle? = null
    private var MH_lasterror: MethodHandle? = null
    private var MH_setlogverbose: MethodHandle? = null
    private var MH_setloglevel: MethodHandle? = null
    private var MH_setshutdowntrace: MethodHandle? = null
    private var MH_setdebuglayer: MethodHandle? = null
    private var MH_setlogfile: MethodHandle? = null
    private var MH_setenvironment: MethodHandle? = null
    private var MH_setpixcapture: MethodHandle? = null
    private var MH_setpixdirectory: MethodHandle? = null
    private var MH_startpixtiming: MethodHandle? = null
    private var MH_stoppixtiming: MethodHandle? = null
    private var MH_setshadingrate: MethodHandle? = null
    private var MH_setantialiasing: MethodHandle? = null
    private var MH_createdlssfeature: MethodHandle? = null
    private var MH_evaluatedlss: MethodHandle? = null
    private var MH_querydlss: MethodHandle? = null
    /** The 8 SR entry points; all skipOptional. */
    private var MH_srpick: MethodHandle? = null
    private var MH_srquerycaps: MethodHandle? = null
    private var MH_rtquerycaps: MethodHandle? = null
    private var MH_srcreate: MethodHandle? = null
    private var MH_srresize: MethodHandle? = null
    private var MH_srevaluate: MethodHandle? = null
    private var MH_srjitter: MethodHandle? = null
    private var MH_srdestroy: MethodHandle? = null
    private var MH_srjitterphase: MethodHandle? = null
    private var MH_srquery: MethodHandle? = null
    /** The 5 FG entry points; all skipOptional. */
    private var MH_fgpick: MethodHandle? = null
    private var MH_fgquerycaps: MethodHandle? = null
    private var MH_fgsetmode: MethodHandle? = null
    private var MH_fgpresented: MethodHandle? = null
    private var MH_fgsetconstants: MethodHandle? = null
    private var MH_fgstatus: MethodHandle? = null
    private var MH_fgstate: MethodHandle? = null
    private var MH_fgsetuitexture: MethodHandle? = null
    // hudless present: the world layer carries no UI, the SDK composites it.
    private var MH_fgsetuicomposition: MethodHandle? = null
    private var MH_fguicompositionready: MethodHandle? = null
    /** Scope shading rate (see [D3D12SetShadingRateNow]). */
    private var MH_setshadingratenow: MethodHandle? = null
    private var MH_setshadingratecombiners: MethodHandle? = null
    /** DLSS switch, independent of the AA mode. */
    private var MH_setdlssenabled: MethodHandle? = null
    /** Snapshot the world depth into a native-private D32 texture. */
    private var MH_snapshotdlssdepth: MethodHandle? = null
    private var MH_setdlssquality: MethodHandle? = null
    private var MH_setdlssrenderpreset: MethodHandle? = null
    private var MH_setdlssoutput: MethodHandle? = null
    private var MH_setdlssconstants: MethodHandle? = null
    private var MH_reflexframebegin: MethodHandle? = null
    private var MH_reflexmarker: MethodHandle? = null
    private var MH_meshsupport: MethodHandle? = null
    /** NRD 实例清单（S4 4-c）：纯 CPU 调用，不需要设备。 */
    private var MH_nrdinspect: MethodHandle? = null
    /** NRD 常驻上下文（S4 4-c）：实例 + PSO + 纹理池 + 描述符堆。 */
    private var MH_nrdinit: MethodHandle? = null
    private var MH_nrdshutdown: MethodHandle? = null
    private var MH_nrddenoise: MethodHandle? = null
    private var MH_nrddenoisetemporal: MethodHandle? = null
    private var MH_dlssdeval: MethodHandle? = null
    private var MH_rtsreval: MethodHandle? = null
    private var MH_dlssdevalexposure: MethodHandle? = null
    private var MH_rtsrevalexposure: MethodHandle? = null
    private var MH_sdkresourcestate: MethodHandle? = null
    /** Reflex mode + availability. */
    private var MH_setreflexmode: MethodHandle? = null
    private var MH_setdlssgmode: MethodHandle? = null
    private var MH_getdlssgpresented: MethodHandle? = null
    private var MH_setreflexframelimit: MethodHandle? = null
    private var MH_sethdrmode: MethodHandle? = null
    private var MH_setraytracingmode: MethodHandle? = null
    private var MH_setswapchainoptions: MethodHandle? = null
    private var MH_gethdrappliedmode: MethodHandle? = null
    private var MH_reflexavailable: MethodHandle? = null
    private var MH_dlssavailable: MethodHandle? = null
    private var MH_createdevice: MethodHandle? = null
    private var MH_createtexture: MethodHandle? = null
    private var MH_createbuffer: MethodHandle? = null
    private var MH_createsampler: MethodHandle? = null
    /** Terrain sampler with mip bias; falls back to [MH_createsampler] when absent. */
    private var MH_createsamplerbias: MethodHandle? = null
    private var MH_createtextureview: MethodHandle? = null
    private var MH_destroyresource: MethodHandle? = null
    private var MH_mapbuffer: MethodHandle? = null
    private var MH_unmapbuffer: MethodHandle? = null
    private var MH_enumerateadapters: MethodHandle? = null
    private var MH_getadapterinfo: MethodHandle? = null
    private var MH_getqueuehandle: MethodHandle? = null
    private var MH_getdevicehandle: MethodHandle? = null
    private var MH_destroydevice: MethodHandle? = null
    private var MH_createhiddenwindow: MethodHandle? = null
    private var MH_destroyhiddenwindow: MethodHandle? = null
    private var MH_gettimestampfrequency: MethodHandle? = null
    private var MH_createcommandencoder: MethodHandle? = null
    private var MH_destroycommandencoder: MethodHandle? = null
    private var MH_begincommandlist: MethodHandle? = null
    private var MH_begincommandlistwithwait: MethodHandle? = null
    private var MH_endcommandlist: MethodHandle? = null
    private var MH_submit: MethodHandle? = null
    private var MH_waitforfence: MethodHandle? = null
    private var MH_getfencevalue: MethodHandle? = null
    private var MH_gettimestampnow: MethodHandle? = null
    private var MH_islistopen: MethodHandle? = null
    private var MH_copybuffer: MethodHandle? = null
    private var MH_writetotexture: MethodHandle? = null
    private var MH_copybuffertotexture: MethodHandle? = null
    private var MH_copytexturetobuffer: MethodHandle? = null
    private var MH_copytexturetotexture: MethodHandle? = null
    private var MH_clearcolortexture: MethodHandle? = null
    private var MH_cleardepthtexture: MethodHandle? = null
    private var MH_clearcolortextureregion: MethodHandle? = null
    private var MH_cleardepthtextureregion: MethodHandle? = null
    private var MH_beginrenderpass: MethodHandle? = null
    private var MH_endrenderpass: MethodHandle? = null
    private var MH_getactivecolortexture: MethodHandle? = null
    private var MH_createquerypool: MethodHandle? = null
    private var MH_destroyquerypool: MethodHandle? = null
    private var MH_writetimestamp: MethodHandle? = null
    private var MH_readqueryvalue: MethodHandle? = null
    private var MH_readqueryvalues: MethodHandle? = null
    private var MH_queuequeryvalues: MethodHandle? = null
    private var MH_tryreadqueryvalues: MethodHandle? = null
    private var MH_initasyncrenderer: MethodHandle? = null
    private var MH_destroyasyncrenderer: MethodHandle? = null
    private var MH_asyncrenderbeginframe: MethodHandle? = null
    private var MH_asyncrenderwaitcomplete: MethodHandle? = null
    private var MH_asyncrenderisrecordingready: MethodHandle? = null
    private var MH_asyncrenderwaitrecordingready: MethodHandle? = null
    private var MH_asyncsendcommandsready: MethodHandle? = null
    private var MH_clearasyncrenderctx: MethodHandle? = null
    // World-layer in-place SMAA (three passes + copy back).
    private var MH_applysmaatotexture: MethodHandle? = null
    private var MH_blittexturetotexture: MethodHandle? = null
    private var MH_sharpentexturetotexture: MethodHandle? = null
    // TAA resolve, held entirely by native.
    private var MH_applytaatotexture: MethodHandle? = null
    private var MH_creategraphicspipeline: MethodHandle? = null
    private var MH_destroypipeline: MethodHandle? = null
    // Mesh shaders: DXC compile + AS/MS pipeline + DispatchMesh.
    private var MH_compileshaderdxc: MethodHandle? = null
    private var MH_createmeshpipeline: MethodHandle? = null
    private var MH_createmeshpipelineroot: MethodHandle? = null
    private var MH_meshrootsig: MethodHandle? = null
    private var MH_meshrootsigrelease: MethodHandle? = null
    private var MH_dispatchmesh: MethodHandle? = null
    private var MH_meshdrawmeshlets: MethodHandle? = null

    // Compute minimum path (prerequisite for the shading-rate image / NRC).
    private var MH_createcomputepipeline: MethodHandle? = null
    private var MH_createcomputepipelineex: MethodHandle? = null
    private var MH_dispatchcomputeex: MethodHandle? = null
    private var MH_barrier: MethodHandle? = null
    // 加速结构（S3）：BLAS/TLAS。
    private var MH_createaccelstructbuffer: MethodHandle? = null
    private var MH_getblasprebuildinfo: MethodHandle? = null
    private var MH_gettlasprebuildinfo: MethodHandle? = null
    private var MH_buildblas: MethodHandle? = null
    private var MH_buildtlas: MethodHandle? = null
    private var MH_copyaccelstruct: MethodHandle? = null
    private var MH_createaccelstructview: MethodHandle? = null
    private var MH_getbufferaddress: MethodHandle? = null
    // 光线追踪状态对象（S3 3-f）：RTPS / 着色器标识符 / DispatchRays。
    private var MH_createraytracingpipeline: MethodHandle? = null
    private var MH_getshaderidentifier: MethodHandle? = null
    private var MH_dispatchrays: MethodHandle? = null
    private var MH_creatertframe: MethodHandle? = null
    private var MH_releasertframe: MethodHandle? = null
    private var MH_creatertgbufferprogram: MethodHandle? = null
    private var MH_creatertgeometrysnapshot: MethodHandle? = null
    private var MH_creatertgeometrydelta: MethodHandle? = null
    private var MH_getrtgeometrymemory: MethodHandle? = null
    private var MH_getrtaccelerationbudget: MethodHandle? = null
    private var MH_setrtsceneworkbudget: MethodHandle? = null
    private var MH_getrtsceneworkbudget: MethodHandle? = null
    private var MH_releasertgeometrysnapshot: MethodHandle? = null
    private var MH_creatertacceleration: MethodHandle? = null
    private var MH_releasertacceleration: MethodHandle? = null
    private var MH_preparertacceleration: MethodHandle? = null
    private var MH_filterrtacceleration: MethodHandle? = null
    private var MH_rtaccelerationmemory: MethodHandle? = null
    private var MH_creatertscene: MethodHandle? = null
    private var MH_releasertscene: MethodHandle? = null
    private var MH_preparertscene: MethodHandle? = null
    private var MH_preparertscenetargets: MethodHandle? = null
    private var MH_getrtscenestats: MethodHandle? = null
    private var MH_releasertgbufferprogram: MethodHandle? = null
    private var MH_rtframe: MethodHandle? = null
    private var MH_rtframetransaction: MethodHandle? = null
    private var MH_rtframesetspatial: MethodHandle? = null
    private var MH_rtframereblur: MethodHandle? = null
    private var MH_rtframedenoise: MethodHandle? = null
    private var MH_rtframeupscale: MethodHandle? = null
    private var MH_rtframecomposite: MethodHandle? = null
    private var MH_rtframebegingbuffer: MethodHandle? = null
    private var MH_rtframeendgbuffer: MethodHandle? = null
    private var MH_rtframereplaygbuffer: MethodHandle? = null
    private var MH_rtcapturegbuffer: MethodHandle? = null
    private var MH_rtreleasegbuffercapture: MethodHandle? = null
    private var MH_rtframereplaycapturedgbuffer: MethodHandle? = null
    private var MH_rttrackstats: MethodHandle? = null
    private var MH_dispatchcompute: MethodHandle? = null
    private var MH_createcomputepipelineshadingrate: MethodHandle? = null
    private var MH_createshadingrateimage: MethodHandle? = null
    private var MH_dispatchcomputeshadingrate: MethodHandle? = null
    private var MH_createtexture3: MethodHandle? = null
    private var MH_dispatchtexture3: MethodHandle? = null
    private var MH_setshadingrateimage: MethodHandle? = null
    private var MH_createsurface: MethodHandle? = null
    private var MH_surfacepresentmodes: MethodHandle? = null
    private var MH_setexclusivefullscreen: MethodHandle? = null
    private var MH_configuresurface: MethodHandle? = null
    private var MH_acquiresurface: MethodHandle? = null
    private var MH_blitsurface: MethodHandle? = null
    private var MH_presentsurface: MethodHandle? = null
    private var MH_getbackbufferhandle: MethodHandle? = null
    private var MH_issurfacesuboptimal: MethodHandle? = null
    private var MH_readbacktexturepixels: MethodHandle? = null
    /** Readback with a known starting state (a GPU-written texture must use this). */
    private var MH_readbacktexturepixelsin: MethodHandle? = null
    /** State the DLSS output stopped in last frame (0 = unknown). */
    private var MH_dlssoutputstate: MethodHandle? = null
    private var MH_destroysurface: MethodHandle? = null
    private var MH_setpipeline: MethodHandle? = null
    private var MH_setscissor: MethodHandle? = null
    /** 1 = set, 0 = failed. */
    private var MH_setviewport: MethodHandle? = null
    private var MH_setvertexbuffer: MethodHandle? = null
    /** 1 = cleared, 0 = failed. */
    private var MH_clearstenciltexture: MethodHandle? = null
    private var MH_setindexbuffer: MethodHandle? = null
    private var MH_pushdescriptors: MethodHandle? = null
    private var MH_drawindexed: MethodHandle? = null
    private var MH_draw: MethodHandle? = null
    private var MH_drawindexedindirect: MethodHandle? = null
    private var MH_drawindirect: MethodHandle? = null
    private var MH_asynccurrentframeslot: MethodHandle? = null
    private var MH_asynccurrentframevalue: MethodHandle? = null
    private var MH_asyncregionbase: MethodHandle? = null
    private var MH_asyncallocate: MethodHandle? = null
    private var MH_asyncresetframe: MethodHandle? = null
    private var MH_asyncgpuhandle: MethodHandle? = null
    private var MH_asyncwritecbv: MethodHandle? = null
    private var MH_asyncwritesrv: MethodHandle? = null
    private var MH_asyncdescriptorcreate: MethodHandle? = null
    private var MH_asyncdescriptordestroy: MethodHandle? = null
    private var MH_asyncbundlepoolcreate: MethodHandle? = null
    private var MH_asyncbundlepooldestroy: MethodHandle? = null
    private var MH_asyncbundlebegin: MethodHandle? = null
    private var MH_asyncbundleend: MethodHandle? = null
    private var MH_asyncbundlesetpipelinestate: MethodHandle? = null
    private var MH_asyncbundlesetdescriptortable: MethodHandle? = null
    private var MH_asyncbundlesetvertexbuffer: MethodHandle? = null
    private var MH_asyncbundlesetindexbuffer: MethodHandle? = null
    private var MH_asyncbundledrawindexed: MethodHandle? = null
    private var MH_asyncbundledraw: MethodHandle? = null
    private var MH_asyncbundledrawindexedindirect: MethodHandle? = null
    private var MH_asyncbundlerecordpartition: MethodHandle? = null
    private var MH_executebundle: MethodHandle? = null
    private var MH_asyncpreparecbvbuffers: MethodHandle? = null
    private var MH_asyncprepareindexbuffers: MethodHandle? = null
    private var MH_asyncpreparetextureviews: MethodHandle? = null
    private var MH_asyncexecutorcreate: MethodHandle? = null
    private var MH_asyncexecutordestroy: MethodHandle? = null
    private var MH_asyncexecutortrybeginframe: MethodHandle? = null
    private var MH_asyncexecutoraddbundle: MethodHandle? = null
    private var MH_asyncexecutoraddtransition: MethodHandle? = null
    private var MH_asyncexecutorendframe: MethodHandle? = null
    private var MH_asyncexecutorlastfencevalue: MethodHandle? = null
    private var MH_asyncexecutorisframeinprogress: MethodHandle? = null
    private var MH_asyncexecutorwaitframe: MethodHandle? = null
    private var MH_asyncfencecreate: MethodHandle? = null
    private var MH_asyncfencedestroy: MethodHandle? = null
    private var MH_asyncfencesignal: MethodHandle? = null
    private var MH_asyncfencewait: MethodHandle? = null
    private var MH_asyncfenceiscomplete: MethodHandle? = null
    private var MH_asyncfenceregistercallback: MethodHandle? = null
    /** The only upcall stub: native calls it from its own worker thread, so it must be static and live for the process — a collected stub is a dangling function pointer, and an exception escaping it terminates the JVM. */
    private var STUB_TARGET: MethodHandle? = null
    private var FENCE_STUB: MemorySegment? = null
    init {
        var lib: SymbolLookup? = null
        var failure: Throwable? = null
        try {
            val dllPath = extractLibrary()
            lib = SymbolLookup.libraryLookup(dllPath, ARENA)
        } catch (t: Throwable) {
            failure = t
        }
        val loaded = lib
        if (loaded == null) {
            LIB = null
            LOAD_FAILURE = failure
        } else {
            LIB = loaded
            var bindFailure: Throwable? = null
            try {
                bindAll()
                verifyABI()
            } catch (t: Throwable) {
                bindFailure = t
            }
            LOAD_FAILURE = bindFailure
            if (bindFailure == null) {
                IllusionLogger.infoMessage(
                    "MCDR native library loaded (FFM): ${LIB_NAME}"
                            + ", ABI v${safeABIVersion()}"
                )
                IllusionLogger.infoMessage("struct layouts: ${MCDRLayout.describeSizes()}")
                IllusionLogger.infoMessage(
                    "startup log level ${IllusionLogger.level()}"
                            + " (before the config loads; the effective level comes from [logging] logLevel in config/dxr_mc-client.toml"
                            + " and is printed again once it does; for development DX12_LOG_VERBOSE=1 forces DEBUG)"
                )
            }
        }
    }
    // ---- Loading ----
    private fun safeABIVersion(): Int {
        return try {
            MH_abiversion!!.invokeExact() as Int
        } catch (t: Throwable) {
            -1
        }
    }
    /** Copy MCDR.dll to `<user.dir>/dxrmc/` and return the path; refreshed on a byte difference, not on timestamps (JAR entries are pinned to 1980 and two DLLs can share a size). */
    private fun extractLibrary(): Path {
        val dir = Path.of(System.getProperty("user.dir"), EXTRACT_DIR)
        Files.createDirectories(dir)
        extractVendorResources(dir)
        val dllPath = dir.resolve(LIB_NAME)
        var useJarDll = true
        var jarBytes: ByteArray? = null
        try {
            MCDR_ABI::class.java.getResourceAsStream("/${LIB_NAME}").use { input ->
                if (input != null) {
                    jarBytes = input.readAllBytes()
                }
            }
        } catch (ignored: Exception) {
        // treat as not-packaged
        }
        if (jarBytes == null) {
            if (!Files.exists(dllPath)) {
                throw IllegalStateException("${LIB_NAME} is neither packaged in the JAR nor present at "
                    + "${dllPath.toAbsolutePath()} — build the MCDR native project first")
            }
            useJarDll = false
        } else if (Files.exists(dllPath)) {
            try {
                if (jarBytes.contentEquals(Files.readAllBytes(dllPath))) {
                    useJarDll = false
                }
            } catch (ignored: Exception) {
            // unreadable local copy -> overwrite it
            }
        }
        if (useJarDll) {
            Files.write(dllPath, jarBytes!!)
        }
        return dllPath.toAbsolutePath()
    }
    /** Unpack the bundled SDK runtimes next to MCDR.dll; never throws — a missing or broken SDK must only make itself unavailable. */
    private fun extractVendorResources(dir: Path) {
        try {
            val entries = javaClass.getResourceAsStream(VENDOR_MANIFEST)?.use { input ->
                input.readAllBytes().toString(Charsets.UTF_8)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            } ?: return
            var written = 0
            var current = 0
            val problems = mutableListOf<String>()
            for (entry in entries) {
                try {
                    val bytes = javaClass.getResourceAsStream("/$entry")?.use { it.readAllBytes() }
                    if (bytes == null) {
                        problems += "$entry (未打进 JAR)"
                        continue
                    }
                    val target = dir.resolve(entry.substringAfterLast('/'))
                    // A licence entry is named "LICENSE-xess.txt" (dash) while its source is "LICENSE.txt".
                    if (Files.exists(target)) {
                        val same = try {
                            Files.readAllBytes(target).contentEquals(bytes)
                        } catch (ignored: Exception) {
                            false
                        }
                        if (same) {
                            current++
                            continue
                        }
                    }
                    Files.write(target, bytes)
                    written++
                } catch (t: Throwable) {
                    problems += "$entry (${t.message ?: t::class.java.simpleName})"
                }
            }
            if (written > 0 || problems.isNotEmpty()) {
                IllusionLogger.infoMessage("SDK runtime DLLs: $written extracted, $current already current"
                    + (if (problems.isEmpty()) "" else "; ${problems.size} failed: ${problems.joinToString("; ")}"))
            }
        } catch (ignored: Throwable) {
        // No manifest (JVM-only repo) or unreadable stream: the SDKs are optional, stay silent.
        }
    }
    private fun requireLoaded() {
        if (LOAD_FAILURE != null) {
            throw IllegalStateException("MCDR native library is unavailable: ${LOAD_FAILURE}", LOAD_FAILURE)
        }
    }
    fun isAvailable(): Boolean {
        return LOAD_FAILURE == null
    }
    /** Why the library is unavailable, or null when it is fine. */
    fun unavailableReason(): String? {
        return LOAD_FAILURE?.toString()
    }
    private fun skip(name: String, desc: FunctionDescriptor): MethodHandle {
        val addr = LIB!!.find(name).orElseThrow {
            IllegalStateException("MCDR export not found: ${name}")
        }
        return LINKER.downcallHandle(addr, desc)
    }
    /** Like [skip], but a missing export yields null instead of failing the whole bind (stale DLL = one feature degrades). */
    private fun skipOptional(name: String, desc: FunctionDescriptor): MethodHandle? {
        return try {
            val addr = LIB!!.find(name).orElse(null) ?: return null
            LINKER.downcallHandle(addr, desc)
        } catch (t: Throwable) {
            null
        }
    }
    // MSVC `long` is 32-bit: C `long` -> JAVA_INT / MCDRLayout.I32, never I64, and every
    // invokeExact argument must sit at exactly the static type its descriptor declares.
    private fun fd(ret: MemoryLayout, vararg args: MemoryLayout): FunctionDescriptor {
        return FunctionDescriptor.of(ret, *args)
    }
    private fun fdv(vararg args: MemoryLayout): FunctionDescriptor {
        return FunctionDescriptor.ofVoid(*args)
    }
    /** Cross-check every mirrored struct offset/size against the running DLL. */
    private fun verifyABI() {
        val version = MH_abiversion!!.invokeExact() as Int
        if (version != MCDR_ABI_VERSION) {
            throw IllegalStateException("MCDR ABI version mismatch: java=${MCDR_ABI_VERSION}, native=${version}")
        }
        Arena.ofConfined().use { arena ->
            val pFields = arena.allocate(MCDRLayout.PTR)
            val pCount = arena.allocate(MCDRLayout.I32)
            MH_abilayout!!.invokeExact(pFields, pCount)
            val count = pCount.get(MCDRLayout.I32, 0)
            val first = pFields.get(MCDRLayout.PTR, 0) as MemorySegment
            if (count <= 0 || first.address() == 0L) {
                throw IllegalStateException("mcdrABILayout() reported ${count} fields")
            }
            val fields = first.reinterpret(MCDRLayout.ABI_FIELD.byteSize() * count)
            val nativeFields = mutableMapOf<String, LongArray>()
            val stride = MCDRLayout.ABI_FIELD.byteSize()
            for (i in 0 until count) {
                val f = fields.asSlice(i * stride, stride)
                val nameSeg = f.get(MCDRLayout.PTR, 0) as MemorySegment
                val name = nameSeg.reinterpret(Long.MAX_VALUE).getString(0)
                nativeFields[name] = longArrayOf(f.get(MCDRLayout.I64, 8) as Long, f.get(MCDRLayout.I64, 16) as Long)
            }
            MCDRLayout.verify(nativeFields)
        }
    }
    private fun unexpected(t: Throwable): RuntimeException {
        return IllegalStateException("MCDR native call failed: ${t}", t)
    }
    private fun ptr(handle: Long): MemorySegment {
        return MemorySegment.ofAddress(handle)
    }
    private fun addr(seg: MemorySegment?): Long {
        return if (seg == null) 0L else seg.address()
    }
    /**
     * `MCDR_ABI_VERSION` from `mcdr_abi.h`; 2->3 the SR tables, 3->4 the FG tables, 4->5 the RT caps,
     * tracker and barrier tables, 5->6 the tracker's promotion / widening counters (append-only),
     * 6->7 the compute constant-buffer slot, 7->8 the acceleration-structure table with its seven
     * entry points, 8->9 the ray tracing state object (RTPS), shader identifiers and DispatchRays,
     * 10->11 the NRD instance inspector.
     * This constant, the Kotlin mirrors and the native `layoutTable()` must agree — [verifyABI]
     * aborts before device creation otherwise.
     */
    const val MCDR_ABI_VERSION: Int = 45
    /** `MCDR_FG_BACKEND_*`, value for value with `mcdr_abi.h`. */
    const val FG_BACKEND_NONE: Int = 0
    const val FG_BACKEND_DLSSG: Int = 1
    const val FG_BACKEND_FSRFG: Int = 2
    const val FG_BACKEND_XESSFG: Int = 3
    /** `MCDR_FG_CHAIN_*`; v1 only reaches the first two. */
    const val FG_CHAIN_APP_NATIVE: Int = 0
    const val FG_CHAIN_SL_PROXY: Int = 1
    const val FG_CHAIN_FSR_WRAP: Int = 2
    const val FG_CHAIN_XESS_WRAP: Int = 3
    /**
     * `MCDR_FG_LATENCY_*`: what this backend is *architecturally* paired with, not runtime
     * availability (read `available` / `note` for that).
     */
    const val FG_LATENCY_NONE: Int = 0
    const val FG_LATENCY_REFLEX: Int = 1
    const val FG_LATENCY_ANTILAG2: Int = 2
    const val FG_LATENCY_XELL: Int = 3
    /** `MCDR_SR_BACKEND_*`, value for value with `mcdr_abi.h`. */
    const val SR_BACKEND_NONE: Int = 0
    const val SR_BACKEND_DLSS: Int = 1
    const val SR_BACKEND_FSR: Int = 2
    const val SR_BACKEND_XESS: Int = 3
    const val SR_FLAG_UNJITTERED_MV: Int = 1
    /** `MCDR_SR_MASK_*`. */
    const val SR_MASK_REACTIVE: Int = 1
    const val SR_MASK_RESPONSIVE: Int = 2
    /** A native `int32_t` bool as a Kotlin `Boolean`. */
    private fun ok(value: Int): Boolean {
        return value != 0
    }
    // ------------------------------------------------------------------
    // Per-thread scratch slots: four reusable buffers, one arena each so growing one never
    // invalidates another; after the first frames a native call allocates nothing.
    // ------------------------------------------------------------------
    private const val SLOT_COUNT = 4
    private val SCRATCH: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }
    private class Scratch {
        private val arenas = arrayOfNulls<Arena>(SLOT_COUNT)
        private val segments = arrayOfNulls<MemorySegment>(SLOT_COUNT)
        init {
            for (i in 0 until SLOT_COUNT) {
                arenas[i] = Arena.ofConfined()
                segments[i] = MemorySegment.NULL
            }
        }
        fun slot(index: Int, bytes: Long): MemorySegment {
            val need = bytes.coerceAtLeast(8L)
            val segments = this.segments
            if (segments[index]!!.byteSize() < need) {
                arenas[index]!!.close()
                arenas[index] = Arena.ofConfined()
                segments[index] = arenas[index]!!.allocate(need, 16L)
            }
            return segments[index]!!
        }
    }
    private fun scratch(index: Int, bytes: Long): MemorySegment {
        return SCRATCH.get().slot(index, bytes)
    }
    // ---- Meta ----
    /** Last error this thread recorded; "" when none. */
    fun D3D12LastError(): String {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val need = MH_lasterror!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (need <= 0) {
                return ""
            }
            val buf = arena.allocate(need.toLong())
            val written = MH_lasterror!!.invokeExact(buf, need) as Int
            return if (written > 0) buf.getString(0) else ""
        }
    }
    /** Route native log output through `DEBUG` (1) or `verbose` (0). */
    fun D3D12SetLogVerbose(enabled: Boolean) {
        if (LOAD_FAILURE != null) {
            return
        }
        try {
            MH_setlogverbose!!.invokeExact(if (enabled) 1 else 0)
        } catch (ignored: Throwable) {
        // logging configuration must never fail engine start-up
        }
    }
    /** 0=error 1=warning 2=info 3=debug; a DLL predating `mcdrSetLogLevel` falls back to [D3D12SetLogVerbose]. Must run before the device is created. */
    fun D3D12SetLogLevel(level: Int) {
        if (LOAD_FAILURE != null) {
            return
        }
        val clamped = level.coerceIn(0, 3)
        try {
            val handle = MH_setloglevel
            if (handle != null) {
                handle.invokeExact(clamped)
            } else {
                D3D12SetLogVerbose(clamped >= 2)
            }
        } catch (ignored: Throwable) {
        // logging configuration must never fail engine start-up
        }
    }
    /** Push/clear one env var — only for native `getenv()` read points that already exist, never as a new switch (see AGENTS.md). */
    fun D3D12SetEnvironment(name: String, value: String?) {
        if (LOAD_FAILURE != null) {
            return
        }
        val handle = MH_setenvironment ?: return
        try {
            Arena.ofConfined().use { arena ->
                val key = cstringUtf8(arena, name)
                val valSeg = if (value == null) MemorySegment.NULL else cstringUtf8(arena, value)
                handle.invokeExact(key, valSeg)
            }
        } catch (ignored: Throwable) {
        // configuration propagation must never fail start-up
        }
    }
    /**
     * Request the D3D12 debug layer — config `[debug] debugLayer`. It is enabled while the first device is
     * created, so a later call changes nothing; return `1` when the request was recorded.
     */
    fun D3D12SetDebugLayer(enabled: Boolean): Int {
        val handle = MH_setdebuglayer ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(if (enabled) 1 else 0) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDebugLayer failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Whether shutdown-path progress markers bypass logLevel; config `[logging] shutdownTrace`.
     * A DLL without this export keeps the built-in always-on behaviour.
     */
    fun D3D12SetShutdownTraceAlways(always: Boolean) {
        if (LOAD_FAILURE != null) {
            return
        }
        val handle = MH_setshutdowntrace ?: return
        try {
            handle.invokeExact(if (always) 1 else 0)
        } catch (ignored: Throwable) {
        // logging configuration must never fail engine start-up
        }
    }

    /**
     * Mirror every native line into `path` (UTF-8, appended, relative = game dir); "" disables it.
     * Config `[logging] nativeLogFile` — launchers like Prism only persist log4j, not the child's stderr.
     * @return false when the loaded DLL has no such export.
     */
    fun D3D12SetLogFile(path: String): Boolean {
        if (LOAD_FAILURE != null) {
            return false
        }
        val handle = MH_setlogfile ?: return false
        return try {
            Arena.ofConfined().use { arena ->
                val status = handle.invokeExact(cstringUtf8(arena, path)) as Int
                status != 0
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetLogFile failed: $t")
            false
        }
    }

    /** Load WinPixGpuCapturer.dll — **before the first D3D12 device**. 1/0/-1, [Int.MIN_VALUE] = no export; never throws. */
    fun D3D12SetPIXCapture(enabled: Boolean): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setpixcapture ?: return Int.MIN_VALUE
        return try {
            val status = handle.invokeExact(if (enabled) 1 else 0) as Int
            status
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetPixCapture failed: $t")
            Int.MIN_VALUE
        }
    }
    /** PIX DLL path or directory; call it before [D3D12SetPIXCapture]. "" restores auto-detection. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SetPIXDirectory(path: String): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setpixdirectory ?: return Int.MIN_VALUE
        return try {
            Arena.ofConfined().use { arena ->
                val status = handle.invokeExact(cstringUtf8(arena, path)) as Int
                status
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetPixDirectory failed: $t")
            Int.MIN_VALUE
        }
    }
    /** Start a PIX timing capture written to `path` (.wpix) — only PIXBeginCapture puts a file where the caller asks. 1/0/negative HRESULT/[Int.MIN_VALUE]; waits on a native thread, never on the render thread. */
    fun D3D12StartPIXTimingCapture(path: String): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_startpixtiming ?: return Int.MIN_VALUE
        return try {
            Arena.ofConfined().use { arena ->
                val status = handle.invokeExact(cstringUtf8(arena, path)) as Int
                status
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("mcdrStartPixTimingCapture failed: $t")
            Int.MIN_VALUE
        }
    }
    /** Stop the timing capture and return without waiting (PIX spools on its own thread). 1/0/negative HRESULT/[Int.MIN_VALUE]. */
    fun D3D12StopPIXTimingCapture(): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_stoppixtiming ?: return Int.MIN_VALUE
        return try {
            val status = handle.invokeExact() as Int
            status
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("mcdrStopPixTimingCapture failed: $t")
            Int.MIN_VALUE
        }
    }
    /** Random-access shading rate: raw `D3D12_SHADING_RATE` (1X1=0x0 1X2=0x1 2X1=0x4 2X2=0x5 2X4=0x6 4X2=0x9 4X4=0xA), -1 = issue nothing. Re-issued on every list start. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SetShadingRate(rate: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setshadingrate ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(rate) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetShadingRate failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Issue one `RSSetShadingRate` on ctx's open list right now — the per-draw ceiling, not the
     * stored one. -1 is **not** accepted here: the caller simply does not call it.
     *  1 = issued, 0 = device cannot honour the rate (or the list is not a GraphicsCommandList5),
     *  -1 = unknown rate, -2 = **no open command list**, [Int.MIN_VALUE] = no export. Never throws.
     */
    fun D3D12SetShadingRateNow(ctx: Long, rate: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setshadingratenow ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), rate) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetShadingRateNow failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Both `D3D12_SHADING_RATE_COMBINER` values (PASSTHROUGH=0 OVERRIDE=1 MIN=2 MAX=3 SUM=4).
     * Stay {PASSTHROUGH, PASSTHROUGH} until SV_ShadingRate is actually written: under MIN a
     * per-primitive 1X1 would undo every 2X2.
     *  1 = stored, -1 = value outside 0..4, [Int.MIN_VALUE] = no export.
     */
    fun D3D12SetShadingRateCombiners(first: Int, second: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setshadingratecombiners ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(first, second) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetShadingRateCombiners failed: $t")
            Int.MIN_VALUE
        }
    }
    /** 0=OFF 1=SMAA 2=TAA 3=DLAA (same numbering as the video option); 4 is **not** legal any more. No restart. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SetAntiAliasing(mode: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setantialiasing ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(mode) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetAntiAliasing failed: $t")
            Int.MIN_VALUE
        }
    }
    /** DLSS switch, decoupled from the AA mode. 1/0/-1/[Int.MIN_VALUE] (an old DLL still gates on `aaMode == 4`). Never throws. */
    fun D3D12SetDlssEnabled(enabled: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setdlssenabled ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(enabled) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDlssEnabled failed: $t")
            Int.MIN_VALUE
        }
    }
    /** 0=Performance 1=Balanced 2=Quality (native maps it to `NVSDK_NGX_PerfQuality_Value`). 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SetDlssQuality(quality: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setdlssquality ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(quality) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDlssQuality failed: $t")
            Int.MIN_VALUE
        }
    }
    /** NGX render preset: 0=Default, 10=J 11=K 12=L 13=M; a change rebuilds the feature. Stored value / -1 / [Int.MIN_VALUE]. */
    fun D3D12SetDlssRenderPreset(preset: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setdlssrenderpreset ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(preset) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDlssRenderPreset failed: $t")
            Int.MIN_VALUE
        }
    }
    /** Build or rebuild the DLSS feature for [input] (`DLSS_INPUT`); optional, [D3D12EvaluateDlss] creates on demand. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12CreateDlssFeature(ctx: Long, input: MemorySegment): Int =
        dlssCall(MH_createdlssfeature, "dx12CreateDlssFeature failed: ", ctx, input)
    /** Per-frame NGX evaluation into `pInOutput`. 1 = evaluated, 0 = skipped (off / unavailable / bad size), -1, [Int.MIN_VALUE]. */
    fun D3D12EvaluateDlss(ctx: Long, input: MemorySegment): Int =
        dlssCall(MH_evaluatedlss, "dx12EvaluateDlss failed: ", ctx, input)
    /** Copy the depth **before** it is cleared into the native-private D32 snapshot; later evaluations prefer it. 1/0/-1/[Int.MIN_VALUE] (= fall back to the caller's depth). */
    fun D3D12SnapshotDlssDepth(ctx: Long, depth: Long): Int =
        dlssCall(MH_snapshotdlssdepth, "dx12SnapshotDlssDepth failed: ", ctx, ptr(depth))
    /** Hand a JVM-created texture to native as the DLSS output; the value must be the **`Dx12Object*` handle** ([D3D12GpuTexture.handle], never a reinterpret_cast'd resource), 0 = revoke. 1/0/-1/[Int.MIN_VALUE]. */
    /** Push `DLSS_CONSTANTS` (camera matrices); [Int.MIN_VALUE] on an old DLL. */
    /** PCL frame begin: opens this frame's SL token (eSimulationStart). */
    fun D3D12ReflexFrameBegin() {
        if (LOAD_FAILURE != null) return
        val handle = MH_reflexframebegin ?: return
        try {
            handle.invokeExact()
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12ReflexFrameBegin failed: ${t}")
        }
    }
    /** 0=off 1=on 2=on+boost. */
    /** DLSS-G: mode 0=off 1=on 2=dynamic; frames 1..5. */
    fun D3D12SetDlssgMode(mode: Int, frames: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_setdlssgmode
        if (handle == null) {
            io.github.dxegp.misc.IllusionLogger.warnMessage("DLSS-G: mcdr_dx12SetDlssgMode is not bound (native build too old?)")
            return
        }
        try {
            handle.invokeExact(mode, frames)
        } catch (t: Throwable) {
            io.github.dxegp.misc.IllusionLogger.warnMessage("DLSS-G: mcdr_dx12SetDlssgMode threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Frames the last Present really submitted; 1 when the export is missing (= no frame generation). */
    fun D3D12DlssgPresentedFrames(): Int {
        val handle = MH_getdlssgpresented ?: return 1
        return try {
            (handle.invokeExact() as Int).coerceAtLeast(1)
        } catch (e: Throwable) {
            1
        }
    }

    fun D3D12SetReflexMode(mode: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_setreflexmode ?: return
        try {
            handle.invokeExact(mode)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetReflexMode(${mode}) failed: ${t}")
        }
    }
    /** 1 = Reflex available; 0 = no export. */
    /** 1 = Streamline reports DLSS loaded; 0 also covers "SL up but the NGX runtime is too old" — grey the UI out. */
    fun D3D12DlssAvailable(): Int {
        if (LOAD_FAILURE != null) return 0
        val handle = MH_dlssavailable ?: return 1  // 旧 DLL 没有这个导出时按“未知=可用”处理
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12DlssAvailable failed: ${t}")
            1
        }
    }
    fun D3D12ReflexAvailable(): Int {
        if (LOAD_FAILURE != null) return 0
        val handle = MH_reflexavailable ?: return 0
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12ReflexAvailable failed: ${t}")
            0
        }
    }
    /** Reflex frame limiter in microseconds per frame (0 = off). */
    fun D3D12SetReflexFrameLimit(micros: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_setreflexframelimit ?: return
        try {
            handle.invokeExact(micros)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetReflexFrameLimit(${micros}) failed: ${t}")
        }
    }
    /** 0=auto 1=force on 2=force off; takes effect only when the swapchain is (re)built. */
    /** 0=off 1=ao 2=ao_shadows 3=full; state only — push it before the device is created. */
    /**
     * 光追 / Work Graph 的能力面（`mcdr_dx12QueryRtCaps`）。
     *
     * 与 `mcdr_device.cppm` 的 `Capabilities` 同源，但**只有这一份能跨进程边界读到**：能力串的
     * `wg=` / `ser=` 段与光追计划的 B-WG 门控都读它。tier 是 D3D12 枚举原值（0/10/11/12），
     * `options21Queried = false` 时 `workGraphsTier = 0` 只表示"没问成"，不表示"不支持"。
     */
    data class RtCaps(
        val raytracingTier: Int,
        val meshShaderTier: Int,
        val workGraphsTier: Int,
        val shadingRateTier: Int,
        val shaderModel: Int,
        val samplerFeedbackTier: Int,
        val options21Queried: Boolean,
        val options22Queried: Boolean,
        val serActuallyReorders: Boolean,
        val enhancedBarriers: Boolean,
        val dxcAvailable: Boolean,
        val max1DDispatchSize: Int,
        val max1DDispatchMeshSize: Int,
        val note: String
    ) {
        /** D3D12_RAYTRACING_TIER_1_0 = 10。 */
        val raytracingSupported: Boolean get() = raytracingTier >= 10

        /** D3D12_RAYTRACING_TIER_1_1 = 11（inline RayQuery 需要的下限）。 */
        val inlineRayQuerySupported: Boolean get() = raytracingTier >= 11

        val meshShadersSupported: Boolean get() = meshShaderTier >= 10

        /** D3D12_WORK_GRAPHS_TIER_1_0 = 10；`options21Queried = false` 时恒 false。 */
        val workGraphsSupported: Boolean get() = options21Queried && workGraphsTier >= 10

        val shadingRateImageSupported: Boolean get() = shadingRateTier >= 2

        /** D3D_SHADER_MODEL_6_9 == 0x69；图形管线的硬下限。 */
        val shaderModel69: Boolean get() = shaderModel >= 0x69

        /**
         * 能力侧允许的硬件后端，顺序即偏好（WG → DXR）。
         *
         * 只看硬件/运行时能力：用户的 `[rt] backend` 与 G1 探针结果由上层合成 ——
         * 探针是构建期事实（`_port\wg_probe`），这里不能假装知道。
         */
        val capabilityBackends: List<String>
            get() = buildList {
                if (workGraphsSupported && inlineRayQuerySupported) add("WG")
                if (raytracingSupported) add("DXR")
            }

        val preferredBackend: String get() = capabilityBackends.firstOrNull() ?: "OFF"
    }

    /** 读能力面；native 未导出该符号时返回 null（旧 native / 非 D3D12 后端）。 */
    fun D3D12QueryRtCaps(): RtCaps? {
        if (LOAD_FAILURE != null) return null
        val handle = MH_rtquerycaps ?: return null
        return try {
            Arena.ofConfined().use { arena ->
                val seg = arena.allocate(MCDRLayout.RT_CAPS)
                if (handle.invokeExact(seg) as Int != 1) {
                    return null
                }
                RtCaps(
                    raytracingTier = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_RAYTRACING_TIER),
                    meshShaderTier = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_MESH_SHADER_TIER),
                    workGraphsTier = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_WORK_GRAPHS_TIER),
                    shadingRateTier = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_SHADING_RATE_TIER),
                    shaderModel = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_SHADER_MODEL),
                    samplerFeedbackTier = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_SAMPLER_FEEDBACK_TIER),
                    options21Queried = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_OPTIONS21_QUERIED) != 0,
                    options22Queried = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_OPTIONS22_QUERIED) != 0,
                    serActuallyReorders = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_SER_ACTUALLY_REORDERS) != 0,
                    enhancedBarriers = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_ENHANCED_BARRIERS) != 0,
                    dxcAvailable = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_DXC_AVAILABLE) != 0,
                    max1DDispatchSize = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_MAX_1D_DISPATCH),
                    max1DDispatchMeshSize = seg.get(MCDRLayout.I32, MCDRLayout.RT_CAPS_MAX_1D_DISPATCH_MESH),
                    note = seg.getString(MCDRLayout.RT_CAPS_NOTE)
                )
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12QueryRtCaps failed: ${t}")
            null
        }
    }

    fun D3D12SetRaytracingMode(mode: Int) {
        if (!isAvailable()) return
        @Suppress("TooGenericExceptionCaught")
        try {
            val handle = MH_setraytracingmode ?: return
            handle.invokeExact(mode)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetRaytracingMode($mode) failed: ${t}")
        }
    }
    fun D3D12SetHdrMode(mode: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_sethdrmode ?: return
        try {
            handle.invokeExact(mode)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetHdrMode($mode) failed: ${t}")
        }
    }

    /** allowTearing / presentChildWindow; applies at the next swapchain build. */
    fun D3D12SetSwapchainOptions(allowTearing: Boolean, presentChild: Boolean) {
        if (LOAD_FAILURE != null) return
        val handle = MH_setswapchainoptions ?: return
        try {
            handle.invokeExact(if (allowTearing) 1 else 0, if (presentChild) 1 else 0)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage(
                "dx12SetSwapchainOptions($allowTearing, $presentChild) failed: ${t}",
            )
        }
    }
    /** HDR mode the swapchain was actually built with, -1 before any build. */
    fun D3D12HdrAppliedMode(): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_gethdrappliedmode ?: return -1
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            -1
        }
    }
    /** 1 = mesh shaders supported, 0 = not, -1 = no export; [reasonOut] gets one line of why. */
    fun D3D12QueryMeshSupport(reasonOut: MemorySegment, capacity: Int): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_meshsupport ?: return -1
        return try {
            (handle.invokeExact(reasonOut, capacity) as Int)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12QueryMeshSupport failed: ${t}")
            -1
        }
    }
    /** 1 = 清单写进 [textOut]，0 = 这份 DLL 没编进 NRD，-1 = 没导出或建实例失败；每行一条事实。 */
    fun D3D12NrdInspect(textOut: MemorySegment, capacity: Int): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_nrdinspect ?: return -1
        return try {
            (handle.invokeExact(textOut, capacity) as Int)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12NrdInspect failed: ${t}")
            -1
        }
    }
    /**
     * 建常驻的 NRD 上下文（S4 4-c）：实例、每条着色器一条 PSO、两套纹理池、一张描述符堆。
     * 分辨率变了会整体重建，所以每帧都可以调；返回 1=就绪 0=没编进 NRD -1=失败。
     */
    fun D3D12NrdInit(width: Int, height: Int, textOut: MemorySegment, capacity: Int): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_nrdinit ?: return -1
        return try {
            (handle.invokeExact(width, height, textOut, capacity) as Int)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12NrdInit failed: ${t}")
            -1
        }
    }

    fun D3D12NrdDenoise(
        encoder: Long,
        textures: LongArray,
        matrices: MemorySegment,
        frameIndex: Int,
        resetHistory: Boolean,
        textOut: MemorySegment,
        capacity: Int,
    ): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_nrddenoise ?: return -1
        val count = textures.size
        Arena.ofConfined().use { arena ->
            val array = arena.allocate(MCDRLayout.PTR.byteSize() * count, MCDRLayout.PTR.byteAlignment())
            for (i in textures.indices) {
                array.setAtIndex(MCDRLayout.PTR, i.toLong(), MemorySegment.ofAddress(textures[i]))
            }
            return try {
                (handle.invokeExact(
                    MemorySegment.ofAddress(encoder),
                    array,
                    count,
                    matrices,
                    frameIndex,
                    if (resetHistory) 1 else 0,
                    textOut,
                    capacity,
                ) as Int)
            } catch (t: Throwable) {
                IllusionLogger.warnMessage("dx12NrdDenoise failed: ${t}")
                -1
            }
        }
    }

    fun D3D12NrdDenoiseTemporal(
        encoder: Long,
        textures: LongArray,
        matrices: MemorySegment,
        jitter: MemorySegment,
        frameIndex: Int,
        resetHistory: Boolean,
        textOut: MemorySegment,
        capacity: Int,
    ): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_nrddenoisetemporal ?: return -1
        if (matrices.byteSize() < 256L ||
            jitter.byteSize() < MCDRLayout.NRD_JITTER_FLOAT_COUNT.toLong() * Float.SIZE_BYTES) return 0
        Arena.ofConfined().use { arena ->
            val array = arena.allocate(MCDRLayout.PTR.byteSize() * textures.size, MCDRLayout.PTR.byteAlignment())
            for (index in textures.indices) {
                array.setAtIndex(MCDRLayout.PTR, index.toLong(), MemorySegment.ofAddress(textures[index]))
            }
            return try {
                (handle.invokeExact(
                    MemorySegment.ofAddress(encoder), array, textures.size, matrices, jitter,
                    frameIndex, if (resetHistory) 1 else 0, textOut, capacity,
                ) as Int)
            } catch (failure: Throwable) {
                IllusionLogger.warnMessage("NRD temporal: FFM call failed (${failure.javaClass.simpleName})")
                -1
            }
        }
    }

    /**
     * [constants] 是 104 个浮点，包含重投影与视图正逆矩阵。缺失时拒绝派发。
     * 返回 1 表示 SL 接受，0 表示未执行（SL 缺函数），
     * -1 表示失败。
     */
    fun D3D12DlssdEval(
        encoder: Long,
        textures: LongArray,
        width: Int,
        height: Int,
        outputWidth: Int,
        outputHeight: Int,
        frameIndex: Int,
        reset: Boolean,
        constants: MemorySegment?,
        textOut: MemorySegment,
        capacity: Int,
    ): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_dlssdeval ?: return -1
        val count = textures.size
        Arena.ofConfined().use { arena ->
            val array = arena.allocate(MCDRLayout.PTR.byteSize() * count, MCDRLayout.PTR.byteAlignment())
            for (i in textures.indices) {
                array.setAtIndex(MCDRLayout.PTR, i.toLong(), MemorySegment.ofAddress(textures[i]))
            }
            return try {
                (handle.invokeExact(
                    MemorySegment.ofAddress(encoder),
                    array,
                    count,
                    width,
                    height,
                    outputWidth,
                    outputHeight,
                    frameIndex,
                    if (reset) 1 else 0,
                    constants ?: MemorySegment.NULL,
                    textOut,
                    capacity,
                ) as Int)
            } catch (t: Throwable) {
                IllusionLogger.warnMessage("dx12DlssdEval failed: ${t}")
                -1
            }
        }
    }

    fun D3D12RtSrEval(
        encoder: Long,
        textures: LongArray,
        width: Int,
        height: Int,
        outputWidth: Int,
        outputHeight: Int,
        frameIndex: Int,
        reset: Boolean,
        constants: MemorySegment,
        textOut: MemorySegment,
        capacity: Int,
    ): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_rtsreval ?: return -1
        Arena.ofConfined().use { arena ->
            val array = arena.allocate(MCDRLayout.PTR.byteSize() * textures.size, MCDRLayout.PTR.byteAlignment())
            for (index in textures.indices) {
                array.setAtIndex(MCDRLayout.PTR, index.toLong(), MemorySegment.ofAddress(textures[index]))
            }
            return try {
                handle.invokeExact(
                    MemorySegment.ofAddress(encoder), array, textures.size,
                    width, height, outputWidth, outputHeight, frameIndex,
                    if (reset) 1 else 0, constants, textOut, capacity,
                ) as Int
            } catch (failure: Throwable) {
                IllusionLogger.warnMessage("RT SR: call failed (${failure.javaClass.simpleName})")
                -1
            }
        }
    }

    fun D3D12DlssdEvalExposure(
        encoder: Long, textures: LongArray, width: Int, height: Int,
        outputWidth: Int, outputHeight: Int, frameIndex: Int, reset: Boolean,
        constants: MemorySegment, exposure: FloatArray, textOut: MemorySegment, capacity: Int,
    ): Int = evaluateRtExposure(MH_dlssdevalexposure, encoder, textures, width, height,
        outputWidth, outputHeight, frameIndex, reset, constants, exposure, textOut, capacity)

    fun D3D12RtSrEvalExposure(
        encoder: Long, textures: LongArray, width: Int, height: Int,
        outputWidth: Int, outputHeight: Int, frameIndex: Int, reset: Boolean,
        constants: MemorySegment, exposure: FloatArray, textOut: MemorySegment, capacity: Int,
    ): Int = evaluateRtExposure(MH_rtsrevalexposure, encoder, textures, width, height,
        outputWidth, outputHeight, frameIndex, reset, constants, exposure, textOut, capacity)

    private fun evaluateRtExposure(
        handle: MethodHandle?, encoder: Long, textures: LongArray, width: Int, height: Int,
        outputWidth: Int, outputHeight: Int, frameIndex: Int, reset: Boolean,
        constants: MemorySegment, exposure: FloatArray, textOut: MemorySegment, capacity: Int,
    ): Int {
        if (LOAD_FAILURE != null || handle == null) return -1
        if (constants.byteSize() < 104L * MCDRLayout.F32.byteSize() ||
            exposure.size != MCDRLayout.RT_EXPOSURE_FLOAT_COUNT || exposure.any { !it.isFinite() }) return 0
        Arena.ofConfined().use { arena ->
            val array = arena.allocate(MCDRLayout.PTR.byteSize() * textures.size, MCDRLayout.PTR.byteAlignment())
            for (index in textures.indices) {
                array.setAtIndex(MCDRLayout.PTR, index.toLong(), MemorySegment.ofAddress(textures[index]))
            }
            val parameters = arena.allocateFrom(ValueLayout.JAVA_FLOAT, *exposure)
            return try {
                handle.invokeExact(
                    MemorySegment.ofAddress(encoder), array, textures.size,
                    width, height, outputWidth, outputHeight, frameIndex,
                    if (reset) 1 else 0, constants, parameters, textOut, capacity,
                ) as Int
            } catch (failure: Throwable) {
                IllusionLogger.warnMessage("RT exposure: call failed (${failure.javaClass.simpleName})")
                -1
            }
        }
    }

    /** 销毁上面的上下文；设备销毁前调一次。 */
    fun D3D12NrdShutdown(): Int {
        if (LOAD_FAILURE != null) return -1
        val handle = MH_nrdshutdown ?: return -1
        return try {
            (handle.invokeExact() as Int)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12NrdShutdown failed: ${t}")
            -1
        }
    }

    /** PCL marker (1=eSimulationEnd 2=eRenderSubmitStart). */
    fun D3D12ReflexMarker(marker: Int) {
        if (LOAD_FAILURE != null) return
        val handle = MH_reflexmarker ?: return
        try {
            handle.invokeExact(marker)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12ReflexMarker(${marker}) failed: ${t}")
        }
    }
    fun D3D12SetDlssConstants(constants: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setdlssconstants ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(constants) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDlssConstants failed: $t")
            Int.MIN_VALUE
        }
    }
    fun D3D12SetDlssOutput(textureHandle: Long): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_setdlssoutput ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(textureHandle)) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SetDlssOutput failed: $t")
            Int.MIN_VALUE
        }
    }
    private fun dlssCall(handle: MethodHandle?, what: String, ctx: Long, input: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val h = handle ?: return Int.MIN_VALUE
        return try {
            h.invokeExact(ptr(ctx), input) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("$what$t")
            Int.MIN_VALUE
        }
    }
    /** Write DLSS/NGX availability into [note] (UTF-8, at most [cap] bytes, may be NULL); 1/0/-1. */
    fun D3D12QueryDlss(note: MemorySegment, cap: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_querydlss ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(note, cap) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12QueryDlss failed: $t")
            Int.MIN_VALUE
        }
    }
    // ---- SR (super resolution) contract layer: not on any per-frame path, so a confined arena is fine ----

    /**
     * One `mcdr_sr_caps` row, field for field. [note] is native's fixed `char[28]` reason, not a
     * machine verdict — read [available] for that.
     */
    data class SrCaps(
        val backend: Int,
        val available: Boolean,
        val coupledDenoise: Boolean,
        val needsExposure: Boolean,
        val takesMasks: Int,
        val externalHeap: Boolean,
        val jitterPhaseCount: Int,
        val maxRenderWidth: Int,
        val maxRenderHeight: Int,
        val nativeAA: Boolean,
        val note: String
    ) {
        val takesReactiveMask: Boolean get() = (takesMasks and SR_MASK_REACTIVE) != 0

        val takesResponsiveMask: Boolean get() = (takesMasks and SR_MASK_RESPONSIVE) != 0
    }

    /** Record the wanted backend (`mcdr_dx12SrPick`): pure bookkeeping, nothing is built or destroyed. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SrPick(backend: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srpick ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(backend) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrPick failed: $t")
            Int.MIN_VALUE
        }
    }

    /** Query one backend's caps; null = unusable (no export / bad argument / call failed — all treated alike). */
    fun querySrCaps(backend: Int): SrCaps? {
        if (LOAD_FAILURE != null) {
            return null
        }
        val handle = MH_srquerycaps ?: return null
        return try {
            Arena.ofConfined().use { arena ->
                val seg = arena.allocate(MCDRLayout.SR_CAPS)
                val rc = handle.invokeExact(backend, seg) as Int
                if (rc != 1) {
                    return null
                }
                SrCaps(
                    backend = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_BACKEND),
                    available = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_AVAILABLE) != 0,
                    coupledDenoise = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_COUPLED_DENOISE) != 0,
                    needsExposure = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_NEEDS_EXPOSURE) != 0,
                    takesMasks = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_TAKES_MASKS),
                    externalHeap = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_EXTERNAL_HEAP) != 0,
                    jitterPhaseCount = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_JITTER_PHASE_COUNT),
                    maxRenderWidth = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_MAX_RENDER_WIDTH),
                    maxRenderHeight = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_MAX_RENDER_HEIGHT),
                    nativeAA = seg.get(MCDRLayout.I32, MCDRLayout.SR_CAPS_NATIVE_AA) != 0,
                    note = seg.getString(MCDRLayout.SR_CAPS_NOTE, Charsets.UTF_8)
                )
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrQueryCaps failed: $t")
            null
        }
    }

    /** One line of SR status into [note]. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SrQuery(note: MemorySegment, cap: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srquery ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(note, cap) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrQuery failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Create or wake the backend (`mcdr_dx12SrCreate`); **the command list must be open**.
     * @param maxRenderWidth upper bound for rebuild decisions, not this frame's size.
     * 1/0/-1/[Int.MIN_VALUE].
     */
    fun D3D12SrCreate(
        ctx: Long,
        backend: Int,
        maxRenderWidth: Int,
        maxRenderHeight: Int,
        displayWidth: Int,
        displayHeight: Int
    ): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srcreate ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), backend, maxRenderWidth, maxRenderHeight, displayWidth, displayHeight) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrCreate failed: $t")
            Int.MIN_VALUE
        }
    }

    /** Size change; 1 = accepted, 0 = backend not ready (ignore), -1, [Int.MIN_VALUE]. */
    fun D3D12SrResize(ctx: Long, renderWidth: Int, renderHeight: Int, displayWidth: Int, displayHeight: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srresize ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), renderWidth, renderHeight, displayWidth, displayHeight) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrResize failed: $t")
            Int.MIN_VALUE
        }
    }

    /** Per-frame submit; [desc] must be a `SR_DISPATCH` block. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12SrEvaluate(ctx: Long, desc: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srevaluate ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(ptr(ctx), desc) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrEvaluate failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Backend's preferred sub-pixel jitter. **Returning 0 is normal** (= no phase table of its own;
     * [outX]/[outY] are untouched). 1/0/-1/[Int.MIN_VALUE].
     */
    fun D3D12SrJitter(frameIndex: Int, outX: MemorySegment, outY: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srjitter ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(frameIndex, outX, outY) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrJitter failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Ask the backend for its jitter phase length — the only source; never hard-code 8 or 16.
     * 0 = n/a, -1 = bad argument, [Int.MIN_VALUE] = no export.
     */
    fun D3D12SrJitterPhaseCount(backend: Int, renderWidth: Int, displayWidth: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_srjitterphase ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(backend, renderWidth, displayWidth) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrJitterPhaseCount failed: $t")
            Int.MIN_VALUE
        }
    }

    /** Release the backend; **only with no open command list** (frame start); idempotent. */
    fun D3D12SrDestroy() {
        if (LOAD_FAILURE != null) {
            return
        }
        val handle = MH_srdestroy ?: return
        try {
            handle.invokeExact()
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12SrDestroy failed: $t")
        }
    }

    /**
     * Record the wanted frame-generation backend (pure bookkeeping; SR and FG may be different
     * vendors). backend 0=none 1=DLSS-G 2=FSR-FG 3=XeSS-FG; 1/0/[Int.MIN_VALUE].
     */
    fun D3D12FgPick(backend: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgpick ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(backend) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgPick failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Fill [out] (`FG_CAPS`, 80 bytes, confined arena). **The only verdict bit is `available`** —
     * the rest explain why. 1/-1/[Int.MIN_VALUE].
     */
    fun D3D12FgQueryCaps(backend: Int, out: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgquerycaps ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(backend, out) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgQueryCaps failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Store the desired options (`FG_DISPATCH`); applied before the next Present, so any thread may
     * call it. mode 0=off 1=fixed(numFrames) 2=dynamic. 1/0/-1/[Int.MIN_VALUE].
     */
    fun D3D12FgSetMode(desc: MemorySegment): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgsetmode ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(desc) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgSetMode failed: $t")
            Int.MIN_VALUE
        }
    }

    /** Frames the last Present really submitted (1 = no interpolation); [Int.MIN_VALUE] = no export. */
    /** Push FG's own `FG_CONSTANTS` (272 B), independent of the DLSS constants. 1/-1/[Int.MIN_VALUE]. */
    fun D3D12FgSetConstants(constants: MemorySegment): Int {
        if (LOAD_FAILURE != null) return Int.MIN_VALUE
        val handle = MH_fgsetconstants ?: return Int.MIN_VALUE
        return try {
            // native is `void mcdr_dx12FgSetConstants(const mcdr_fg_constants*)`, bound as fdv(PTR):
            // casting to Int builds a (MemorySegment)Integer descriptor for a void handle and throws
            // WrongMethodTypeException, which the catch swallows — the constants then never arrive.
            handle.invokeExact(constants)
            1
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgSetConstants failed: $t")
            Int.MIN_VALUE
        }
    }

    fun D3D12FgPresented(): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgpresented ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgPresented failed: $t")
            Int.MIN_VALUE
        }
    }

    /** One line of FG status into [note]. 1/0/-1/[Int.MIN_VALUE]. */
    fun D3D12FgStatus(note: MemorySegment, cap: Int): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgstatus ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(note, cap) as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgStatus failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Hand the GUI composite target to the FG backend as its UI texture (`Dx12Object*` handle,
     * 0 = revoke); call it once per target rebuild. 1/[Int.MIN_VALUE].
     */
    fun D3D12FgSetUiTexture(objectHandle: Long): Int {
        if (LOAD_FAILURE != null) return Int.MIN_VALUE
        val handle = MH_fgsetuitexture ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(MemorySegment.NULL.takeIf { objectHandle == 0L } ?: MemorySegment.ofAddress(objectHandle))
            1
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgSetUiTexture failed: $t")
            Int.MIN_VALUE
        }
    }
    /**
     * hudless present: the backbuffer carries the world only and the backend composites the UI onto
     * every presented frame. Requires [D3D12FgUiCompositionReady] — else the UI disappears; switch it
     * only on the once-per-frame latch. 1/[Int.MIN_VALUE].
     */
    fun D3D12FgSetUiComposition(enabled: Boolean): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgsetuicomposition ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact(if (enabled) 1 else 0)
            1
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgSetUiComposition failed: $t")
            Int.MIN_VALUE
        }
    }

    /** 1 = the backend will composite the UI this Present, 0 = present world+GUI yourself, [Int.MIN_VALUE] = no export. */
    fun D3D12FgUiCompositionReady(): Int {
        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fguicompositionready ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgUiCompositionReady failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * Packed FG state (`mcdr_dx12FgState`): [0..7] desiredMode, [8..15] chainMode, [16..23] active,
     * [24..31] rebuilds. Read this instead of `options.txt` — a native rollback clears desiredMode
     * while the option still says "on". [Int.MIN_VALUE] = no export.
     */
    fun D3D12FgState(): Int {        if (LOAD_FAILURE != null) {
            return Int.MIN_VALUE
        }
        val handle = MH_fgstate ?: return Int.MIN_VALUE
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgState failed: $t")
            Int.MIN_VALUE
        }
    }

    /**
     * One `mcdr_fg_caps` row. [note] is native's short English reason (<= 27 bytes), shown as-is and
     * deliberately untranslated so it still matches the log.
     */
    data class FgCaps(
        val backend: Int,
        val available: Boolean,
        val runtimeLoaded: Boolean,
        val deviceSupported: Boolean,
        val chainCompatible: Boolean,
        val needsReflex: Boolean,
        val needsUiResource: Boolean,
        val needsHudless: Boolean,
        val transitionsInputs: Boolean,
        val maxGeneratedFrames: Int,
        val chainOwner: Int,
        /** `MCDR_FG_LATENCY_*`: architectural pairing, **not** runtime availability. */
        val latencyProvider: Int,
        val note: String
    )

    /**
     * Query one backend's caps; null = unavailable (no export / bad argument / call failed).
     * Unlike SR this never probes LoadLibrary, so it is safe on the per-frame path.
     */
    fun queryFgCaps(backend: Int): FgCaps? {
        if (LOAD_FAILURE != null) {
            return null
        }
        val handle = MH_fgquerycaps ?: return null
        return try {
            Arena.ofConfined().use { arena ->
                val seg = arena.allocate(MCDRLayout.FG_CAPS)
                val rc = handle.invokeExact(backend, seg) as Int
                if (rc != 1) {
                    return null
                }
                FgCaps(
                    backend = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_BACKEND),
                    available = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_AVAILABLE) != 0,
                    runtimeLoaded = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_RUNTIME_LOADED) != 0,
                    deviceSupported = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_DEVICE_SUPPORTED) != 0,
                    chainCompatible = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_CHAIN_COMPATIBLE) != 0,
                    needsReflex = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_NEEDS_REFLEX) != 0,
                    needsUiResource = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_NEEDS_UI_RESOURCE) != 0,
                    needsHudless = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_NEEDS_HUDLESS) != 0,
                    transitionsInputs = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_TRANSITIONS_INPUTS) != 0,
                    maxGeneratedFrames = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_MAX_GENERATED_FRAMES),
                    chainOwner = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_CHAIN_OWNER),
                    latencyProvider = seg.get(MCDRLayout.I32, MCDRLayout.FG_CAPS_LATENCY_PROVIDER),
                    note = seg.getString(MCDRLayout.FG_CAPS_NOTE, Charsets.UTF_8)
                )
            }
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("dx12FgQueryCaps failed: $t")
            null
        }
    }

    /** Throw with the native error text when `value` is false. */
    private fun check(value: Boolean, what: String) {
        if (!value) {
            val detail = D3D12LastError()
            throw IllegalStateException("${what} failed"
                + (if (detail.isEmpty()) "" else ": $detail"))
        }
    }
    // ---- Device + resources ----
    /** Create the device and run the native self-test: adapter + feature level + result, or "ERROR: ...". */
    fun ID3D12CreateDevice(): String {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val need = MH_createdevice!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (need <= 0) {
                return "ERROR: native library reported no device summary"
            }
            val buf = arena.allocate(need.toLong())
            val written = MH_createdevice!!.invokeExact(buf, need) as Int
            if (written <= 0) {
                return "ERROR: native library reported no device summary"
            }
            return buf.getString(0)
        }
    }
    /** Returns a native handle (`Dx12Object*`); 0 on failure. */
    fun ID3D12CreateTexture(usage: Int, format: Int, width: Int,
                            height: Int, depthOrLayers: Int, mipLevels: Int): Long {
        requireLoaded()
        try {
            return addr(MH_createtexture!!.invokeExact(
                usage, format, width, height, depthOrLayers, mipLevels) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Returns a native handle (`Dx12Object*`); 0 on failure. */
    fun ID3D12CreateBuffer(usage: Int, size: Long): Long {
        requireLoaded()
        try {
            return addr(MH_createbuffer!!.invokeExact(usage, size) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Returns a native handle (`Dx12Object*`); 0 on failure. */
    fun ID3D12CreateSampler(addressU: Int, addressV: Int, minFilter: Int,
                            magFilter: Int, maxAnisotropy: Int, maxLod: Float): Long {
        requireLoaded()
        try {
            return addr(MH_createsampler!!.invokeExact(
                addressU, addressV, minFilter, magFilter, maxAnisotropy, maxLod) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Sampler with mip LOD bias; 0 = no such export — fall back to [ID3D12CreateSampler], never fail startup. */
    fun ID3D12CreateSamplerWithMipBias(addressU: Int, addressV: Int, minFilter: Int,
                                       magFilter: Int, maxAnisotropy: Int, maxLod: Float,
                                       mipLodBias: Float): Long {
        requireLoaded()
        val handle = MH_createsamplerbias ?: return 0L
        try {
            return addr(handle.invokeExact(
                addressU, addressV, minFilter, magFilter, maxAnisotropy, maxLod, mipLodBias) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Returns a native handle (`Dx12Object*`); 0 on failure. */
    fun ID3D12CreateTextureView(textureHandle: Long, baseMipLevel: Int, mipLevels: Int): Long {
        requireLoaded()
        try {
            return addr(MH_createtextureview!!.invokeExact(
                ptr(textureHandle), baseMipLevel, mipLevels) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun ID3D12DestroyResource(handle: Long) {
        requireLoaded()
        try {
            MH_destroyresource!!.invokeExact(ptr(handle))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Mapped view is explicitly LITTLE_ENDIAN; null on failure (see [D3D12LastError]). */
    fun D3D12MapBuffer(bufferHandle: Long, offset: Long, length: Long,
                       read: Boolean, write: Boolean): ByteBuffer? {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val pLen = arena.allocate(MCDRLayout.I64)
            val base = MH_mapbuffer!!.invokeExact(
                ptr(bufferHandle), offset, length, if (read) 1 else 0, if (write) 1 else 0, pLen) as MemorySegment
            if (base.address() == 0L) {
                return null
            }
            val actual = pLen.get(MCDRLayout.I64, 0) as Long
            return base.reinterpret(actual).asByteBuffer().order(ByteOrder.nativeOrder())
        }
    }
    fun D3D12UnmapBuffer(bufferHandle: Long) {
        requireLoaded()
        try {
            MH_unmapbuffer!!.invokeExact(ptr(bufferHandle))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** JSON array of every D3D12-capable adapter. */
    fun ID3D12EnumerateAdapters(): String {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val need = MH_enumerateadapters!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (need <= 0) {
                return "[]"
            }
            val buf = arena.allocate(need.toLong())
            val written = MH_enumerateadapters!!.invokeExact(buf, need) as Int
            return if (written > 0) buf.getString(0) else "[]"
        }
    }
    /** JSON object for the live device's adapter; "" when there is no device. */
    fun ID3D12GetAdapterInfo(): String {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val need = MH_getadapterinfo!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (need <= 0) {
                return ""
            }
            val buf = arena.allocate(need.toLong())
            val written = MH_getadapterinfo!!.invokeExact(buf, need) as Int
            return if (written > 0) buf.getString(0) else ""
        }
    }
    /** Global queue COM pointer; 0 when no device. */
    fun ID3D12GetQueueHandle(): Long {
        requireLoaded()
        try {
            return MH_getqueuehandle!!.invokeExact() as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Global device COM pointer; 0 when no device. */
    fun ID3D12GetDeviceHandle(): Long {
        requireLoaded()
        try {
            return MH_getdevicehandle!!.invokeExact() as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Hidden Win32 window for a standalone loop; 0 on failure. */
    fun D3D12CreateHiddenWindow(width: Int, height: Int): Long {
        requireLoaded()
        try {
            return MH_createhiddenwindow!!.invokeExact(width, height) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12DestroyHiddenWindow(hwnd: Long) {
        requireLoaded()
        try {
            MH_destroyhiddenwindow!!.invokeExact(hwnd)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Command layer ----
    /** GPU timestamp ticks per second; 0 if unknown. */
    fun D3D12GetTimestampFrequency(): Long {
        requireLoaded()
        try {
            return MH_gettimestampfrequency!!.invokeExact() as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Returns a `CommandContext*` handle. */
    fun D3D12CreateCommandEncoder(): Long {
        requireLoaded()
        try {
            return addr(MH_createcommandencoder!!.invokeExact() as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12DestroyCommandEncoder(ctx: Long) {
        requireLoaded()
        try {
            MH_destroycommandencoder!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Reset the allocator and open the list. */
    fun D3D12BeginCommandList(ctx: Long) {
        requireLoaded()
        try {
            MH_begincommandlist!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Wait on the queue fence before resetting the allocator; 0 = no wait. */
    fun D3D12BeginCommandListWithWait(ctx: Long, waitForValue: Long) {
        requireLoaded()
        try {
            MH_begincommandlistwithwait!!.invokeExact(ptr(ctx), waitForValue)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12EndCommandList(ctx: Long) {
        requireLoaded()
        try {
            MH_endcommandlist!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** ExecuteCommandLists + Signal(fence, ++value); non-blocking, returns this submit's fence value. */
    fun D3D12Submit(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_submit!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Waits on **this context's** fence only — a one-shot encoder cannot advance a frame encoder's value. false = timeout, not an error. */
    fun D3D12WaitForFence(ctx: Long, value: Long, timeoutNs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_waitforfence!!.invokeExact(ptr(ctx), value, timeoutNs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Current value of this context's fence; a new fence targets this value + 1. */
    fun D3D12GetFenceValue(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_getfencevalue!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Read the GPU timestamp now (blocking). */
    fun D3D12GetTimestampNow(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_gettimestampnow!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12IsListOpen(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_islistopen!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** CopyBufferRegion into the open list. */
    fun D3D12CopyBuffer(ctx: Long, src: Long, srcOffset: Long,
                        dst: Long, dstOffset: Long, size: Long) {
        requireLoaded()
        try {
            MH_copybuffer!!.invokeExact(ptr(ctx), ptr(src), srcOffset, ptr(dst), dstOffset, size)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Staging -> texture sub-region, tightly packed width*height texels. */
    fun D3D12WriteToTexture(ctx: Long, stagingBuf: Long, stagingOffset: Long,
                            width: Int, height: Int, dstTex: Long, mip: Int, layer: Int, dstX: Int, dstY: Int) {
        requireLoaded()
        try {
            MH_writetotexture!!.invokeExact(ptr(ctx), ptr(stagingBuf), stagingOffset,
                width, height, ptr(dstTex), mip, layer, dstX, dstY)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** (possibly padded) buffer sub-region -> texture. */
    fun D3D12CopyBufferToTexture(ctx: Long, srcBuf: Long,
                                 srcOffset: Long, srcWidth: Int, srcHeight: Int,
                                 dstTex: Long, mip: Int, layer: Int,
                                 dstX: Int, dstY: Int,
                                 w: Int, h: Int) {
        requireLoaded()
        try {
            MH_copybuffertotexture!!.invokeExact(ptr(ctx), ptr(srcBuf), srcOffset,
                srcWidth, srcHeight, ptr(dstTex), mip, layer, dstX, dstY, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Texture -> readback buffer; the row pitch is 256-aligned, honour it. */
    fun D3D12CopyTextureToBuffer(ctx: Long, srcTex: Long,
                                 mip: Int, layer: Int,
                                 srcX: Int, srcY: Int,
                                 w: Int, h: Int,
                                 dstBuf: Long, dstOffset: Long) {
        requireLoaded()
        try {
            MH_copytexturetobuffer!!.invokeExact(ptr(ctx),
                ptr(srcTex), mip, layer,
                srcX, srcY, w, h,
                ptr(dstBuf), dstOffset)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12CopyTextureToTexture(ctx: Long, srcTex: Long, dstTex: Long,
                                  mip: Int, layer: Int, srcX: Int, srcY: Int, dstX: Int, dstY: Int, w: Int, h: Int) {
        requireLoaded()
        try {
            MH_copytexturetotexture!!.invokeExact(ptr(ctx), ptr(srcTex), ptr(dstTex),
                mip, layer, srcX, srcY, dstX, dstY, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Full-texture colour clear; the texture must be a RENDER_ATTACHMENT. */
    fun D3D12ClearColorTexture(ctx: Long, texture: Long, r: Float, g: Float, b: Float, a: Float) {
        requireLoaded()
        try {
            MH_clearcolortexture!!.invokeExact(ptr(ctx), ptr(texture), r, g, b, a)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Three in-place SMAA passes on the world layer; false when skipped (mode, texture kind, or no export). */
    fun dx12ApplySmaaToTexture(ctx: Long, texture: Long): Boolean {
        requireLoaded()
        val handle = MH_applysmaatotexture ?: return false
        return try {
            ok(handle.invokeExact(ptr(ctx), ptr(texture)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Full-screen blit src->dst (sizes may differ); leaves dst in RENDER_TARGET. false when the export is missing. */
    fun dx12BlitTextureToTexture(ctx: Long, src: Long, dst: Long): Boolean {
        requireLoaded()
        val handle = MH_blittexturetotexture ?: return false
        return try {
            ok(handle.invokeExact(ptr(ctx), ptr(src), ptr(dst)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** 5-tap unsharp src->dst (TAA/DLAA/DLSS post-sharpen); false => fall back to [dx12BlitTextureToTexture]. */
    /** Whether this DLL exports the post-sharpen. */
    fun supportsSharpenTextureToTexture(): Boolean {
        requireLoaded()
        return MH_sharpentexturetotexture != null
    }
    fun dx12SharpenTextureToTexture(ctx: Long, src: Long, dst: Long, amount: Float): Boolean {
        requireLoaded()
        val handle = MH_sharpentexturetotexture ?: return false
        return try {
            ok(handle.invokeExact(ptr(ctx), ptr(src), ptr(dst), amount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Whether this DLL exports the native TAA resolve. */
    fun supportsTaaToTexture(): Boolean {
        requireLoaded()
        return MH_applytaatotexture != null
    }
    /** Two passes (resolve, back-fill) on [scene], left in RENDER_TARGET; `reset` = history unusable, only this frame is written. 1 = done, 0 = skipped, -1 = failed (native logs it and disables TAA). */
    fun dx12ApplyTaaToTexture(
        ctx: Long,
        scene: Long,
        motion: Long,
        jitterX: Float,
        jitterY: Float,
        prevJitterX: Float,
        prevJitterY: Float,
        blend: Float,
        reset: Boolean,
        sharpenAmount: Float,    ): Int {
        requireLoaded()
        val handle = MH_applytaatotexture ?: return -1
        return try {
            handle.invokeExact(
                ptr(ctx), ptr(scene), ptr(motion),
                jitterX, jitterY, prevJitterX, prevJitterY, blend,
                if (reset) 1 else 0,
                sharpenAmount,
            ) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12ClearDepthTexture(ctx: Long, texture: Long, depth: Double) {
        requireLoaded()
        try {
            MH_cleardepthtexture!!.invokeExact(ptr(ctx), ptr(texture), depth)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** 1 = cleared, 0 = failed. */
    fun D3D12ClearStencilTexture(ctx: Long, texture: Long, value: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_clearstenciltexture!!.invokeExact(ptr(ctx), ptr(texture), value) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Rect clear in render-target pixels (per-slot atlas clears wiped already-baked icons). */
    fun D3D12ClearColorTextureRegion(ctx: Long, texture: Long,
                                     r: Float, g: Float,
                                     b: Float, a: Float,
                                     x: Int, y: Int, w: Int, h: Int) {
        requireLoaded()
        try {
            MH_clearcolortextureregion!!.invokeExact(ptr(ctx),
                ptr(texture),
                r, g, b, a, x, y, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12ClearDepthTextureRegion(ctx: Long, texture: Long, depth: Double,
                                     x: Int, y: Int, w: Int, h: Int) {
        requireLoaded()
        try {
            MH_cleardepthtextureregion!!.invokeExact(ptr(ctx), ptr(texture), depth, x, y, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /**
     * RTV/DSV + transitions + OMSetRenderTargets + viewport/scissor (+ optional clears).
     * colorMips selects the real RTV mip (atlas uploads bind mipViews[level]); a zero-length colour
     * array is a legal depth-only pass; colorClearFlags 0=load 1=clear.
     */
    fun D3D12BeginRenderPass(ctx: Long, colorTextures: LongArray?, colorMips: IntArray?,
                             colorClearFlags: ByteArray?, clearColors: FloatArray?,
                             depthTexture: Long, depthMip: Int,
                             depthClearFlag: Byte, depthClearValue: Double,
                             x: Int, y: Int, w: Int, h: Int) {
        requireLoaded()
        val colorCount = colorTextures?.size ?: 0
        // Slot 0: attachment pointer array; slot 1: the widened int32 clear flags.
        val views = scratch(0, 1.coerceAtLeast(colorCount) * 8L)
        for (i in 0 until colorCount) {
            views.set(MCDRLayout.PTR, i * 8L, ptr(colorTextures!![i]))
        }
        var mips = MemorySegment.NULL
        if (colorCount > 0 && colorMips != null && colorMips.size >= colorCount) {
            mips = scratch(2, colorCount * 4L)
            MemorySegment.copy(colorMips, 0, mips, MCDRLayout.I32, 0, colorCount)
        }
        var flags = MemorySegment.NULL
        if (colorCount > 0 && colorClearFlags != null && colorClearFlags.size >= colorCount) {
            flags = scratch(1, colorCount * 4L)
            for (i in 0 until colorCount) {
                flags.set(MCDRLayout.I32, i * 4L, colorClearFlags[i].toInt())
            }
        }
        var colors = MemorySegment.NULL
        if (clearColors != null && clearColors.size >= colorCount * 4) {
            colors = scratch(3, colorCount.coerceAtLeast(1) * 16L)
            MemorySegment.copy(clearColors, 0, colors, MCDRLayout.F32, 0, colorCount * 4)
        }
        try {
            MH_beginrenderpass!!.invokeExact(ptr(ctx), views, colorCount, mips, flags, colors,
                ptr(depthTexture), depthMip, depthClearFlag.toInt(), depthClearValue, x, y, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12EndRenderPass(ctx: Long) {
        requireLoaded()
        try {
            MH_endrenderpass!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** First active colour attachment of the open pass; 0 when none. */
    fun D3D12GetActiveColorTexture(ctx: Long): Long {
        requireLoaded()
        try {
            return addr(MH_getactivecolortexture!!.invokeExact(ptr(ctx)) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Returns a native handle; 0 on failure. */
    fun D3D12CreateQueryPool(size: Int): Long {
        requireLoaded()
        try {
            return addr(MH_createquerypool!!.invokeExact(size) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12DestroyQueryPool(pool: Long) {
        requireLoaded()
        try {
            MH_destroyquerypool!!.invokeExact(ptr(pool))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** EndQuery(TIMESTAMP) at `index` into the open list. */
    fun D3D12WriteTimestamp(ctx: Long, pool: Long, index: Int) {
        requireLoaded()
        try {
            MH_writetimestamp!!.invokeExact(ptr(ctx), ptr(pool), index)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Blocking read of one timestamp. */
    fun D3D12ReadQueryValue(pool: Long, index: Int): Long {
        requireLoaded()
        try {
            return MH_readqueryvalue!!.invokeExact(ptr(pool), index) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Blocking read of `count` timestamps into [out]. */
    fun D3D12ReadQueryValues(pool: Long, start: Int, count: Int, out: LongArray) {
        requireLoaded()
        if (count <= 0) {
            return
        }
        val buf = scratch(0, count * 8L)
        try {
            val rc = MH_readqueryvalues!!.invokeExact(ptr(pool), start, count, buf) as Int
            if (rc != 0) {
                MemorySegment.copy(buf, MCDRLayout.I64, 0L, out, 0, minOf(count, out.size))
            }
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12QueueQueryValues(ctx: Long, pool: Long, start: Int, count: Int): Boolean {
        requireLoaded()
        val method = MH_queuequeryvalues ?: return false
        try {
            return (method.invokeExact(ptr(ctx), ptr(pool), start, count) as Int) == 1
        } catch (failure: Throwable) {
            throw unexpected(failure)
        }
    }

    fun D3D12TryReadQueryValues(pool: Long, start: Int, count: Int, out: LongArray): Int {
        requireLoaded()
        require(count > 0 && count <= out.size)
        val method = MH_tryreadqueryvalues ?: return -1
        val buffer = scratch(0, count * Long.SIZE_BYTES.toLong())
        try {
            val status = method.invokeExact(ptr(pool), start, count, buffer) as Int
            if (status == 1) MemorySegment.copy(buffer, MCDRLayout.I64, 0L, out, 0, count)
            return status
        } catch (failure: Throwable) {
            throw unexpected(failure)
        }
    }

    // ---- Async: dedicated render thread ----
    /** Start the render thread; **must precede [D3D12CreateCommandEncoder]**. */
    fun D3D12InitAsyncRenderer(workerCount: Int) {
        requireLoaded()
        try {
            MH_initasyncrenderer!!.invokeExact(workerCount)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12DestroyAsyncRenderer() {
        requireLoaded()
        try {
            MH_destroyasyncrenderer!!.invokeExact()
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Must run before process exit: releasing the ComPtrs in DLL_PROCESS_DETACH jumps into a dead dxgi vtable (exit code 0x87A); idempotent. */
    fun D3D12DestroyDevice() {
        requireLoaded()
        try {
            MH_destroydevice!!.invokeExact()
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Non-blocking: ask the render thread for the next frame. */
    fun D3D12AsyncRenderBeginFrame(ctx: Long): Boolean {
        return D3D12AsyncRenderBeginStatus(ctx) == 1
    }

    fun D3D12AsyncRenderBeginStatus(ctx: Long): Int {
        requireLoaded()
        try {
            return MH_asyncrenderbeginframe!!.invokeExact(ptr(ctx)) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Blocking: true only after this frame is successfully submitted. */
    fun D3D12AsyncRenderWaitComplete(ctx: Long, timeoutMs: Long): Boolean {
        return D3D12AsyncRenderWaitStatus(ctx, timeoutMs) == 1
    }

    fun D3D12AsyncRenderWaitStatus(ctx: Long, timeoutMs: Long): Int {
        requireLoaded()
        try {
            return MH_asyncrenderwaitcomplete!!.invokeExact(ptr(ctx), timeoutMs) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Non-blocking: has the render thread reached "recording ready"? */
    fun D3D12AsyncRenderIsRecordingReady(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderisrecordingready!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Blocking "recording ready"; replaces the old sleep(1) poll on the critical path. */
    fun D3D12AsyncRenderWaitRecordingReady(ctx: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderwaitrecordingready!!.invokeExact(ptr(ctx), timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Tell the render thread that all commands are recorded. */
    fun D3D12AsyncSendCommandsReady(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncsendcommandsready!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Clear a completed async context; active submissions retain ownership. */
    fun D3D12ClearAsyncRenderCtx() {
        requireLoaded()
        try {
            MH_clearasyncrenderctx!!.invokeExact()
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Pipeline ----
    /** Compile the HLSL pair and build the pipeline; 0 on failure (see [D3D12LastError]). */
    fun D3D12CreateGraphicsPipeline(desc: MCDRPipelineDesc): Long {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val packed = desc.pack(arena)
            return addr(MH_creategraphicspipeline!!.invokeExact(packed) as MemorySegment)
        }
    }
    fun D3D12DestroyPipeline(pipeline: Long) {
        requireLoaded()
        try {
            MH_destroypipeline!!.invokeExact(ptr(pipeline))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Mesh shaders: only DXC can emit AS/MS (D3DCompile stops at SM 5.1), and the pipeline state
    // stream has no equivalent in the packed graphics descriptor — so native compiles too. ----
    /** Compile one entry point to DXIL; [entry] "" means "main"; null on failure. */
    fun D3D12CompileShaderDxc(source: String, entry: String, profile: String): ByteArray? {
        requireLoaded()
        val utf8 = source.toByteArray(StandardCharsets.UTF_8)
        Arena.ofConfined().use { arena ->
            val src = arena.allocate(utf8.size.toLong().coerceAtLeast(1L))
            MemorySegment.copy(utf8, 0, src, ValueLayout.JAVA_BYTE, 0, utf8.size)
            val entrySeg = cstringUtf8(arena, entry)
            val profileSeg = cstringUtf8(arena, profile)
            // First call probes the required size (null output buffer).
            val size = MH_compileshaderdxc!!.invokeExact(
                src, utf8.size, entrySeg, profileSeg, MemorySegment.NULL, 0) as Int
            if (size <= 0) {
                return null
            }
            val out = arena.allocate(size.toLong())
            val written = MH_compileshaderdxc!!.invokeExact(
                src, utf8.size, entrySeg, profileSeg, out, size) as Int
            if (written != size) {
                return null
            }
            val bytes = ByteArray(size)
            MemorySegment.copy(out, ValueLayout.JAVA_BYTE, 0, bytes, 0, size)
            return bytes
        }
    }
    /** Build an AS/MS pipeline from DXIL; 0 on failure. */
    fun D3D12CreateMeshPipeline(desc: MCDRMeshPipelineDesc): Long {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val packed = desc.pack(arena)
            return addr(MH_createmeshpipeline!!.invokeExact(packed) as MemorySegment)
        }
    }
    /** Same as [D3D12CreateMeshPipeline], but reusing the caller-supplied `ID3D12RootSignature*` address (0 = create one). */
    /** Build a root signature from a binding layout; `meshExtras` != 0 appends b8 + t14/t15. */
    fun D3D12CreateMeshRootSignature(bindings: MemorySegment?, count: Int, meshExtras: Int): Long {
        requireLoaded()
        val handle = MH_meshrootsig ?: return 0L
        val ptr = bindings ?: MemorySegment.NULL
        return handle.invokeExact(ptr, count, meshExtras) as Long
    }
    /** Same, from a JVM (type, reg) list: type 0=CBV 1=SRV+sampler 2=SRV, reg = b#/t#. */
    fun D3D12CreateMeshRootSignature(bindings: List<Pair<Int, Int>>, meshExtras: Int = 1): Long {
        requireLoaded()
        val handle = MH_meshrootsig ?: return 0L
        if (bindings.isEmpty()) {
            return handle.invokeExact(MemorySegment.NULL, 0, meshExtras) as Long
        }
        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(2L * bindings.size)
            bindings.forEachIndexed { index, (type, reg) ->
                segment.set(ValueLayout.JAVA_BYTE, index * 2L, type.toByte())
                segment.set(ValueLayout.JAVA_BYTE, index * 2L + 1, reg.toByte())
            }
            return handle.invokeExact(segment, bindings.size, meshExtras) as Long
        }
    }
    /** Release a root signature from [D3D12CreateMeshRootSignature]; 0 is safe. */
    fun D3D12ReleaseRootSignature(handle: Long) {
        if (handle == 0L) return
        MH_meshrootsigrelease?.invokeExact(handle)
    }
    fun D3D12CreateMeshPipelineWithRootSignature(desc: MCDRMeshPipelineDesc, rootSignature: Long): Long {
        requireLoaded()
        val handle = MH_createmeshpipelineroot ?: return 0L
        Arena.ofConfined().use { arena ->
            val packed = desc.pack(arena)
            return addr(handle.invokeExact(packed, rootSignature) as MemorySegment)
        }
    }
    /** `DispatchMesh(x,y,z)`; needs a mesh pipeline bound and an open render pass. */
    fun D3D12DispatchMesh(ctx: Long, x: Int, y: Int, z: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_dispatchmesh!!.invokeExact(ptr(ctx), x, y, z) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /**
     * One `DispatchMesh(meshletCount)` for a whole batch. Binding contract (`meshExtras = 1`):
     * b8 = [globals] (256-aligned, first dword = meshletCount), t14 vertex pool (28 B/vertex),
     * t15 section table (32 B), t16 meshlet table (32 B) — all RAW SRVs, no index buffer.
     */
    fun D3D12MeshDrawMeshlets(ctx: Long, vertexBuffer: Long, vertexOffset: Long, sections: Long,
                              sectionsOffset: Long, sectionsLength: Long, meshlets: Long, meshletsOffset: Long,
                              meshletsLength: Long, globals: Long, globalsOffset: Long,
                              meshletCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_meshdrawmeshlets!!.invokeExact(
                ptr(ctx), ptr(vertexBuffer), vertexOffset, ptr(sections), sectionsOffset, sectionsLength,
                ptr(meshlets), meshletsOffset, meshletsLength, ptr(globals), globalsOffset, meshletCount
            ) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Compute: fixed root signature [0] root UAV u0, [1] root constants b0 (8 dwords), matching
    // [D3D12DispatchCompute]; Dispatch() on a DIRECT list is legal, no compute queue needed. ----
    /** Build the compute PSO from `cs_6_x` DXIL; 0 on failure. */
    fun D3D12CreateComputePipeline(dxil: ByteArray): Long {
        requireLoaded()
        val handle = MH_createcomputepipeline ?: return 0L
        Arena.ofConfined().use { arena ->
            val src = arena.allocate(dxil.size.toLong().coerceAtLeast(1L))
            MemorySegment.copy(dxil, 0, src, ValueLayout.JAVA_BYTE, 0, dxil.size)
            return addr(handle.invokeExact(src, dxil.size) as MemorySegment)
        }
    }

    /** SRV 走 t0..、UAV 走 u0..，各 0..16；两张表可以任一为空。 */
    fun D3D12CreateComputePipelineEx(dxil: ByteArray, srvCount: Int, uavCount: Int): Long {
        requireLoaded()
        val handle = MH_createcomputepipelineex ?: return 0L
        Arena.ofConfined().use { arena ->
            val src = arena.allocate(dxil.size.toLong().coerceAtLeast(1L))
            MemorySegment.copy(dxil, 0, src, ValueLayout.JAVA_BYTE, 0, dxil.size)
            return addr(handle.invokeExact(src, dxil.size, srvCount, uavCount) as MemorySegment)
        }
    }

    /**
     * `[srvs]`/`[uavs]` 是纹理或缓冲句柄，二者不能是同一张纹理。
     * `[cbv]` 是整块常量缓冲（0 = 不绑），`[cbvOffset]` 必须 256 字节对齐（D3D12 常量缓冲要求）。
     * `false` = 参数错或列表未开。
     */
    fun D3D12DispatchComputeEx(ctx: Long, pipeline: Long, srvs: LongArray, uavs: LongArray,
                               constants: IntArray, cbv: Long, cbvOffset: Long,
                               x: Int, y: Int, z: Int): Boolean {
        requireLoaded()
        val handle = MH_dispatchcomputeex ?: return false
        val count = constants.size
        require(count in 0..8) { "compute constants: 0..8 dwords, got $count" }
        Arena.ofConfined().use { arena ->
            fun handles(values: LongArray): MemorySegment {
                if (values.isEmpty()) return MemorySegment.NULL
                val seg = arena.allocate(values.size * 8L)
                values.forEachIndexed { i, v -> seg.set(MCDRLayout.PTR, i * 8L, ptr(v)) }
                return seg
            }
            val srvSeg = handles(srvs)
            val uavSeg = handles(uavs)
            val cSeg = if (count == 0) MemorySegment.NULL else arena.allocate(count * 4L)
            for (i in 0 until count) cSeg.set(MCDRLayout.I32, i * 4L, constants[i])
            return ok(
                handle.invokeExact(ptr(ctx), ptr(pipeline), srvSeg, srvs.size, uavSeg, uavs.size,
                    cSeg, count, ptr(cbv), cbvOffset, x, y, z) as Int
            )
        }
    }

    /**
     * 一条 BLAS 几何（`mcdr_rt_geometry` 的 Kotlin 镜像）：顶点与索引都指向已经上传到 GPU 的缓冲，
     * 偏移是**字节**偏移（顶点为 `StartVertexLocation * vertexStride`）。
     * [indexStride] 是索引宽度（2 = R16_UINT、4 = R32_UINT）：段内顶点超过 65535 时必须是 4。
     * [useAlphaTest] 为 false 时几何标成不透明 —— 驱动据此跳过任何跨几何的 alpha 判定。
     */
    class RtGeometry(
        val vertexBuffer: Long,
        val vertexStride: Long,
        val vertexCount: Long,
        val vertexOffset: Long,
        val indexBuffer: Long,
        val indexCount: Long,
        val indexOffset: Long,
        val indexStride: Int = 2,
        val useAlphaTest: Boolean = false,
    )

    /** 建一块能直接当 BLAS/TLAS 用的缓冲（DEFAULT 堆 + ALLOW_UNORDERED_ACCESS，初始状态 AS）。0 = 失败。 */
    fun D3D12CreateAccelStructBuffer(size: Long): Long {
        requireLoaded()
        try {
            return addr(MH_createaccelstructbuffer!!.invokeExact(size) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * 查询 BLAS 预构建信息，返回 `[结果上限字节, scratch 字节]`；失败返回 null（原因见 native 最后错误）。
     * 最多 4 条几何（native 侧 `kMaxAccelGeometries`）。
     */
    fun D3D12GetBlasPrebuildInfo(geometries: List<RtGeometry>): LongArray? {
        requireLoaded()
        require(geometries.isNotEmpty() && geometries.size <= MAX_ACCEL_GEOMETRIES) {
            "blas geometries: 1..$MAX_ACCEL_GEOMETRIES, got ${geometries.size}"
        }
        Arena.ofConfined().use { arena ->
            val seg = geometryArray(arena, geometries)
            val out = arena.allocate(16L)
            val ok = MH_getblasprebuildinfo!!.invokeExact(seg, geometries.size, out) as Int
            if (ok == 0) return null
            return longArrayOf(out.get(MCDRLayout.I64, 0L), out.get(MCDRLayout.I64, 8L))
        }
    }

    /** 查询 TLAS 预构建信息，返回 `[结果上限字节, scratch 字节]`；失败返回 null。 */
    fun D3D12GetTlasPrebuildInfo(instanceCount: Int): LongArray? {
        requireLoaded()
        require(instanceCount > 0) { "tlas instances must be positive, got $instanceCount" }
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(16L)
            val ok = MH_gettlasprebuildinfo!!.invokeExact(instanceCount, out) as Int
            if (ok == 0) return null
            return longArrayOf(out.get(MCDRLayout.I64, 0L), out.get(MCDRLayout.I64, 8L))
        }
    }

    /**
     * 在已打开的命令列表上构建/更新 BLAS。[update] = true 走 `PERFORM_UPDATE`（要求 [dest] 里已经是
     * 同一拓扑的 BLAS）；false 走首次构建（`PREFER_FAST_BUILD + ALLOW_UPDATE`）。
     */
    fun D3D12BuildBlas(ctx: Long, dest: Long, scratch: Long, geometries: List<RtGeometry>,
                       update: Boolean): Boolean {
        requireLoaded()
        require(geometries.isNotEmpty() && geometries.size <= MAX_ACCEL_GEOMETRIES) {
            "blas geometries: 1..$MAX_ACCEL_GEOMETRIES, got ${geometries.size}"
        }
        Arena.ofConfined().use { arena ->
            val seg = geometryArray(arena, geometries)
            return ok(MH_buildblas!!.invokeExact(
                ptr(ctx), ptr(dest), ptr(scratch), seg, geometries.size,
                if (update) 1 else 0,
            ) as Int)
        }
    }

    /** 在已打开的命令列表上重建 TLAS。[instances] 是装满 64 字节实例记录的缓冲。 */
    fun D3D12BuildTlas(ctx: Long, dest: Long, scratch: Long, instances: Long,
                       instanceCount: Int): Boolean {
        requireLoaded()
        require(instanceCount > 0) { "tlas instances must be positive, got $instanceCount" }
        return ok(MH_buildtlas!!.invokeExact(
            ptr(ctx), ptr(dest), ptr(scratch), ptr(instances), instanceCount,
        ) as Int)
    }

    /** 把 [src] 压缩拷贝到 [dest]（COMPACT）。紧凑版要等这次提交执行完才能当 SRV 用，下一帧再切。 */
    fun D3D12CopyAccelStruct(ctx: Long, dest: Long, src: Long): Boolean {
        requireLoaded()
        return ok(MH_copyaccelstruct!!.invokeExact(ptr(ctx), ptr(dest), ptr(src)) as Int)
    }

    /** 给加速结构建 SRV（可直接放进 compute 的 SRV 表给 `RaytracingAccelerationStructure`）。0 = 失败。 */
    fun D3D12CreateAccelStructView(accel: Long): Long {
        requireLoaded()
        try {
            return addr(MH_createaccelstructview!!.invokeExact(ptr(accel)) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** 缓冲的 GPU 虚拟地址（0 = 失败）。加速结构实例描述里必须写这个地址，写句柄等于指向堆。 */
    fun D3D12GetBufferAddress(buffer: Long): Long {
        requireLoaded()
        return try {
            MH_getbufferaddress!!.invokeExact(ptr(buffer)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * 一个 hit group（`mcdr_rt_hit_group` 的 Kotlin 镜像）：名字 + 三个入口。
     * 三角形几何只需要 [closestHit]（[anyHit] 用于镂空判定）；[intersection] 只有程序化体素才要。
     * 四个字符串只在建状态对象那一次调用里被读，调用返回后无需保留。
     */
    class RtHitGroup(
        val name: String,
        val closestHit: String? = null,
        val anyHit: String? = null,
        val intersection: String? = null,
    )

    /**
     * 用 `lib_6_x` 编出来的 DXIL 库建光线追踪状态对象（`D3D12_STATE_OBJECT_TYPE_RAYTRACING_PIPELINE`）。
     * 全局根签名与 compute 共用同一套四参数，所以 DispatchRays 与 compute 派发绑的是同一批槽位。
     * [maxRecursion] 1..8（S3 只要 1）；返回 0 = 失败（原因在 native 日志里）。
     */
    fun D3D12CreateRaytracingPipeline(dxil: ByteArray, raygen: String, miss: String,
                                      hitGroups: List<RtHitGroup>, srvCount: Int, uavCount: Int,
                                      maxRecursion: Int = 1): Long {
        requireLoaded()
        require(hitGroups.isNotEmpty()) { "ray tracing pipeline needs at least one hit group" }
        require(hitGroups.size <= MAX_RT_HIT_GROUPS) {
            "hit group count ${hitGroups.size} exceeds $MAX_RT_HIT_GROUPS"
        }
        val handle = MH_createraytracingpipeline ?: return 0L
        Arena.ofConfined().use { arena ->
            val src = arena.allocate(dxil.size.toLong().coerceAtLeast(1L))
            MemorySegment.copy(dxil, 0, src, ValueLayout.JAVA_BYTE, 0, dxil.size)
            val groups = arena.allocate(hitGroups.size * MCDRLayout.RT_HIT_GROUP.byteSize())
            hitGroups.forEachIndexed { i, group ->
                val base = i * MCDRLayout.RT_HIT_GROUP.byteSize()
                groups.set(MCDRLayout.PTR, base + MCDRLayout.RT_HIT_GROUP_NAME,
                    cstringUtf8(arena, group.name))
                group.closestHit?.let {
                    groups.set(MCDRLayout.PTR, base + MCDRLayout.RT_HIT_GROUP_CLOSEST_HIT,
                        cstringUtf8(arena, it))
                }
                group.anyHit?.let {
                    groups.set(MCDRLayout.PTR, base + MCDRLayout.RT_HIT_GROUP_ANY_HIT,
                        cstringUtf8(arena, it))
                }
                group.intersection?.let {
                    groups.set(MCDRLayout.PTR, base + MCDRLayout.RT_HIT_GROUP_INTERSECTION,
                        cstringUtf8(arena, it))
                }
            }
            try {
                return addr(handle.invokeExact(
                    src, dxil.size.toLong(), cstringUtf8(arena, raygen), cstringUtf8(arena, miss),
                    groups, hitGroups.size, srvCount, uavCount, maxRecursion,
                ) as MemorySegment)
            } catch (t: Throwable) {
                throw unexpected(t)
            }
        }
    }

    /** 取某个导出名的 32 B 着色器标识符（SBT 记录的前 32 字节）。null = 名字不存在。 */
    fun D3D12GetShaderIdentifier(pipeline: Long, exportName: String): ByteArray? {
        requireLoaded()
        val handle = MH_getshaderidentifier ?: return null
        Arena.ofConfined().use { arena ->
            val out = arena.allocate(SHADER_IDENTIFIER_BYTES.toLong())
            val name = cstringUtf8(arena, exportName)
            if (!ok(handle.invokeExact(ptr(pipeline), name, out) as Int)) {
                return null
            }
            val bytes = ByteArray(SHADER_IDENTIFIER_BYTES)
            MemorySegment.copy(out, ValueLayout.JAVA_BYTE, 0, bytes, 0, SHADER_IDENTIFIER_BYTES)
            return bytes
        }
    }

    /**
     * 在已打开的命令列表上 DispatchRays。偏移与步长都是字节数：起点必须是 32 的倍数、步长也必须是
     * 32 的倍数（D3D12 规范），记录本身建议 64 B 给局部根参数留位置。
     * `[cbv]`/`[cbvOffset]` 与 compute 派发同一份常量缓冲（偏移 256 对齐）。
     */
    fun D3D12DispatchRays(ctx: Long, pipeline: Long, shaderTable: Long, raygenOffset: Long,
                          missOffset: Long, missStride: Long, missCount: Int, hitOffset: Long,
                          hitStride: Long, hitCount: Int, srvs: LongArray, uavs: LongArray,
                          constants: IntArray, cbv: Long, cbvOffset: Long,
                          width: Int, height: Int): Boolean {
        requireLoaded()
        val handle = MH_dispatchrays ?: return false
        val count = constants.size
        require(count in 0..8) { "ray tracing constants: 0..8 dwords, got $count" }
        Arena.ofConfined().use { arena ->
            fun handles(values: LongArray): MemorySegment {
                if (values.isEmpty()) return MemorySegment.NULL
                val seg = arena.allocate(values.size * 8L)
                values.forEachIndexed { i, v -> seg.set(MCDRLayout.PTR, i * 8L, ptr(v)) }
                return seg
            }
            val srvSeg = handles(srvs)
            val uavSeg = handles(uavs)
            val cSeg = if (count == 0) MemorySegment.NULL else arena.allocate(count * 4L)
            for (i in 0 until count) cSeg.set(MCDRLayout.I32, i * 4L, constants[i])
            try {
                return ok(handle.invokeExact(
                    ptr(ctx), ptr(pipeline), ptr(shaderTable), raygenOffset, missOffset, missStride,
                    missCount, hitOffset, hitStride, hitCount, srvSeg, srvs.size, uavSeg, uavs.size,
                    cSeg, count, ptr(cbv), cbvOffset, width, height,
                ) as Int)
            } catch (t: Throwable) {
                throw unexpected(t)
            }
        }
    }

    data class RtFrameOutput(
        val frameId: Long,
        val snapshotId: Long,
        val direct: Long,
        val indirect: Long,
        val aux: Long,
        val specular: Long,
        val specularGrazing: Long,
        val constants: Long,
        val byteCount: Long,
        val exposure: Long,
        val motion: Long,
        val packedNormalRoughness: Long,
        val viewZ: Long,
        val diffuseSignal: Long,
        val specularSignal: Long,
        val specularHitDistance: Long,
    )

    fun D3D12CreateRtFrame(
        rayDxil: ByteArray, guideDxil: ByteArray, exposureDxil: ByteArray,
        motionDxil: ByteArray, packDxil: ByteArray, signalDxil: ByteArray,
        remodulateDxil: ByteArray, compositeDxil: ByteArray,
    ): Long {
        requireLoaded()
        val handle = MH_creatertframe ?: return 0L
        Arena.ofConfined().use { arena ->
            val program = arena.allocate(MCDRLayout.RT_FRAME_PROGRAM)
            val rayData = arena.allocate(rayDxil.size.toLong()).also { it.asByteBuffer().put(rayDxil) }
            val guideData = arena.allocate(guideDxil.size.toLong()).also { it.asByteBuffer().put(guideDxil) }
            program.set(MCDRLayout.PTR, 0, rayData)
            program.set(MCDRLayout.I64, 8, rayDxil.size.toLong())
            program.set(MCDRLayout.PTR, 16, guideData)
            program.set(MCDRLayout.I64, 24, guideDxil.size.toLong())
            val exposureData = arena.allocate(exposureDxil.size.toLong()).also { it.asByteBuffer().put(exposureDxil) }
            program.set(MCDRLayout.PTR, 32, exposureData)
            program.set(MCDRLayout.I64, 40, exposureDxil.size.toLong())
            val motionData = arena.allocate(motionDxil.size.toLong()).also { it.asByteBuffer().put(motionDxil) }
            program.set(MCDRLayout.PTR, 48, motionData)
            program.set(MCDRLayout.I64, 56, motionDxil.size.toLong())
            val packData = arena.allocate(packDxil.size.toLong()).also { it.asByteBuffer().put(packDxil) }
            program.set(MCDRLayout.PTR, 64, packData)
            program.set(MCDRLayout.I64, 72, packDxil.size.toLong())
            val signalData = arena.allocate(signalDxil.size.toLong()).also { it.asByteBuffer().put(signalDxil) }
            program.set(MCDRLayout.PTR, 80, signalData)
            program.set(MCDRLayout.I64, 88, signalDxil.size.toLong())
            val remodulateData = arena.allocate(remodulateDxil.size.toLong()).also { it.asByteBuffer().put(remodulateDxil) }
            program.set(MCDRLayout.PTR, 96, remodulateData)
            program.set(MCDRLayout.I64, 104, remodulateDxil.size.toLong())
            val compositeData = arena.allocate(compositeDxil.size.toLong()).also { it.asByteBuffer().put(compositeDxil) }
            program.set(MCDRLayout.PTR, 112, compositeData)
            program.set(MCDRLayout.I64, 120, compositeDxil.size.toLong())
            try {
                return handle.invokeExact(program) as Long
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
        }
    }

    fun D3D12ReleaseRtFrame(frame: Long): Boolean {
        requireLoaded()
        val handle = MH_releasertframe ?: return false
        try {
            return ok(handle.invokeExact(frame) as Int)
        } catch (failure: Throwable) {
            throw unexpected(failure)
        }
    }

    data class RtGBufferOutput(
        val revision: Long, val resourceRevision: Long, val frameNanos: Long,
        val width: Int, val height: Int, val colors: LongArray, val depth: Long, val byteCount: Long,
    )

    fun D3D12RtFrameBeginGBuffer(ctx: Long, frame: Long, frameNanos: Long,
                               width: Int, height: Int): RtGBufferOutput? {
        requireLoaded()
        val handle = MH_rtframebegingbuffer ?: return null
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.RT_GBUFFER_OUTPUT)
            try {
                if (!ok(handle.invokeExact(ptr(ctx), frame, frameNanos, width, height, output) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
            return RtGBufferOutput(output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8),
                output.get(MCDRLayout.I64, 16), output.get(MCDRLayout.I32, 24), output.get(MCDRLayout.I32, 28),
                LongArray(5) { output.get(MCDRLayout.PTR, 32 + it * 8L).address() },
                output.get(MCDRLayout.PTR, 72).address(), output.get(MCDRLayout.I64, 80))
        }
    }

    fun D3D12CreateRtGBufferProgram(mesh: ByteArray, pixel: ByteArray,
                                   bindings: List<Pair<Int, Int>>, cullEnabled: Boolean, polygonMode: Int): Long {
        requireLoaded()
        val handle = MH_creatertgbufferprogram ?: return 0L
        Arena.ofConfined().use { arena ->
            val description = arena.allocate(MCDRLayout.RT_GBUFFER_PROGRAM)
            val meshData = arena.allocate(mesh.size.toLong().coerceAtLeast(1))
            val pixelData = arena.allocate(pixel.size.toLong().coerceAtLeast(1))
            MemorySegment.copy(mesh, 0, meshData, ValueLayout.JAVA_BYTE, 0, mesh.size)
            MemorySegment.copy(pixel, 0, pixelData, ValueLayout.JAVA_BYTE, 0, pixel.size)
            val bindingData = arena.allocate((bindings.size * 2L).coerceAtLeast(1))
            bindings.forEachIndexed { index, [type, reg] ->
                require(type in 0..255 && reg in 0..255)
                bindingData.set(ValueLayout.JAVA_BYTE, index * 2L, type.toByte())
                bindingData.set(ValueLayout.JAVA_BYTE, index * 2L + 1, reg.toByte())
            }
            description.set(MCDRLayout.PTR, 0, meshData)
            description.set(MCDRLayout.I64, 8, mesh.size.toLong())
            description.set(MCDRLayout.PTR, 16, pixelData)
            description.set(MCDRLayout.I64, 24, pixel.size.toLong())
            description.set(MCDRLayout.PTR, 32, bindingData)
            description.set(MCDRLayout.I32, 40, bindings.size)
            description.set(MCDRLayout.I32, 44, if (cullEnabled) 1 else 0)
            description.set(MCDRLayout.I32, 48, polygonMode)
            return try {
                handle.invokeExact(description) as Long
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
        }
    }

    fun D3D12ReleaseRtGBufferProgram(program: Long): Boolean {
        requireLoaded()
        val handle = MH_releasertgbufferprogram ?: return false
        return try {
            ok(handle.invokeExact(program) as Int)
        } catch (failure: Throwable) {
            throw unexpected(failure)
        }
    }

    data class RtGBufferBinding(
        val type: Int, val buffer: Long = 0L, val offset: Long = 0L, val length: Long = 0L,
        val texelFormat: Int = 0, val view: Long = 0L,
    )

    data class RtGBufferBatch(
        val program: Long, val vertices: Long, val vertexOffset: Long,
        val sections: Long, val sectionsOffset: Long, val sectionsLength: Long,
        val meshlets: Long, val meshletsOffset: Long, val meshletsLength: Long,
        val meshletCount: Int, val bindings: List<RtGBufferBinding>, val projectionBinding: Int,
    )

    private fun packRtGBufferBatch(arena: Arena, slot: MemorySegment, batch: RtGBufferBatch) {
        require(batch.bindings.isNotEmpty() && batch.bindings.size <= 64)
        val bindingStride = MCDRLayout.BINDING_SLOT.byteSize()
        val bindings = arena.allocate(bindingStride * batch.bindings.size, 8)
        for ([bindingIndex, binding] in batch.bindings.withIndex()) {
            val entry = bindings.asSlice(bindingIndex * bindingStride, bindingStride)
            entry.set(MCDRLayout.I32, 0, binding.type)
            entry.set(MCDRLayout.I32, 4, binding.texelFormat)
            entry.set(MCDRLayout.PTR, 8, ptr(binding.buffer))
            entry.set(MCDRLayout.PTR, 16, ptr(binding.view))
            entry.set(MCDRLayout.I64, 24, binding.offset)
            entry.set(MCDRLayout.I64, 32, binding.length)
        }
        slot.set(MCDRLayout.I64, 0, batch.program)
        slot.set(MCDRLayout.PTR, 8, ptr(batch.vertices))
        slot.set(MCDRLayout.I64, 16, batch.vertexOffset)
        slot.set(MCDRLayout.PTR, 24, ptr(batch.sections))
        slot.set(MCDRLayout.I64, 32, batch.sectionsOffset)
        slot.set(MCDRLayout.I64, 40, batch.sectionsLength)
        slot.set(MCDRLayout.PTR, 48, ptr(batch.meshlets))
        slot.set(MCDRLayout.I64, 56, batch.meshletsOffset)
        slot.set(MCDRLayout.I64, 64, batch.meshletsLength)
        slot.set(MCDRLayout.I32, 72, batch.meshletCount)
        slot.set(MCDRLayout.I32, 76, batch.bindings.size)
        slot.set(MCDRLayout.PTR, 80, bindings)
        slot.set(MCDRLayout.I32, 88, batch.projectionBinding)
    }

    fun D3D12RtCaptureGBuffer(ctx: Long, capture: Long, frameNanos: Long, batch: RtGBufferBatch): Long {
        requireLoaded()
        val handle = MH_rtcapturegbuffer ?: return 0L
        Arena.ofConfined().use { arena ->
            val description = arena.allocate(MCDRLayout.RT_GBUFFER_BATCH)
            packRtGBufferBatch(arena, description, batch)
            return try {
                handle.invokeExact(ptr(ctx), capture, frameNanos, description) as Long
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
        }
    }

    fun D3D12RtReleaseGBufferCapture(ctx: Long, capture: Long): Boolean {
        requireLoaded()
        val handle = MH_rtreleasegbuffercapture ?: return false
        return try {
            ok(handle.invokeExact(ptr(ctx), capture) as Int)
        } catch (failure: Throwable) {
            throw unexpected(failure)
        }
    }

    fun D3D12RtFrameReplayCapturedGBuffer(
        ctx: Long, frame: Long, revision: Long, capture: Long, projection: FloatArray,
        cameraOffsetX: Float, cameraOffsetY: Float, cameraOffsetZ: Float,
    ): Boolean {
        requireLoaded()
        require(projection.size == 16)
        val handle = MH_rtframereplaycapturedgbuffer ?: return false
        Arena.ofConfined().use { arena ->
            val description = arena.allocate(MCDRLayout.RT_GBUFFER_REPLAY)
            description.set(MCDRLayout.I64, 0, revision)
            description.set(MCDRLayout.I32, 20, projection.size)
            description.set(MCDRLayout.PTR, 24, arena.allocateFrom(MCDRLayout.F32, *projection))
            description.set(MCDRLayout.F32, 32, cameraOffsetX)
            description.set(MCDRLayout.F32, 36, cameraOffsetY)
            description.set(MCDRLayout.F32, 40, cameraOffsetZ)
            return try {
                ok(handle.invokeExact(ptr(ctx), frame, capture, description) as Int)
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
        }
    }

    fun D3D12RtFrameReplayGBuffer(
        ctx: Long, frame: Long, revision: Long, batches: List<RtGBufferBatch>, projection: FloatArray,
        cameraOffsetX: Float, cameraOffsetY: Float, cameraOffsetZ: Float,
    ): Boolean {
        requireLoaded()
        require(batches.isNotEmpty() && batches.size <= 65536 && projection.size == 16)
        val handle = MH_rtframereplaygbuffer ?: return false
        Arena.ofConfined().use { arena ->
            val description = arena.allocate(MCDRLayout.RT_GBUFFER_REPLAY)
            val batchStride = MCDRLayout.RT_GBUFFER_BATCH.byteSize()
            val batchData = arena.allocate(batchStride * batches.size, 8)
            for ([batchIndex, batch] in batches.withIndex()) {
                packRtGBufferBatch(arena, batchData.asSlice(batchIndex * batchStride, batchStride), batch)
            }
            val projectionData = arena.allocateFrom(MCDRLayout.F32, *projection)
            description.set(MCDRLayout.I64, 0, revision)
            description.set(MCDRLayout.PTR, 8, batchData)
            description.set(MCDRLayout.I32, 16, batches.size)
            description.set(MCDRLayout.I32, 20, projection.size)
            description.set(MCDRLayout.PTR, 24, projectionData)
            description.set(MCDRLayout.F32, 32, cameraOffsetX)
            description.set(MCDRLayout.F32, 36, cameraOffsetY)
            description.set(MCDRLayout.F32, 40, cameraOffsetZ)
            return try {
                ok(handle.invokeExact(ptr(ctx), frame, description) as Int)
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
        }
    }

    fun D3D12RtFrameEndGBuffer(ctx: Long, frame: Long, revision: Long, success: Boolean): Boolean {
        requireLoaded()
        val handle = MH_rtframeendgbuffer ?: return false
        return try {
            ok(handle.invokeExact(ptr(ctx), frame, revision, if (success) 1 else 0) as Int)
        } catch (failure: Throwable) {
            throw unexpected(failure)
        }
    }

    data class RtGeometryOutput(
        val snapshotId: Long,
        val records: Long,
        val vertices: Long,
        val indices: Long,
        val materials: Long,
        val quads: Long,
        val byteCount: Long,
        val uploadedBytes: Long = 0,
        val uploadCapacity: Long = 0,
    )

    fun D3D12CreateRtGeometrySnapshot(ctx: Long, worldEpoch: Long, geometryRevision: Long,
        materialRevision: Long, reuseGeometry: Long, reuseMaterials: Long,
        records: ByteBuffer?, vertices: ByteBuffer?, indices: ByteBuffer?, materials: ByteBuffer?,
        quads: ByteBuffer?): RtGeometryOutput? {
        requireLoaded()
        val handle = MH_creatertgeometrysnapshot ?: return null
        Arena.ofConfined().use { arena ->
            val description = arena.allocate(MCDRLayout.RT_GEOMETRY_DESC)
            val output = arena.allocate(MCDRLayout.RT_GEOMETRY_OUTPUT)
            longArrayOf(worldEpoch, geometryRevision, materialRevision, reuseGeometry, reuseMaterials)
                .forEachIndexed { index, value -> description.set(MCDRLayout.I64, index * 8L, value) }
            val inputs = listOf(records, vertices, indices, materials, quads)
            inputs.forEachIndexed { index, buffer ->
                if (buffer != null && buffer.hasRemaining()) {
                    val data = if (buffer.isDirect) MemorySegment.ofBuffer(buffer) else {
                        arena.allocate(buffer.remaining().toLong(), 16).also {
                            it.copyFrom(MemorySegment.ofBuffer(buffer))
                        }
                    }
                    description.set(MCDRLayout.PTR, 40 + index * 16L, data)
                    description.set(MCDRLayout.I64, 48 + index * 16L, buffer.remaining().toLong())
                }
            }
            try {
                if (!ok(handle.invokeExact(ptr(ctx), description, output) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            } finally {
                java.lang.ref.Reference.reachabilityFence(inputs)
            }
            return RtGeometryOutput(output.get(MCDRLayout.I64, 0),
                output.get(MCDRLayout.PTR, 8).address(), output.get(MCDRLayout.PTR, 16).address(),
                output.get(MCDRLayout.PTR, 24).address(), output.get(MCDRLayout.PTR, 32).address(),
                output.get(MCDRLayout.PTR, 40).address(), output.get(MCDRLayout.I64, 48))
        }
    }

    fun D3D12ReleaseRtGeometrySnapshot(snapshot: Long): Boolean {
        requireLoaded()
        val handle = MH_releasertgeometrysnapshot ?: return false
        return try {
            ok(handle.invokeExact(snapshot) as Int)
        } catch (failure: Throwable) {
            throw unexpected(failure)
        }
    }

    data class RtGeometryContent(val identity: Long, val byteCount: Long, val bytes: ByteBuffer?)

    fun D3D12CreateRtGeometryDelta(ctx: Long, worldEpoch: Long, geometryRevision: Long,
        materialRevision: Long, baseSnapshot: Long, reuseMaterials: Long, records: ByteBuffer,
        contents: List<List<RtGeometryContent>>, materials: ByteBuffer?, capacities: LongArray): RtGeometryOutput? {
        requireLoaded()
        require(contents.all { it.size == 3 } && capacities.size == 3)
        val handle = MH_creatertgeometrydelta ?: return null
        Arena.ofConfined().use { arena ->
            fun payload(buffer: ByteBuffer?): MemorySegment {
                if (buffer == null || !buffer.hasRemaining()) return MemorySegment.NULL
                if (buffer.isDirect) return MemorySegment.ofBuffer(buffer)
                return arena.allocate(buffer.remaining().toLong(), 16).also {
                    it.copyFrom(MemorySegment.ofBuffer(buffer))
                }
            }
            val description = arena.allocate(MCDRLayout.RT_GEOMETRY_DELTA_DESC)
            val output = arena.allocate(MCDRLayout.RT_GEOMETRY_DELTA_OUTPUT)
            val entries = if (contents.isEmpty()) MemorySegment.NULL
                else arena.allocate(MCDRLayout.RT_GEOMETRY_CONTENT.byteSize() * contents.size, 8)
            longArrayOf(worldEpoch, geometryRevision, materialRevision, baseSnapshot, reuseMaterials)
                .forEachIndexed { index, value -> description.set(MCDRLayout.I64, index * 8L, value) }
            description.set(MCDRLayout.PTR, 40, payload(records))
            description.set(MCDRLayout.I64, 48, records.remaining().toLong())
            description.set(MCDRLayout.PTR, 56, entries)
            description.set(MCDRLayout.I32, 64, contents.size)
            description.set(MCDRLayout.PTR, 72, payload(materials))
            description.set(MCDRLayout.I64, 80, materials?.remaining()?.toLong() ?: 0L)
            capacities.forEachIndexed { index, capacity -> description.set(MCDRLayout.I64, 88L + index * 8L, capacity) }
            contents.forEachIndexed { record, groups ->
                groups.forEachIndexed { kind, content ->
                    require(content.bytes == null || content.bytes.remaining().toLong() == content.byteCount)
                    val base = record * 72L
                    entries.set(MCDRLayout.I64, base + kind * 8L, content.identity)
                    entries.set(MCDRLayout.PTR, base + 24L + kind * 16L, payload(content.bytes))
                    entries.set(MCDRLayout.I64, base + 32L + kind * 16L, content.byteCount)
                }
            }
            try {
                if (!ok(handle.invokeExact(ptr(ctx), description, output) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            } finally {
                java.lang.ref.Reference.reachabilityFence(contents)
                java.lang.ref.Reference.reachabilityFence(records)
                java.lang.ref.Reference.reachabilityFence(materials)
            }
            return RtGeometryOutput(output.get(MCDRLayout.I64, 0),
                output.get(MCDRLayout.PTR, 8).address(), output.get(MCDRLayout.PTR, 16).address(),
                output.get(MCDRLayout.PTR, 24).address(), output.get(MCDRLayout.PTR, 32).address(),
                output.get(MCDRLayout.PTR, 40).address(), output.get(MCDRLayout.I64, 48),
                output.get(MCDRLayout.I64, 56), output.get(MCDRLayout.I64, 64))
        }
    }

    fun D3D12GetRtGeometryMemory(): LongArray? {
        requireLoaded()
        val handle = MH_getrtgeometrymemory ?: return null
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.RT_GEOMETRY_MEMORY)
            try {
                if (!ok(handle.invokeExact(output) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
            return longArrayOf(output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8))
        }
    }

    data class RtAccelerationBudget(
        val byteCount: Long,
        val limitBytes: Long,
        val rejectionCount: Long,
        val peakBytes: Long,
    )

    fun D3D12GetRtAccelerationBudget(): RtAccelerationBudget? {
        requireLoaded()
        val handle = MH_getrtaccelerationbudget ?: return null
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.I64, 4L)
            try {
                if (!ok(handle.invokeExact(output, 4) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
            return RtAccelerationBudget(output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8),
                output.get(MCDRLayout.I64, 16), output.get(MCDRLayout.I64, 24))
        }
    }

    data class RtAccelerationOutput(
        val snapshotId: Long, val acceleration: Long, val instances: Long, val byteCount: Long,
        val instanceCount: Int, val blasCount: Int, val buildCount: Int, val ready: Boolean,
        val blasMicros: Long, val peakBuildMicros: Long, val tlasMicros: Long, val scratchBytes: Long,
    )

    private fun rtAccelerationOutput(output: MemorySegment): RtAccelerationOutput = RtAccelerationOutput(
        output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.PTR, 8).address(),
        output.get(MCDRLayout.PTR, 16).address(), output.get(MCDRLayout.I64, 24),
        output.get(MCDRLayout.I32, 32), output.get(MCDRLayout.I32, 36),
        output.get(MCDRLayout.I32, 40), output.get(MCDRLayout.I32, 44) == 1,
        output.get(MCDRLayout.I64, 48), output.get(MCDRLayout.I64, 56),
        output.get(MCDRLayout.I64, 64), output.get(MCDRLayout.I64, 72),
    )

    fun D3D12CreateRtAcceleration(snapshot: Long, reuse: Long): Long {
        requireLoaded()
        val handle = MH_creatertacceleration ?: return 0L
        return try { handle.invokeExact(snapshot, reuse) as Long } catch (failure: Throwable) { throw unexpected(failure) }
    }

    fun D3D12ReleaseRtAcceleration(acceleration: Long): Boolean {
        requireLoaded()
        val handle = MH_releasertacceleration ?: return false
        return try { ok(handle.invokeExact(acceleration) as Int) } catch (failure: Throwable) { throw unexpected(failure) }
    }

    fun D3D12PrepareRtAcceleration(ctx: Long, acceleration: Long): RtAccelerationOutput? {
        requireLoaded()
        val handle = MH_preparertacceleration ?: return null
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.RT_ACCELERATION_OUTPUT)
            try {
                if (!ok(handle.invokeExact(ptr(ctx), acceleration, output) as Int)) return null
            } catch (failure: Throwable) { throw unexpected(failure) }
            return rtAccelerationOutput(output)
        }
    }

    fun D3D12FilterRtAcceleration(ctx: Long, acceleration: Long, invalidSections: Set<Long>): RtAccelerationOutput? {
        requireLoaded()
        val handle = MH_filterrtacceleration ?: return null
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.RT_ACCELERATION_OUTPUT)
            val sections = if (invalidSections.isEmpty()) MemorySegment.NULL else arena.allocate(invalidSections.size * 8L, 8)
            invalidSections.forEachIndexed { index, section -> sections.set(MCDRLayout.I64, index * 8L, section) }
            try {
                if (!ok(handle.invokeExact(ptr(ctx), acceleration, sections, invalidSections.size, output) as Int)) return null
            } catch (failure: Throwable) { throw unexpected(failure) }
            return rtAccelerationOutput(output)
        }
    }

    fun D3D12RtAccelerationMemory(first: Long, second: Long): Long {
        requireLoaded()
        val handle = MH_rtaccelerationmemory ?: return 0L
        return try { handle.invokeExact(first, second) as Long } catch (failure: Throwable) { throw unexpected(failure) }
    }

    data class RtSceneOutput(
        val snapshotId: Long, val accelerationId: Long, val pendingSnapshotId: Long,
        val pendingAccelerationId: Long, val sceneRevision: Long, val geometryRevision: Long,
        val materialRevision: Long, val byteCount: Long,
        val active: RtAccelerationOutput, val candidate: RtAccelerationOutput,
        val coverageFlags: Long,
    )

    data class RtSceneStats(
        val worldEpoch: Long, val frameId: Long, val targetSnapshotId: Long,
        val recordCount: Int, val recordedCount: Int, val pendingBuildCount: Int,
        val obsoleteCount: Int, val absentCount: Int, val proxySectionCount: Int,
        val buildsThisFrame: Int, val oldestRequestFrame: Long, val sceneRevision: Long,
        val pendingSnapshotId: Long, val liveRevision: Long,
    ) {
        val oldestAgeFrames: Long get() = if (oldestRequestFrame == 0L) 0L else
            (frameId - oldestRequestFrame).coerceAtLeast(0L)
    }

    data class RtSceneWorkBudget(
        val worldEpoch: Long, val frameId: Long,
        val cpuLimitNanos: Long, val gpuLimitNanos: Long, val triangleLimit: Long,
        val cpuNanos: Long, val predictedNanos: Long, val triangles: Long, val publications: Long,
        val deferred: Long, val oversized: Long, val samples: Long,
        val lastGpuNanos: Long, val totalGpuNanos: Long, val cpuOverruns: Long, val gpuOverruns: Long,
        val pendingSamples: Long, val cancelledSamples: Long, val failures: Long, val peakGpuNanos: Long,
        val lastGpuFrame: Long, val lastGpuWorld: Long, val lastCpuNanos: Long, val factor: Long,
    )

    fun D3D12SetRtSceneWorkBudget(scene: Long, cpuMicros: Long, gpuMicros: Long, triangles: Long): Boolean {
        requireLoaded()
        val handle = MH_setrtsceneworkbudget ?: return false
        return try {
            ok(handle.invokeExact(scene, cpuMicros, gpuMicros, triangles) as Int)
        } catch (failure: Throwable) { throw unexpected(failure) }
    }

    fun D3D12GetRtSceneWorkBudget(scene: Long): RtSceneWorkBudget? {
        requireLoaded()
        val handle = MH_getrtsceneworkbudget ?: return null
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.I64, 24L)
            try {
                if (!ok(handle.invokeExact(scene, output, 24) as Int)) return null
            } catch (failure: Throwable) { throw unexpected(failure) }
            val values = LongArray(24) { index -> output.get(MCDRLayout.I64, index * 8L) }
            return RtSceneWorkBudget(values[0], values[1], values[2], values[3], values[4], values[5],
                values[6], values[7], values[8], values[9], values[10], values[11], values[12], values[13],
                values[14], values[15], values[16], values[17], values[18], values[19], values[20], values[21],
                values[22], values[23])
        }
    }

    fun D3D12GetRtSceneStats(scene: Long): RtSceneStats? {
        requireLoaded()
        val handle = MH_getrtscenestats ?: return null
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(14L * 8, 8)
            try {
                if (!ok(handle.invokeExact(scene, output, 14) as Int)) return null
            } catch (failure: Throwable) { throw unexpected(failure) }
            return RtSceneStats(output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8),
                output.get(MCDRLayout.I64, 16), output.get(MCDRLayout.I64, 24).toInt(),
                output.get(MCDRLayout.I64, 32).toInt(), output.get(MCDRLayout.I64, 40).toInt(),
                output.get(MCDRLayout.I64, 48).toInt(), output.get(MCDRLayout.I64, 56).toInt(),
                output.get(MCDRLayout.I64, 64).toInt(), output.get(MCDRLayout.I64, 72).toInt(),
                output.get(MCDRLayout.I64, 80), output.get(MCDRLayout.I64, 88),
                output.get(MCDRLayout.I64, 96), output.get(MCDRLayout.I64, 104))
        }
    }

    fun D3D12CreateRtScene(): Long {
        requireLoaded()
        val handle = MH_creatertscene ?: return 0L
        return try { handle.invokeExact() as Long } catch (failure: Throwable) { throw unexpected(failure) }
    }

    fun D3D12ReleaseRtScene(scene: Long): Boolean {
        requireLoaded()
        val handle = MH_releasertscene ?: return false
        return try { ok(handle.invokeExact(scene) as Int) } catch (failure: Throwable) { throw unexpected(failure) }
    }

    fun D3D12PrepareRtScene(ctx: Long, scene: Long, worldEpoch: Long, snapshot: Long,
        liveRevision: Long, versions: ByteBuffer, frameId: Long): RtSceneOutput? =
        prepareRtScene(ctx, scene, worldEpoch, snapshot, liveRevision, versions, frameId, null, null)

    fun D3D12PrepareRtSceneTargets(ctx: Long, scene: Long, worldEpoch: Long, snapshot: Long,
        liveRevision: Long, versions: ByteBuffer, frameId: Long, targets: ByteBuffer,
        outputs: ByteBuffer? = null): RtSceneOutput? =
        prepareRtScene(ctx, scene, worldEpoch, snapshot, liveRevision, versions, frameId, targets, outputs)

    private fun prepareRtScene(ctx: Long, scene: Long, worldEpoch: Long, snapshot: Long,
        liveRevision: Long, versions: ByteBuffer, frameId: Long, targets: ByteBuffer?,
        outputs: ByteBuffer?): RtSceneOutput? {
        requireLoaded()
        val handle = (if (targets == null) MH_preparertscene else MH_preparertscenetargets) ?: return null
        if (versions.remaining() % 16 != 0 || frameId <= 0L) return null
        if (targets != null && (targets.remaining() % 8 != 0 || targets.remaining() / 8 > 65535)) return null
        if (outputs != null && (outputs.remaining() % 48 != 0 || outputs.remaining() / 48 > 1048576)) return null
        Arena.ofConfined().use { arena ->
            val description = arena.allocate(MCDRLayout.RT_SCENE_DESC)
            val output = arena.allocate(MCDRLayout.RT_SCENE_OUTPUT)
            val sections = if (versions.hasRemaining()) arena.allocate(versions.remaining().toLong(), 8).also {
                it.copyFrom(MemorySegment.ofBuffer(versions))
            } else MemorySegment.NULL
            description.set(MCDRLayout.I64, 0, worldEpoch)
            description.set(MCDRLayout.I64, 8, snapshot)
            description.set(MCDRLayout.I64, 16, liveRevision)
            description.set(MCDRLayout.PTR, 24, sections)
            description.set(MCDRLayout.I32, 32, versions.remaining() / 16)
            description.set(MCDRLayout.I64, 40, frameId)
            if (outputs != null) {
                val support = if (outputs.hasRemaining()) arena.allocate(outputs.remaining().toLong(), 8).also {
                    it.copyFrom(MemorySegment.ofBuffer(outputs))
                } else MemorySegment.NULL
                description.set(MCDRLayout.PTR, 48, support)
                description.set(MCDRLayout.I32, 56, outputs.remaining() / 48)
                description.set(MCDRLayout.I32, 60, 1)
            }
            try {
                val status = if (targets == null) {
                    handle.invokeExact(ptr(ctx), scene, description, output) as Int
                } else {
                    val nodes = if (targets.hasRemaining()) arena.allocate(targets.remaining().toLong(), 8).also {
                        it.copyFrom(MemorySegment.ofBuffer(targets))
                    } else MemorySegment.NULL
                    handle.invokeExact(ptr(ctx), scene, description, nodes, targets.remaining() / 8, output) as Int
                }
                if (!ok(status)) return null
            } catch (failure: Throwable) { throw unexpected(failure) }
            return RtSceneOutput(output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8),
                output.get(MCDRLayout.I64, 16), output.get(MCDRLayout.I64, 24),
                output.get(MCDRLayout.I64, 32), output.get(MCDRLayout.I64, 40),
                output.get(MCDRLayout.I64, 48), output.get(MCDRLayout.I64, 56),
                rtAccelerationOutput(output.asSlice(64, 80)), rtAccelerationOutput(output.asSlice(144, 80)),
                output.get(MCDRLayout.I64, 224))
        }
    }

    fun D3D12RtFrameSetSpatial(ctx: Long, frame: Long, frameId: Long, worldEpoch: Long,
                             liveRevision: Long, spatial: ByteBuffer): Boolean {
        requireLoaded()
        val handle = MH_rtframesetspatial ?: return false
        Arena.ofConfined().use { arena ->
            val data = arena.allocate(spatial.remaining().toLong(), 4)
            data.asByteBuffer().put(spatial.duplicate())
            return try {
                ok(handle.invokeExact(ptr(ctx), frame, frameId, worldEpoch, liveRevision,
                    data, spatial.remaining().toLong()) as Int)
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
        }
    }

    data class RtFrameRequest(
        val frameId: Long, val worldEpoch: Long, val sceneRevision: Long, val materialRevision: Long,
        val snapshotId: Long, val width: Int, val height: Int, val accelerationId: Long,
        val gbufferRevision: Long, val constants: ByteBuffer, val depthScale: Float, val frameNanos: Long,
        val exposureMode: Int, val exposureEv: Float, val exposureSpeed: Float, val resetExposure: Boolean,
        val signalEncoding: Int, val jitterDeltaX: Float, val jitterDeltaY: Float, val denoisingRange: Float,
        val contentJitterX: Float, val contentJitterY: Float,
    )

    private fun writeRtFrameRequest(arena: Arena, description: MemorySegment, request: RtFrameRequest) {
        longArrayOf(request.frameId, request.worldEpoch, request.sceneRevision, request.materialRevision, request.snapshotId)
            .forEachIndexed { index, value -> description.set(MCDRLayout.I64, index * 8L, value) }
        description.set(MCDRLayout.I32, 40, request.width)
        description.set(MCDRLayout.I32, 44, request.height)
        description.set(MCDRLayout.I64, 48, request.accelerationId)
        description.set(MCDRLayout.I64, 56, request.gbufferRevision)
        val constantData = arena.allocate(request.constants.remaining().toLong(), 16)
        constantData.asByteBuffer().put(request.constants.duplicate())
        description.set(MCDRLayout.PTR, 64, constantData)
        description.set(MCDRLayout.I32, 72, request.constants.remaining())
        description.set(MCDRLayout.F32, 76, request.depthScale)
        description.set(MCDRLayout.I64, 80, request.frameNanos)
        description.set(MCDRLayout.I32, 88, request.exposureMode)
        description.set(MCDRLayout.F32, 92, request.exposureEv)
        description.set(MCDRLayout.F32, 96, request.exposureSpeed)
        description.set(MCDRLayout.I32, 100, if (request.resetExposure) 1 else 0)
        description.set(MCDRLayout.I32, 104, request.signalEncoding)
        description.set(MCDRLayout.F32, 108, request.jitterDeltaX)
        description.set(MCDRLayout.F32, 112, request.jitterDeltaY)
        description.set(MCDRLayout.F32, 116, request.denoisingRange)
        description.set(MCDRLayout.F32, 120, request.contentJitterX)
        description.set(MCDRLayout.F32, 124, request.contentJitterY)
    }

    private fun readRtFrameOutput(output: MemorySegment): RtFrameOutput = RtFrameOutput(
        output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8),
        output.get(MCDRLayout.PTR, 16).address(), output.get(MCDRLayout.PTR, 24).address(),
        output.get(MCDRLayout.PTR, 32).address(), output.get(MCDRLayout.PTR, 40).address(),
        output.get(MCDRLayout.PTR, 48).address(), output.get(MCDRLayout.PTR, 56).address(),
        output.get(MCDRLayout.I64, 64), output.get(MCDRLayout.PTR, 72).address(),
        output.get(MCDRLayout.PTR, 80).address(), output.get(MCDRLayout.PTR, 88).address(),
        output.get(MCDRLayout.PTR, 96).address(), output.get(MCDRLayout.PTR, 104).address(),
        output.get(MCDRLayout.PTR, 112).address(), output.get(MCDRLayout.PTR, 120).address(),
    )

    fun D3D12RtFrame(
        ctx: Long, frame: Long, frameId: Long, worldEpoch: Long, sceneRevision: Long,
        materialRevision: Long, snapshotId: Long, width: Int, height: Int,
        accelerationId: Long, gbufferRevision: Long, constants: ByteBuffer, depthScale: Float,
        frameNanos: Long, exposureMode: Int, exposureEv: Float, exposureSpeed: Float, resetExposure: Boolean,
        signalEncoding: Int, jitterDeltaX: Float, jitterDeltaY: Float, denoisingRange: Float,
        contentJitterX: Float, contentJitterY: Float,
    ): RtFrameOutput? {
        requireLoaded()
        val handle = MH_rtframe ?: return null
        Arena.ofConfined().use { arena ->
            val description = arena.allocate(MCDRLayout.RT_FRAME_DESC)
            val output = arena.allocate(MCDRLayout.RT_FRAME_OUTPUT)
            writeRtFrameRequest(arena, description, RtFrameRequest(frameId, worldEpoch, sceneRevision,
                materialRevision, snapshotId, width, height, accelerationId, gbufferRevision, constants,
                depthScale, frameNanos, exposureMode, exposureEv, exposureSpeed, resetExposure,
                signalEncoding, jitterDeltaX, jitterDeltaY, denoisingRange, contentJitterX, contentJitterY))
            try {
                if (!ok(handle.invokeExact(ptr(ctx), frame, description, output) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
            return readRtFrameOutput(output)
        }
    }

    data class RtDenoiseOutput(
        val frameId: Long,
        val snapshotId: Long,
        val diffuse: Long,
        val specular: Long,
        val byteCount: Long,
        val encoding: Int,
        val dispatchCount: Int,
    )

    data class RtTransactionOutput(
        val trace: RtFrameOutput, val denoise: RtDenoiseOutput, val upscale: RtDenoiseOutput,
        val composite: RtDenoiseOutput, val fallbackFlags: Int, val timestampMask: Int,
    )

    private fun readRtDenoiseOutput(output: MemorySegment): RtDenoiseOutput = RtDenoiseOutput(
        output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8),
        output.get(MCDRLayout.PTR, 16).address(), output.get(MCDRLayout.PTR, 24).address(),
        output.get(MCDRLayout.I64, 32), output.get(MCDRLayout.I32, 40), output.get(MCDRLayout.I32, 44),
    )

    fun D3D12RtFrameTransaction(
        ctx: Long, frame: Long, trace: RtFrameRequest, liveRevision: Long, spatial: ByteBuffer,
        historyGeneration: Long, historyFrame: Long, planRevision: Long, width: Int, height: Int,
        nrdMatrices: FloatArray, slConstants: FloatArray, source: Long, upscaler: Int, debugMode: Int,
        timestamps: Long, firstTimestamp: Int,
    ): RtTransactionOutput? {
        require(nrdMatrices.size == 64 && slConstants.size == 104)
        requireLoaded()
        val handle = MH_rtframetransaction ?: return null
        Arena.ofConfined().use { arena ->
            val description = arena.allocate(MCDRLayout.RT_TRANSACTION_DESC)
            val output = arena.allocate(MCDRLayout.RT_TRANSACTION_OUTPUT)
            writeRtFrameRequest(arena, description.asSlice(0, 128), trace)
            val denoise = description.asSlice(128, 64)
            val matrices = arena.allocate(256L, 16L)
            val constants = arena.allocate(416L, 16L)
            nrdMatrices.forEachIndexed { index, value -> matrices.setAtIndex(MCDRLayout.F32, index.toLong(), value) }
            slConstants.forEachIndexed { index, value -> constants.setAtIndex(MCDRLayout.F32, index.toLong(), value) }
            denoise.set(MCDRLayout.I64, 0, trace.frameId)
            denoise.set(MCDRLayout.I64, 8, historyGeneration)
            denoise.set(MCDRLayout.I64, 16, historyFrame)
            denoise.set(MCDRLayout.I64, 24, planRevision)
            denoise.set(MCDRLayout.I32, 32, width)
            denoise.set(MCDRLayout.I32, 36, height)
            denoise.set(MCDRLayout.PTR, 40, matrices)
            denoise.set(MCDRLayout.I32, 48, nrdMatrices.size)
            denoise.set(MCDRLayout.I32, 52, slConstants.size)
            denoise.set(MCDRLayout.PTR, 56, constants)
            val spatialData = arena.allocate(spatial.remaining().toLong(), 4)
            spatialData.asByteBuffer().put(spatial.duplicate())
            description.set(MCDRLayout.I64, 192, liveRevision)
            description.set(MCDRLayout.PTR, 200, spatialData)
            description.set(MCDRLayout.I64, 208, spatial.remaining().toLong())
            description.set(MCDRLayout.PTR, 216, ptr(source))
            description.set(MCDRLayout.I32, 224, upscaler)
            description.set(MCDRLayout.I32, 228, debugMode)
            description.set(MCDRLayout.PTR, 232, ptr(timestamps))
            description.set(MCDRLayout.I32, 240, firstTimestamp)
            description.set(MCDRLayout.I32, 244, 0)
            try {
                if (!ok(handle.invokeExact(ptr(ctx), frame, description, output) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
            if (output.get(MCDRLayout.I32, 276) != 1) return null
            return RtTransactionOutput(readRtFrameOutput(output.asSlice(0, 128)),
                readRtDenoiseOutput(output.asSlice(128, 48)), readRtDenoiseOutput(output.asSlice(176, 48)),
                readRtDenoiseOutput(output.asSlice(224, 48)), output.get(MCDRLayout.I32, 272),
                output.get(MCDRLayout.I32, 280))
        }
    }

    fun D3D12RtFrameDenoise(
        ctx: Long, frame: Long, frameId: Long, historyGeneration: Long, historyFrame: Long,
        planRevision: Long, width: Int, height: Int, nrdMatrices: FloatArray, slConstants: FloatArray,
    ): RtDenoiseOutput? {
        require(nrdMatrices.size == 64 && slConstants.size == 104)
        requireLoaded()
        val handle = MH_rtframedenoise ?: return null
        Arena.ofConfined().use { arena ->
            val description = arena.allocate(MCDRLayout.RT_DENOISE_DESC)
            val output = arena.allocate(MCDRLayout.RT_DENOISE_OUTPUT)
            val matrices = arena.allocate(256L, 16L)
            val constants = arena.allocate(416L, 16L)
            nrdMatrices.forEachIndexed { index, value -> matrices.setAtIndex(MCDRLayout.F32, index.toLong(), value) }
            slConstants.forEachIndexed { index, value -> constants.setAtIndex(MCDRLayout.F32, index.toLong(), value) }
            description.set(MCDRLayout.I64, 0, frameId)
            description.set(MCDRLayout.I64, 8, historyGeneration)
            description.set(MCDRLayout.I64, 16, historyFrame)
            description.set(MCDRLayout.I64, 24, planRevision)
            description.set(MCDRLayout.I32, 32, width)
            description.set(MCDRLayout.I32, 36, height)
            description.set(MCDRLayout.PTR, 40, matrices)
            description.set(MCDRLayout.I32, 48, nrdMatrices.size)
            description.set(MCDRLayout.I32, 52, slConstants.size)
            description.set(MCDRLayout.PTR, 56, constants)
            try {
                if (!ok(handle.invokeExact(ptr(ctx), frame, description, output) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
            return RtDenoiseOutput(output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8),
                output.get(MCDRLayout.PTR, 16).address(), output.get(MCDRLayout.PTR, 24).address(),
                output.get(MCDRLayout.I64, 32), output.get(MCDRLayout.I32, 40), output.get(MCDRLayout.I32, 44))
        }
    }

    fun D3D12RtFrameUpscale(ctx: Long, frame: Long, frameId: Long, backend: Int): RtDenoiseOutput? {
        requireLoaded()
        val handle = MH_rtframeupscale ?: return null
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.RT_DENOISE_OUTPUT)
            try {
                if (!ok(handle.invokeExact(ptr(ctx), frame, frameId, backend, output) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
            return RtDenoiseOutput(output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8),
                output.get(MCDRLayout.PTR, 16).address(), output.get(MCDRLayout.PTR, 24).address(),
                output.get(MCDRLayout.I64, 32), output.get(MCDRLayout.I32, 40), output.get(MCDRLayout.I32, 44))
        }
    }

    fun D3D12RtFrameComposite(ctx: Long, frame: Long, frameId: Long, source: Long, debugMode: Int): RtDenoiseOutput? {
        requireLoaded()
        val handle = MH_rtframecomposite ?: return null
        Arena.ofConfined().use { arena ->
            val output = arena.allocate(MCDRLayout.RT_DENOISE_OUTPUT)
            try {
                if (!ok(handle.invokeExact(ptr(ctx), frame, frameId, ptr(source), debugMode, output) as Int)) return null
            } catch (failure: Throwable) {
                throw unexpected(failure)
            }
            return RtDenoiseOutput(output.get(MCDRLayout.I64, 0), output.get(MCDRLayout.I64, 8),
                output.get(MCDRLayout.PTR, 16).address(), output.get(MCDRLayout.PTR, 24).address(),
                output.get(MCDRLayout.I64, 32), output.get(MCDRLayout.I32, 40), output.get(MCDRLayout.I32, 44))
        }
    }

    fun D3D12RtFrameReblur(ctx: Long, frame: Long, frameId: Long): Boolean {
        requireLoaded()
        val handle = MH_rtframereblur ?: return false
        try {
            return ok(handle.invokeExact(ptr(ctx), frame, frameId) as Int)
        } catch (failure: Throwable) {
            throw unexpected(failure)
        }
    }

    /** native 侧 `kMaxRtHitGroups`：一次建状态对象最多几个 hit group。 */
    const val MAX_RT_HIT_GROUPS: Int = 4
    /** `D3D12_SHADER_IDENTIFIER_SIZE_IN_BYTES`：SBT 记录里标识符占的字节数。 */
    const val SHADER_IDENTIFIER_BYTES: Int = 32
    /** native 侧 `kMaxAccelGeometries`：一条 BLAS 最多几条几何。 */
    const val MAX_ACCEL_GEOMETRIES: Int = 4

    // 把几何表写进 arena：字段顺序与 mcdr_rt_geometry 一致（指针、6 个 i64、flags、reserved）。
    private fun geometryArray(arena: Arena, geometries: List<RtGeometry>): MemorySegment {
        val stride = MCDRLayout.RT_GEOMETRY.byteSize()
        val seg = arena.allocate(stride * geometries.size)
        geometries.forEachIndexed { i, g ->
            val base = i * stride
            seg.set(MCDRLayout.PTR, base + MCDRLayout.RT_GEOMETRY_VERTEX_BUFFER, ptr(g.vertexBuffer))
            seg.set(MCDRLayout.I64, base + MCDRLayout.RT_GEOMETRY_VERTEX_STRIDE, g.vertexStride)
            seg.set(MCDRLayout.I64, base + MCDRLayout.RT_GEOMETRY_VERTEX_COUNT, g.vertexCount)
            seg.set(MCDRLayout.I64, base + MCDRLayout.RT_GEOMETRY_VERTEX_OFFSET, g.vertexOffset)
            seg.set(MCDRLayout.PTR, base + MCDRLayout.RT_GEOMETRY_INDEX_BUFFER, ptr(g.indexBuffer))
            seg.set(MCDRLayout.I64, base + MCDRLayout.RT_GEOMETRY_INDEX_COUNT, g.indexCount)
            seg.set(MCDRLayout.I64, base + MCDRLayout.RT_GEOMETRY_INDEX_OFFSET, g.indexOffset)
            seg.set(MCDRLayout.I32, base + MCDRLayout.RT_GEOMETRY_FLAGS, if (g.useAlphaTest) 1 else 0)
            seg.set(MCDRLayout.I32, base + MCDRLayout.RT_GEOMETRY_INDEX_STRIDE, g.indexStride)
        }
        return seg
    }

    enum class BarrierKind { TRANSITION, UAV, ALIASING }

    /** 一条屏障；[before]/[after] 取 [D3D12State] 的位值，[subresource] 默认全部。 */
    data class BarrierDesc(
        val resource: Long,
        val before: Int,
        val after: Int,
        val kind: BarrierKind = BarrierKind.TRANSITION,
        val resourceAfter: Long = 0L,
        val flags: Int = 0,
        val subresource: Int = D3D12State.ALL_SUBRESOURCES,
    )

    object D3D12State {
        const val ALL_SUBRESOURCES: Int = -1
        const val COMMON: Int = 0x0
        const val VERTEX_AND_CONSTANT_BUFFER: Int = 0x1
        const val INDEX_BUFFER: Int = 0x2
        const val RENDER_TARGET: Int = 0x4
        const val UNORDERED_ACCESS: Int = 0x8
        const val DEPTH_WRITE: Int = 0x10
        const val DEPTH_READ: Int = 0x20
        const val NON_PIXEL_SHADER_RESOURCE: Int = 0x40
        const val PIXEL_SHADER_RESOURCE: Int = 0x80
        const val INDIRECT_ARGUMENT: Int = 0x200
        const val COPY_DEST: Int = 0x400
        const val COPY_SOURCE: Int = 0x800
        const val ACCELERATION_STRUCTURE: Int = 0x400000
        const val GENERIC_READ: Int = 0xAC
    }

    /** 返回 false = 参数/设备问题或列表未开。 */
    fun D3D12Barrier(ctx: Long, descs: List<BarrierDesc>): Boolean {
        requireLoaded()
        val handle = MH_barrier ?: return false
        if (descs.isEmpty()) return true
        val stride = MCDRLayout.BARRIER_DESC_SIZE
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(stride * descs.size)
            descs.forEachIndexed { i, d ->
                seg.set(MCDRLayout.PTR, i * stride, MemorySegment.ofAddress(d.resource))
                seg.set(MCDRLayout.PTR, i * stride + 8, MemorySegment.ofAddress(d.resourceAfter))
                seg.set(MCDRLayout.I32, i * stride + 16, d.kind.ordinal)
                seg.set(MCDRLayout.I32, i * stride + 20, d.flags)
                seg.set(MCDRLayout.I32, i * stride + 24, d.before)
                seg.set(MCDRLayout.I32, i * stride + 28, d.after)
                seg.set(MCDRLayout.I32, i * stride + 32, d.subresource)
            }
            return ok(handle.invokeExact(ptr(ctx), seg, descs.size) as Int)
        }
    }

    data class SdkResourceState(
        val state: Long,
        val owner: Long,
        val acquired: Long,
        val released: Long,
        val rejected: Long,
        val externalResources: Long,
        val violations: Long,
        val heapRestores: Long,
    )

    fun D3D12QuerySdkResourceState(ctx: Long, texture: Long): SdkResourceState? {
        requireLoaded()
        val handle = MH_sdkresourcestate ?: return null
        Arena.ofConfined().use { arena ->
            val count = MCDRLayout.SDK_RESOURCE_STATE_VALUE_COUNT
            val values = arena.allocate(count * 8L, 8L)
            if ((handle.invokeExact(ptr(ctx), ptr(texture), values, count) as Int) != 1) return null
            return SdkResourceState(
                state = values.get(MCDRLayout.I64, 0L),
                owner = values.get(MCDRLayout.I64, 8L),
                acquired = values.get(MCDRLayout.I64, 16L),
                released = values.get(MCDRLayout.I64, 24L),
                rejected = values.get(MCDRLayout.I64, 32L),
                externalResources = values.get(MCDRLayout.I64, 40L),
                violations = values.get(MCDRLayout.I64, 48L),
                heapRestores = values.get(MCDRLayout.I64, 56L),
            )
        }
    }

    data class RtTrackStats(
        val transitions: Int,
        val mismatches: Int,
        /** COMMON → 任意状态的隐式提升，D3D12 允许，不需要 barrier。 */
        val promotions: Int,
        /** 调用方用组合读掩码声明 before（单状态是它的子集），不算错。 */
        val widened: Int,
        val firstUse: Int,
        val uavBarriers: Int,
        val tracked: Int,
        val note: String,
    )

    /** 影子跟踪器计数；null = 设备未建。 */
    fun D3D12QueryRtTrackStats(): RtTrackStats? {
        requireLoaded()
        val handle = MH_rttrackstats ?: return null
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(MCDRLayout.RT_TRACK_STATS)
            if ((handle.invokeExact(seg) as Int) != 1) return null
            val note = buildString {
                for (i in 0 until 64) {
                    val c = seg.get(MCDRLayout.I8, 28L + i).toInt()
                    if (c == 0) break
                    append(c.toChar())
                }
            }
            return RtTrackStats(
                transitions = seg.get(MCDRLayout.I32, 0L),
                mismatches = seg.get(MCDRLayout.I32, 4L),
                promotions = seg.get(MCDRLayout.I32, 8L),
                widened = seg.get(MCDRLayout.I32, 12L),
                firstUse = seg.get(MCDRLayout.I32, 16L),
                uavBarriers = seg.get(MCDRLayout.I32, 20L),
                tracked = seg.get(MCDRLayout.I32, 24L),
                note = note,
            )
        }
    }

    /**
     * `Dispatch(x,y,z)` on the open DIRECT list. [uavTexture] must carry `USAGE_STORAGE`(32);
     * native brackets it with COMMON -> UNORDERED_ACCESS -> COMMON, so the caller tracks no state.
     * @param constants 0..8 dwords for b0
     */
    fun D3D12DispatchCompute(ctx: Long, pipeline: Long, uavTexture: Long, constants: IntArray,
                             x: Int, y: Int, z: Int): Boolean {
        requireLoaded()
        val handle = MH_dispatchcompute ?: return false
        val count = constants.size
        require(count in 0..8) { "compute constants: 0..8 dwords, got $count" }
        Arena.ofConfined().use { arena ->
            val seg = if (count == 0) MemorySegment.NULL else arena.allocate(count * 4L)
            for (i in 0 until count) {
                seg.set(MCDRLayout.I32, i * 4L, constants[i])
            }
            return ok(handle.invokeExact(ptr(ctx), ptr(pipeline), ptr(uavTexture), seg, count, x, y, z) as Int)
        }
    }

    /** NUL-terminated UTF-8 copy in `arena`; never called with null. */
    /**
     * SRI compute PSO; root signature is fixed: [0] table (t0/t1 SRVs), [1] table (u0 UAV),
     * [2] root constants b0 (8 dwords). Typed UAV/SRV **must** sit in a descriptor table — as root
     * descriptors CreateComputePipelineState returns E_INVALIDARG.
     */
    fun D3D12CreateComputePipelineShadingRate(dxil: ByteArray): Long {
        requireLoaded()
        val handle = MH_createcomputepipelineshadingrate ?: return 0L
        Arena.ofConfined().use { arena ->
            val src = arena.allocate(dxil.size.toLong().coerceAtLeast(1L))
            MemorySegment.copy(dxil, 0, src, ValueLayout.JAVA_BYTE, 0, dxil.size)
            return addr(handle.invokeExact(src, dxil.size) as MemorySegment)
        }
    }

    /**
     * `R8_UINT` shading-rate image; the arguments are **tile counts** (ceil(pixel /
     * ShadingRateImageTileSize), NVIDIA 16). 0 on failure, including VRS tier < 2.
     */
    fun D3D12CreateShadingRateImage(tilesX: Int, tilesY: Int): Long {
        requireLoaded()
        val handle = MH_createshadingrateimage ?: return 0L
        return addr(handle.invokeExact(tilesX, tilesY) as MemorySegment)
    }

    /**
     * Regenerate the SRI image from last frame's depth (t0), motion (t1) and image (t2) into u0;
     * with no history pass the current image and clear the temporal-clamp bit (bit1 of dword 8).
     */
    fun D3D12DispatchComputeShadingRate(ctx: Long, pipeline: Long, srvDepth: Long, srvMotion: Long,
                                        srvPrevious: Long, image: Long, constants: IntArray,
                                        x: Int, y: Int, z: Int): Boolean {
        requireLoaded()
        val handle = MH_dispatchcomputeshadingrate ?: return false
        val count = constants.size
        require(count in 0..8) { "SRI constants: 0..8 dwords, got $count" }
        Arena.ofConfined().use { arena ->
            val seg = if (count == 0) MemorySegment.NULL else arena.allocate(count * 4L)
            for (i in 0 until count) {
                seg.set(MCDRLayout.I32, i * 4L, constants[i])
            }
            return ok(handle.invokeExact(ptr(ctx), ptr(pipeline), ptr(srvDepth), ptr(srvMotion),
                ptr(srvPrevious), ptr(image), seg, count, x, y, z) as Int)
        }
    }

    /** Generic 3xSRV + 1xUAV + b0(8 int) compute pass — the SRI signature, same native implementation. */
    fun D3D12CreateComputePipelineTexture3(dxil: ByteArray): Long {
        requireLoaded()
        val handle = MH_createtexture3 ?: return 0L
        Arena.ofConfined().use { arena ->
            val src = arena.allocate(dxil.size.toLong().coerceAtLeast(1L))
            MemorySegment.copy(dxil, 0, src, ValueLayout.JAVA_BYTE, 0, dxil.size)
            return addr(handle.invokeExact(src, dxil.size) as MemorySegment)
        }
    }

    /** 1 = dispatched. */
    fun D3D12DispatchComputeTexture3(ctx: Long, pipeline: Long, srv0: Long, srv1: Long, srv2: Long,
                                     image: Long, constants: IntArray, x: Int, y: Int, z: Int): Int {
        requireLoaded()
        val handle = MH_dispatchtexture3 ?: return Int.MIN_VALUE
        val count = constants.size
        require(count in 0..8) { "texture3 constants: 0..8 dwords, got $count" }
        Arena.ofConfined().use { arena ->
            val seg = if (count == 0) MemorySegment.NULL else arena.allocate(count * 4L)
            for (i in 0 until count) {
                seg.set(MCDRLayout.I32, i * 4L, constants[i])
            }
            return handle.invokeExact(ptr(ctx), ptr(pipeline), ptr(srv0), ptr(srv1), ptr(srv2),
                ptr(image), seg, count, x, y, z) as Int
        }
    }

    /** Attach the SRI image to the open list (`image == 0L` detaches); native moves it COMMON -> SHADING_RATE_SOURCE. */
    fun D3D12SetShadingRateImage(ctx: Long, image: Long): Boolean {
        requireLoaded()
        val handle = MH_setshadingrateimage ?: return false
        return ok(handle.invokeExact(ptr(ctx), ptr(image)) as Int)
    }

    private fun cstringUtf8(arena: Arena, s: String): MemorySegment {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        val seg = arena.allocate(bytes.size + 1L)
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.size)
        seg.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0.toByte())
        return seg
    }
    // ---- Surface (DXGI swapchain) ----
    /** DXGI swapchain for a native HWND; 0 on failure. */
    fun D3D12CreateSurface(hwnd: Long): Long {
        requireLoaded()
        try {
            return addr(MH_createsurface!!.invokeExact(hwnd) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** PresentMode ordinals (IMMEDIATE=0 MAILBOX=1 FIFO=2 FIFO_RELAXED=3); DXGI FLIP supports all but MAILBOX. */
    fun D3D12SurfacePresentModes(): IntArray {
        requireLoaded()
        try {
            val total = MH_surfacepresentmodes!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (total <= 0) {
                return IntArray(0)
            }
            val buf = scratch(0, total * 4L)
            val written = MH_surfacepresentmodes!!.invokeExact(buf, total) as Int
            val n = minOf(total, written.coerceAtLeast(0))
            val out = IntArray(n)
            for (i in 0 until n) {
                out[i] = buf.get(MCDRLayout.I32, i * 4L) as Int
            }
            return out
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** ResizeBuffers to width x height with the given present mode. */
    /** Real exclusive fullscreen; false keeps the composited picture. */
    fun D3D12SetExclusiveFullscreen(surface: Long, on: Boolean): Boolean {
        requireLoaded()
        try {
            return ok(MH_setexclusivefullscreen!!.invokeExact(ptr(surface), if (on) 1 else 0) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12ConfigureSurface(surface: Long, width: Int, height: Int, presentMode: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_configuresurface!!.invokeExact(ptr(surface), width, height, presentMode) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Acquire the next back buffer. */
    fun D3D12AcquireSurface(surface: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_acquiresurface!!.invokeExact(ptr(surface)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Copy `texture` into the acquired back buffer on the open list. */
    fun D3D12BlitSurface(ctx: Long, surface: Long, texture: Long) {
        requireLoaded()
        try {
            MH_blitsurface!!.invokeExact(ptr(ctx), ptr(surface), ptr(texture))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12PresentSurface(surface: Long) {
        requireLoaded()
        try {
            MH_presentsurface!!.invokeExact(ptr(surface))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Raw `ID3D12Resource*` of the acquired back buffer (deliberately not a cached `Dx12Object*`). */
    fun D3D12GetBackBufferHandle(surface: Long): Long {
        requireLoaded()
        try {
            return MH_getbackbufferhandle!!.invokeExact(ptr(surface)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Whether the last present reported a suboptimal status. */
    fun D3D12IsSurfaceSuboptimal(surface: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_issurfacesuboptimal!!.invokeExact(ptr(surface)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** 3x3 sampled pixels as int[36] (9 px x RGBA); null on failure. Diagnostic. */
    fun D3D12ReadbackTexturePixels(texHandle: Long): IntArray? {
        return readback36(MH_readbacktexturepixels, texHandle, "dx12ReadbackTexturePixels")
    }
    /**
     * Readback with the **real** current state. The stateless variant assumes COMMON, which only
     * holds for textures the GPU never wrote: a DLSS output stops in COPY_SOURCE or
     * UNORDERED_ACCESS, and a COMMON barrier from there is invalid — it read back as zeros.
     * @param fromState must come from [D3D12DlssOutputState]; it is restored afterwards.
     */
    fun D3D12ReadbackTexturePixelsIn(texHandle: Long, fromState: Int): IntArray? {
        requireLoaded()
        val handle = MH_readbacktexturepixelsin ?: return null
        val count = 36
        val buf = scratch(0, count * 4L)
        try {
            val rc = handle.invokeExact(ptr(texHandle), fromState, buf) as Int
            if (rc == 0) {
                return null
            }
            val out = IntArray(count)
            for (i in 0 until count) {
                out[i] = buf.get(MCDRLayout.I32, i * 4L) as Int
            }
            return out
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** State the DLSS output stopped in at the end of last frame; 0 = no handover this frame, do not sample. */
    fun D3D12DlssOutputState(): Int {
        requireLoaded()
        val handle = MH_dlssoutputstate ?: return 0
        return try {
            handle.invokeExact() as Int
        } catch (t: Throwable) {
            0
        }
    }
    private fun readback36(handle: MethodHandle?, obj: Long, what: String): IntArray? {
        requireLoaded()
        val count = 36
        val buf = scratch(0, count * 4L)
        try {
            val rc = handle!!.invokeExact(ptr(obj), buf) as Int
            if (rc == 0) {
                return null
            }
            val out = IntArray(count)
            for (i in 0 until count) {
                out[i] = buf.get(MCDRLayout.I32, i * 4L) as Int
            }
            return out
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12DestroySurface(surface: Long) {
        requireLoaded()
        try {
            MH_destroysurface!!.invokeExact(ptr(surface))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Draw commands ----
    /** `hasDepth` selects the with/without-depth PSO variant. */
    fun D3D12SetPipeline(ctx: Long, pipeline: Long, hasDepth: Boolean): Boolean {
        requireLoaded()
        try {
            return ok(MH_setpipeline!!.invokeExact(ptr(ctx), ptr(pipeline), if (hasDepth) 1 else 0) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Scissor rect on the open pass. */
    fun D3D12SetScissor(ctx: Long, x: Int, y: Int, width: Int, height: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setscissor!!.invokeExact(ptr(ctx), x, y, width, height) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** 1 = set, 0 = failed. */
    fun D3D12SetViewport(ctx: Long, x: Int, y: Int, width: Int, height: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setviewport!!.invokeExact(ptr(ctx), x, y, width, height) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** [slot] 0..15. */
    fun D3D12SetVertexBuffer(ctx: Long, slot: Int, buffer: Long, offset: Long, stride: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setvertexbuffer!!.invokeExact(ptr(ctx), slot, ptr(buffer), offset, stride) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** `indexType` 0=SHORT(R16_UINT) 1=INT(R32_UINT). */
    fun D3D12SetIndexBuffer(ctx: Long, buffer: Long, indexType: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setindexbuffer!!.invokeExact(ptr(ctx), ptr(buffer), indexType) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Write the core's descriptors into this frame's transient heap and bind root table 0. Parallel arrays, one entry per binding: 0=CBV 1=SRV(texture view) 2=SRV(texel buffer). */
    fun D3D12PushDescriptors(ctx: Long, types: IntArray?, buffers: LongArray?,
                             offsets: LongArray?, lengths: LongArray?, texelFormats: IntArray?, views: LongArray?): Boolean {
        requireLoaded()
        val count = if (types == null) 0 else types.size
        if (count == 0) {
            // A pipeline with no bindings is a no-op, not a failure.
            return true
        }
        val stride = MCDRLayout.BINDING_SLOT.byteSize()
        val slots = scratch(0, count * stride)
        for (i in 0 until count) {
            val base = i * stride
            slots.set(MCDRLayout.I32, base, types!![i])
            slots.set(MCDRLayout.I32, base + MCDRLayout.BINDING_SLOT_TEXEL_FORMAT, if (texelFormats == null || i >= texelFormats.size) 0 else texelFormats[i])
            slots.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_BUFFER, ptr(if (buffers == null || i >= buffers.size) 0L else buffers[i]))
            slots.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_VIEW, ptr(if (views == null || i >= views.size) 0L else views[i]))
            slots.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_OFFSET, if (offsets == null || i >= offsets.size) 0L else offsets[i])
            slots.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_LENGTH, if (lengths == null || i >= lengths.size) 0L else lengths[i])
        }
        try {
            return ok(MH_pushdescriptors!!.invokeExact(ptr(ctx), slots, count) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** DrawIndexedInstanced on the open pass. */
    fun D3D12DrawIndexed(ctx: Long, indexCount: Int, instanceCount: Int,
                         firstIndex: Int, baseVertex: Int, firstInstance: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_drawindexed!!.invokeExact(ptr(ctx), indexCount, instanceCount,
                firstIndex, baseVertex, firstInstance) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** DrawInstanced on the open pass. */
    fun D3D12Draw(ctx: Long, vertexCount: Int, instanceCount: Int,
                  firstVertex: Int, firstInstance: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_draw!!.invokeExact(ptr(ctx), vertexCount, instanceCount,
                firstVertex, firstInstance) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** ExecuteIndirect (DrawIndexedInstanced) with the given command buffer. */
    fun D3D12DrawIndexedIndirect(ctx: Long, commands: Long, offset: Long, drawCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_drawindexedindirect!!.invokeExact(ptr(ctx), ptr(commands), offset, drawCount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** ExecuteIndirect (DrawInstanced) with the given command buffer. */
    fun D3D12DrawIndirect(ctx: Long, commands: Long, offset: Long, drawCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_drawindirect!!.invokeExact(ptr(ctx), ptr(commands), offset, drawCount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Async: partitioned descriptor allocator, bundle pool, executor, fence manager ----
    /** Fence value % 4 — this frame's drawHeap section. */
    fun D3D12AsyncCurrentFrameSlot(ctx: Long): Int {
        requireLoaded()
        try {
            return MH_asynccurrentframeslot!!.invokeExact(ptr(ctx)) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Raw frame number (= ctx->fenceValue), for bundle allocator slot rotation. */
    fun D3D12AsyncCurrentFrameValue(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_asynccurrentframevalue!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Partition drawHeap into disjoint (frameSlot, worker) regions; 0 on failure. */
    fun D3D12AsyncDescriptorCreate(workerCount: Int): Long {
        requireLoaded()
        try {
            return addr(MH_asyncdescriptorcreate!!.invokeExact(workerCount) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Hand the whole drawHeap section back to the sync ring. */
    fun D3D12AsyncDescriptorDestroy(alloc: Long) {
        requireLoaded()
        try {
            MH_asyncdescriptordestroy!!.invokeExact(ptr(alloc))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Absolute base slot of (frameSlot, worker); -1 when out of range. */
    fun D3D12AsyncDescriptorRegionBase(alloc: Long, frameSlot: Int, worker: Int): Int {
        requireLoaded()
        try {
            return MH_asyncregionbase!!.invokeExact(ptr(alloc), frameSlot, worker) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Atomically take `count` contiguous slots; -1 when exhausted. */
    fun ID3D12AsyncDescriptorAllocate(alloc: Long, frameSlot: Int, worker: Int, count: Int): Int {
        requireLoaded()
        try {
            return MH_asyncallocate!!.invokeExact(ptr(alloc), frameSlot, worker, count) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Frame boundary: reset every worker's cursor under this frame slot. */
    fun D3D12AsyncDescriptorResetFrame(alloc: Long, frameSlot: Int) {
        requireLoaded()
        try {
            MH_asyncresetframe!!.invokeExact(ptr(alloc), frameSlot)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** GPU handle of an absolute slot (SetGraphicsRootDescriptorTable). */
    fun ID3D12AsyncDescriptorGpuHandle(alloc: Long, slot: Int): Long {
        requireLoaded()
        try {
            return MH_asyncgpuhandle!!.invokeExact(ptr(alloc), slot) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Write buffer[offset, offset+size) as a CBV into `slot`. */
    fun D3D12AsyncDescriptorWriteCBV(alloc: Long, slot: Int, buffer: Long,
                                     offset: Long, size: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncwritecbv!!.invokeExact(ptr(alloc), slot, ptr(buffer), offset, size) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Copy an existing texture-view descriptor into `slot`. */
    fun D3D12AsyncDescriptorWriteSRV(alloc: Long, slot: Int, view: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncwritesrv!!.invokeExact(ptr(alloc), slot, ptr(view)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** One BUNDLE allocator + command list per worker; 0 on failure. */
    fun D3D12AsyncBundlePoolCreate(workerCount: Int): Long {
        requireLoaded()
        try {
            return addr(MH_asyncbundlepoolcreate!!.invokeExact(workerCount) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12AsyncBundlePoolDestroy(pool: Long) {
        requireLoaded()
        try {
            MH_asyncbundlepooldestroy!!.invokeExact(ptr(pool))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** `frameSlot` must be `fenceValue % 4`; false once the frame's batch budget is exhausted. */
    fun D3D12AsyncBundleBegin(pool: Long, worker: Int, frameSlot: Int, frameValue: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlebegin!!.invokeExact(ptr(pool), worker, frameSlot, frameValue) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Returns the bundle list handle, or 0 on failure. */
    fun D3D12AsyncBundleEnd(pool: Long, worker: Int): Long {
        requireLoaded()
        try {
            return MH_asyncbundleend!!.invokeExact(ptr(pool), worker) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Bind a pipeline inside the bundle (root signature + primitive topology). */
    fun D3D12AsyncBundleSetPipelineState(pool: Long, worker: Int, pipeline: Long,
                                         hasDepth: Boolean): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlesetpipelinestate!!.invokeExact(
                ptr(pool), worker, ptr(pipeline), if (hasDepth) 1 else 0) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun AsyncBundleSetDescriptorTable(pool: Long, worker: Int, slot: Int,
                                      gpuHandle: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlesetdescriptortable!!.invokeExact(ptr(pool), worker, slot, gpuHandle) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun AsyncBundleSetVertexBuffer(pool: Long, worker: Int, slot: Int,
                                   buffer: Long, offset: Long, stride: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlesetvertexbuffer!!.invokeExact(
                ptr(pool), worker, slot, ptr(buffer), offset, stride) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** `indexType` 0=SHORT 1=INT. */
    fun AsyncBundleSetIndexBuffer(pool: Long, worker: Int, buffer: Long, indexType: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlesetindexbuffer!!.invokeExact(
                ptr(pool), worker, ptr(buffer), indexType) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12AsyncBundleDrawIndexed(pool: Long, worker: Int, indexCount: Int,
                                    instanceCount: Int, firstIndex: Int, baseVertex: Int, firstInstance: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundledrawindexed!!.invokeExact(ptr(pool), worker, indexCount,
                instanceCount, firstIndex, baseVertex, firstInstance) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12AsyncBundleDraw(pool: Long, worker: Int, vertexCount: Int,
                             instanceCount: Int, firstVertex: Int, firstInstance: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundledraw!!.invokeExact(ptr(pool), worker, vertexCount,
                instanceCount, firstVertex, firstInstance) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Device-level command signature. */
    fun AsyncBundleDrawIndexedIndirect(pool: Long, worker: Int, commands: Long,
                                       offset: Long, drawCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundledrawindexedindirect!!.invokeExact(
                ptr(pool), worker, ptr(commands), offset, drawCount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Fold one worker partition's whole per-core sequence into one native call. The descriptor arrays are indexed by the **global** core `d = startDraw + i`; `bType` has one entry per binding slot. */
    fun AsyncBundleRecordPartition(alloc: Long, pool: Long, worker: Int,
                                   baseSlot: Int, bc: Int, startDraw: Int, count: Int,
                                   bType: IntArray,
                                   bBuf: LongArray, bOff: LongArray, bLen: LongArray, bView: LongArray,
                                   idxBuf: LongArray, idxType: IntArray, idxCount: IntArray, firstIdx: IntArray, baseVert: IntArray,
                                   vbSlot: IntArray, vbBuf: LongArray, vbOff: LongArray, vbStride: IntArray): Boolean {
        return recordPartition(false, alloc, pool, worker, baseSlot, bc, startDraw, count,
            bType, bBuf, bOff, bLen, bView,
            idxBuf, idxType, idxCount, firstIdx, baseVert,
            vbSlot, vbBuf, vbOff, vbStride)
    }
    /** Whole batch shares one binding set: the descriptors are written once and only the first `bc` entries are read. */
    fun AsyncBundleRecordPartitionShared(alloc: Long, pool: Long, worker: Int,
                                         baseSlot: Int, bc: Int, startDraw: Int, count: Int,
                                         bType: IntArray,
                                         bBuf: LongArray, bOff: LongArray, bLen: LongArray, bView: LongArray,
                                         idxBuf: LongArray, idxType: IntArray, idxCount: IntArray, firstIdx: IntArray, baseVert: IntArray,
                                         vbSlot: IntArray, vbBuf: LongArray, vbOff: LongArray, vbStride: IntArray): Boolean {
        return recordPartition(true, alloc, pool, worker, baseSlot, bc, startDraw, count,
            bType, bBuf, bOff, bLen, bView,
            idxBuf, idxType, idxCount, firstIdx, baseVert,
            vbSlot, vbBuf, vbOff, vbStride)
    }
    private fun recordPartition(shared: Boolean, alloc: Long, pool: Long, worker: Int,
        baseSlot: Int, bc: Int, startDraw: Int, count: Int,
        bType: IntArray,
        bBuf: LongArray, bOff: LongArray, bLen: LongArray, bView: LongArray,
        idxBuf: LongArray, idxType: IntArray, idxCount: IntArray, firstIdx: IntArray, baseVert: IntArray,
        vbSlot: IntArray, vbBuf: LongArray, vbOff: LongArray, vbStride: IntArray): Boolean {
        requireLoaded()
        if (bc <= 0 || count <= 0) {
            return false
        }
        val slotStride = MCDRLayout.BINDING_SLOT.byteSize()
        val drawStride = MCDRLayout.PARTITION_DRAW.byteSize()
        val bindingEntries = if (shared) bc else count * bc
        // Two arenas: growing one must never invalidate the other.
        val bindings = scratch(0, bindingEntries * slotStride)
        val draws = scratch(1, count * drawStride)
        for (i in 0 until count) {
            val d = startDraw + i
            val drawBase = i * drawStride
            draws.set(MCDRLayout.PTR, drawBase + MCDRLayout.PARTITION_DRAW_INDEX_BUFFER, ptr(idxBuf[d]))
            draws.set(MCDRLayout.PTR, drawBase + MCDRLayout.PARTITION_DRAW_VERTEX_BUFFER, ptr(vbBuf[d]))
            draws.set(MCDRLayout.I64, drawBase + MCDRLayout.PARTITION_DRAW_VERTEX_OFFSET, vbOff[d])
            draws.set(MCDRLayout.I32, drawBase + MCDRLayout.PARTITION_DRAW_INDEX_TYPE, idxType[d])
            draws.set(MCDRLayout.I32, drawBase + MCDRLayout.PARTITION_DRAW_INDEX_COUNT, idxCount[d])
            draws.set(MCDRLayout.I32, drawBase + MCDRLayout.PARTITION_DRAW_FIRST_INDEX, firstIdx[d])
            draws.set(MCDRLayout.I32, drawBase + MCDRLayout.PARTITION_DRAW_BASE_VERTEX, baseVert[d])
            draws.set(MCDRLayout.I32, drawBase + MCDRLayout.PARTITION_DRAW_VERTEX_SLOT, vbSlot[d])
            draws.set(MCDRLayout.I32, drawBase + MCDRLayout.PARTITION_DRAW_VERTEX_STRIDE, vbStride[d])
            draws.set(MCDRLayout.I32, drawBase + MCDRLayout.PARTITION_DRAW_PAD0, 0)
        }
        if (shared) {
            // Only core 0's block is read and reused for every core, so the destination is entry 0.
            packBindings(bindings, slotStride, 0, bc, startDraw * bc, bType, bBuf, bOff, bLen, bView)
        } else {
            for (i in 0 until count) {
                packBindings(bindings, slotStride, i * bc, bc,
                    (startDraw + i) * bc, bType, bBuf, bOff, bLen, bView)
            }
        }
        try {
            return ok(MH_asyncbundlerecordpartition!!.invokeExact(ptr(alloc), ptr(pool), worker,
                baseSlot, bc, if (shared) 1 else 0, bindings, draws, count) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Pack `bc` binding slots from the parallel arrays at global index `srcBase`. */
    private fun packBindings(dst: MemorySegment, stride: Long, dstEntry: Int, bc: Int,
        srcBase: Int, bType: IntArray, bBuf: LongArray, bOff: LongArray, bLen: LongArray, bView: LongArray) {
        for (j in 0 until bc) {
            val s = srcBase + j
            val base = (dstEntry + j) * stride
            // `bType` has one entry per binding slot; the other four arrays are per core per slot.
            // Indexing bType by the global slot threw AIOOBE for any startDraw > 0.
            dst.set(MCDRLayout.I32, base, bType[j])
            dst.set(MCDRLayout.I32, base + MCDRLayout.BINDING_SLOT_TEXEL_FORMAT, 0)
            dst.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_BUFFER, ptr(bBuf[s]))
            dst.set(MCDRLayout.PTR, base + MCDRLayout.BINDING_SLOT_VIEW, ptr(bView[s]))
            dst.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_OFFSET, bOff[s])
            dst.set(MCDRLayout.I64, base + MCDRLayout.BINDING_SLOT_LENGTH, bLen[s])
        }
    }
    /** Replay a bundle, in order, on the main list. */
    fun D3D12ExecuteBundle(ctx: Long, bundle: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_executebundle!!.invokeExact(ptr(ctx), bundle) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Transition this batch's CBVs to VERTEX_AND_CONSTANT_BUFFER on the main list — **a bundle may not contain a barrier**; vertex buffers share that state. */
    fun AsyncPrepareCBVBuffers(ctx: Long, buffers: LongArray?): Boolean {
        return prepareObjects(MH_asyncpreparecbvbuffers, ctx, buffers, "dx12AsyncPrepareCBVBuffers")
    }
    /** Same for index buffers -> INDEX_BUFFER. */
    fun AsyncPrepareIndexBuffers(ctx: Long, buffers: LongArray?): Boolean {
        return prepareObjects(MH_asyncprepareindexbuffers, ctx, buffers, "dx12AsyncPrepareIndexBuffers")
    }
    /** Same for sampled views -> SHADER_RESOURCE. */
    fun AsyncPrepareTextureViews(ctx: Long, views: LongArray?): Boolean {
        return prepareObjects(MH_asyncpreparetextureviews, ctx, views, "dx12AsyncPrepareTextureViews")
    }
    private fun prepareObjects(handle: MethodHandle?, ctx: Long, objects: LongArray?, what: String): Boolean {
        requireLoaded()
        val count = if (objects == null) 0 else objects.size
        if (count == 0) {
            return true
        }
        val arr = scratch(0, count * 8L)
        for (i in 0 until count) {
            arr.set(MCDRLayout.PTR, i * 8L, ptr(objects!![i]))
        }
        try {
            return ok(handle!!.invokeExact(ptr(ctx), arr, count) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** kFramesInFlight allocator/list sets + fence; 0 on failure. */
    fun D3D12AsyncExecutorCreate(): Long {
        requireLoaded()
        try {
            return addr(MH_asyncexecutorcreate!!.invokeExact() as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12AsyncExecutorDestroy(exec: Long) {
        requireLoaded()
        try {
            MH_asyncexecutordestroy!!.invokeExact(ptr(exec))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Non-blocking; false when the previous frame has not finished (skip this frame). */
    fun AsyncExecutorTryBeginFrame(exec: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutortrybeginframe!!.invokeExact(ptr(exec)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Aggregate a bundle for replay; insertion order is preserved. */
    fun D3D12AsyncExecutorAddBundle(exec: Long, bundle: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutoraddbundle!!.invokeExact(ptr(exec), bundle) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Add a transition on the main list (a bundle may not contain a barrier). */
    fun AsyncExecutorAddTransition(exec: Long, obj: Long,
                                   stateBefore: Int, stateAfter: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutoraddtransition!!.invokeExact(
                ptr(exec), ptr(obj), stateBefore, stateAfter) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Close and submit this frame; returns the fence value, 0 on failure. */
    fun AsyncExecutorEndFrame(exec: Long): Long {
        requireLoaded()
        try {
            return MH_asyncexecutorendframe!!.invokeExact(ptr(exec)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun AsyncExecutorLastFenceValue(exec: Long): Long {
        requireLoaded()
        try {
            return MH_asyncexecutorlastfencevalue!!.invokeExact(ptr(exec)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun AsyncExecutorIsFrameInProgress(exec: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutorisframeinprogress!!.invokeExact(ptr(exec)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Block until the fence reaches `fenceValue`; `timeoutMs == 0` waits forever. */
    fun AsyncExecutorWaitFrame(exec: Long, fenceValue: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutorwaitframe!!.invokeExact(ptr(exec), fenceValue, timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ---- Fence manager (SetEventOnCompletion + monitor thread, no polling) ----
    /** ID3D12Fence + monitor thread; 0 on failure. */
    fun D3D12AsyncFenceCreate(): Long {
        requireLoaded()
        try {
            return addr(MH_asyncfencecreate!!.invokeExact() as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    fun D3D12AsyncFenceDestroy(mgr: Long) {
        requireLoaded()
        try {
            MH_asyncfencedestroy!!.invokeExact(ptr(mgr))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** queue->Signal(fence, value). */
    fun AsyncFenceSignal(mgr: Long, value: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncfencesignal!!.invokeExact(ptr(mgr), value) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Block until `value`; `timeoutMs == 0` waits forever. */
    fun AsyncFenceWait(mgr: Long, value: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncfencewait!!.invokeExact(ptr(mgr), value, timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Non-blocking. */
    fun D3D12AsyncFenceIsComplete(mgr: Long, value: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncfenceiscomplete!!.invokeExact(ptr(mgr), value) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    /** Fires once when the fence reaches `value`, then releases the handle; `timeoutMs == 0` = no timeout, and the callback runs outside the registry lock. */
    fun AsyncFenceRegisterCallback(mgr: Long, value: Long,
                                   callback: D3D12FenceCallback?, timeoutMs: Long): Boolean {
        requireLoaded()
        if (callback == null) {
            return false
        }
        val handle = NEXT_CALLBACK_HANDLE.getAndIncrement()
        CALLBACKS.put(handle, CallbackEntry(callback))
        try {
            val accepted = ok(MH_asyncfenceregistercallback!!.invokeExact(
                ptr(mgr), value, FENCE_STUB!!, handle.toLong(), timeoutMs) as Int)
            if (!accepted) {
                CALLBACKS.remove(handle)
            }
            return accepted
        } catch (t: Throwable) {
            CALLBACKS.remove(handle)
            throw unexpected(t)
        }
    }
    private val CALLBACKS = ConcurrentHashMap<Int, CallbackEntry>()
    private val NEXT_CALLBACK_HANDLE = AtomicInteger(1)
    private class CallbackEntry(val callback: D3D12FenceCallback) {
        val fired = AtomicBoolean()
    }
    /** Upcall target: **never let an exception escape** — that terminates the JVM abruptly. */
    @JvmStatic
    private fun onFenceComplete(userHandle: Long, value: Long, completed: Int) {
        try {
            val handle = userHandle.toInt()
            val entry = CALLBACKS.get(handle) ?: return
            if (completed != 0 && !entry.fired.compareAndSet(false, true)) {
                return  // already fired for this registration
            }
            if (completed != 0) {
                CALLBACKS.remove(handle)
            }
            entry.callback.onFenceComplete(value, completed != 0)
        } catch (t: Throwable) {
            IllusionLogger.warnMessage("fence callback failed: ${t}")
        }
    }
    // ---- FFM linker bootstrap ----
    private fun bindAll() {
        bindFenceStub()
        bindMetaAndDevice()
        bindCommand()
        bindPipelineSurfaceDraw()
        bindAsync()
        // The JVM owns the verbose switch and must push it — native no longer reads the environment.
        D3D12SetLogVerbose(LOG_VERBOSE)
        // Start from the built-in default; IllusionLogger.applyFromConfig() pushes the configured value later.
        D3D12SetShutdownTraceAlways(true)
    }
    private fun bindMetaAndDevice() {
        MH_abiversion = skip("mcdrABIVersion", fd(MCDRLayout.I32))
        MH_abilayout = skip("mcdrABILayout", fdv(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_lasterror = skip("mcdrLastError", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_setlogverbose = skip("mcdrSetLogVerbose", fdv(MCDRLayout.I32))
        // 0=error 1=warning 2=info 3=debug; a DLL without it falls back to the boolean mcdrSetLogVerbose.
        // mcdr_dx12SetEnvironment only serves native's existing getenv() read points — never a new feature.
        MH_setenvironment = skipOptional("mcdr_dx12SetEnvironment", fdv(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_setloglevel = skipOptional("mcdrSetLogLevel", fdv(MCDRLayout.I32))
        // Config [logging] shutdownTrace; a DLL without it keeps the built-in always-on behaviour.
        MH_setshutdowntrace = skipOptional("mcdrSetShutdownTraceAlways", fdv(MCDRLayout.I32))
        // Config [debug] debugLayer — the layer attaches once, while the first device is created.
        MH_setdebuglayer = skipOptional("mcdr_dx12SetDebugLayer", fd(MCDRLayout.I32, MCDRLayout.I32))
        // Config [logging] nativeLogFile — launchers do not persist the child's stdout/stderr.
        MH_setlogfile = skipOptional("mcdrSetLogFile", fd(MCDRLayout.I32, MCDRLayout.PTR))
        // **Must run before the first D3D12 device exists** — the layer hooks D3D12 when it loads.
        MH_setpixcapture = skipOptional("mcdrSetPixCapture", fd(MCDRLayout.I32, MCDRLayout.I32))
        // Config [debug] pixDirectory: the DLL itself or its directory; before mcdrSetPixCapture.
        MH_setpixdirectory = skipOptional(
            "mcdrSetPixDirectory",
            fd(MCDRLayout.I32, MCDRLayout.PTR),
        )
        // PIXBeginCapture from the process itself — the only way to get a file at a chosen path.
        MH_startpixtiming = skipOptional(
            "mcdrStartPixTimingCapture",
            fd(MCDRLayout.I32, MCDRLayout.PTR),
        )
        MH_stoppixtiming = skipOptional("mcdrStopPixTimingCapture", fd(MCDRLayout.I32))
        // Raw D3D12_SHADING_RATE, -1 = off; capability is judged natively (tier 0 / AdditionalShadingRates).
        MH_setshadingrate = skipOptional("mcdrSetShadingRate", fd(MCDRLayout.I32, MCDRLayout.I32))
        // 0=OFF 1=SMAA 2=TAA 3=DLAA (same numbering as the video option); DLAA is accepted but degrades to OFF.
        MH_setantialiasing = skipOptional("mcdrSetAntiAliasing", fd(MCDRLayout.I32, MCDRLayout.I32))
        // Scope shading rate (ctx + rate); no export => MIN_VALUE and the JVM stays non-scoped.
        MH_setshadingratenow =
            skipOptional("mcdr_dx12SetShadingRateNow", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_setshadingratecombiners =
            skipOptional("mcdr_dx12SetShadingRateCombiners", fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32))
        // Create and Evaluate hit the same native implementation; bind Evaluate only when evaluating per frame.
        MH_createdlssfeature = skipOptional("mcdr_dx12CreateDlssFeature", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_evaluatedlss = skipOptional("mcdr_dx12EvaluateDlss", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_querydlss = skipOptional("mcdr_dx12QueryDlss", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        // The 8 SR entry points; no export => MIN_VALUE, callers degrade silently.
        MH_srpick = skipOptional("mcdr_dx12SrPick", fd(MCDRLayout.I32, MCDRLayout.I32))
        MH_srquerycaps = skipOptional("mcdr_dx12SrQueryCaps", fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR))
        MH_srcreate = skipOptional(
            "mcdr_dx12SrCreate",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32)
        )
        MH_srresize = skipOptional(
            "mcdr_dx12SrResize",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32)
        )
        MH_srevaluate = skipOptional("mcdr_dx12SrEvaluate", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_srjitter = skipOptional("mcdr_dx12SrJitter", fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_srdestroy = skipOptional("mcdr_dx12SrDestroy", fdv())
        MH_srjitterphase =
            skipOptional(
                "mcdr_dx12SrJitterPhaseCount",
                fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32),
            )
        MH_srquery = skipOptional("mcdr_dx12SrQuery", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        // FG entry points. Still skipOptional: an ABI-version mismatch throws first, so a half-upgraded pair degrades.
        MH_fgpick = skipOptional("mcdr_dx12FgPick", fd(MCDRLayout.I32, MCDRLayout.I32))
        MH_fgquerycaps = skipOptional(
            "mcdr_dx12FgQueryCaps",
            fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR)
        )
        MH_fgsetmode = skipOptional("mcdr_dx12FgSetMode", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_fgpresented = skipOptional("mcdr_dx12FgPresented", fd(MCDRLayout.I32))
        MH_fgsetconstants = skipOptional("mcdr_dx12FgSetConstants", fdv(MCDRLayout.PTR))
        MH_fgstatus = skipOptional("mcdr_dx12FgStatus", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_fgstate = skipOptional("mcdr_dx12FgState", fd(MCDRLayout.I32))
        MH_fgsetuitexture = skipOptional("mcdr_dx12FgSetUiTexture", fdv(MCDRLayout.PTR))
        // hudless present + who composites the UI; old DLL => MIN_VALUE, FrameGeneration keeps compositing itself.
        MH_fgsetuicomposition = skipOptional("mcdr_dx12FgSetUiComposition", fdv(MCDRLayout.I32))
        MH_fguicompositionready = skipOptional("mcdr_dx12FgUiCompositionReady", fd(MCDRLayout.I32))
        // DLSS switch decoupled from the AA mode; no export => MIN_VALUE, the legacy bridge falls back to aaMode == 4.
        MH_setdlssenabled = skipOptional("mcdr_dx12SetDlssEnabled", fd(MCDRLayout.I32, MCDRLayout.I32))
        // **Before** vanilla clearDepthTexture: afterwards the depth is 0.0 (reverse-Z far) and the depth term becomes a constant.
        MH_snapshotdlssdepth = skipOptional("mcdr_dx12SnapshotDlssDepth",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        // 0=Performance 1=Balanced 2=Quality; no export => MIN_VALUE, native still picks by ratio.
        MH_setdlssquality = skipOptional("mcdr_dx12SetDlssQuality", fd(MCDRLayout.I32, MCDRLayout.I32))
        MH_setdlssrenderpreset =
            skipOptional("mcdr_dx12SetDlssRenderPreset", fd(MCDRLayout.I32, MCDRLayout.I32))
        // JVM-created DLSS output texture as a Dx12Object* handle; no export => MIN_VALUE keeps the private UAV path.
        MH_setdlssconstants = skipOptional("mcdr_dx12SetDlssConstants",
            fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_reflexframebegin = skipOptional("mcdr_dx12ReflexFrameBegin", fdv())
        MH_reflexmarker = skipOptional("mcdr_dx12ReflexMarker", fdv(MCDRLayout.I32))
        MH_meshsupport = skipOptional("mcdr_dx12QueryMeshSupport", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_nrdinspect = skipOptional("mcdr_dx12NrdInspect", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_nrdinit = skipOptional("mcdr_dx12NrdInit", fd(
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_nrdshutdown = skipOptional("mcdr_dx12NrdShutdown", fd(MCDRLayout.I32))
        MH_nrddenoise =
            skipOptional(
                "mcdr_dx12NrdDenoise",
                fd(
                    MCDRLayout.I32,
                    MCDRLayout.PTR,
                    MCDRLayout.PTR,
                    MCDRLayout.I32,
                    MCDRLayout.PTR,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.PTR,
                    MCDRLayout.I32,
                ),
            )
        MH_nrddenoisetemporal = skipOptional(
            "mcdr_dx12NrdDenoiseTemporal",
            fd(
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.I32,
            ),
        )
        MH_rtsreval =
            skipOptional(
                "mcdr_dx12RtSrEval",
                fd(
                    MCDRLayout.I32,
                    MCDRLayout.PTR,
                    MCDRLayout.PTR,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.PTR,
                    MCDRLayout.PTR,
                    MCDRLayout.I32,
                ),
            )
        MH_dlssdeval =
            skipOptional(
                "mcdr_dx12DlssdEval",
                fd(
                    MCDRLayout.I32,
                    MCDRLayout.PTR,
                    MCDRLayout.PTR,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.I32,
                    MCDRLayout.PTR,
                    MCDRLayout.PTR,
                    MCDRLayout.I32,
                ),
            )
        MH_dlssdevalexposure = skipOptional("mcdr_dx12DlssdEvalExposure", fd(
            MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I32,
        ))
        MH_rtsrevalexposure = skipOptional("mcdr_dx12RtSrEvalExposure", fd(
            MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I32,
        ))
        MH_setreflexmode = skipOptional("mcdr_dx12SetReflexMode", fdv(MCDRLayout.I32))
        MH_setdlssgmode = skipOptional("mcdr_dx12SetDlssgMode", fdv(MCDRLayout.I32, MCDRLayout.I32))
    MH_getdlssgpresented = skipOptional("mcdr_dx12GetDlssgPresented", fd(MCDRLayout.I32))
        MH_setreflexframelimit = skipOptional("mcdr_dx12SetReflexFrameLimit", fdv(MCDRLayout.I32))
        // **Before** device/swapchain creation; pushed while the config is read.
        MH_sethdrmode = skipOptional("mcdr_dx12SetHdrMode", fdv(MCDRLayout.I32))
        MH_setraytracingmode = skipOptional("mcdr_dx12SetRaytracingMode", fdv(MCDRLayout.I32))
        // 能力面（只读）：给光追计划的 S0 能力串与门控用，缺这个符号时按「旧 native」处理。
        MH_rtquerycaps = skipOptional("mcdr_dx12QueryRtCaps", fd(MCDRLayout.I32, MCDRLayout.PTR))
        // State only; native picks the G-Buffer / RT branch once device + surface are ready.
        MH_setswapchainoptions =
            skipOptional("mcdr_dx12SetSwapchainOptions", fdv(MCDRLayout.I32, MCDRLayout.I32))
        // Applies at the next swapchain build.
        MH_gethdrappliedmode = skipOptional("mcdr_dx12GetHdrAppliedMode", fd(MCDRLayout.I32))
        MH_reflexavailable = skipOptional("mcdr_dx12ReflexAvailable", fd(MCDRLayout.I32))
        MH_dlssavailable = skipOptional("mcdr_dx12DlssAvailable", fd(MCDRLayout.I32))
        MH_setdlssoutput = skipOptional("mcdr_dx12SetDlssOutput",
            fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_createdevice = skip("mcdr_dx12CreateDevice", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_destroydevice = skip("mcdr_dx12DestroyDevice", fdv())
        MH_createtexture = skip("mcdr_dx12CreateTexture", fd(
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_createbuffer = skip("mcdr_dx12CreateBuffer", fd(MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I64))
        MH_createsampler = skip("mcdr_dx12CreateSampler", fd(
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.F32
        ))
        MH_createsamplerbias = skipOptional("mcdr_dx12CreateSamplerWithMipBias", fd(
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.F32,
            MCDRLayout.F32
        ))
        MH_createtextureview = skip("mcdr_dx12CreateTextureView", fd(
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_destroyresource = skip("mcdr_dx12DestroyResource", fdv(MCDRLayout.PTR))
        MH_mapbuffer = skip("mcdr_dx12MapBuffer", fd(
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.PTR
        ))
        MH_unmapbuffer = skip("mcdr_dx12UnmapBuffer", fdv(MCDRLayout.PTR))
        MH_enumerateadapters = skip("mcdr_dx12EnumerateAdapters", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_getadapterinfo = skip("mcdr_dx12GetAdapterInfo", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_getqueuehandle = skip("mcdr_dx12GetQueueHandle", fd(MCDRLayout.I64))
        MH_getdevicehandle = skip("mcdr_dx12GetDeviceHandle", fd(MCDRLayout.I64))
        MH_createhiddenwindow = skip("mcdr_dx12CreateHiddenWindow", fd(MCDRLayout.I64, MCDRLayout.I32, MCDRLayout.I32))
        MH_destroyhiddenwindow = skip("mcdr_dx12DestroyHiddenWindow", fdv(MCDRLayout.I64))
    }
    private fun bindCommand() {
        // Native renamed these two exports (the other 123 are unchanged).
        MH_gettimestampfrequency = skip("MCDR_D3D12_get_timestamp_frequency", fd(MCDRLayout.I64))
        MH_createcommandencoder = skip("MCDR_D3D12_create_command_encoder", fd(MCDRLayout.PTR))
        MH_destroycommandencoder = skip("mcdr_dx12DestroyCommandEncoder", fdv(MCDRLayout.PTR))
        MH_begincommandlist = skip("mcdr_dx12BeginCommandList", fdv(MCDRLayout.PTR))
        MH_begincommandlistwithwait = skip("mcdr_dx12BeginCommandListWithWait", fdv(MCDRLayout.PTR, MCDRLayout.I64))
        MH_endcommandlist = skip("mcdr_dx12EndCommandList", fdv(MCDRLayout.PTR))
        MH_submit = skip("mcdr_dx12Submit", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_waitforfence = skip("mcdr_dx12WaitForFence", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64))
        MH_getfencevalue = skip("mcdr_dx12GetFenceValue", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_gettimestampnow = skip("mcdr_dx12GetTimestampNow", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_islistopen = skip("mcdr_dx12IsListOpen", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_copybuffer = skip("mcdr_dx12CopyBuffer", fdv(
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64
        ))
        MH_writetotexture = skip("mcdr_dx12WriteToTexture", fdv(
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_copybuffertotexture = skip("mcdr_dx12CopyBufferToTexture",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I64,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_copytexturetobuffer = skip("mcdr_dx12CopyTextureToBuffer",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.I64
            ))
        MH_copytexturetotexture = skip("mcdr_dx12CopyTextureToTexture",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_clearcolortexture = skip("mcdr_dx12ClearColorTexture", fdv(
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.F32,
            MCDRLayout.F32,
            MCDRLayout.F32,
            MCDRLayout.F32
        ))
        MH_cleardepthtexture = skip("mcdr_dx12ClearDepthTexture", fdv(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.F64))
        MH_clearcolortextureregion = skip("mcdr_dx12ClearColorTextureRegion",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.F32,
                MCDRLayout.F32,
                MCDRLayout.F32,
                MCDRLayout.F32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_cleardepthtextureregion = skip("mcdr_dx12ClearDepthTextureRegion",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.F64,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_beginrenderpass = skip("mcdr_dx12BeginRenderPass",
            fdv(
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.F64,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32
            ))
        MH_endrenderpass = skip("mcdr_dx12EndRenderPass", fdv(MCDRLayout.PTR))
        MH_getactivecolortexture = skip("mcdr_dx12GetActiveColorTexture", fd(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_createquerypool = skip("mcdr_dx12CreateQueryPool", fd(MCDRLayout.PTR, MCDRLayout.I32))
        MH_destroyquerypool = skip("mcdr_dx12DestroyQueryPool", fdv(MCDRLayout.PTR))
        MH_writetimestamp = skip("mcdr_dx12WriteTimestamp", fdv(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_readqueryvalue = skip("mcdr_dx12ReadQueryValue", fd(MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.I32))
        MH_readqueryvalues = skip("mcdr_dx12ReadQueryValues", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.PTR
        ))
        MH_queuequeryvalues = skipOptional("mcdr_dx12QueueQueryValues", MCDRLayout.QUERY_QUEUE)
        MH_tryreadqueryvalues = skipOptional("mcdr_dx12TryReadQueryValues", MCDRLayout.QUERY_POLL)
        MH_initasyncrenderer = skip("mcdr_dx12InitAsyncRenderer", fdv(MCDRLayout.I32))
        MH_destroyasyncrenderer = skip("mcdr_dx12DestroyAsyncRenderer", fdv())
        MH_asyncrenderbeginframe = skip("mcdr_dx12AsyncRenderBeginFrame", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_asyncrenderwaitcomplete = skip("mcdr_dx12AsyncRenderWaitComplete", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64
        ))
        MH_asyncrenderisrecordingready = skip("mcdr_dx12AsyncRenderIsRecordingReady", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_asyncrenderwaitrecordingready = skip("mcdr_dx12AsyncRenderWaitRecordingReady", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64
        ))
        MH_asyncsendcommandsready = skip("mcdr_dx12AsyncSendCommandsReady", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_clearasyncrenderctx = skip("mcdr_dx12ClearAsyncRenderCtx", fdv())
        // skipOptional throughout: a missing export disables one pass, never the whole bind.
        MH_applysmaatotexture = skipOptional("mcdr_dx12ApplySmaaToTexture",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        // In-place SMAA on the world layer; false => no export or SMAA off (rendering continues).
        MH_blittexturetotexture = skipOptional("mcdr_dx12BlitTextureToTexture",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        // The GUI target takes over by blitting the scaled world target; no export => GUI shares the main target.
        MH_sharpentexturetotexture = skipOptional("mcdr_dx12SharpenTextureToTexture",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.F32))
        // 5-tap sharpen (TAA/DLAA/DLSS); no export => degrade to blit.
        MH_applytaatotexture = skipOptional("mcdr_dx12ApplyTaaToTexture",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
                MCDRLayout.F32, MCDRLayout.F32, MCDRLayout.F32, MCDRLayout.F32, MCDRLayout.F32,
                MCDRLayout.I32, MCDRLayout.F32))
    }
    private fun bindPipelineSurfaceDraw() {
        MH_creategraphicspipeline = skip("mcdr_dx12CreateGraphicsPipeline", fd(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_destroypipeline = skip("mcdr_dx12DestroyPipeline", fdv(MCDRLayout.PTR))
        // TAA resolve; no export => TAA disables itself with a logged reason.
        MH_compileshaderdxc = skip("mcdr_dx12CompileShaderDxc", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_createmeshpipeline = skip("mcdr_dx12CreateMeshPipeline", fd(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_createmeshpipelineroot = skip("mcdr_dx12CreateMeshPipelineWithRootSignature",
            fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I64))
        MH_meshrootsig = skip("mcdr_dx12CreateMeshRootSignature",
            fd(MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32))
        MH_meshrootsigrelease = skip("mcdr_dx12ReleaseRootSignature", fdv(MCDRLayout.I64))
        MH_dispatchmesh = skip("mcdr_dx12DispatchMesh", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        // meshlet terrain dispatch (vertex pool / section table / meshlet table + b8).
        MH_meshdrawmeshlets = skip("mcdr_dx12MeshDrawMeshlets", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
        // compute minimum path: root UAV u0 + 8 root constants b0.
        MH_createcomputepipeline = skip("mcdr_dx12CreateComputePipeline", fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_createcomputepipelineex = skip("mcdr_dx12CreateComputePipelineEx",
            fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32))
        MH_dispatchcomputeex = skip("mcdr_dx12DispatchComputeEx", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I64,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32
        ))
        MH_barrier = skip("mcdr_dx12Barrier", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        // 加速结构（S3）：BLAS/TLAS 的建与查询。全部是必需导出 —— 版本 8 的 DLL 才有，握手已经挡住了旧 DLL。
        MH_createaccelstructbuffer = skip("mcdr_dx12CreateAccelStructBuffer",
            fd(MCDRLayout.PTR, MCDRLayout.I64))
        MH_getblasprebuildinfo = skip("mcdr_dx12GetBlasPrebuildInfo",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR))
        MH_gettlasprebuildinfo = skip("mcdr_dx12GetTlasPrebuildInfo",
            fd(MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR))
        MH_buildblas = skip("mcdr_dx12BuildBlas", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32
        ))
        MH_buildtlas = skip("mcdr_dx12BuildTlas", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32
        ))
        MH_copyaccelstruct = skip("mcdr_dx12CopyAccelStruct",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_createaccelstructview = skip("mcdr_dx12CreateAccelStructView",
            fd(MCDRLayout.PTR, MCDRLayout.PTR))
        MH_getbufferaddress = skip("mcdr_dx12GetBufferAddress",
            fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_createraytracingpipeline = skip("mcdr_dx12CreateRaytracingPipeline", fd(
            MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32
        ))
        MH_getshaderidentifier = skip("mcdr_dx12GetShaderIdentifier",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_dispatchrays = skip("mcdr_dx12DispatchRays", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I32,
            MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64,
            MCDRLayout.I32, MCDRLayout.I32
        ))
        MH_creatertframe = skip("mcdr_dx12CreateRtFrame", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_creatertgbufferprogram = skip("mcdr_dx12CreateRtGBufferProgram", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_creatertgeometrysnapshot = skip("mcdr_dx12CreateRtGeometrySnapshot", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_creatertgeometrydelta = skip("mcdr_dx12CreateRtGeometryDelta", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_getrtgeometrymemory = skip("mcdr_dx12GetRtGeometryMemory", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_getrtaccelerationbudget = skipOptional("mcdr_dx12GetRtAccelerationBudget", MCDRLayout.RT_ACCELERATION_BUDGET)
        MH_setrtsceneworkbudget = skipOptional("mcdr_dx12SetRtSceneWorkBudget", MCDRLayout.RT_SCENE_WORK_SET)
        MH_getrtsceneworkbudget = skipOptional("mcdr_dx12GetRtSceneWorkBudget", MCDRLayout.RT_SCENE_WORK_GET)
        MH_releasertgeometrysnapshot = skip("mcdr_dx12ReleaseRtGeometrySnapshot", fd(MCDRLayout.I32, MCDRLayout.I64))
        MH_creatertacceleration = skip("mcdr_dx12CreateRtAcceleration", fd(MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I64))
        MH_releasertacceleration = skip("mcdr_dx12ReleaseRtAcceleration", fd(MCDRLayout.I32, MCDRLayout.I64))
        MH_preparertacceleration = skip("mcdr_dx12PrepareRtAcceleration", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR))
        MH_filterrtacceleration = skip("mcdr_dx12FilterRtAcceleration", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR))
        MH_rtaccelerationmemory = skip("mcdr_dx12RtAccelerationMemory", fd(MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I64))
        MH_creatertscene = skip("mcdr_dx12CreateRtScene", fd(MCDRLayout.I64))
        MH_releasertscene = skip("mcdr_dx12ReleaseRtScene", fd(MCDRLayout.I32, MCDRLayout.I64))
        MH_preparertscene = skip("mcdr_dx12PrepareRtScene", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_preparertscenetargets = skip("mcdr_dx12PrepareRtSceneTargets", MCDRLayout.RT_SCENE_TARGETS_CALL)
        MH_getrtscenestats = skipOptional("mcdr_dx12GetRtSceneStats", MCDRLayout.RT_SCENE_STATS)
        MH_releasertgbufferprogram = skip("mcdr_dx12ReleaseRtGBufferProgram", fd(MCDRLayout.I32, MCDRLayout.I64))
        MH_releasertframe = skip("mcdr_dx12ReleaseRtFrame", fd(MCDRLayout.I32, MCDRLayout.I64))
        MH_rtframebegingbuffer = skip("mcdr_dx12RtFrameBeginGBuffer",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64,
                MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.PTR))
        MH_rtframeendgbuffer = skip("mcdr_dx12RtFrameEndGBuffer",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I32))
        MH_rtframereplaygbuffer = skip("mcdr_dx12RtFrameReplayGBuffer",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR))
        MH_rtcapturegbuffer = skip("mcdr_dx12RtCaptureGBuffer",
            fd(MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.PTR))
        MH_rtreleasegbuffercapture = skip("mcdr_dx12RtReleaseGBufferCapture",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64))
        MH_rtframereplaycapturedgbuffer = skip("mcdr_dx12RtFrameReplayCapturedGBuffer",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.PTR))
        MH_rtframe = skip("mcdr_dx12RtFrame",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_rtframetransaction = skip("mcdr_dx12RtFrameTransaction", MCDRLayout.RT_FRAME_TRANSACTION)
        MH_rtframesetspatial = skip("mcdr_dx12RtFrameSetSpatial", MCDRLayout.RT_FRAME_SET_SPATIAL)
        MH_rtframereblur = skip("mcdr_dx12RtFrameReblur",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64))
        MH_rtframedenoise = skip("mcdr_dx12RtFrameDenoise",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_rtframeupscale = skip("mcdr_dx12RtFrameUpscale",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I32, MCDRLayout.PTR))
        MH_rtframecomposite = skip("mcdr_dx12RtFrameComposite",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64, MCDRLayout.I64,
                MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.PTR))
        MH_rttrackstats = skip("mcdr_dx12QueryRtTrackStats", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_sdkresourcestate = skipOptional("mcdr_dx12QuerySdkResourceState",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_dispatchcompute = skip("mcdr_dx12DispatchCompute", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        // shading-rate image: 2xSRV + 1xUAV compute PSO, R8_UINT image, SRI binding.
        MH_createcomputepipelineshadingrate = skip("mcdr_dx12CreateComputePipelineShadingRate",
            fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_createshadingrateimage = skip("mcdr_dx12CreateShadingRateImage",
            fd(MCDRLayout.PTR, MCDRLayout.I32, MCDRLayout.I32))
        MH_dispatchcomputeshadingrate = skip("mcdr_dx12DispatchComputeShadingRate", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32
        ))
        MH_setshadingrateimage = skip("mcdr_dx12SetShadingRateImage",
            fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR))
        // generic compute texture pass, same root signature as the SRI one.
        MH_createtexture3 = skip("mcdr_dx12CreateComputePipelineTexture3",
            fd(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_dispatchtexture3 = skip("mcdr_dx12DispatchComputeTexture3", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.PTR, MCDRLayout.PTR,
            MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32, MCDRLayout.I32
        ))
        MH_createsurface = skip("mcdr_dx12CreateSurface", fd(MCDRLayout.PTR, MCDRLayout.I64))
        MH_surfacepresentmodes = skip("mcdr_dx12SurfacePresentModes", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I32))
        MH_setexclusivefullscreen = skip("mcdr_dx12SetExclusiveFullscreen", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_configuresurface = skip("mcdr_dx12ConfigureSurface", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_acquiresurface = skip("mcdr_dx12AcquireSurface", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_blitsurface = skip("mcdr_dx12BlitSurface", fdv(MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.PTR))
        MH_presentsurface = skip("mcdr_dx12PresentSurface", fdv(MCDRLayout.PTR))
        MH_getbackbufferhandle = skip("mcdr_dx12GetBackBufferHandle", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_issurfacesuboptimal = skip("mcdr_dx12IsSurfaceSuboptimal", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_readbacktexturepixels = skip("mcdr_dx12ReadbackTexturePixels", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR
        ))
        // Append-only; no export => black-frame sampling falls back to the old path.
        MH_readbacktexturepixelsin = skipOptional("mcdr_dx12ReadbackTexturePixelsIn", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR
        ))
        MH_dlssoutputstate = skipOptional("mcdr_dx12DlssOutputState", fd(MCDRLayout.I32))
        MH_destroysurface = skip("mcdr_dx12DestroySurface", fdv(MCDRLayout.PTR))
        MH_setpipeline = skip("mcdr_dx12SetPipeline", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.PTR, MCDRLayout.I32))
        MH_setscissor = skip("mcdr_dx12SetScissor", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        // SetViewport / ClearStencilTexture, kept next to MH_setscissor.
        MH_setviewport = skip("mcdr_dx12SetViewport", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_clearstenciltexture = skip("mcdr_dx12ClearStencilTexture", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_setvertexbuffer = skip("mcdr_dx12SetVertexBuffer", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
        MH_setindexbuffer = skip("mcdr_dx12SetIndexBuffer", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_pushdescriptors = skip("mcdr_dx12PushDescriptors", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_drawindexed = skip("mcdr_dx12DrawIndexed", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_draw = skip("mcdr_dx12Draw", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_drawindexedindirect = skip("mcdr_dx12DrawIndexedIndirect", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
        MH_drawindirect = skip("mcdr_dx12DrawIndirect", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
    }
    private fun bindFenceStub() {
        try {
            STUB_TARGET = MethodHandles.lookup().findStatic(MCDR_ABI::class.java, "onFenceComplete",
                MethodType.methodType(Void.TYPE, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("cannot resolve the fence upcall target", e)
        }
        FENCE_STUB = LINKER.upcallStub(STUB_TARGET!!, fdv(MCDRLayout.I64, MCDRLayout.I64, MCDRLayout.I32), ARENA)
    }
    /** Binds the async path: descriptor partitioning, bundle pool, replay, executor, fence manager. */
    private fun bindAsync() {
        // Frame slot/value + the per-frame partitioned descriptor allocator.
        MH_asynccurrentframeslot = skip("mcdr_dx12AsyncCurrentFrameSlot", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_asynccurrentframevalue = skip("mcdr_dx12AsyncCurrentFrameValue", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_asyncdescriptorcreate = skip("mcdr_dx12AsyncDescriptorCreate", fd(MCDRLayout.PTR, MCDRLayout.I32))
        MH_asyncdescriptordestroy = skip("mcdr_dx12AsyncDescriptorDestroy", fdv(MCDRLayout.PTR))
        MH_asyncregionbase = skip("mcdr_dx12AsyncDescriptorRegionBase", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_asyncallocate = skip("mcdr_dx12AsyncDescriptorAllocate", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_asyncresetframe = skip("mcdr_dx12AsyncDescriptorResetFrame", fdv(MCDRLayout.PTR, MCDRLayout.I32))
        MH_asyncgpuhandle = skip("mcdr_dx12AsyncDescriptorGpuHandle", fd(MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.I32))
        MH_asyncwritecbv = skip("mcdr_dx12AsyncDescriptorWriteCBV", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64
        ))
        MH_asyncwritesrv = skip("mcdr_dx12AsyncDescriptorWriteSRV", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR
        ))
        // Per-worker bundle recorders.
        MH_asyncbundlepoolcreate = skip("mcdr_dx12AsyncBundlePoolCreate", fd(MCDRLayout.PTR, MCDRLayout.I32))
        MH_asyncbundlepooldestroy = skip("mcdr_dx12AsyncBundlePoolDestroy", fdv(MCDRLayout.PTR))
        MH_asyncbundlebegin = skip("mcdr_dx12AsyncBundleBegin", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I64
        ))
        MH_asyncbundleend = skip("mcdr_dx12AsyncBundleEnd", fd(MCDRLayout.I64, MCDRLayout.PTR, MCDRLayout.I32))
        MH_asyncbundlesetpipelinestate = skip("mcdr_dx12AsyncBundleSetPipelineState", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_asyncbundlesetdescriptortable = skip("mcdr_dx12AsyncBundleSetDescriptorTable", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I64
        ))
        MH_asyncbundlesetvertexbuffer = skip("mcdr_dx12AsyncBundleSetVertexBuffer", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
        MH_asyncbundlesetindexbuffer = skip("mcdr_dx12AsyncBundleSetIndexBuffer", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_asyncbundledrawindexed = skip("mcdr_dx12AsyncBundleDrawIndexed", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_asyncbundledraw = skip("mcdr_dx12AsyncBundleDraw", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_asyncbundledrawindexedindirect = skip("mcdr_dx12AsyncBundleDrawIndexedIndirect", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I32
        ))
        // The hot path: bindings/draws are struct arrays, so a worker gets a zero-copy slice.
        MH_asyncbundlerecordpartition = skip("mcdr_dx12AsyncBundleRecordPartition",
            fd(
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.I32,
                MCDRLayout.PTR,
                MCDRLayout.PTR,
                MCDRLayout.I32
            ))
        // Bundle replay plus the barrier pre-pass that must run on the main list first.
        MH_executebundle = skip("mcdr_dx12ExecuteBundle", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64))
        MH_asyncpreparecbvbuffers = skip("mcdr_dx12AsyncPrepareCBVBuffers", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_asyncprepareindexbuffers = skip("mcdr_dx12AsyncPrepareIndexBuffers", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        MH_asyncpreparetextureviews = skip("mcdr_dx12AsyncPrepareTextureViews", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32
        ))
        // Main command list executor.
        MH_asyncexecutorcreate = skip("mcdr_dx12AsyncExecutorCreate", fd(MCDRLayout.PTR))
        MH_asyncexecutordestroy = skip("mcdr_dx12AsyncExecutorDestroy", fdv(MCDRLayout.PTR))
        MH_asyncexecutortrybeginframe = skip("mcdr_dx12AsyncExecutorTryBeginFrame", fd(MCDRLayout.I32, MCDRLayout.PTR))
        MH_asyncexecutoraddbundle = skip("mcdr_dx12AsyncExecutorAddBundle", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64
        ))
        MH_asyncexecutoraddtransition = skip("mcdr_dx12AsyncExecutorAddTransition", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.PTR,
            MCDRLayout.I32,
            MCDRLayout.I32
        ))
        MH_asyncexecutorendframe = skip("mcdr_dx12AsyncExecutorEndFrame", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_asyncexecutorlastfencevalue = skip("mcdr_dx12AsyncExecutorLastFenceValue", fd(MCDRLayout.I64, MCDRLayout.PTR))
        MH_asyncexecutorisframeinprogress = skip("mcdr_dx12AsyncExecutorIsFrameInProgress", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR
        ))
        MH_asyncexecutorwaitframe = skip("mcdr_dx12AsyncExecutorWaitFrame", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64
        ))
        // Fence manager. FENCE_STUB is the only upcall; timeoutMs == 0 means infinite, which is why
        // the descriptor carries the timeout rather than the callee.
        MH_asyncfencecreate = skip("mcdr_dx12AsyncFenceCreate", fd(MCDRLayout.PTR))
        MH_asyncfencedestroy = skip("mcdr_dx12AsyncFenceDestroy", fdv(MCDRLayout.PTR))
        MH_asyncfencesignal = skip("mcdr_dx12AsyncFenceSignal", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64))
        MH_asyncfencewait = skip("mcdr_dx12AsyncFenceWait", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64
        ))
        MH_asyncfenceiscomplete = skip("mcdr_dx12AsyncFenceIsComplete", fd(MCDRLayout.I32, MCDRLayout.PTR, MCDRLayout.I64))
        MH_asyncfenceregistercallback = skip("mcdr_dx12AsyncFenceRegisterCallback", fd(
            MCDRLayout.I32,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.PTR,
            MCDRLayout.I64,
            MCDRLayout.I64
        ))
    }}
