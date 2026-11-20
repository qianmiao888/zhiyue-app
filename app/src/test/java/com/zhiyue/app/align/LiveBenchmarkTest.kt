package com.zhiyue.app.align

import com.zhiyue.app.audio.FeatureExtractor
import com.zhiyue.app.audio.FeatureSpec
import com.zhiyue.app.audio.MelBank
import com.zhiyue.app.model.Ceremony
import com.zhiyue.app.model.CeremonyParser
import com.zhiyue.app.model.Segment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** 固定内置录音的端侧识别基准；音频、特征与位置映射均使用生产文件和代码。 */
class LiveBenchmarkTest {

    private data class Expected(val segment: Segment, val index: Int, val frameStartMs: Long, val crossBoundary: Boolean = false)

    private class Stats(val name: String, private val startMs: Long) {
        var callbacks = 0
        var validCallbacks = 0
        var lockedCallbacks = 0
        var lockedFrames = 0
        var validFramesUnlockedAfterLock = 0
        var segmentOk = 0
        var lineOk = 0
        var charOk = 0
        var everLocked = false
        var firstLockMs: Long? = null
        var firstLockLatencyMs: Long? = null
        var firstLockDetail = ""
        var relockCount = 0
        var unlockCount = 0
        var maxPositionErrorMs = 0L
        var positionErrorTotalMs = 0L
        var firstFailure = ""
        var bestTrueCandidateCost = Float.MAX_VALUE
        var bestTrueCandidateMargin = 0f
        var bestWrongCandidateCost = Float.MAX_VALUE
        var elapsedMs = 0L
        private var previousLocked = false

        fun observe(out: Aligner.Result?, expected: Expected) {
            callbacks++
            val streamPosition = expected.frameStartMs + FeatureSpec.WIN * 1000L / FeatureSpec.SR
            // 法本与参考特征都是 100ms 网格；任意毫秒起播按同一网格量化，不掩盖整帧跟踪误差。
            val position = (streamPosition + 50) / 100 * 100
            val nowLocked = out?.locked == true
            if (nowLocked && !previousLocked && everLocked) relockCount++
            if (!nowLocked && previousLocked) unlockCount++
            previousLocked = nowLocked
            if (out != null) {
                val rawPosition = out.positionMs - Aligner.ANALYSIS_WINDOW_MS
                val trueCandidate = out.segment == expected.index && abs(rawPosition - expected.frameStartMs) <= 400
                if (trueCandidate && out.matchCost < bestTrueCandidateCost) {
                    bestTrueCandidateCost = out.matchCost
                    bestTrueCandidateMargin = out.margin
                } else if (!trueCandidate) {
                    bestWrongCandidateCost = minOf(bestWrongCandidateCost, out.matchCost)
                }
            }
            if (out?.locked == true) {
                lockedCallbacks++
                if (!everLocked) {
                    firstLockMs = position
                    firstLockLatencyMs = position - startMs
                    firstLockDetail = "expected=${expected.index}@$position actual=${out.segment}@${out.positionMs} " +
                        "cost=${out.matchCost} margin=${out.margin}"
                }
                everLocked = true
            }
            if (expected.crossBoundary || position >= expected.segment.durationMs) return
            val seg = expected.segment
            val expectedLine = seg.lineAt(position)
            val lineStart = seg.lineStarts.getOrNull(expectedLine)?.toLong() ?: 0L
            val lineEnd = seg.lineEnds.getOrNull(expectedLine)?.toLong() ?: seg.durationMs.toLong()
            if (position < lineStart || position >= lineEnd) return
            validCallbacks++
            if (out?.locked != true) {
                if (everLocked) validFramesUnlockedAfterLock++
                return
            }
            lockedFrames++
            val actualSegIndex = out.segment
            val actualPosition = out.positionMs.coerceIn(0L, expected.segment.durationMs.toLong())
            val segmentCorrect = actualSegIndex == expected.index
            if (segmentCorrect) segmentOk++
            val actualSeg = if (segmentCorrect) expected.segment else null
            val actualLine = actualSeg?.lineAt(actualPosition) ?: -1
            val lineCorrect = segmentCorrect && actualLine == expectedLine
            if (lineCorrect) lineOk++
            val expectedChar = seg.charAt(expectedLine, position)
            val charCorrect = lineCorrect && actualSeg != null && actualSeg.charAt(actualLine, actualPosition) == expectedChar
            if (charCorrect) charOk++
            val error = if (segmentCorrect) abs(actualPosition - position) else Long.MAX_VALUE / 4
            if (segmentCorrect) {
                maxPositionErrorMs = maxOf(maxPositionErrorMs, error)
                positionErrorTotalMs += error
            }
            if (!charCorrect && firstFailure.isEmpty()) {
                firstFailure = "frame=$callbacks expected=${expected.index}/$expectedLine/$expectedChar@$position " +
                    "actual=$actualSegIndex/$actualLine/${if (lineCorrect) actualSeg?.charAt(actualLine, actualPosition) else -1}@$actualPosition " +
                    "confidence=${out.confidence} cost=${out.matchCost} margin=${out.margin}"
            }
        }

        fun passed(): Boolean = everLocked && (validCallbacks == 0 || lockedFrames > 0) &&
            segmentOk == lockedFrames && lineOk == lockedFrames && charOk == lockedFrames

        fun json(): JSONObject = JSONObject()
            .put("name", name)
            .put("passed", passed())
            .put("callbacks", callbacks)
            .put("validCallbacks", validCallbacks)
            .put("lockedCallbacks", lockedCallbacks)
            .put("lockedFrames", lockedFrames)
            .put("validFramesUnlockedAfterLock", validFramesUnlockedAfterLock)
            .put("segmentAccuracy", accuracy(segmentOk))
            .put("lineAccuracy", accuracy(lineOk))
            .put("charAccuracy", accuracy(charOk))
            .put("firstLockMs", firstLockMs ?: JSONObject.NULL)
            .put("firstLockLatencyMs", firstLockLatencyMs ?: JSONObject.NULL)
            .put("firstLockDetail", firstLockDetail)
            .put("relockCount", relockCount)
            .put("unlockCount", unlockCount)
            .put("maxPositionErrorMs", maxPositionErrorMs)
            .put("meanPositionErrorMs", if (segmentOk > 0) positionErrorTotalMs.toDouble() / segmentOk else JSONObject.NULL)
            .put("firstFailure", firstFailure)
            .put("bestTrueCandidateCost", if (bestTrueCandidateCost == Float.MAX_VALUE) JSONObject.NULL else bestTrueCandidateCost)
            .put("bestTrueCandidateMargin", bestTrueCandidateMargin)
            .put("bestWrongCandidateCost", if (bestWrongCandidateCost == Float.MAX_VALUE) JSONObject.NULL else bestWrongCandidateCost)
            .put("elapsedMs", elapsedMs)

        private fun accuracy(ok: Int): Double = if (lockedFrames == 0) 0.0 else ok.toDouble() / lockedFrames
    }

    @Test
    fun fixedCorpus() {
        val manifestFile = File(System.getenv("ZHIYUE_BENCHMARK_MANIFEST") ?: "d:\\Buddha\\.cache\\live_benchmark\\manifest.json")
        val manifest = JSONObject(manifestFile.readText())
        val suite = System.getenv("ZHIYUE_BENCHMARK_SUITE") ?: "smoke"
        val label = System.getenv("ZHIYUE_BENCHMARK_LABEL") ?: "current"
        val filter = System.getenv("ZHIYUE_BENCHMARK_FILTER").orEmpty()
        val maxCases = System.getenv("ZHIYUE_BENCHMARK_MAX_CASES")?.toIntOrNull() ?: Int.MAX_VALUE
        val melBytes = File(manifest.getString("mel")).readBytes()
        val melFloats = ByteBuffer.wrap(melBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val weights = FloatArray(melFloats.remaining())
        melFloats.get(weights)
        val mel = MelBank(FeatureSpec.BANDS, FeatureSpec.BINS, weights)
        val assets = File(manifest.getString("assets"))
        val cases = JSONArray()
        var passed = 0
        var executed = 0
        val started = System.currentTimeMillis()

        for (ci in 0 until manifest.getJSONArray("ceremonies").length()) {
            val entry = manifest.getJSONArray("ceremonies").getJSONObject(ci)
            val ceremony = CeremonyParser.parse(File(entry.getString("json")).readText())
            val ref = ReferenceSet(ceremony.id, ceremony) { File(assets, it).readBytes() }
            val definitions = ArrayList<CaseDefinition>()
            if (suite == "smoke") {
                definitions.add(CaseDefinition(0, 0L, 20_000L, false))
            } else {
                for (si in ceremony.segments.indices) {
                    definitions.add(CaseDefinition(si, 0L, 0L, false))
                    val midpoint = ceremony.segments[si].durationMs / 2L
                    definitions.add(CaseDefinition(si, 8_000L, 20_000L, false))
                    definitions.add(CaseDefinition(si, midpoint, 20_000L, false))
                }
                definitions.add(CaseDefinition(0, 0L, 0L, true))
            }
            for (chunkMs in listOf(80, 100)) {
                for (definition in definitions) {
                    val name = "${ceremony.id}/${if (definition.continuous) "continuous" else ceremony.segments[definition.segment].id}" +
                        "/start=${definition.startMs}/chunk=$chunkMs"
                    if (filter.isNotEmpty() && !name.contains(filter)) continue
                    if (executed >= maxCases) continue
                    executed++
                    val stats = Stats(name, definition.startMs)
                    val caseStart = System.currentTimeMillis()
                    if (definition.continuous) {
                        runContinuous(ceremony, entry.getJSONArray("segments"), ref, mel, chunkMs, stats)
                    } else {
                        val segmentJson = entry.getJSONArray("segments").getJSONObject(definition.segment)
                        runSingle(ceremony, definition, segmentJson, ref, mel, chunkMs, stats)
                    }
                    stats.elapsedMs = System.currentTimeMillis() - caseStart
                    if (stats.passed()) passed++
                    cases.put(stats.json())
                    println(
                        "BENCHMARK ${if (stats.passed()) "PASS" else "FAIL"} $name " +
                            "locked=${stats.lockedFrames}/${stats.validCallbacks} " +
                            "seg=${stats.segmentOk} line=${stats.lineOk} char=${stats.charOk} " +
                            "maxErr=${stats.maxPositionErrorMs}ms ${stats.firstFailure}",
                    )
                }
            }
        }

        val report = JSONObject()
            .put("label", label)
            .put("suite", suite)
            .put("cases", executed)
            .put("passed", passed)
            .put("allPassed", executed > 0 && passed == executed)
            .put("elapsedMs", System.currentTimeMillis() - started)
            .put("results", cases)
        val reportFile = File(manifestFile.parentFile, "report-$label-$suite.json")
        reportFile.writeText(report.toString(2))
        println("BENCHMARK_SUMMARY passed=$passed/$executed report=$reportFile")
        if (System.getenv("ZHIYUE_BENCHMARK_ENFORCE") == "1") {
            assertTrue("固定样本识别未全部通过，详见 $reportFile", executed > 0 && passed == executed)
        }
    }

    private data class CaseDefinition(val segment: Int, val startMs: Long, val limitMs: Long, val continuous: Boolean)

    private fun runSingle(
        ceremony: Ceremony,
        definition: CaseDefinition,
        segmentJson: JSONObject,
        ref: ReferenceSet,
        mel: MelBank,
        chunkMs: Int,
        stats: Stats,
    ) {
        val aligner = Aligner(ref)
        var frameIndex = 0
        val extractor = FeatureExtractor(mel) { frame, rms ->
            val out = aligner.push(frame, rms)
            val expected = Expected(
                ceremony.segments[definition.segment],
                definition.segment,
                definition.startMs + frameIndex * 100L,
            )
            stats.observe(out, expected)
            frameIndex++
        }
        feedPcm(segmentJson.getString("pcm"), definition.startMs, definition.limitMs, chunkMs, extractor)
    }

    private fun runContinuous(
        ceremony: Ceremony,
        segmentsJson: JSONArray,
        ref: ReferenceSet,
        mel: MelBank,
        chunkMs: Int,
        stats: Stats,
    ) {
        val aligner = Aligner(ref)
        // 连续播放按真实 PCM 拼接长度建立真值；个别 m4a 与 JSON 标称时长并不相同。
        val actualDurationsMs = LongArray(segmentsJson.length()) { i ->
            val pcm = File(segmentsJson.getJSONObject(i).getString("pcm"))
            pcm.length() / 2 * 1000L / FeatureSpec.SR
        }
        var frameIndex = 0
        val extractor = FeatureExtractor(mel) { frame, rms ->
            val out = aligner.push(frame, rms)
            val globalStart = frameIndex * 100L
            var base = 0L
            var expected: Expected? = null
            for ((index, seg) in ceremony.segments.withIndex()) {
                if (globalStart < base + actualDurationsMs[index]) {
                    val local = globalStart - base
                    val cross = local + Aligner.ANALYSIS_WINDOW_MS > actualDurationsMs[index] ||
                        local > (seg.frames - 1) * 100L
                    expected = Expected(seg, index, local, cross)
                    break
                }
                base += actualDurationsMs[index]
            }
            if (expected != null) stats.observe(out, expected)
            frameIndex++
        }
        for (i in 0 until segmentsJson.length()) {
            feedPcm(segmentsJson.getJSONObject(i).getString("pcm"), 0L, 0L, chunkMs, extractor)
        }
    }

    private fun feedPcm(path: String, startMs: Long, limitMs: Long, chunkMs: Int, extractor: FeatureExtractor) {
        File(path).inputStream().buffered().use { input ->
            var skip = startMs * FeatureSpec.SR * 2 / 1000
            while (skip > 0) {
                val n = input.skip(skip)
                if (n <= 0) return
                skip -= n
            }
            var remaining = if (limitMs > 0) limitMs * FeatureSpec.SR * 2 / 1000 else Long.MAX_VALUE
            val bytes = ByteArray(chunkMs * FeatureSpec.SR * 2 / 1000)
            while (remaining > 0) {
                val n = input.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt())
                if (n <= 0) break
                val shorts = ShortArray(n / 2)
                ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                extractor.feed(shorts, shorts.size)
                remaining -= n
            }
        }
    }
}
