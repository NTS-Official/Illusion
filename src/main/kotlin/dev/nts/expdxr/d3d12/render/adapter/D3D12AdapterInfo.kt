package dev.nts.expdxr.d3d12.render.adapter

import com.mojang.blaze3d.systems.DeviceType
import java.util.*

/**
 * Real adapter facts reported by the native backend through
 * [dev.nts.expdxr.d3d12.extern.MCDR_ABI.dx12GetAdapterInfo] (`mcdr_dx12GetAdapterInfo` in `mcdr_abi.h`).
 *
 * Before this class existed [D3D12Device] hardcoded `"D3D12"` / `"D3D12 driver"` /
 * [DeviceType.DISCRETE], so the F3 system block showed the same three strings on
 * every machine. The native side now reports what DXGI - and, when it is
 * available, DXCore - actually says.
 *
 * [current] is the instance belonging to the live D3D12 device. It stays `null`
 * while the game renders through GL/Vulkan, which is exactly the signal the F3
 * mixin uses to decide whether to append the DXRMC lines at all.
 */
class D3D12AdapterInfo private constructor(
    /** DXGI `DXGI_ADAPTER_DESC1.Description`, UTF-8. */
    val name: String,
    /** Vendor display name, mapped natively from the PCI vendor id. */
    val vendor: String,
    val vendorId: Int,
    val deviceId: Int,
    /** `"12_2"` / `"12_1"` / `"12_0"` / `"11_1"` / `"11_0"` / `"unknown"`. */
    val featureLevel: String,
    val featureLevelValue: Int,
    /** DXCore driver version, `"a.b.c.d"`; empty when DXCore is unavailable. */
    val driverVersion: String,
    val driverDescription: String,
    /** `"integrated"` / `"discrete"` / `"virtual"` / `"cpu"` / `"other"`. */
    val deviceType: String,
    val vramBytes: Long,
    val sharedBytes: Long,
    val dedicatedSystemBytes: Long,
    /** `"%08X%08X"` of the adapter LUID (LowPart first), as the native side writes it. */
    val luid: String,
    val isHardware: Boolean,
    val isDetachable: Boolean,
    /** Whether DXCore contributed [driverVersion] and [deviceType]. */
    val dxcore: Boolean
) {
    /**
     * Vanilla-style driver string, i.e. what [com.mojang.blaze3d.systems.DeviceInfo.driverInfo]
     * carries: `"<feature level> <driver version>"` (mirrors the Vulkan backend's
     * `"%s %s %s"` composition of version / driver name / driver info).
     */
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

    /** Backend name shown in the F3 backend/driver line. */
    fun backendName(): String = "Direct3D 12"

    /** Vendor name, with a placeholder when the native mapping had nothing to say. */
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

    /** One-line summary for the startup log. */
    fun summary(): String = name + " [" + vendorName() + " " + deviceType + "]" +
        " backend=" + backendName() + " driver=" + driverInfo() +
        " vram=" + vramMiB() + "MiB shared=" + sharedMiB() + "MiB" +
        " luid=" + luid + " dxcore=" + dxcore

    companion object {
        /**
         * The adapter belonging to the live D3D12 device, or `null` when the game
         * is rendering through GL/Vulkan. Read by the F3 mixin.
         */
        @JvmField
        @Volatile
        var current: D3D12AdapterInfo? = null

        /** Never-null placeholder used when the native call returned nothing usable. */
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
                fields["dxcore"] == "true"
            )
        }

        /**
         * Flat `{"key": value, ...}` reader. The native side never emits nested
         * objects or arrays, so a single scan is enough; handling it here instead
         * of pulling in Gson keeps this usable from the mod's standalone
         * `ktc.ps1` compile check, whose ad-hoc classpath has no Gson.
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
            val out = HashMap<String, String>()
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