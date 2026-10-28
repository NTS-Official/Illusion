package dev.nts.expdxr.d3d12.render.adapter

import com.mojang.renderpearl.api.device.DeviceType
import java.util.*

/**
 * native 上报的真实适配器信息（`mcdr_dx12GetAdapterInfo`）。
 *
 * [current] 属于当前活的 D3D12 设备；GL/Vulkan 渲染时为 null —— F3 mixin 正是靠它决定是否追加 DXRMC 行。
 */
class D3D12AdapterInfo private constructor(
    // 字符串域取值：featureLevel ∈ "12_2"|"12_1"|"12_0"|"11_1"|"11_0"|"unknown"，
    // deviceType ∈ "integrated"|"discrete"|"virtual"|"cpu"|"other"；driverVersion 空 = 无 DXCore。
    val name: String,
    val vendor: String,
    val vendorId: Int,
    val deviceId: Int,
    val featureLevel: String,
    val featureLevelValue: Int,
    val driverVersion: String,
    val driverDescription: String,
    val deviceType: String,
    val vramBytes: Long,
    val sharedBytes: Long,
    val dedicatedSystemBytes: Long,
    val luid: String,
    val isHardware: Boolean,
    val isDetachable: Boolean,
    val d3dcore: Boolean,

    // VRS：tier 0 = 不支持；tileSize 0 = 没有 tier 2（SRI）；additional = 支持 2X4/4X2/4X4。
    val shadingRateTier: Int = 0,

    val shadingRateTileSize: Int = 0,

    val additionalShadingRates: Boolean = false
) {
    /** vanilla 风格驱动串：`"<feature level> <driver version>"`。 */
    fun driverInfo(): String {
        val parts = arrayListOf<String>()
        if (featureLevel.isNotEmpty() && featureLevel != "unknown") {
            parts.add(featureLevel)
        }
        if (driverVersion.isNotEmpty()) {
            parts.add(driverVersion)
        }
        if (parts.isEmpty()) {
            parts.add("unknown driver")
        }
        return parts.joinToString(" ")
    }

    /** F3 的 backend/driver 行显示的 backend 名。 */
    fun backendName(): String = "Direct3D 12"

    /** 厂商名；native 映射不出来时给占位符。 */
    fun vendorName(): String = if (vendor.isNotEmpty()) vendor else "unknown"

    fun deviceTypeEnum(): DeviceType = when (deviceType.lowercase(Locale.ROOT)) {
        "integrated" -> DeviceType.INTEGRATED
        "discrete" -> DeviceType.DISCRETE
        "virtual" -> DeviceType.VIRTUAL
        "cpu" -> DeviceType.CPU
        else -> DeviceType.OTHER
    }

    fun vramMiB(): Long = vramBytes shr 20

    fun sharedMiB(): Long = sharedBytes shr 20

    /** 启动日志用的一行摘要。 */
    fun summary(): String = "${name} [${vendorName()} ${deviceType}]" +
        " backend=${backendName()} driver=${driverInfo()}" +
        " vram=${vramMiB()}MiB shared=${sharedMiB()}MiB" +
        " luid=${luid} d3dcore=${d3dcore}"

    companion object {
        /** 当前活的 D3D12 设备的适配器；GL/Vulkan 渲染时为 null（F3 mixin 读它）。 */
        @JvmField
        @Volatile
        var current: D3D12AdapterInfo? = null

        /** native 没给出可用内容时的占位（永不为 null）。 */
        @JvmField
        val UNKNOWN: D3D12AdapterInfo = D3D12AdapterInfo(
            "", "", 0, 0, "unknown", 0, "", "", "other", 0L, 0L, 0L, "",
            false, false, false
        )

        fun parse(json: String?): D3D12AdapterInfo {
            val fields = parseFlatObject(json)
            if (fields.isEmpty()) {
                return UNKNOWN
            }
            return D3D12AdapterInfo(
                fields["name"] ?: "",
                fields["vendor"] ?: "",
                fields["vendor_id"]?.toIntOrNull() ?: 0,
                fields["device_id"]?.toIntOrNull() ?: 0,
                fields["feature_level"] ?: "unknown",
                fields["feature_level_value"]?.toIntOrNull() ?: 0,
                fields["driver_version"] ?: "",
                fields["driver_description"] ?: "",
                fields["device_type"] ?: "other",
                fields["vram_bytes"]?.toLongOrNull() ?: 0L,
                fields["shared_bytes"]?.toLongOrNull() ?: 0L,
                fields["dedicated_system_bytes"]?.toLongOrNull() ?: 0L,
                fields["luid"] ?: "",
                fields["is_hardware"] == "true",
                fields["is_detachable"] == "true",
                fields["dxcore"] == "true",
                fields["shading_rate_tier"]?.toIntOrNull() ?: 0,
                fields["shading_rate_image_tile_size"]?.toIntOrNull() ?: 0,
                fields["additional_shading_rates"] == "true"
            )
        }

        /**
         * 解析 `mcdr_dx12EnumerateAdapters` 返回的 JSON **数组**：按 DXGI 优先序，列出所有能建
         * D3D12 12_0 设备的适配器。Illusion 在模组构造期（活设备尚不存在时）读它。
         */
        fun parseList(json: String?): List<D3D12AdapterInfo> {
            if (json == null) {
                return emptyList()
            }
            val open = json.indexOf('[')
            val close = json.lastIndexOf(']')
            if (open < 0 || close <= open) {
                return emptyList()
            }
            return splitTopLevel(json.substring(open + 1, close))
                .map { parse(it) }
                .filter { it.name.isNotEmpty() || it.featureLevelValue > 0 }
        }

        /**
         * 扁平 `{"key": value}` 读取：native 从不发嵌套结构，单趟扫描即可。自己写而不引 Gson，
         * 是因为 `ktc.ps1` 独立编译检查的 ad-hoc classpath 里没有 Gson。
         */
        private fun parseFlatObject(json: String?): Map<String, String> {
            if (json == null) {
                return emptyMap()
            }
            val open = json.indexOf('{')
            val close = json.lastIndexOf('}')
            if (open < 0 || close <= open) {
                return emptyMap()
            }
            val out = mutableMapOf<String, String>()
            for (field in splitTopLevel(json.substring(open + 1, close))) {
                val colon = field.indexOf(':')
                if (colon < 0) {
                    continue
                }
                val key = unquote(field.substring(0, colon).trim())
                if (key.isEmpty()) {
                    continue
                }
                out[key] = unquote(field.substring(colon + 1).trim())
            }
            return out
        }

        private fun splitTopLevel(body: String): List<String> {
            val parts = arrayListOf<String>()
            val sb = StringBuilder()
            var inString = false
            var escaped = false
            for (ch in body) {
                if (escaped) {
                    sb.append(ch)
                    escaped = false
                } else if (inString && ch == '\\') {
                    sb.append(ch)
                    escaped = true
                } else if (ch == '"') {
                    inString = !inString
                    sb.append(ch)
                } else if (ch == ',' && !inString) {
                    parts.add(sb.toString())
                    sb.setLength(0)
                } else {
                    sb.append(ch)
                }
            }
            if (sb.isNotEmpty()) {
                parts.add(sb.toString())
            }
            return parts
        }

        private fun unquote(token: String): String {
            if (token.length < 2 || token[0] != '"' || token[token.length - 1] != '"') {
                return token
            }
            return unescape(token.substring(1, token.length - 1))
        }

        private fun unescape(raw: String): String {
            if (raw.indexOf('\\') < 0) {
                return raw
            }
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                if (ch != '\\' || i + 1 >= raw.length) {
                    sb.append(ch)
                    i++
                    continue
                }
                when (val next = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        val code = if (i + 5 < raw.length) {
                            raw.substring(i + 2, i + 6).toIntOrNull(16)
                        } else {
                            null
                        }
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                        sb.append(next)
                    }
                    else -> sb.append(next)
                }
                i += 2
            }
            return sb.toString()
        }
    }
}

