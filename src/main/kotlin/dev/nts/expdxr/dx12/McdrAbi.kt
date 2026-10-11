@file:Suppress("unused", "UNCHECKED_CAST", "FunctionName", "LocalVariableName", "RedundantVisibilityModifier", "MemberVisibilityCanBePrivate", "KotlinRedundantDiagnosticSuppress", "USELESS_CAST", "SENSELESS_COMPARISON")

package dev.nts.expdxr.dx12

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.HashMap
import java.util.List
import java.util.Map

import dev.nts.expdxr.dx12.McdrLayout.F32
import dev.nts.expdxr.dx12.McdrLayout.F64
import dev.nts.expdxr.dx12.McdrLayout.I32
import dev.nts.expdxr.dx12.McdrLayout.I64
import dev.nts.expdxr.dx12.McdrLayout.PTR

/**
 * Java FFM (java.lang.foreign) binding for the MCDR native render library.
 *
 * <p>This class replaces `Dx12Native` from the Fabric original. Every method keeps
 * the name and signature of its JNI counterpart so the rest of the backend ports with a
 * class-name change only; what changed is the mechanism:
 *
 * <ul>
 *   <li>`System.load` + `javac -h` generated `Java_com_..._*` exports are
 *       gone. [SymbolLookup.libraryLookup] loads the DLL and resolves
 *       plain `extern "C"` symbols — the native side is a pure C-ABI library with no
 *       knowledge of Java.</li>
 *   <li>Structs cross the boundary through [McdrLayout] group layouts instead of the
 *       hand-written little-endian `putInt`/`putU8` packing that the JNI
 *       bridge needed. [McdrLayout.verify] checks every offset against the running
 *       DLL at load time, so a drifted header is a startup error instead of silent memory
 *       corruption.</li>
 *   <li>Handles stay plain `long` addresses (the Java side never dereferences them),
 *       converted to [MemorySegment] only at the call boundary.</li>
 *   <li>Errors arrive through [dx12LastError] instead of a thrown Java exception
 *       raised from native code, so a failure inside a hot call no longer unwinds through
 *       the JNI frame.</li>
 * </ul>
 *
 * <p>All method handles are linked once and invoked with `invokeExact`; nothing on a
 * per-frame path allocates a [MethodHandle].
 */
object McdrAbi {
    /**
     * 高频诊断日志开关：设置环境变量 DX12_LOG_VERBOSE=1 开启，默认关闭。
     * 与 native 侧日志级别同款环境变量；Java 端每帧/每调用的诊断打印均以此门控，
     * 避免海量 System.err 同步 I/O 拖垮帧率（P29 帧数修复）。
     */
    @JvmField
    val LOG_VERBOSE: Boolean = "1" == System.getenv("DX12_LOG_VERBOSE")

    /**
     * 帧时间插桩开关（P3）：设置环境变量 DX12_PROF=1 开启，默认关闭。
     * 与 LOG_VERBOSE 分离——后者会附带大量诊断日志，会干扰计时。
     */
    @JvmField
    val PROF: Boolean = LOG_VERBOSE || "1" == System.getenv("DX12_PROF")

    private const val LIB_NAME = "MCDR.dll"
    private const val EXTRACT_DIR = "dxrmc"

    /** Kept open for the engine's lifetime: it owns the loaded library and every upcall stub. */
    private val ARENA: Arena = Arena.ofShared()

    private val LINKER: Linker = Linker.nativeLinker()

    private var LIB: SymbolLookup? = null
    private var LOAD_FAILURE: Throwable? = null

    // ------------------------------------------------------------------
    // Method handles. Not final so that a failed load leaves them null instead of throwing
    // ExceptionInInitializerError; requireLoaded() turns that into a readable message.
    // ------------------------------------------------------------------
    private var MH_abiversion: MethodHandle? = null
    private var MH_abilayout: MethodHandle? = null
    private var MH_lasterror: MethodHandle? = null
    private var MH_setlogverbose: MethodHandle? = null

    private var MH_createdevice: MethodHandle? = null
    private var MH_createtexture: MethodHandle? = null
    private var MH_createbuffer: MethodHandle? = null
    private var MH_createsampler: MethodHandle? = null
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
    private var MH_initasyncrenderer: MethodHandle? = null
    private var MH_destroyasyncrenderer: MethodHandle? = null
    private var MH_asyncrenderbeginframe: MethodHandle? = null
    private var MH_asyncrenderwaitcomplete: MethodHandle? = null
    private var MH_asyncrenderisrecordingready: MethodHandle? = null
    private var MH_asyncrenderwaitrecordingready: MethodHandle? = null
    private var MH_asyncsendcommandsready: MethodHandle? = null
    private var MH_clearasyncrenderctx: MethodHandle? = null

    private var MH_creategraphicspipeline: MethodHandle? = null
    private var MH_destroypipeline: MethodHandle? = null

    private var MH_createsurface: MethodHandle? = null
    private var MH_surfacepresentmodes: MethodHandle? = null
    private var MH_configuresurface: MethodHandle? = null
    private var MH_acquiresurface: MethodHandle? = null
    private var MH_blitsurface: MethodHandle? = null
    private var MH_presentsurface: MethodHandle? = null
    private var MH_getbackbufferhandle: MethodHandle? = null
    private var MH_issurfacesuboptimal: MethodHandle? = null
    private var MH_readbacksurfacepixels: MethodHandle? = null
    private var MH_readbacktexturepixels: MethodHandle? = null
    private var MH_dumptexturetofile: MethodHandle? = null
    private var MH_destroysurface: MethodHandle? = null
    private var MH_getactivesurfacehandle: MethodHandle? = null

    private var MH_setpipeline: MethodHandle? = null
    private var MH_setscissor: MethodHandle? = null
    /** 26.2 新增：mcdr_dx12SetViewport（返回 1 成功 / 0 失败）。 */
    private var MH_setviewport: MethodHandle? = null
    private var MH_setvertexbuffer: MethodHandle? = null
    /** 26.2 新增：mcdr_dx12ClearStencilTexture（返回 1 成功 / 0 失败）。 */
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

    /**
     * 唯一的 upcall：围栏回调跳板。native 从它自己的 worker std::thread 调用，
     * JVM 对此一无所知，所以这两个字段必须是静态的并活到进程结束——被回收的
     * upcall stub 就是一个悬空函数指针。异常逃出 upcall 目标会直接终止 JVM，
     * 因此 [onFenceComplete] 自己吞掉 Throwable，不依赖 catchException 包装。
     */
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
                verifyAbi()
            } catch (t: Throwable) {
                bindFailure = t
            }
            LOAD_FAILURE = bindFailure
            if (bindFailure == null) {
                System.out.println("[dx12] MCDR native library loaded (FFM): " + LIB_NAME
                    + ", ABI v" + safeAbiVersion())
                System.out.println("[dx12] struct layouts: " + McdrLayout.describeSizes())
                System.out.println("[dx12] 高频调试日志"
                    + (if (LOG_VERBOSE) "已开启 (DX12_LOG_VERBOSE=1)" else "已关闭，如需开启请设置环境变量 DX12_LOG_VERBOSE=1"))
                System.out.println("[dx12] 诊断 GREEN 着色器注入已关闭，如需开启请设置环境变量 DX12_DIAG_GREEN=1")
                System.out.println("[dx12] 诊断 WHITE 着色器注入已关闭，如需开启请设置环境变量 DX12_DIAG_WHITE=1")
            }
        }
    }

    // ==================================================================
    // Loading
    // ==================================================================

    private fun safeAbiVersion(): Int {
        return try {
            MH_abiversion!!.invokeExact() as Int
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * Copy MCDR.dll out of the mod JAR to `<user.dir>/dxrmc/` and return the
     * path. The local copy is refreshed whenever its bytes differ from the JAR resource —
     * a byte-for-byte comparison rather than the original "local mtime newer && same size"
     * test, because JAR entry timestamps are pinned to the 1980 epoch and two different
     * DLLs can share a size, which once let a stale local copy shadow a new build.
     *
     * <p>If the resource is absent (the JVM and native repos are separate, so a build may
     * legitimately ship without a DLL) an already-extracted local file is used as-is.
     */
    private fun extractLibrary(): Path {
        val dir = Path.of(System.getProperty("user.dir"), EXTRACT_DIR)
        Files.createDirectories(dir)
        val dllPath = dir.resolve(LIB_NAME)

        var useJarDll = true
        var jarBytes: ByteArray? = null
        try {
            McdrAbi::class.java.getResourceAsStream("/" + LIB_NAME).use { input ->
                if (input != null) {
                    jarBytes = input.readAllBytes()
                }
            }
        } catch (ignored: Exception) {
            // fall through: treat as "not packaged"
        }

        if (jarBytes == null) {
            if (!Files.exists(dllPath)) {
                throw IllegalStateException(LIB_NAME + " is neither packaged in the JAR nor present at "
                    + dllPath.toAbsolutePath() + " — build the MCDR native project first")
            }
            useJarDll = false
        } else if (Files.exists(dllPath)) {
            try {
                if (java.util.Arrays.equals(jarBytes, Files.readAllBytes(dllPath))) {
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

    private fun requireLoaded() {
        if (LOAD_FAILURE != null) {
            throw IllegalStateException("MCDR native library is unavailable: " + LOAD_FAILURE, LOAD_FAILURE)
        }
    }
    /** True when the library loaded, bound and its layout self-check passed. */
    fun isAvailable(): Boolean {
        return LOAD_FAILURE == null
    }
    /** Why the library is unavailable, or null when it is fine. */
    fun unavailableReason(): String? {
        return LOAD_FAILURE?.toString()
    }
    private fun h(name: String, desc: FunctionDescriptor): MethodHandle {
        val addr = LIB!!.find(name).orElseThrow {
            IllegalStateException("MCDR export not found: " + name)
        }
        return LINKER.downcallHandle(addr, desc)
    }

    private fun fd(ret: MemoryLayout, vararg args: MemoryLayout): FunctionDescriptor {
        return FunctionDescriptor.of(ret, *args)
    }

    private fun fdv(vararg args: MemoryLayout): FunctionDescriptor {
        return FunctionDescriptor.ofVoid(*args)
    }

    /** Cross-check every mirrored struct offset/size against the running DLL. */
    private fun verifyAbi() {
        val version = MH_abiversion!!.invokeExact() as Int
        if (version != MCDR_ABI_VERSION) {
            throw IllegalStateException("MCDR ABI version mismatch: java=" + MCDR_ABI_VERSION
                + " native=" + version)
        }
        Arena.ofConfined().use { arena ->
            val pFields = arena.allocate(PTR)
            val pCount = arena.allocate(I32)
            MH_abilayout!!.invokeExact(pFields, pCount)
            val count = pCount.get(I32, 0)
            val first = pFields.get(PTR, 0) as MemorySegment
            if (count <= 0 || first.address() == 0L) {
                throw IllegalStateException("mcdrABILayout() reported " + count + " fields")
            }
            val fields = first.reinterpret(McdrLayout.ABI_FIELD.byteSize() * count)

            val nativeFields = mutableMapOf<String, LongArray>()
            val stride = McdrLayout.ABI_FIELD.byteSize()
            for (i in 0 until count) {
                val f = fields.asSlice(i * stride, stride)
                val nameSeg = f.get(PTR, 0) as MemorySegment
                val name = nameSeg.reinterpret(Long.MAX_VALUE).getString(0)
                nativeFields[name] = longArrayOf(f.get(I64, 8) as Long, f.get(I64, 16) as Long)
            }
            McdrLayout.verify(nativeFields)
        }
    }

    private fun unexpected(t: Throwable): RuntimeException {
        return IllegalStateException("MCDR native call failed: " + t, t)
    }

    private fun ptr(handle: Long): MemorySegment {
        return MemorySegment.ofAddress(handle)
    }

    private fun addr(seg: MemorySegment?): Long {
        return if (seg == null) 0L else seg.address()
    }

    /** `MCDR_ABI_VERSION` from `mcdr_abi.h`. */
    const val MCDR_ABI_VERSION: Int = 1

    /** Return value of a native "bool" (declared `int32_t`) as a Java boolean. */
    private fun ok(value: Int): Boolean {
        return value != 0
    }

    // ==================================================================
    // Per-thread scratch slots.
    //
    // A thread owns four reusable buffers, each backed by its own arena so that growing one
    // never invalidates another. This replaces the JNI bridge's per-call Get*ArrayElements /
    // new[] pairs on the draw hot path: after the first few frames the native calls perform
    // no allocation at all.
    // ==================================================================
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
            val need = Math.max(bytes, 8L)
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

    // ==================================================================
    // Meta
    // ==================================================================

    /** Last error message recorded by any native entry point on the calling thread; "" if none. */
    fun dx12LastError(): String {
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
    fun dx12SetLogVerbose(enabled: Boolean) {
        if (LOAD_FAILURE != null) {
            return
        }
        try {
            MH_setlogverbose!!.invokeExact(if (enabled) 1 else 0)
        } catch (ignored: Throwable) {
            // logging configuration must never fail engine start-up
        }
    }

    /** Throw [IllegalStateException] carrying the native error text when `value` is 0. */
    private fun check(value: Boolean, what: String) {
        if (!value) {
            val detail = dx12LastError()
            throw IllegalStateException(what + " failed"
                + (if (detail.isEmpty()) "" else ": " + detail))
        }
    }

    // ==================================================================
    // Device + resources
    // ==================================================================
    /**
     * Probe D3D12 device creation + run the native resource self-test.
     *
     * @return adapter name + feature level + self-test result
     *         (e.g. "NVIDIA GeForce RTX 4090 (D3D_FEATURE_LEVEL_12_1); SELF-TEST OK (...)")
     *         or a string starting with "ERROR: "
     */
    fun dx12CreateDevice(): String {
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

    /** Create a texture; returns a native handle (long = Dx12Object*). */
    fun dx12CreateTexture(usage: Int, format: Int, width: Int,
        height: Int, depthOrLayers: Int, mipLevels: Int): Long {
        requireLoaded()
        try {
            return addr(MH_createtexture!!.invokeExact(
                usage, format, width, height, depthOrLayers, mipLevels) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Create a buffer; returns a native handle (long = Dx12Object*). */
    fun dx12CreateBuffer(usage: Int, size: Long): Long {
        requireLoaded()
        try {
            return addr(MH_createbuffer!!.invokeExact(usage, size) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Create a sampler; returns a native handle (long = Dx12Object*). */
    fun dx12CreateSampler(addressU: Int, addressV: Int, minFilter: Int,
        magFilter: Int, maxAnisotropy: Int, maxLod: Float): Long {
        requireLoaded()
        try {
            return addr(MH_createsampler!!.invokeExact(
                addressU, addressV, minFilter, magFilter, maxAnisotropy, maxLod) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Create a texture view; returns a native handle (long = Dx12Object*). */
    fun dx12CreateTextureView(textureHandle: Long, baseMipLevel: Int, mipLevels: Int): Long {
        requireLoaded()
        try {
            return addr(MH_createtextureview!!.invokeExact(
                ptr(textureHandle), baseMipLevel, mipLevels) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Destroy a native resource created by one of the dx12Create* methods. */
    fun dx12DestroyResource(handle: Long) {
        requireLoaded()
        try {
            MH_destroyresource!!.invokeExact(ptr(handle))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Map a buffer region and return a view of the mapped memory.
     *
     * <p>The returned buffer is set to [ByteOrder.nativeOrder] (LITTLE_ENDIAN on
     * Windows x64). The JNI original handed back a `NewDirectByteBuffer`, which Java
     * defines as BIG_ENDIAN and which silently corrupted every UBO matrix until P26 — the
     * order is now explicit at the boundary as well as declarative in the layouts.
     *
     * @return the mapped view, or null on failure (see [dx12LastError])
     */
    fun dx12MapBuffer(bufferHandle: Long, offset: Long, length: Long,
        read: Boolean, write: Boolean): ByteBuffer? {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val pLen = arena.allocate(I64)
            val base = MH_mapbuffer!!.invokeExact(
                ptr(bufferHandle), offset, length, if (read) 1 else 0, if (write) 1 else 0, pLen) as MemorySegment
            if (base.address() == 0L) {
                return null
            }
            val actual = pLen.get(I64, 0) as Long
            return base.reinterpret(actual).asByteBuffer().order(ByteOrder.nativeOrder())
        }
    }

    /** Unmap a previously mapped buffer. */
    fun dx12UnmapBuffer(bufferHandle: Long) {
        requireLoaded()
        try {
            MH_unmapbuffer!!.invokeExact(ptr(bufferHandle))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Enumerate every D3D12-capable adapter as a JSON array string. */
    fun dx12EnumerateAdapters(): String {
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
    /**
     * Describe the adapter behind the live D3D12 device as a flat JSON object
     * (`mcdr_dx12GetAdapterInfo`). Returns "" when no D3D12 device exists, which is
     * what [Dx12AdapterInfo.parse] turns into [Dx12AdapterInfo.UNKNOWN].
     */
    fun dx12GetAdapterInfo(): String {
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

    /** Global command queue COM pointer, or 0 when no device exists. */
    fun dx12GetQueueHandle(): Long {
        requireLoaded()
        try {
            return MH_getqueuehandle!!.invokeExact() as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Global device COM pointer, or 0 when no device exists. */
    fun dx12GetDeviceHandle(): Long {
        requireLoaded()
        try {
            return MH_getdevicehandle!!.invokeExact() as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Create a hidden Win32 window for a standalone render loop; 0 on failure. */
    fun dx12CreateHiddenWindow(width: Int, height: Int): Long {
        requireLoaded()
        try {
            return MH_createhiddenwindow!!.invokeExact(width, height) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Destroy a hidden window. */
    fun dx12DestroyHiddenWindow(hwnd: Long) {
        requireLoaded()
        try {
            MH_destroyhiddenwindow!!.invokeExact(hwnd)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    // ==================================================================
    // Command layer
    // ==================================================================

    /** GPU timestamp frequency in ticks per second (0 if unknown). */
    fun dx12GetTimestampFrequency(): Long {
        requireLoaded()
        try {
            return MH_gettimestampfrequency!!.invokeExact() as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Create a command context (CommandContext*); returns the native handle. */
    fun dx12CreateCommandEncoder(): Long {
        requireLoaded()
        try {
            return addr(MH_createcommandencoder!!.invokeExact() as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Destroy a command context created by [dx12CreateCommandEncoder]. */
    fun dx12DestroyCommandEncoder(ctx: Long) {
        requireLoaded()
        try {
            MH_destroycommandencoder!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Begin recording on the current command list (resets its allocator). */
    fun dx12BeginCommandList(ctx: Long) {
        requireLoaded()
        try {
            MH_begincommandlist!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Begin recording with an explicit GPU wait: blocks until the queue fence reaches
     * `waitForValue` before resetting the allocator. Pass 0 to skip waiting.
     */
    fun dx12BeginCommandListWithWait(ctx: Long, waitForValue: Long) {
        requireLoaded()
        try {
            MH_begincommandlistwithwait!!.invokeExact(ptr(ctx), waitForValue)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Close the current command list. */
    fun dx12EndCommandList(ctx: Long) {
        requireLoaded()
        try {
            MH_endcommandlist!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Submit the recorded command list: ExecuteCommandLists + Signal(fence, ++value).
     * Non-blocking; returns the fence value of this submit.
     */
    fun dx12Submit(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_submit!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Wait until the fence reaches `value`.
     *
     * <p>Waits on the device-level queue fence, not a per-context one, mirroring the JNI
     * original: vanilla creates fence tokens on one-shot encoders that are never submitted,
     * so a per-context wait would never complete.
     *
     * <p>A timeout is reported as `false`, not as an error — official
     * `GpuFence.awaitCompletion` returns false at timeout 0 rather than throwing, and
     * `StagedVertexBuffer` reclamation depends on that.
     */
    fun dx12WaitForFence(ctx: Long, value: Long, timeoutNs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_waitforfence!!.invokeExact(ptr(ctx), value, timeoutNs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Current device-level queue fence value (for createFence). */
    fun dx12GetFenceValue(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_getfencevalue!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Read the GPU timestamp now (blocking). */
    fun dx12GetTimestampNow(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_gettimestampnow!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Whether the context's command list is currently open for recording. */
    fun dx12IsListOpen(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_islistopen!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Record a buffer-to-buffer CopyBufferRegion into the command list. */
    fun dx12CopyBuffer(ctx: Long, src: Long, srcOffset: Long,
        dst: Long, dstOffset: Long, size: Long) {
        requireLoaded()
        try {
            MH_copybuffer!!.invokeExact(ptr(ctx), ptr(src), srcOffset, ptr(dst), dstOffset, size)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Upload `width*height` tightly-packed texels from a staging buffer into a texture sub-region. */
    fun dx12WriteToTexture(ctx: Long, stagingBuf: Long, stagingOffset: Long,
        width: Int, height: Int, dstTex: Long, mip: Int, layer: Int, dstX: Int, dstY: Int) {
        requireLoaded()
        try {
            MH_writetotexture!!.invokeExact(ptr(ctx), ptr(stagingBuf), stagingOffset,
                width, height, ptr(dstTex), mip, layer, dstX, dstY)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Copy a sub-region of a (possibly padded) source buffer into a texture. */
    fun dx12CopyBufferToTexture(ctx: Long, srcBuf: Long, srcOffset: Long,
        srcWidth: Int, srcHeight: Int, dstTex: Long, mip: Int, layer: Int, dstX: Int, dstY: Int,
        w: Int, h: Int) {
        requireLoaded()
        try {
            MH_copybuffertotexture!!.invokeExact(ptr(ctx), ptr(srcBuf), srcOffset,
                srcWidth, srcHeight, ptr(dstTex), mip, layer, dstX, dstY, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Copy a texture sub-region into a readback buffer (row pitch is 256-aligned). */
    fun dx12CopyTextureToBuffer(ctx: Long, srcTex: Long, mip: Int, layer: Int,
        srcX: Int, srcY: Int, w: Int, h: Int, dstBuf: Long, dstOffset: Long) {
        requireLoaded()
        try {
            MH_copytexturetobuffer!!.invokeExact(ptr(ctx), ptr(srcTex), mip, layer,
                srcX, srcY, w, h, ptr(dstBuf), dstOffset)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Copy a texture sub-region into another texture. */
    fun dx12CopyTextureToTexture(ctx: Long, srcTex: Long, dstTex: Long,
        mip: Int, layer: Int, srcX: Int, srcY: Int, dstX: Int, dstY: Int, w: Int, h: Int) {
        requireLoaded()
        try {
            MH_copytexturetotexture!!.invokeExact(ptr(ctx), ptr(srcTex), ptr(dstTex),
                mip, layer, srcX, srcY, dstX, dstY, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Record a full-texture colour clear (RENDER_ATTACHMENT texture). */
    fun dx12ClearColorTexture(ctx: Long, texture: Long, r: Float, g: Float, b: Float, a: Float) {
        requireLoaded()
        try {
            MH_clearcolortexture!!.invokeExact(ptr(ctx), ptr(texture), r, g, b, a)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Record a full-texture depth clear. */
    fun dx12ClearDepthTexture(ctx: Long, texture: Long, depth: Double) {
        requireLoaded()
        try {
            MH_cleardepthtexture!!.invokeExact(ptr(ctx), ptr(texture), depth)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * 26.2 新增：清空模板纹理区域（C 原型
     * `int32_t mcdr_dx12ClearStencilTexture(mcdr_command_ctx* ctx, mcdr_object* texture, int32_t value);`）。
     * 完全仿照 [dx12SetScissor] 的包装风格：返回 1 成功 / 0 失败，异常统一走 [unexpected]。
     */
    fun dx12ClearStencilTexture(ctx: Long, texture: Long, value: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_clearstenciltexture!!.invokeExact(ptr(ctx), ptr(texture), value) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Record a rectangular-region colour clear. GuiItemAtlas slots are cleared per-slot
     * before a STALE redraw; the original whole-texture clear wiped already-baked item
     * icons, which then never re-rendered after paging back. Coordinates are in
     * render-target pixel space.
     */
    fun dx12ClearColorTextureRegion(ctx: Long, texture: Long, r: Float, g: Float,
        b: Float, a: Float, x: Int, y: Int, w: Int, h: Int) {
        requireLoaded()
        try {
            MH_clearcolortextureregion!!.invokeExact(ptr(ctx), ptr(texture), r, g, b, a, x, y, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Record a rectangular-region depth clear (same coordinate convention). */
    fun dx12ClearDepthTextureRegion(ctx: Long, texture: Long, depth: Double,
        x: Int, y: Int, w: Int, h: Int) {
        requireLoaded()
        try {
            MH_cleardepthtextureregion!!.invokeExact(ptr(ctx), ptr(texture), depth, x, y, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Begin a render pass: create RTV/DSV descriptors, transition attachments,
     * OMSetRenderTargets, viewport/scissor, optional clears.
     *
     * @param colorTextures   colour attachment textures (null entries are skipped, and a
     *                        zero-length array is a legal depth-only pass)
     * @param colorMips       per-attachment RTV mip slice (TextureAtlas uploads write mip
     *                        levels one at a time via mipViews[level], so the real mip must
     *                        be bound)
     * @param colorClearFlags per-attachment 0=load / 1=clear
     * @param clearColors     r,g,b,a per attachment (length colorTextures.length * 4)
     * @param depthMip        depth attachment DSV mip slice
     */
    fun dx12BeginRenderPass(ctx: Long, colorTextures: LongArray?, colorMips: IntArray?,
        colorClearFlags: ByteArray?, clearColors: FloatArray?, depthTexture: Long, depthMip: Int,
        depthClearFlag: Byte, depthClearValue: Double, x: Int, y: Int, w: Int, h: Int) {
        requireLoaded()
        val colorCount = if (colorTextures == null) 0 else colorTextures.size
        // Slot 0: the attachment pointer array. Slot 1: the widened int32 clear flags.
        val views = scratch(0, Math.max(1, colorCount) * 8L)
        for (i in 0 until colorCount) {
            views.set(PTR, i * 8L, ptr(colorTextures!![i]))
        }
        var mips = MemorySegment.NULL
        if (colorCount > 0 && colorMips != null && colorMips.size >= colorCount) {
            mips = scratch(2, colorCount * 4L)
            MemorySegment.copy(colorMips, 0, mips, I32, 0, colorCount)
        }
        var flags = MemorySegment.NULL
        if (colorCount > 0 && colorClearFlags != null && colorClearFlags.size >= colorCount) {
            flags = scratch(1, colorCount * 4L)
            for (i in 0 until colorCount) {
                flags.set(I32, i * 4L, colorClearFlags[i].toInt())
            }
        }
        var colors = MemorySegment.NULL
        if (clearColors != null && clearColors.size >= colorCount * 4) {
            colors = scratch(3, Math.max(1, colorCount) * 16L)
            MemorySegment.copy(clearColors, 0, colors, F32, 0, colorCount * 4)
        }
        try {
            MH_beginrenderpass!!.invokeExact(ptr(ctx), views, colorCount, mips, flags, colors,
                ptr(depthTexture), depthMip, depthClearFlag.toInt(), depthClearValue, x, y, w, h)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** End the current render pass. */
    fun dx12EndRenderPass(ctx: Long) {
        requireLoaded()
        try {
            MH_endrenderpass!!.invokeExact(ptr(ctx))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Handle of the first active colour attachment of the open pass, or 0. */
    fun dx12GetActiveColorTexture(ctx: Long): Long {
        requireLoaded()
        try {
            return addr(MH_getactivecolortexture!!.invokeExact(ptr(ctx)) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Create a timestamp query pool; returns the native handle. */
    fun dx12CreateQueryPool(size: Int): Long {
        requireLoaded()
        try {
            return addr(MH_createquerypool!!.invokeExact(size) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Destroy a query pool. */
    fun dx12DestroyQueryPool(pool: Long) {
        requireLoaded()
        try {
            MH_destroyquerypool!!.invokeExact(ptr(pool))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Record EndQuery(TIMESTAMP) at `index` into the command list. */
    fun dx12WriteTimestamp(ctx: Long, pool: Long, index: Int) {
        requireLoaded()
        try {
            MH_writetimestamp!!.invokeExact(ptr(ctx), ptr(pool), index)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Blocking read of one timestamp value. */
    fun dx12ReadQueryValue(pool: Long, index: Int): Long {
        requireLoaded()
        try {
            return MH_readqueryvalue!!.invokeExact(ptr(pool), index) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Blocking read of `count` timestamps starting at `start`. */
    fun dx12ReadQueryValues(pool: Long, start: Int, count: Int, out: LongArray) {
        requireLoaded()
        if (count <= 0) {
            return
        }
        val buf = scratch(0, count * 8L)
        try {
            val rc = MH_readqueryvalues!!.invokeExact(ptr(pool), start, count, buf) as Int
            if (rc != 0) {
                MemorySegment.copy(buf, I64, 0, out, 0, Math.min(count, out.size))
            }
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ------------------------------------------------------------------
    // P33 async: dedicated render thread
    // ------------------------------------------------------------------

    /** Start the dedicated render thread. Must precede [dx12CreateCommandEncoder]. */
    fun dx12InitAsyncRenderer(workerCount: Int) {
        requireLoaded()
        try {
            MH_initasyncrenderer!!.invokeExact(workerCount)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Tear down the dedicated render thread and release its events. */
    fun dx12DestroyAsyncRenderer() {
        requireLoaded()
        try {
            MH_destroyasyncrenderer!!.invokeExact()
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * P31：释放设备上下文（device / queue / 全部描述符堆 / DXGI adapter）。
     *
     * 必须在进程退出前调用。若把这些 ComPtr 留给 DLL_PROCESS_DETACH 去析构，
     * 那时 dxgi.dll 已经 detach，Release() 会跳进失效的 vtable，产生原生异常
     * 并以异常码 0x87A（=2170）作为进程退出码，把一次正常的关闭变成 BUILD FAILED。
     * 幂等：重复调用安全。
     */
    fun dx12DestroyDevice() {
        requireLoaded()
        try {
            MH_destroydevice!!.invokeExact()
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Non-blocking: ask the render thread to start the next frame. */
    fun dx12AsyncRenderBeginFrame(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderbeginframe!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Blocking: wait until this frame has been submitted (or the timeout expires). */
    fun dx12AsyncRenderWaitComplete(ctx: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderwaitcomplete!!.invokeExact(ptr(ctx), timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Non-blocking: has the render thread reached "recording ready"? */
    fun dx12AsyncRenderIsRecordingReady(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderisrecordingready!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Blocking: wait for "recording ready" (or the timeout). Replaces the main thread's
     * `sleep(1)` poll, whose scheduler latency landed directly on the frame's
     * critical path.
     */
    fun dx12AsyncRenderWaitRecordingReady(ctx: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncrenderwaitrecordingready!!.invokeExact(ptr(ctx), timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Tell the render thread that all commands have been recorded. */
    fun dx12AsyncSendCommandsReady(ctx: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncsendcommandsready!!.invokeExact(ptr(ctx)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Clear `gAsyncRenderCtx` (used by the Java-side P41 timeout fallback). */
    fun dx12ClearAsyncRenderCtx() {
        requireLoaded()
        try {
            MH_clearasyncrenderctx!!.invokeExact()
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    // ==================================================================
    // Pipeline
    // ==================================================================

    /**
     * Compile an HLSL pair and create a pipeline (root signature + both PSO variants).
     *
     * <p>The JNI version took a hand-packed little-endian `ByteBuffer`; this takes the
     * structured [McdrPipelineDesc] and packs it through [McdrLayout]. Both ends
     * now share one definition, which is what removes the wrong-offset class of bug — and
     * with it the positional re-association of semantic names that the old separate
     * `semanticCount` section required.
     *
     * @return native pipeline handle; 0 on failure (see [dx12LastError])
     */
    fun dx12CreateGraphicsPipeline(desc: McdrPipelineDesc): Long {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val packed = desc.pack(arena)
            return addr(MH_creategraphicspipeline!!.invokeExact(packed) as MemorySegment)
        }
    }

    /** Destroy a pipeline created by [dx12CreateGraphicsPipeline]. */
    fun dx12DestroyPipeline(pipeline: Long) {
        requireLoaded()
        try {
            MH_destroypipeline!!.invokeExact(ptr(pipeline))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    // ==================================================================
    // Surface (DXGI swapchain)
    // ==================================================================

    /** Create a DXGI swapchain for a native HWND; 0 on failure. */
    fun dx12CreateSurface(hwnd: Long): Long {
        requireLoaded()
        try {
            return addr(MH_createsurface!!.invokeExact(hwnd) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Present modes this implementation supports, as `GpuSurface.PresentMode`
     * ordinals (IMMEDIATE=0, MAILBOX=1, FIFO=2, FIFO_RELAXED=3). DXGI FLIP supports
     * {IMMEDIATE, FIFO, FIFO_RELAXED}.
     */
    fun dx12SurfacePresentModes(): IntArray {
        requireLoaded()
        try {
            val total = MH_surfacepresentmodes!!.invokeExact(MemorySegment.NULL, 0) as Int
            if (total <= 0) {
                return IntArray(0)
            }
            val buf = scratch(0, total * 4L)
            val written = MH_surfacepresentmodes!!.invokeExact(buf, total) as Int
            val n = Math.min(total, Math.max(0, written))
            val out = IntArray(n)
            for (i in 0 until n) {
                out[i] = buf.get(I32, i * 4L) as Int
            }
            return out
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** ResizeBuffers to `width x height` with the given present mode. */
    fun dx12ConfigureSurface(surface: Long, width: Int, height: Int, presentMode: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_configuresurface!!.invokeExact(ptr(surface), width, height, presentMode) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Acquire the next back buffer. */
    fun dx12AcquireSurface(surface: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_acquiresurface!!.invokeExact(ptr(surface)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Record a copy of `texture` into the current back buffer on the open command list. */
    fun dx12BlitSurface(ctx: Long, surface: Long, texture: Long) {
        requireLoaded()
        try {
            MH_blitsurface!!.invokeExact(ptr(ctx), ptr(surface), ptr(texture))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Present the current back buffer. */
    fun dx12PresentSurface(surface: Long) {
        requireLoaded()
        try {
            MH_presentsurface!!.invokeExact(ptr(surface))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Raw `ID3D12Resource*` of the currently acquired back buffer, for blit/readback
     * (deliberately not a cached `Dx12Object*`).
     */
    fun dx12GetBackBufferHandle(surface: Long): Long {
        requireLoaded()
        try {
            return MH_getbackbufferhandle!!.invokeExact(ptr(surface)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Whether the last present reported a suboptimal status. */
    fun dx12IsSurfaceSuboptimal(surface: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_issurfacesuboptimal!!.invokeExact(ptr(surface)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** P6 diagnostic: 3x3 sampled back-buffer pixels as int[36] (9 px x RGBA); null on failure. */
    fun dx12ReadbackSurfacePixels(surface: Long): IntArray? {
        return readback36(MH_readbacksurfacepixels, surface, "dx12ReadbackSurfacePixels")
    }

    /** P6 diagnostic: 3x3 sampled texture pixels as int[36] (9 px x RGBA); null on failure. */
    fun dx12ReadbackTexturePixels(texHandle: Long): IntArray? {
        return readback36(MH_readbacktexturepixels, texHandle, "dx12ReadbackTexturePixels")
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
                out[i] = buf.get(I32, i * 4L) as Int
            }
            return out
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** P27 diagnostic: dump a texture to `dx12_dump_<tag>.bmp` plus an ASCII thumbnail. */
    fun dx12DumpTextureToFile(texHandle: Long, tag: String?) {
        requireLoaded()
        Arena.ofConfined().use { arena ->
            val cTag = arena.allocateFrom(if (tag == null) "tex" else tag)
            MH_dumptexturetofile!!.invokeExact(ptr(texHandle), cTag)
        }
    }

    /** Destroy a surface created by [dx12CreateSurface]. */
    fun dx12DestroySurface(surface: Long) {
        requireLoaded()
        try {
            MH_destroysurface!!.invokeExact(ptr(surface))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** P6 diagnostic: native handle of the active surface, for reading back the real frame. */
    fun dx12GetActiveSurfaceHandle(): Long {
        requireLoaded()
        try {
            return addr(MH_getactivesurfacehandle!!.invokeExact() as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    // ==================================================================
    // Draw commands
    // ==================================================================

    /** Bind a graphics pipeline; `hasDepth` selects the with/without-depth PSO variant. */
    fun dx12SetPipeline(ctx: Long, pipeline: Long, hasDepth: Boolean): Boolean {
        requireLoaded()
        try {
            return ok(MH_setpipeline!!.invokeExact(ptr(ctx), ptr(pipeline), if (hasDepth) 1 else 0) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Set the scissor rect on the open render pass. */
    fun dx12SetScissor(ctx: Long, x: Int, y: Int, width: Int, height: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setscissor!!.invokeExact(ptr(ctx), x, y, width, height) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * 26.2 新增：设置视口（C 原型
     * `int32_t mcdr_dx12SetViewport(mcdr_command_ctx* ctx, int32_t x, int32_t y, int32_t width, int32_t height);`）。
     * 完全仿照 [dx12SetScissor] 的包装风格：返回 1 成功 / 0 失败，异常统一走 [unexpected]。
     */
    fun dx12SetViewport(ctx: Long, x: Int, y: Int, width: Int, height: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setviewport!!.invokeExact(ptr(ctx), x, y, width, height) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Bind a vertex buffer to `slot` (0..15). */
    fun dx12SetVertexBuffer(ctx: Long, slot: Int, buffer: Long, offset: Long, stride: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setvertexbuffer!!.invokeExact(ptr(ctx), slot, ptr(buffer), offset, stride) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Bind an index buffer; `indexType` 0=SHORT(R16_UINT), 1=INT(R32_UINT). */
    fun dx12SetIndexBuffer(ctx: Long, buffer: Long, indexType: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_setindexbuffer!!.invokeExact(ptr(ctx), ptr(buffer), indexType) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Push the draw's descriptors into the transient per-frame heap and bind root
     * descriptor table 0 (mirror of the Vulkan push descriptors).
     *
     * <p>Parallel arrays, one entry per pipeline binding in declaration order: types
     * 0=CBV / 1=SRV(texture view) / 2=SRV(texel buffer); for CBV the matching
     * buffer/offset/length; for texel buffers buffer/texelFormat; for texture views the
     * texture-view handle. The six arrays are folded into one `mcdr_binding_slot`
     * array, which is written straight into this thread's scratch slot — one pass, no
     * per-call native allocation.
     */
    fun dx12PushDescriptors(ctx: Long, types: IntArray?, buffers: LongArray?,
        offsets: LongArray?, lengths: LongArray?, texelFormats: IntArray?, views: LongArray?): Boolean {
        requireLoaded()
        val count = if (types == null) 0 else types.size
        if (count == 0) {
            // A pipeline with no bindings is a no-op, not a failure (preserved from the
            // JNI original, which returned success before touching ctx).
            return true
        }
        val stride = McdrLayout.BINDING_SLOT.byteSize()
        val slots = scratch(0, count * stride)
        for (i in 0 until count) {
            val base = i * stride
            slots.set(I32, base, types!![i])
            slots.set(I32, base + 4, if (texelFormats == null || i >= texelFormats.size) 0 else texelFormats[i])
            slots.set(PTR, base + 8, ptr(if (buffers == null || i >= buffers.size) 0L else buffers[i]))
            slots.set(PTR, base + 16, ptr(if (views == null || i >= views.size) 0L else views[i]))
            slots.set(I64, base + 24, if (offsets == null || i >= offsets.size) 0L else offsets[i])
            slots.set(I64, base + 32, if (lengths == null || i >= lengths.size) 0L else lengths[i])
        }
        try {
            return ok(MH_pushdescriptors!!.invokeExact(ptr(ctx), slots, count) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** DrawIndexedInstanced on the open render pass. */
    fun dx12DrawIndexed(ctx: Long, indexCount: Int, instanceCount: Int,
        firstIndex: Int, baseVertex: Int, firstInstance: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_drawindexed!!.invokeExact(ptr(ctx), indexCount, instanceCount,
                firstIndex, baseVertex, firstInstance) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** DrawInstanced on the open render pass. */
    fun dx12Draw(ctx: Long, vertexCount: Int, instanceCount: Int,
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
    fun dx12DrawIndexedIndirect(ctx: Long, commands: Long, offset: Long, drawCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_drawindexedindirect!!.invokeExact(ptr(ctx), ptr(commands), offset, drawCount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** ExecuteIndirect (DrawInstanced) with the given command buffer. */
    fun dx12DrawIndirect(ctx: Long, commands: Long, offset: Long, drawCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_drawindirect!!.invokeExact(ptr(ctx), ptr(commands), offset, drawCount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }
    // ==================================================================
    // Async: partitioned descriptor allocator, bundle pool, executor, fence manager
    // (bound in bindAll; see the ASYNC BLOCK marker below)
    // ==================================================================

    /** Current frame's drawHeap section (= fenceValue % 4), for alignment with worker descriptor regions. */
    fun dx12AsyncCurrentFrameSlot(ctx: Long): Int {
        requireLoaded()
        try {
            return MH_asynccurrentframeslot!!.invokeExact(ptr(ctx)) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Current frame's raw frame number (= ctx->fenceValue), for bundle allocator slot rotation. */
    fun dx12AsyncCurrentFrameValue(ctx: Long): Long {
        requireLoaded()
        try {
            return MH_asynccurrentframevalue!!.invokeExact(ptr(ctx)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Create the partitioned descriptor allocator: it carves `drawHeap` into disjoint
     * (frameSlot, worker) regions so workers can write transient CBV/SRV concurrently, and
     * narrows the synchronous ring to the reserved slots at the start of each section.
     *
     * @return 0 on failure
     */
    fun dx12AsyncDescriptorCreate(workerCount: Int): Long {
        requireLoaded()
        try {
            return addr(MH_asyncdescriptorcreate!!.invokeExact(workerCount) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Destroy the partitioned allocator and hand the whole drawHeap section back to the sync ring. */
    fun dx12AsyncDescriptorDestroy(alloc: Long) {
        requireLoaded()
        try {
            MH_asyncdescriptordestroy!!.invokeExact(ptr(alloc))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Absolute base slot of the (frameSlot, worker) region; -1 when out of range. */
    fun dx12AsyncDescriptorRegionBase(alloc: Long, frameSlot: Int, worker: Int): Int {
        requireLoaded()
        try {
            return MH_asyncregionbase!!.invokeExact(ptr(alloc), frameSlot, worker) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Atomically allocate `count` contiguous slots inside the region; -1 when exhausted. */
    fun dx12AsyncDescriptorAllocate(alloc: Long, frameSlot: Int, worker: Int, count: Int): Int {
        requireLoaded()
        try {
            return MH_asyncallocate!!.invokeExact(ptr(alloc), frameSlot, worker, count) as Int
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Frame boundary: reset every worker's allocation cursor under this frame slot. */
    fun dx12AsyncDescriptorResetFrame(alloc: Long, frameSlot: Int) {
        requireLoaded()
        try {
            MH_asyncresetframe!!.invokeExact(ptr(alloc), frameSlot)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** GPU handle of an absolute slot (for SetGraphicsRootDescriptorTable). */
    fun dx12AsyncDescriptorGpuHandle(alloc: Long, slot: Int): Long {
        requireLoaded()
        try {
            return MH_asyncgpuhandle!!.invokeExact(ptr(alloc), slot) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Write `buffer[offset, offset+size)` as a constant buffer view into `slot`. */
    fun dx12AsyncDescriptorWriteCBV(alloc: Long, slot: Int, buffer: Long,
        offset: Long, size: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncwritecbv!!.invokeExact(ptr(alloc), slot, ptr(buffer), offset, size) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Copy an existing texture-view descriptor into `slot`. */
    fun dx12AsyncDescriptorWriteSRV(alloc: Long, slot: Int, view: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncwritesrv!!.invokeExact(ptr(alloc), slot, ptr(view)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Create the bundle recorder pool (one BUNDLE allocator + command list per worker); 0 on failure. */
    fun dx12AsyncBundlePoolCreate(workerCount: Int): Long {
        requireLoaded()
        try {
            return addr(MH_asyncbundlepoolcreate!!.invokeExact(workerCount) as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Destroy the bundle recorder pool. */
    fun dx12AsyncBundlePoolDestroy(pool: Long) {
        requireLoaded()
        try {
            MH_asyncbundlepooldestroy!!.invokeExact(ptr(pool))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Begin recording worker `worker`'s bundle. `frameSlot` must be
     * `fenceValue % 4`; `frameValue` picks a distinct allocator slot within the
     * frame (a bundle already replayed by ExecuteBundle cannot be Reset until the parent
     * list has finished). Returns false once the frame's batch budget is exhausted.
     */
    fun dx12AsyncBundleBegin(pool: Long, worker: Int, frameSlot: Int, frameValue: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlebegin!!.invokeExact(ptr(pool), worker, frameSlot, frameValue) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** End recording; returns the bundle command list handle, or 0 on failure. */
    fun dx12AsyncBundleEnd(pool: Long, worker: Int): Long {
        requireLoaded()
        try {
            return MH_asyncbundleend!!.invokeExact(ptr(pool), worker) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Bind a pipeline inside the bundle (root signature + primitive topology). */
    fun dx12AsyncBundleSetPipelineState(pool: Long, worker: Int, pipeline: Long,
        hasDepth: Boolean): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlesetpipelinestate!!.invokeExact(
                ptr(pool), worker, ptr(pipeline), if (hasDepth) 1 else 0) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Bind a root descriptor table inside the bundle. */
    fun dx12AsyncBundleSetDescriptorTable(pool: Long, worker: Int, slot: Int,
        gpuHandle: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlesetdescriptortable!!.invokeExact(ptr(pool), worker, slot, gpuHandle) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Bind a vertex buffer inside the bundle. */
    fun dx12AsyncBundleSetVertexBuffer(pool: Long, worker: Int, slot: Int,
        buffer: Long, offset: Long, stride: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlesetvertexbuffer!!.invokeExact(
                ptr(pool), worker, slot, ptr(buffer), offset, stride) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Bind an index buffer inside the bundle; `indexType` 0=SHORT, 1=INT. */
    fun dx12AsyncBundleSetIndexBuffer(pool: Long, worker: Int, buffer: Long, indexType: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundlesetindexbuffer!!.invokeExact(
                ptr(pool), worker, ptr(buffer), indexType) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Record DrawIndexedInstanced inside the bundle. */
    fun dx12AsyncBundleDrawIndexed(pool: Long, worker: Int, indexCount: Int,
        instanceCount: Int, firstIndex: Int, baseVertex: Int, firstInstance: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundledrawindexed!!.invokeExact(ptr(pool), worker, indexCount,
                instanceCount, firstIndex, baseVertex, firstInstance) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Record DrawInstanced inside the bundle. */
    fun dx12AsyncBundleDraw(pool: Long, worker: Int, vertexCount: Int,
        instanceCount: Int, firstVertex: Int, firstInstance: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundledraw!!.invokeExact(ptr(pool), worker, vertexCount,
                instanceCount, firstVertex, firstInstance) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Record DrawIndexedIndirect inside the bundle (device-level command signature). */
    fun dx12AsyncBundleDrawIndexedIndirect(pool: Long, worker: Int, commands: Long,
        offset: Long, drawCount: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncbundledrawindexedindirect!!.invokeExact(
                ptr(pool), worker, ptr(commands), offset, drawCount) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * P57: fold one worker partition's per-draw sequence — write the descriptors, take the
     * GPU handle, set the root table, bind the index and vertex buffers, DrawIndexedInstanced(1)
     * — into a single native call.
     *
     * <p>The 14 parallel arrays are still accepted (so the callers port unchanged) but are
     * repacked once into the [McdrLayout.BINDING_SLOT] and
     * [McdrLayout.PARTITION_DRAW] struct arrays the ABI actually takes. Arrays are
     * indexed by the <em>global</em> draw `d = startDraw + i` as `arr[d*bc + j]`,
     * matching the JNI original; the native side receives only this partition's slice.
     */
    fun dx12AsyncBundleRecordPartition(alloc: Long, pool: Long, worker: Int,
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

    /**
     * P59: the whole batch shares one binding set, so the descriptor block is written once
     * and the root table is set once; each draw then only re-binds its index/vertex buffers
     * and issues DrawIndexedInstanced(1). Only the first `bc` descriptor entries are
     * read.
     */
    fun dx12AsyncBundleRecordPartitionShared(alloc: Long, pool: Long, worker: Int,
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
        val slotStride = McdrLayout.BINDING_SLOT.byteSize()
        val drawStride = McdrLayout.PARTITION_DRAW.byteSize()
        val bindingEntries = if (shared) bc else count * bc

        // Slot 0 holds the binding block, slot 1 the per-draw records: separate arenas so
        // growing one never invalidates the other.
        val bindings = scratch(0, bindingEntries * slotStride)
        val draws = scratch(1, count * drawStride)

        for (i in 0 until count) {
            val d = startDraw + i
            val drawBase = i * drawStride
            draws.set(PTR, drawBase, ptr(idxBuf[d]))
            draws.set(PTR, drawBase + 8, ptr(vbBuf[d]))
            draws.set(I64, drawBase + 16, vbOff[d])
            draws.set(I32, drawBase + 24, idxType[d])
            draws.set(I32, drawBase + 28, idxCount[d])
            draws.set(I32, drawBase + 32, firstIdx[d])
            draws.set(I32, drawBase + 36, baseVert[d])
            draws.set(I32, drawBase + 40, vbSlot[d])
            draws.set(I32, drawBase + 44, vbStride[d])
            draws.set(I32, drawBase + 48, 0)
        }
        if (shared) {
            // Only draw 0's binding block is read, and it is reused for every draw in the
            // partition, so the destination is entry 0 regardless of startDraw.
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
            dst.set(I32, base, bType[s])
            dst.set(I32, base + 4, 0)
            dst.set(PTR, base + 8, ptr(bBuf[s]))
            dst.set(PTR, base + 16, ptr(bView[s]))
            dst.set(I64, base + 24, bOff[s])
            dst.set(I64, base + 32, bLen[s])
        }
    }

    /** Replay a bundle, in order, on the existing (main) command list. */
    fun dx12ExecuteBundle(ctx: Long, bundle: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_executebundle!!.invokeExact(ptr(ctx), bundle) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Transition this batch's CBV buffers to VERTEX_AND_CONSTANT_BUFFER on the main list
     * before dispatching bundles (a bundle may not contain a ResourceBarrier). Vertex
     * buffers share that target state, so they travel through this entry point too.
     */
    fun dx12AsyncPrepareCBVBuffers(ctx: Long, buffers: LongArray?): Boolean {
        return prepareObjects(MH_asyncpreparecbvbuffers, ctx, buffers, "dx12AsyncPrepareCBVBuffers")
    }

    /** Transition this batch's index buffers to INDEX_BUFFER on the main list. */
    fun dx12AsyncPrepareIndexBuffers(ctx: Long, buffers: LongArray?): Boolean {
        return prepareObjects(MH_asyncprepareindexbuffers, ctx, buffers, "dx12AsyncPrepareIndexBuffers")
    }

    /** Transition this batch's sampled texture views to SHADER_RESOURCE on the main list. */
    fun dx12AsyncPrepareTextureViews(ctx: Long, views: LongArray?): Boolean {
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
            arr.set(PTR, i * 8L, ptr(objects!![i]))
        }
        try {
            return ok(handle!!.invokeExact(ptr(ctx), arr, count) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Create the main command list executor (kFramesInFlight allocator/list sets + fence); 0 on failure. */
    fun dx12AsyncExecutorCreate(): Long {
        requireLoaded()
        try {
            return addr(MH_asyncexecutorcreate!!.invokeExact() as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Destroy the main command list executor. */
    fun dx12AsyncExecutorDestroy(exec: Long) {
        requireLoaded()
        try {
            MH_asyncexecutordestroy!!.invokeExact(ptr(exec))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Non-blocking frame start; false when the previous frame has not finished (skip this frame). */
    fun dx12AsyncExecutorTryBeginFrame(exec: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutortrybeginframe!!.invokeExact(ptr(exec)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Aggregate a bundle for replay (insertion order is preserved). */
    fun dx12AsyncExecutorAddBundle(exec: Long, bundle: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutoraddbundle!!.invokeExact(ptr(exec), bundle) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Add a resource transition on the main list (a bundle may not contain a barrier). */
    fun dx12AsyncExecutorAddTransition(exec: Long, obj: Long,
        stateBefore: Int, stateAfter: Int): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutoraddtransition!!.invokeExact(
                ptr(exec), ptr(obj), stateBefore, stateAfter) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Close and submit this frame; returns the submit's fence value (0 on failure). */
    fun dx12AsyncExecutorEndFrame(exec: Long): Long {
        requireLoaded()
        try {
            return MH_asyncexecutorendframe!!.invokeExact(ptr(exec)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Fence value of the most recent submit. */
    fun dx12AsyncExecutorLastFenceValue(exec: Long): Long {
        requireLoaded()
        try {
            return MH_asyncexecutorlastfencevalue!!.invokeExact(ptr(exec)) as Long
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Whether a frame is currently being recorded. */
    fun dx12AsyncExecutorIsFrameInProgress(exec: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutorisframeinprogress!!.invokeExact(ptr(exec)) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Block until the given fence value is reached (`timeoutMs == 0` means wait forever). */
    fun dx12AsyncExecutorWaitFrame(exec: Long, fenceValue: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncexecutorwaitframe!!.invokeExact(ptr(exec), fenceValue, timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    // ------------------------------------------------------------------
    // P33 async: fence manager (SetEventOnCompletion + monitor thread, no polling)
    // ------------------------------------------------------------------

    /** Create the fence manager (ID3D12Fence + monitor thread); 0 on failure. */
    fun dx12AsyncFenceCreate(): Long {
        requireLoaded()
        try {
            return addr(MH_asyncfencecreate!!.invokeExact() as MemorySegment)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Destroy the fence manager. */
    fun dx12AsyncFenceDestroy(mgr: Long) {
        requireLoaded()
        try {
            MH_asyncfencedestroy!!.invokeExact(ptr(mgr))
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** `queue->Signal(fence, value)`. */
    fun dx12AsyncFenceSignal(mgr: Long, value: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncfencesignal!!.invokeExact(ptr(mgr), value) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Block until the fence reaches `value` (`timeoutMs == 0` means wait forever). */
    fun dx12AsyncFenceWait(mgr: Long, value: Long, timeoutMs: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncfencewait!!.invokeExact(ptr(mgr), value, timeoutMs) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /** Non-blocking: has the fence reached `value`? */
    fun dx12AsyncFenceIsComplete(mgr: Long, value: Long): Boolean {
        requireLoaded()
        try {
            return ok(MH_asyncfenceiscomplete!!.invokeExact(ptr(mgr), value) as Int)
        } catch (t: Throwable) {
            throw unexpected(t)
        }
    }

    /**
     * Register a completion callback: when the fence reaches `value` the monitor
     * thread calls [Dx12FenceCallback.onFenceComplete]
     * (`timeoutMs == 0` means no timeout).
     *
     * <p>The callback is invoked outside the registry lock, so it may register or unregister
     * again. Each registration fires at most once; the handle is released after it fires.
     * This is the project's only [Linker.upcallStub] — the JNI original
     * needed a cached `JavaVM*`, a `NewGlobalRef`, per-invocation
     * GetEnv/AttachCurrentThread bookkeeping and a `GetMethodID(cls, "onFenceComplete",
     * "(JZ)V")` string lookup; all of that is now one integer handle into a registry.
     */
    fun dx12AsyncFenceRegisterCallback(mgr: Long, value: Long,
        callback: Dx12FenceCallback?, timeoutMs: Long): Boolean {
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

    private val CALLBACKS = java.util.concurrent.ConcurrentHashMap<Int, CallbackEntry>()
    private val NEXT_CALLBACK_HANDLE = java.util.concurrent.atomic.AtomicInteger(1)

    private class CallbackEntry(val callback: Dx12FenceCallback) {
        val fired = java.util.concurrent.atomic.AtomicBoolean()
    }

    /**
     * Upcall target. Must never let an exception escape — the JVM terminates abruptly if an
     * upcall stub's target throws — so everything is caught and reported to stderr.
     */
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
            System.err.println("[dx12] fence callback failed: " + t)
        }
    }

    // ==================================================================
    // FFM linker bootstrap
    // ==================================================================

    private fun bindAll() {
        bindFenceStub()
        bindMetaAndDevice()
        bindCommand()
        bindPipelineSurfaceDraw()
        bindAsync()
    }

    private fun bindMetaAndDevice() {
        MH_abiversion = h("mcdrABIVersion", fd(I32))
        MH_abilayout = h("mcdrABILayout", fdv(PTR, PTR))
        MH_lasterror = h("mcdrLastError", fd(I32, PTR, I32))
        MH_setlogverbose = h("mcdrSetLogVerbose", fdv(I32))

        MH_createdevice = h("mcdr_dx12CreateDevice", fd(I32, PTR, I32))
        MH_destroydevice = h("mcdr_dx12DestroyDevice", fdv())
        MH_createtexture = h("mcdr_dx12CreateTexture", fd(PTR, I32, I32, I32, I32, I32, I32))
        MH_createbuffer = h("mcdr_dx12CreateBuffer", fd(PTR, I32, I64))
        MH_createsampler = h("mcdr_dx12CreateSampler", fd(PTR, I32, I32, I32, I32, I32, F32))
        MH_createtextureview = h("mcdr_dx12CreateTextureView", fd(PTR, PTR, I32, I32))
        MH_destroyresource = h("mcdr_dx12DestroyResource", fdv(PTR))
        MH_mapbuffer = h("mcdr_dx12MapBuffer", fd(PTR, PTR, I64, I64, I32, I32, PTR))
        MH_unmapbuffer = h("mcdr_dx12UnmapBuffer", fdv(PTR))
        MH_enumerateadapters = h("mcdr_dx12EnumerateAdapters", fd(I32, PTR, I32))
        MH_getadapterinfo = h("mcdr_dx12GetAdapterInfo", fd(I32, PTR, I32))
        MH_getqueuehandle = h("mcdr_dx12GetQueueHandle", fd(I64))
        MH_getdevicehandle = h("mcdr_dx12GetDeviceHandle", fd(I64))
        MH_createhiddenwindow = h("mcdr_dx12CreateHiddenWindow", fd(I64, I32, I32))
        MH_destroyhiddenwindow = h("mcdr_dx12DestroyHiddenWindow", fdv(I64))
    }

    private fun bindCommand() {
        MH_gettimestampfrequency = h("mcdr_dx12GetTimestampFrequency", fd(I64))
        MH_createcommandencoder = h("mcdr_dx12CreateCommandEncoder", fd(PTR))
        MH_destroycommandencoder = h("mcdr_dx12DestroyCommandEncoder", fdv(PTR))
        MH_begincommandlist = h("mcdr_dx12BeginCommandList", fdv(PTR))
        MH_begincommandlistwithwait = h("mcdr_dx12BeginCommandListWithWait", fdv(PTR, I64))
        MH_endcommandlist = h("mcdr_dx12EndCommandList", fdv(PTR))
        MH_submit = h("mcdr_dx12Submit", fd(I64, PTR))
        MH_waitforfence = h("mcdr_dx12WaitForFence", fd(I32, PTR, I64, I64))
        MH_getfencevalue = h("mcdr_dx12GetFenceValue", fd(I64, PTR))
        MH_gettimestampnow = h("mcdr_dx12GetTimestampNow", fd(I64, PTR))
        MH_islistopen = h("mcdr_dx12IsListOpen", fd(I32, PTR))
        MH_copybuffer = h("mcdr_dx12CopyBuffer", fdv(PTR, PTR, I64, PTR, I64, I64))
        MH_writetotexture = h("mcdr_dx12WriteToTexture", fdv(PTR, PTR, I64, I32, I32, PTR, I32, I32, I32, I32))
        MH_copybuffertotexture = h("mcdr_dx12CopyBufferToTexture",
            fdv(PTR, PTR, I64, I32, I32, PTR, I32, I32, I32, I32, I32, I32))
        MH_copytexturetobuffer = h("mcdr_dx12CopyTextureToBuffer",
            fdv(PTR, PTR, I32, I32, I32, I32, I32, I32, PTR, I64))
        MH_copytexturetotexture = h("mcdr_dx12CopyTextureToTexture",
            fdv(PTR, PTR, PTR, I32, I32, I32, I32, I32, I32, I32, I32))
        MH_clearcolortexture = h("mcdr_dx12ClearColorTexture", fdv(PTR, PTR, F32, F32, F32, F32))
        MH_cleardepthtexture = h("mcdr_dx12ClearDepthTexture", fdv(PTR, PTR, F64))
        MH_clearcolortextureregion = h("mcdr_dx12ClearColorTextureRegion",
            fdv(PTR, PTR, F32, F32, F32, F32, I32, I32, I32, I32))
        MH_cleardepthtextureregion = h("mcdr_dx12ClearDepthTextureRegion",
            fdv(PTR, PTR, F64, I32, I32, I32, I32))
        MH_beginrenderpass = h("mcdr_dx12BeginRenderPass",
            fdv(PTR, PTR, I32, PTR, PTR, PTR, PTR, I32, I32, F64, I32, I32, I32, I32))
        MH_endrenderpass = h("mcdr_dx12EndRenderPass", fdv(PTR))
        MH_getactivecolortexture = h("mcdr_dx12GetActiveColorTexture", fd(PTR, PTR))
        MH_createquerypool = h("mcdr_dx12CreateQueryPool", fd(PTR, I32))
        MH_destroyquerypool = h("mcdr_dx12DestroyQueryPool", fdv(PTR))
        MH_writetimestamp = h("mcdr_dx12WriteTimestamp", fdv(PTR, PTR, I32))
        MH_readqueryvalue = h("mcdr_dx12ReadQueryValue", fd(I64, PTR, I32))
        MH_readqueryvalues = h("mcdr_dx12ReadQueryValues", fd(I32, PTR, I32, I32, PTR))
        MH_initasyncrenderer = h("mcdr_dx12InitAsyncRenderer", fdv(I32))
        MH_destroyasyncrenderer = h("mcdr_dx12DestroyAsyncRenderer", fdv())
        MH_asyncrenderbeginframe = h("mcdr_dx12AsyncRenderBeginFrame", fd(I32, PTR))
        MH_asyncrenderwaitcomplete = h("mcdr_dx12AsyncRenderWaitComplete", fd(I32, PTR, I64))
        MH_asyncrenderisrecordingready = h("mcdr_dx12AsyncRenderIsRecordingReady", fd(I32, PTR))
        MH_asyncrenderwaitrecordingready = h("mcdr_dx12AsyncRenderWaitRecordingReady", fd(I32, PTR, I64))
        MH_asyncsendcommandsready = h("mcdr_dx12AsyncSendCommandsReady", fd(I32, PTR))
        MH_clearasyncrenderctx = h("mcdr_dx12ClearAsyncRenderCtx", fdv())
    }

    private fun bindPipelineSurfaceDraw() {
        MH_creategraphicspipeline = h("mcdr_dx12CreateGraphicsPipeline", fd(PTR, PTR))
        MH_destroypipeline = h("mcdr_dx12DestroyPipeline", fdv(PTR))

        MH_createsurface = h("mcdr_dx12CreateSurface", fd(PTR, I64))
        MH_surfacepresentmodes = h("mcdr_dx12SurfacePresentModes", fd(I32, PTR, I32))
        MH_configuresurface = h("mcdr_dx12ConfigureSurface", fd(I32, PTR, I32, I32, I32))
        MH_acquiresurface = h("mcdr_dx12AcquireSurface", fd(I32, PTR))
        MH_blitsurface = h("mcdr_dx12BlitSurface", fdv(PTR, PTR, PTR))
        MH_presentsurface = h("mcdr_dx12PresentSurface", fdv(PTR))
        MH_getbackbufferhandle = h("mcdr_dx12GetBackBufferHandle", fd(I64, PTR))
        MH_issurfacesuboptimal = h("mcdr_dx12IsSurfaceSuboptimal", fd(I32, PTR))
        MH_readbacksurfacepixels = h("mcdr_dx12ReadbackSurfacePixels", fd(I32, PTR, PTR))
        MH_readbacktexturepixels = h("mcdr_dx12ReadbackTexturePixels", fd(I32, PTR, PTR))
        MH_dumptexturetofile = h("mcdr_dx12DumpTextureToFile", fdv(PTR, PTR))
        MH_destroysurface = h("mcdr_dx12DestroySurface", fdv(PTR))
        MH_getactivesurfacehandle = h("mcdr_dx12GetActiveSurfaceHandle", fd(PTR))

        MH_setpipeline = h("mcdr_dx12SetPipeline", fd(I32, PTR, PTR, I32))
        MH_setscissor = h("mcdr_dx12SetScissor", fd(I32, PTR, I32, I32, I32, I32))
        // 26.2 新增：SetViewport / ClearStencilTexture，紧随 MH_setscissor 保持原有分组。
        MH_setviewport = h("mcdr_dx12SetViewport", fd(I32, PTR, I32, I32, I32, I32))
        MH_clearstenciltexture = h("mcdr_dx12ClearStencilTexture", fd(I32, PTR, PTR, I32))
        MH_setvertexbuffer = h("mcdr_dx12SetVertexBuffer", fd(I32, PTR, I32, PTR, I64, I32))
        MH_setindexbuffer = h("mcdr_dx12SetIndexBuffer", fd(I32, PTR, PTR, I32))
        MH_pushdescriptors = h("mcdr_dx12PushDescriptors", fd(I32, PTR, PTR, I32))
        MH_drawindexed = h("mcdr_dx12DrawIndexed", fd(I32, PTR, I32, I32, I32, I32, I32))
        MH_draw = h("mcdr_dx12Draw", fd(I32, PTR, I32, I32, I32, I32))
        MH_drawindexedindirect = h("mcdr_dx12DrawIndexedIndirect", fd(I32, PTR, PTR, I64, I32))
        MH_drawindirect = h("mcdr_dx12DrawIndirect", fd(I32, PTR, PTR, I64, I32))
    }

    /**
     * Builds the single upcall stub this library needs: the fence-callback trampoline.
     *
     * <p>Native invokes it from a worker `std::thread` the JVM knows nothing about, so
     * the stub lives in the shared [ARENA] and is referenced from [FENCE_STUB]
     * for the process lifetime — a collected stub is a dangling function pointer. An
     * exception escaping an upcall target terminates the JVM abruptly, which is why
     * `onFenceComplete` catches [Throwable] itself rather than trusting a
     * `catchException` wrapper.
     */
    private fun bindFenceStub() {
        try {
            STUB_TARGET = MethodHandles.lookup().findStatic(McdrAbi::class.java, "onFenceComplete",
                MethodType.methodType(Void.TYPE, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("cannot resolve the fence upcall target", e)
        }
        FENCE_STUB = LINKER.upcallStub(STUB_TARGET!!, fdv(I64, I64, I32), ARENA)
    }

    /**
     * Binds the 41 entry points of the P33 async path: descriptor partitioning, the
     * per-worker bundle recorder pool, bundle replay, the main-list executor and the fence
     * manager. The eight async *render thread* controls are bound in
     * [bindCommand] because they sit with the rest of the frame lifecycle.
     */
    private fun bindAsync() {
        // Frame slot/value and the per-frame partitioned descriptor allocator.
        MH_asynccurrentframeslot = h("mcdr_dx12AsyncCurrentFrameSlot", fd(I32, PTR))
        MH_asynccurrentframevalue = h("mcdr_dx12AsyncCurrentFrameValue", fd(I64, PTR))
        MH_asyncdescriptorcreate = h("mcdr_dx12AsyncDescriptorCreate", fd(PTR, I32))
        MH_asyncdescriptordestroy = h("mcdr_dx12AsyncDescriptorDestroy", fdv(PTR))
        MH_asyncregionbase = h("mcdr_dx12AsyncDescriptorRegionBase", fd(I32, PTR, I32, I32))
        MH_asyncallocate = h("mcdr_dx12AsyncDescriptorAllocate", fd(I32, PTR, I32, I32, I32))
        MH_asyncresetframe = h("mcdr_dx12AsyncDescriptorResetFrame", fdv(PTR, I32))
        MH_asyncgpuhandle = h("mcdr_dx12AsyncDescriptorGpuHandle", fd(I64, PTR, I32))
        MH_asyncwritecbv = h("mcdr_dx12AsyncDescriptorWriteCBV", fd(I32, PTR, I32, PTR, I64, I64))
        MH_asyncwritesrv = h("mcdr_dx12AsyncDescriptorWriteSRV", fd(I32, PTR, I32, PTR))

        // Per-worker bundle recorders.
        MH_asyncbundlepoolcreate = h("mcdr_dx12AsyncBundlePoolCreate", fd(PTR, I32))
        MH_asyncbundlepooldestroy = h("mcdr_dx12AsyncBundlePoolDestroy", fdv(PTR))
        MH_asyncbundlebegin = h("mcdr_dx12AsyncBundleBegin", fd(I32, PTR, I32, I32, I64))
        MH_asyncbundleend = h("mcdr_dx12AsyncBundleEnd", fd(I64, PTR, I32))
        MH_asyncbundlesetpipelinestate = h("mcdr_dx12AsyncBundleSetPipelineState", fd(I32, PTR, I32, PTR, I32))
        MH_asyncbundlesetdescriptortable = h("mcdr_dx12AsyncBundleSetDescriptorTable", fd(I32, PTR, I32, I32, I64))
        MH_asyncbundlesetvertexbuffer = h("mcdr_dx12AsyncBundleSetVertexBuffer", fd(I32, PTR, I32, I32, PTR, I64, I32))
        MH_asyncbundlesetindexbuffer = h("mcdr_dx12AsyncBundleSetIndexBuffer", fd(I32, PTR, I32, PTR, I32))
        MH_asyncbundledrawindexed = h("mcdr_dx12AsyncBundleDrawIndexed", fd(I32, PTR, I32, I32, I32, I32, I32, I32))
        MH_asyncbundledraw = h("mcdr_dx12AsyncBundleDraw", fd(I32, PTR, I32, I32, I32, I32, I32))
        MH_asyncbundledrawindexedindirect = h("mcdr_dx12AsyncBundleDrawIndexedIndirect", fd(I32, PTR, I32, PTR, I64, I32))
        // The hot path: one call replaces what used to be 20 parameters and 14 parallel
        // arrays. bindings/draws are struct arrays, so a worker can be handed a zero-copy
        // asSlice of a single allocation instead of 14 copied Java arrays.
        MH_asyncbundlerecordpartition = h("mcdr_dx12AsyncBundleRecordPartition",
            fd(I32, PTR, PTR, I32, I32, I32, I32, PTR, PTR, I32))

        // Bundle replay plus the barrier pre-pass that must run on the main list first.
        MH_executebundle = h("mcdr_dx12ExecuteBundle", fd(I32, PTR, I64))
        MH_asyncpreparecbvbuffers = h("mcdr_dx12AsyncPrepareCBVBuffers", fd(I32, PTR, PTR, I32))
        MH_asyncprepareindexbuffers = h("mcdr_dx12AsyncPrepareIndexBuffers", fd(I32, PTR, PTR, I32))
        MH_asyncpreparetextureviews = h("mcdr_dx12AsyncPrepareTextureViews", fd(I32, PTR, PTR, I32))

        // Main command list executor.
        MH_asyncexecutorcreate = h("mcdr_dx12AsyncExecutorCreate", fd(PTR))
        MH_asyncexecutordestroy = h("mcdr_dx12AsyncExecutorDestroy", fdv(PTR))
        MH_asyncexecutortrybeginframe = h("mcdr_dx12AsyncExecutorTryBeginFrame", fd(I32, PTR))
        MH_asyncexecutoraddbundle = h("mcdr_dx12AsyncExecutorAddBundle", fd(I32, PTR, I64))
        MH_asyncexecutoraddtransition = h("mcdr_dx12AsyncExecutorAddTransition", fd(I32, PTR, PTR, I32, I32))
        MH_asyncexecutorendframe = h("mcdr_dx12AsyncExecutorEndFrame", fd(I64, PTR))
        MH_asyncexecutorlastfencevalue = h("mcdr_dx12AsyncExecutorLastFenceValue", fd(I64, PTR))
        MH_asyncexecutorisframeinprogress = h("mcdr_dx12AsyncExecutorIsFrameInProgress", fd(I32, PTR))
        MH_asyncexecutorwaitframe = h("mcdr_dx12AsyncExecutorWaitFrame", fd(I32, PTR, I64, I64))

        // Fence manager. FENCE_STUB is the only upcall in the library; timeoutMs == 0 means
        // infinite, which is why the descriptor carries the timeout rather than the callee
        // defaulting it.
        MH_asyncfencecreate = h("mcdr_dx12AsyncFenceCreate", fd(PTR))
        MH_asyncfencedestroy = h("mcdr_dx12AsyncFenceDestroy", fdv(PTR))
        MH_asyncfencesignal = h("mcdr_dx12AsyncFenceSignal", fd(I32, PTR, I64))
        MH_asyncfencewait = h("mcdr_dx12AsyncFenceWait", fd(I32, PTR, I64, I64))
        MH_asyncfenceiscomplete = h("mcdr_dx12AsyncFenceIsComplete", fd(I32, PTR, I64))
        MH_asyncfenceregistercallback = h("mcdr_dx12AsyncFenceRegisterCallback", fd(I32, PTR, I64, PTR, I64, I64))
    }
}
