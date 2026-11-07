package io.github.dxegp.d3d12.render.shader

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.dxegp.misc.IllusionLogger
import io.github.dxegp.misc.settings.D3D12Settings
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path

/**
 * Java FFM binding for the standalone GLSL -> HLSL converter (`illusion_shader.dll`).
 *
 * The C ABI is described by shader-convert-abi.md in the Illusion-shaders-extension project. The DLL
 * is loaded once, lazily, from `[debug] shaderConverterDll`; a missing or broken DLL only keeps the
 * old SPIRV-Cross chain — every call site must treat a null/failed response as "use the old chain".
 *
 * Responses are cached by the serialized request: conversion is deterministic and one session asks
 * for the same shader/stage hundreds of times with identical defines and options.
 */
object IllusionShaderBridge {
    private val GSON = Gson()
    private val ARENA: Arena = Arena.ofShared()
    private val LINKER: Linker = Linker.nativeLinker()

    private var convertHandle: MethodHandle? = null
    private var freeHandle: MethodHandle? = null
    private var abiVersion = 0
    private var loadedPath: Path? = null
    private var failure: String? = null

    /**
     * Conversions are deterministic for identical requests (pinned by tests/deterministic.rs), so the
     * serialized request is a sound cache key: one game session converts 454 stages for 63 distinct
     * shader/stage pairs. Access-ordered map = cheap LRU; the cap keeps a pack with thousands of
     * variants from pinning every HLSL document in memory.
     */
    private const val CACHE_LIMIT = 2048
    private val cache = object : LinkedHashMap<String, Response>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Response>?): Boolean =
            size > CACHE_LIMIT
    }
    private var cacheHits = 0L
    private var cacheMisses = 0L

    /** Result of one conversion; [manifestJson] is re-serialized so callers can parse it directly. */
    data class Response(val ok: Boolean, val hlsl: String?, val manifestJson: String?,
                        val warnings: List<String>, val errorKind: String?, val errorMessage: String?)

    /** `illusion_shader.dll` under the game directory, per config. */
    fun configuredPath(): Path = Path.of(System.getProperty("user.dir"), D3D12Settings.shaderConverterDll)

    val available: Boolean get() = convertHandle != null

    /**
     * Load the DLL once; returns false (and logs one line) when it is missing or fails to bind.
     * A failure is sticky: we do not retry on every shader compile.
     */
    @Synchronized
    fun load(path: Path): Boolean {
        if (convertHandle != null) return true
        failure?.let { return false }
        val absolute = path.toAbsolutePath()
        if (!Files.isRegularFile(absolute)) {
            failure = "missing"
            IllusionLogger.warnFormat("shader converter: %s not found, shadow conversion stays off",
                absolute)
            return false
        }
        return try {
            val lookup = SymbolLookup.libraryLookup(absolute, ARENA)
            val convertSymbol = lookup.find("illusion_shader_convert").orElseThrow {
                IllegalStateException("illusion_shader_convert is not exported")
            }
            val freeSymbol = lookup.find("illusion_shader_free").orElseThrow {
                IllegalStateException("illusion_shader_free is not exported")
            }
            val versionSymbol = lookup.find("illusion_shader_abi_version").orElseThrow {
                IllegalStateException("illusion_shader_abi_version is not exported")
            }
            val versionHandle = LINKER.downcallHandle(versionSymbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT))
            convertHandle = LINKER.downcallHandle(convertSymbol, FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
            freeHandle = LINKER.downcallHandle(freeSymbol,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
            abiVersion = versionHandle.invokeExact() as Int
            loadedPath = absolute
            IllusionLogger.infoMessage("shader converter: loaded ${absolute.fileName} (abi $abiVersion)")
            true
        } catch (e: Throwable) {
            failure = e.message ?: e.javaClass.simpleName
            IllusionLogger.warnFormat("shader converter: loading %s failed: %s", absolute, failure)
            false
        }
    }

    /** Run one conversion; null when the DLL is not loaded. */
    fun convert(request: JsonObject): Response? {
        val convert = convertHandle ?: return null
        val free = freeHandle ?: return null
        val json = GSON.toJson(request)
        synchronized(cache) {
            cache[json]?.let {
                cacheHits++
                return it
            }
            cacheMisses++
        }
        val arena = Arena.ofConfined()
        try {
            val requestSegment = arena.allocateFrom(json)
            val out = arena.allocate(ValueLayout.ADDRESS)
            val code = convert.invokeExact(requestSegment, out) as Int
            val pointer = out.get(ValueLayout.ADDRESS, 0)
            if (pointer.address() == 0L) {
                return Response(false, null, null, emptyList(), "abi",
                    "illusion_shader_convert returned no response (code $code)")
            }
            val text = try {
                pointer.reinterpret(Long.MAX_VALUE).getString(0)
            } finally {
                free.invokeExact(pointer)
            }
            val response = parse(text)
            synchronized(cache) { cache[json] = response }
            return response
        } finally {
            arena.close()
        }
    }

    /** One line for the close summary: how much work the cache removed in this session. */
    fun cacheSummary(): String = synchronized(cache) {
        "cache ${cacheHits} hit(s), ${cacheMisses} conversion(s), ${cache.size} entrie(s)"
    }

    private fun parse(text: String): Response {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return Response(false, null, null, emptyList(), "response",
                "the converter response is not a JSON object")
        if (root.get("ok")?.asBoolean == true) {
            val hlsl = root.get("hlsl")?.asString
            val manifest = root.get("manifest")?.let { GSON.toJson(it) }
            val warnings = root.getAsJsonArray("warnings")?.map { it.asString } ?: emptyList()
            return Response(true, hlsl, manifest, warnings, null, null)
        }
        return Response(false, null, null, emptyList(),
            root.get("kind")?.asString ?: "error",
            root.get("message")?.asString ?: "the converter reported an error without a message")
    }
}
