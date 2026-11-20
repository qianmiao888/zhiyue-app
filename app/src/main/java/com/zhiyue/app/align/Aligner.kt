package com.zhiyue.app.align

import com.zhiyue.app.audio.FeatureSpec
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 针对内置固定音轨的两阶段在线对齐：窗口指纹粗筛，完整逐帧特征精排。 */
class Aligner(private val ref: ReferenceSet) {

    data class Result(
        val segment: Int,
        val frameInSegment: Int,
        val confidence: Float,
        val locked: Boolean,
        val matchCost: Float = 1f,
        val margin: Float = 0f,
    ) {
        /** 特征帧覆盖 400ms 声音，输出应对齐本次输入结束时的位置。 */
        val positionMs: Long get() = frameInSegment * 100L + ANALYSIS_WINDOW_MS
    }

    private data class Candidate(val start: Int, val coarse: Float)
    private data class AcousticCost(val cost: Float, val shift: Int)
    private data class Match(
        val pos: Int,
        val cost: Float,
        val score: Float,
        val margin: Float,
    )

    private val liveWindow = 20
    private val minWindow = 8
    private val live = ArrayDeque<FloatArray>()
    private var locked = false
    private var hasTrack = false
    private var cursor = 0
    private var confidence = 0f
    private var hits = 0
    private var lastHitPos = -1
    private var misses = 0
    private var silenceFrames = 0
    private var preferredSegment: Int? = null

    fun reset() {
        live.clear()
        locked = false
        hasTrack = false
        cursor = 0
        confidence = 0f
        hits = 0
        lastHitPos = -1
        misses = 0
        silenceFrames = 0
        preferredSegment = null
    }

    /** 手动跳段仅限定下一次声学搜索范围，不伪造锁定。 */
    fun seekToSegment(segment: Int) {
        live.clear()
        cursor = ref.startFrameOf(segment)
        locked = false
        hasTrack = false
        confidence = 0f
        hits = 0
        lastHitPos = -1
        misses = 0
        silenceFrames = 0
        preferredSegment = segment.coerceIn(0, ref.offsets.lastIndex)
    }

    /** 暂停后旧上下文已不连续，恢复时必须重新听音锁定。 */
    fun pause() = reset()

    fun push(frame: FloatArray, rms: Float = 1f): Result? {
        if (rms < SILENCE_RMS) {
            silenceFrames++
            noteMiss()
            if (silenceFrames >= 3) live.clear()
            return null
        }
        silenceFrames = 0
        live.addLast(frame)
        if (live.size > liveWindow) live.removeFirst()
        val win = live.size
        if (win < minWindow) {
            noteMiss()
            return null
        }

        val maxStart = ref.totalFrames - win
        if (maxStart <= 0) {
            noteMiss()
            return null
        }
        val wasLocked = locked
        val trackingStart = cursor - win + 1
        var lo = if (wasLocked) max(0, trackingStart - 8) else 0
        var hi = if (wasLocked) min(maxStart, trackingStart + 8) else maxStart
        val preferred = preferredSegment
        if (!wasLocked && preferred != null) {
            lo = ref.startFrameOf(preferred)
            hi = min(maxStart, ref.endFrameOf(preferred) - win)
        }
        if (hi < lo) {
            noteMiss()
            return null
        }

        val match = search(lo, hi, win, wasLocked)
        if (match == null) {
            noteMiss()
            return null
        }
        val threshold = when {
            wasLocked -> TRACK_COST
            hasTrack -> TRACK_COST
            else -> LOCK_COST
        }
        val matched = match.cost < threshold

        if (!wasLocked) {
            val distinct = win >= liveWindow && match.margin >= LOCK_MARGIN
            val continuous = !hasTrack || match.pos in cursor - 2..cursor + misses + 3
            if (matched && distinct && continuous) {
                if (lastHitPos >= 0 && match.pos in lastHitPos - 1..lastHitPos + 3) hits++ else hits = 1
                lastHitPos = match.pos
                if (hits >= 3) {
                    locked = true
                    hasTrack = true
                    cursor = match.pos
                    preferredSegment = null
                    misses = 0
                }
            } else {
                hits = 0
                lastHitPos = -1
            }
            if (!locked && hasTrack) misses++
        } else if (matched && match.pos in cursor - 1..cursor + 2) {
            cursor = match.pos
            misses = 0
        } else {
            // 对外立即解锁；最近轨迹只用于重锁排序，不能继续展示成已确认结果。
            misses++
            locked = false
            hits = 0
            lastHitPos = -1
            confidence *= 0.5f
        }

        val raw = (1f - match.cost).coerceIn(0f, 1f)
        confidence = confidence * 0.6f + raw * 0.4f
        val outputPos = if (locked) cursor else match.pos
        return Result(
            ref.segmentOf(outputPos),
            ref.frameInSegment(outputPos),
            confidence,
            locked,
            match.cost,
            match.margin,
        )
    }

    /** 均值指纹保留 Top-K，再对候选邻域计算完整逐帧声学代价。 */
    private fun search(lo: Int, hi: Int, win: Int, lockedNow: Boolean): Match? {
        val liveMean = FloatArray(FeatureSpec.BANDS)
        for (frame in live) for (b in 0 until FeatureSpec.BANDS) liveMean[b] += frame[b] / win
        val stride = if (lockedNow) 1 else 4
        val topK = if (lockedNow) 8 else 32
        val queue = PriorityQueue<Candidate>(topK, compareByDescending { it.coarse })
        var start = lo
        while (start <= hi) {
            if (ref.windowInsideSegment(start, win)) {
                val coarse = ref.meanCost(start, win, liveMean)
                if (queue.size < topK) {
                    queue.add(Candidate(start, coarse))
                } else if (coarse < queue.peek().coarse) {
                    queue.poll()
                    queue.add(Candidate(start, coarse))
                }
            }
            start += stride
        }
        if (queue.isEmpty()) return null

        val candidates = HashSet<Int>()
        for (candidate in queue) {
            for (p in candidate.start - stride..candidate.start + stride) {
                if (p in lo..hi && ref.windowInsideSegment(p, win)) candidates.add(p)
            }
        }
        // 粗筛可能被重复唱段占满；锁定与重锁都必须精查最近轨迹预测位置。
        if (hasTrack) {
            val expectedStart = cursor + (if (lockedNow) 0 else misses) + 1 - win + 1
            for (p in expectedStart - 8..expectedStart + 8) {
                if (p in lo..hi && ref.windowInsideSegment(p, win)) candidates.add(p)
            }
        }
        var bestPos = -1
        var bestCost = Float.MAX_VALUE
        var bestScore = Float.MAX_VALUE
        var alternative = Float.MAX_VALUE
        val scored = ArrayList<Pair<Int, Float>>(candidates.size)
        for (candidate in candidates) {
            val acoustic = cost(candidate, win)
            if (acoustic.cost == Float.MAX_VALUE) continue
            val pos = candidate + win - 1 + acoustic.shift
            val score = acoustic.cost + prior(pos)
            scored.add(pos to score)
            if (score < bestScore) {
                bestPos = pos
                bestCost = acoustic.cost
                bestScore = score
            }
        }
        if (bestPos < 0) return null
        for ((pos, score) in scored) {
            if (abs(pos - bestPos) > 30 && score < alternative) alternative = score
        }
        val margin = if (alternative == Float.MAX_VALUE) 1f else alternative - bestScore
        if (hasTrack) {
            val start = bestPos - win + 1
            val left = if (ref.windowInsideSegment(start - 1, win)) cost(start - 1, win).cost else Float.NaN
            val right = if (ref.windowInsideSegment(start + 1, win)) cost(start + 1, win).cost else Float.NaN
            val denominator = left - 2f * bestCost + right
            if (denominator > 1e-6f) {
                val delta = (0.5f * (left - right) / denominator).coerceIn(-1f, 1f)
                if (delta > 0.5f) bestPos++ else if (delta < -0.5f) bestPos--
            }
        }
        return Match(bestPos, bestCost, bestScore, margin)
    }

    private fun cost(start: Int, win: Int): AcousticCost {
        var best = Float.MAX_VALUE
        var bestShift = 0
        // 固定样本与参考音轨同源，不做会引入边界抖动的速度漂移。
        for (shift in 0..0) {
            val shifted = start + shift
            if (!ref.windowInsideSegment(shifted, win)) continue
            var sum = 0f
            for (i in 0 until win) {
                val base = (shifted + i) * FeatureSpec.BANDS
                val liveFrame = live[i]
                var dot = 0f
                for (b in 0 until FeatureSpec.BANDS) dot += ref.frames[base + b] * liveFrame[b]
                sum += 1f - dot / FeatureSpec.BANDS
            }
            val cost = sum / win
            if (cost < best) {
                best = cost
                bestShift = shift
            }
        }
        return AcousticCost(best, bestShift)
    }

    private fun noteMiss() {
        if (!hasTrack) return
        if (locked) {
            locked = false
            hits = 0
            lastHitPos = -1
            misses = 1
        } else {
            misses++
        }
    }

    private fun prior(pos: Int): Float {
        if (!hasTrack) return 0f
        val expected = cursor + 1 + if (locked) 0 else misses
        val diff = abs(pos - expected)
        return 0.01f * diff
    }

    companion object {
        const val ANALYSIS_WINDOW_MS = 400L
        private const val SILENCE_RMS = 0.002f
        private const val LOCK_COST = 0.04f
        private const val TRACK_COST = 0.015f
        private const val LOCK_MARGIN = 0.015f
    }
}
