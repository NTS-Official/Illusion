package dev.nts.expdxr.dxrmc.sr

import com.mojang.renderpearl.api.GpuFormat
import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.backend.D3D12CommandEncoderBackend
import dev.nts.expdxr.d3d12.extern.MCDR_ABI
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTexture
import dev.nts.expdxr.d3d12.render.core.D3D12GpuTextureView
import java.util.Locale

/**
 * 把 26.3 的 OIT 透射率重建结果写成一张 R8 掩码，供 FSR 的 `reactive` / XeSS 的 `responsive` 用。
 * **不能直接读附件**：里面装的是吸收率的**小波系数**（8 个系数 / 2 张 RGBA16F / rank 2），必须按
 * `oit_sample.glsl:sampleAbsorbance` 还原成整列吸收率再取 `1 - exp(-吸收率)`。见 docs/superresolution/README.md §6。
 */
object D3D12SrMask {

    private const val MAX_MASK = 900

    /** 以下三个必须与 `LevelRenderer.OIT_*` 一致：系数 = 2^3、附件 = 系数 / 4、rank 2。 */
    private const val COEFF_COUNT = 8

    private const val ATTACHMENTS = 2

    private const val WAVELET_RANK = 2

    private const val HLSL = """
Texture2D<float4> t0 : register(t0);
Texture2D<float4> t1 : register(t1);
RWTexture2D<float> u0 : register(u0);

cbuffer PassConstants : register(b0) {
    int width;
    int height;
    int attachmentCount;
    int scale;
    int coeffCount;
    int rank;
    int reserved0;
    int reserved1;
};

[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    int2 p = (int2)tid.xy;
    if (p.x >= width || p.y >= height) {
        return;
    }

    float c[8];
    float4 a = t0.Load(int3(p, 0));
    c[0] = a.x; c[1] = a.y; c[2] = a.z; c[3] = a.w;
    if (attachmentCount > 1) {
        float4 b = t1.Load(int3(p, 0));
        c[4] = b.x; c[5] = b.y; c[6] = b.z; c[7] = b.w;
    } else {
        c[4] = 0.0; c[5] = 0.0; c[6] = 0.0; c[7] = 0.0;
    }

    float averageAbsorbance = c[0];
    if (averageAbsorbance == 0.0) {
        u0[p] = 0.0;
        return;
    }

    // 整列：originalDepth = 1、currentAbsorbance = 0（不扣"本像素之前"的吸收）。
    float depthMeasuredInBins = (float)(coeffCount - 1);
    float depth = depthMeasuredInBins / (float)coeffCount;

    int treeIndexB = clamp((int)floor(depthMeasuredInBins), 0, coeffCount - 1);
    bool shouldSampleA = treeIndexB >= 1;
    int treeIndexA = shouldSampleA ? (treeIndexB - 1) : treeIndexB;
    treeIndexB += coeffCount;
    treeIndexA += coeffCount;

    float sampleB = averageAbsorbance;
    float sampleA = shouldSampleA ? averageAbsorbance : 0.0;

    for (int level = 0; level <= rank; level++) {
        int power = rank - level;
        float waveletWidth = exp2(-(float)power);
        float waveletHeightScale = exp2((float)power * 0.5);

        int parentIndexB = treeIndexB >> 1;
        int isRightHalfB = treeIndexB & 1;
        float waveletSignB = 1.0 - 2.0 * (float)isRightHalfB;
        float indexAtParentLevel = (float)(parentIndexB & ((1 << power) - 1));
        float depthOffset = depth - waveletWidth * indexAtParentLevel;

        float differenceCoefficientB = c[parentIndexB];
        sampleB -= waveletHeightScale * differenceCoefficientB * waveletSignB;
        treeIndexB = parentIndexB;

        if (shouldSampleA) {
            int parentIndexA = treeIndexA >> 1;
            float differenceCoefficientA =
                (parentIndexA == parentIndexB) ? differenceCoefficientB : c[parentIndexA];
            int isRightHalfA = treeIndexA & 1;
            float waveletSignA = 1.0 - 2.0 * (float)isRightHalfA;
            sampleA -= waveletHeightScale * differenceCoefficientA * waveletSignA;
            treeIndexA = parentIndexA;
        }
    }

    // lerpAlpha 恒为 0（depthMeasuredInBins < coeffCount），所以整列吸收率就是 sampleA。
    float absorbance = max(sampleA, 0.0);
    float transmittance = clamp(exp(-absorbance), 0.0001, 1.0);
    u0[p] = saturate(1.0 - transmittance) * ((float)scale / 1000.0);
}
"""

    private var pipeline = 0L
    private var texture: D3D12GpuTexture? = null
    private var sourceViews = LongArray(ATTACHMENTS)
    private var sourceTextures = arrayOfNulls<D3D12GpuTexture>(ATTACHMENTS)
    private var attachments = 0
    private var width = 0
    private var height = 0
    private var failure: String? = null
    private var announced = false
    private var verified = false

    /** 每帧由 main pass 的 RETURN 挂点填一次（那一刻 OIT 已合成完）；`views` 按顺序取前 [ATTACHMENTS] 张。 */
    @JvmStatic
    fun captureTransmittance(views: Array<D3D12GpuTextureView>) {
        var count = 0
        for (view in views) {
            if (count >= ATTACHMENTS) {
                break
            }
            val tex = view.texture() as? D3D12GpuTexture
            if (tex == null || tex.isClosed()) {
                continue
            }
            sourceTextures[count] = tex
            count++
        }
        if (count > 0) {
            attachments = count
        }
    }

    @JvmStatic
    fun hasSource(): Boolean = attachments > 0

    /**
     * 生成这一帧的掩码，返回纹理句柄（0 = 没做成，调用方按「没有掩码」处理）。
     * 用调用方**已经打开**的命令列表：掩码必须在 SR 的 evaluate 之前、同一条列表里完成。
     */
    fun produce(encoder: D3D12CommandEncoderBackend, maskWidth: Int, maskHeight: Int): Long {
        if (failure != null) {
            return 0L
        }
        if (maskWidth <= 0 || maskHeight <= 0) {
            return 0L
        }
        if (!ensureResources(maskWidth, maskHeight)) {
            return 0L
        }
        val tex = texture ?: return 0L
        val dispatched = MCDR_ABI.D3D12DispatchComputeTexture3(
            encoder.nativeHandle(), pipeline, sourceViews[0], sourceViews[0], sourceViews[0],
            tex.handle(),
            intArrayOf(maskWidth, maskHeight, attachments, MAX_MASK, COEFF_COUNT, WAVELET_RANK, 0, 0),
            (maskWidth + 7) / 8, (maskHeight + 7) / 8, 1)
        if (dispatched != 1) {
            fail("dx12DispatchComputeTexture3 returned $dispatched: " + MCDR_ABI.D3D12LastError())
            return 0L
        }
        if (!verified) {
            verified = true
            reportMaskStatistics(tex)
        }
        return tex.handle()
    }

    fun note(): String? = failure

    private fun ensureResources(maskWidth: Int, maskHeight: Int): Boolean {
        if (pipeline == 0L) {
            val dxil = MCDR_ABI.D3D12CompileShaderDxc(HLSL, "main", "cs_6_0")
            if (dxil == null) {
                fail("the mask shader did not compile: " + MCDR_ABI.D3D12LastError())
                return false
            }
            pipeline = MCDR_ABI.D3D12CreateComputePipelineTexture3(dxil)
            if (pipeline == 0L) {
                fail("the mask pipeline was not created: " + MCDR_ABI.D3D12LastError())
                return false
            }
        }
        if (sourceViews[0] == 0L) {
            var count = 0
            for (i in 0 until ATTACHMENTS) {
                val tex = sourceTextures[i]
                if (tex == null || tex.isClosed()) {
                    continue
                }
                val view = MCDR_ABI.ID3D12CreateTextureView(tex.handle(), 0, 1)
                if (view == 0L) {
                    fail("the transmittance view $i was not created: " + MCDR_ABI.D3D12LastError())
                    return false
                }
                sourceViews[i] = view
                count++
            }
            if (count == 0) {
                return false
            }
            attachments = count
            // 只用得到 count 张，其余槽填第一张（shader 用 attachmentCount 判断，不会读它）。
            for (i in count until ATTACHMENTS) {
                sourceViews[i] = sourceViews[0]
            }
            if (!announced) {
                announced = true
                Illusion.LOGGER.info(
                    "SR mask wavelet reconstruction: {} coefficient attachment(s), {} coefficients, rank {}",
                    attachments, COEFF_COUNT, WAVELET_RANK)
            }
        }
        if (texture == null || width != maskWidth || height != maskHeight) {
            texture?.close()
            texture = D3D12GpuTexture(
                // 32 = USAGE_STORAGE（native 只认这个位加 ALLOW_UNORDERED_ACCESS）；1/2 = COPY_DST/COPY_SRC，读回用。
                D3D12GpuTexture.USAGE_STORAGE or 1 or 2,
                "DXRMC SR mask", GpuFormat.R8_UNORM, maskWidth, maskHeight, 1, 1)
            width = maskWidth
            height = maskHeight
            verified = false
            Illusion.LOGGER.info("SR mask {}x{} mask texture", maskWidth, maskHeight)
        }
        return true
    }

    /** 首次派发之后读回掩码本身，证明"真的写出了非零值"（否则再好的通路也只是浪费一次 dispatch）。 */
    private fun reportMaskStatistics(tex: D3D12GpuTexture) {
        val pixels = MCDR_ABI.D3D12ReadbackTexturePixels(tex.handle()) ?: return
        if (pixels.isEmpty()) {
            return
        }
        var sum = 0.0
        var nonZero = 0
        var peak = 0
        for (packed in pixels) {
            val value = packed and 0xFF
            sum += value
            if (value > peak) {
                peak = value
            }
            if (value > 2) {
                nonZero++
            }
        }
        val mean = sum / pixels.size
        val percent = 100.0 * nonZero / pixels.size
        Illusion.LOGGER.info(
            "SR mask first frame: mean={} peak={} non-zero={}% of {} pixels ({} attachments, scale {})",
            fmt(mean), peak, fmt(percent), pixels.size, attachments, MAX_MASK)
    }

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun fail(reason: String) {
        failure = reason
        Illusion.LOGGER.warn("SR mask disabled: {}", reason)
    }

    fun release() {
        texture?.close()
        texture = null
        width = 0
        height = 0
        verified = false
    }
}
