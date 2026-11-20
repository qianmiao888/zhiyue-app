package com.zhiyue.app.audio

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 端侧声学特征规格。与离线脚本 tools/prepare_assets.py 完全一致：
 * 16kHz 单声道、400ms 窗、100ms 帧移、8192 点 FFT、32 维 log-mel、逐帧标准化。
 */
object FeatureSpec {
    const val SR = 16000
    const val N_FFT = 8192
    const val WIN = 6400
    const val HOP = 1600
    const val BANDS = 32
    const val BINS = N_FFT / 2 + 1

    val hann = FloatArray(WIN) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * i / (WIN - 1))).toFloat()
    }
}

/** mel 滤波器组由脚本导出，保证与离线特征逐位一致。 */
class MelBank(val bands: Int, val bins: Int, private val weights: FloatArray) {
    fun apply(power: FloatArray, out: FloatArray) {
        for (b in 0 until bands) {
            var sum = 0f
            val offset = b * bins
            for (j in 0 until bins) sum += power[j] * weights[offset + j]
            out[b] = sum
        }
    }

    companion object {
        fun load(context: Context): MelBank {
            val bytes = context.assets.open("models/mel32.f32").use { it.readBytes() }
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val w = FloatArray(fb.remaining())
            fb.get(w)
            return MelBank(FeatureSpec.BANDS, FeatureSpec.BINS, w)
        }
    }
}

/** 迭代式基 2 实数 FFT，只输出功率谱。 */
object RealFft {
    private val cosTable = FloatArray(FeatureSpec.N_FFT / 2) {
        cos(2.0 * Math.PI * it / FeatureSpec.N_FFT).toFloat()
    }
    private val sinTable = FloatArray(FeatureSpec.N_FFT / 2) {
        sin(2.0 * Math.PI * it / FeatureSpec.N_FFT).toFloat()
    }

    fun power(input: FloatArray, out: FloatArray) {
        val n = FeatureSpec.N_FFT
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until minOf(FeatureSpec.WIN, input.size)) re[i] = input[i]

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val tw = k * step
                    val wr = cosTable[tw]
                    val wi = -sinTable[tw]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += len
            }
            len = len shl 1
        }

        for (k in 0 until FeatureSpec.BINS) out[k] = re[k] * re[k] + im[k] * im[k]
    }
}

/** 流式提取：喂入 16k 16bit PCM，按帧回调 32 维特征。 */
class FeatureExtractor(
    private val mel: MelBank,
    private val onFrame: (FloatArray, Float) -> Unit,
) {
    constructor(mel: MelBank, onFrame: (FloatArray) -> Unit) : this(
        mel,
        { frame, _ -> onFrame(frame) },
    )

    private val buffer = FloatArray(FeatureSpec.WIN)
    private var filled = 0
    private val scratch = FloatArray(FeatureSpec.WIN)
    private val power = FloatArray(FeatureSpec.BINS)
    private val bands = FloatArray(FeatureSpec.BANDS)

    fun feed(pcm: ShortArray, length: Int) {
        var i = 0
        while (i < length) {
            val take = minOf(FeatureSpec.WIN - filled, length - i)
            for (k in 0 until take) buffer[filled + k] = pcm[i + k] / 32768f
            filled += take
            i += take
            if (filled == FeatureSpec.WIN) {
                emit()
                System.arraycopy(buffer, FeatureSpec.HOP, buffer, 0, FeatureSpec.WIN - FeatureSpec.HOP)
                filled = FeatureSpec.WIN - FeatureSpec.HOP
            }
        }
    }

    private fun emit() {
        for (k in 0 until FeatureSpec.WIN) scratch[k] = buffer[k] * FeatureSpec.hann[k]
        RealFft.power(scratch, power)
        mel.apply(power, bands)

        var mean = 0f
        for (b in 0 until FeatureSpec.BANDS) {
            bands[b] = ln(bands[b] + 1e-10f)
            mean += bands[b]
        }
        mean /= FeatureSpec.BANDS
        var variance = 0f
        for (b in 0 until FeatureSpec.BANDS) {
            bands[b] -= mean
            variance += bands[b] * bands[b]
        }
        val std = sqrt(variance / FeatureSpec.BANDS) + 1e-6f
        val out = FloatArray(FeatureSpec.BANDS) { bands[it] / std }
        var energy = 0.0
        for (sample in buffer) energy += sample * sample
        val rms = sqrt(energy / FeatureSpec.WIN).toFloat()
        onFrame(out, rms)
    }
}
