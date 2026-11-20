package com.zhiyue.app.align

import android.content.Context
import com.zhiyue.app.audio.FeatureSpec
import com.zhiyue.app.model.Ceremony
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 固定参考音轨特征及窗口前缀和，支持不跨段的快速候选粗筛。 */
class ReferenceSet internal constructor(
    val ceremonyId: String,
    ceremony: Ceremony,
    readAsset: (String) -> ByteArray,
) {
    constructor(context: Context, ceremony: Ceremony) : this(
        ceremony.id,
        ceremony,
        { path -> context.assets.open(path).use { it.readBytes() } },
    )

    val frames: FloatArray
    val totalFrames: Int
    val offsets: IntArray = IntArray(ceremony.segments.size)
    private val durations: IntArray = IntArray(ceremony.segments.size)
    private val frameCounts: IntArray = IntArray(ceremony.segments.size)
    private val prefix: FloatArray

    init {
        var total = 0
        for ((i, seg) in ceremony.segments.withIndex()) {
            offsets[i] = total
            durations[i] = seg.durationMs
            frameCounts[i] = seg.frames
            total += seg.frames
        }
        totalFrames = total
        frames = FloatArray(total * FeatureSpec.BANDS)
        for ((i, seg) in ceremony.segments.withIndex()) {
            val bytes = readAsset(seg.features)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val count = minOf(fb.remaining(), seg.frames * FeatureSpec.BANDS)
            fb.get(frames, offsets[i] * FeatureSpec.BANDS, count)
        }
        prefix = FloatArray((total + 1) * FeatureSpec.BANDS)
        for (frame in 0 until total) {
            for (band in 0 until FeatureSpec.BANDS) {
                prefix[(frame + 1) * FeatureSpec.BANDS + band] =
                    prefix[frame * FeatureSpec.BANDS + band] + frames[frame * FeatureSpec.BANDS + band]
            }
        }
    }

    fun copyFrameInto(index: Int, out: FloatArray) {
        val start = index.coerceIn(0, totalFrames - 1) * FeatureSpec.BANDS
        System.arraycopy(frames, start, out, 0, FeatureSpec.BANDS)
    }

    fun segmentOf(globalFrame: Int): Int {
        val frame = globalFrame.coerceIn(0, totalFrames - 1)
        for (i in offsets.indices.reversed()) {
            if (frame >= offsets[i]) return i
        }
        return 0
    }

    fun frameInSegment(globalFrame: Int): Int =
        (globalFrame - offsets[segmentOf(globalFrame)]).coerceAtLeast(0)

    fun durationOf(segment: Int): Int = durations[segment]

    fun framesOf(segment: Int): Int = frameCounts[segment]

    fun startFrameOf(segment: Int): Int = offsets[segment.coerceIn(0, offsets.lastIndex)]

    fun endFrameOf(segment: Int): Int = startFrameOf(segment) + frameCounts[segment]

    /** 候选及其全部声学帧必须属于同一段，禁止制造参考录音中不存在的跨段窗。 */
    fun windowInsideSegment(start: Int, win: Int): Boolean {
        if (start < 0 || start + win > totalFrames || win <= 0) return false
        val segment = segmentOf(start)
        return start + win <= endFrameOf(segment)
    }

    /** 以窗口均值为短指纹，精排仍使用完整的逐帧 32 维特征。 */
    fun meanCost(start: Int, win: Int, liveMean: FloatArray): Float {
        var dot = 0f
        val from = start * FeatureSpec.BANDS
        val to = (start + win) * FeatureSpec.BANDS
        for (band in 0 until FeatureSpec.BANDS) {
            val mean = (prefix[to + band] - prefix[from + band]) / win
            dot += mean * liveMean[band]
        }
        // 两侧都是均值，乘回 win 后与逐帧平均点积同尺度。
        return 1f - dot * win / FeatureSpec.BANDS
    }
}
