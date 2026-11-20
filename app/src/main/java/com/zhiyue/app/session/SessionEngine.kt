package com.zhiyue.app.session

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import com.zhiyue.app.align.Aligner
import com.zhiyue.app.align.ReferenceSet
import com.zhiyue.app.audio.FeatureExtractor
import com.zhiyue.app.audio.FeatureSpec
import com.zhiyue.app.audio.MelBank
import com.zhiyue.app.model.Ceremony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

enum class Mode { LIVE, DEMO }

data class SessionState(
    val prepared: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val mode: Mode = Mode.DEMO,
    val segmentIndex: Int = 0,
    val lineIndex: Int = 0,
    /** 当前正在唱的字在本句中的下标（之前的字视为已诵） */
    val charIndex: Int = 0,
    val segmentProgress: Float = 0f,
    val overallProgress: Float = 0f,
    val elapsedMs: Long = 0,
    val totalMs: Long = 0,
    val confidence: Float = 0f,
    val micLevel: Float = 0f,
    val locked: Boolean = false,
    val status: String = "",
)

/**
 * 一场法会的运行控制：音频输入（麦克风 / 内置录音回放）→ 特征 → 对齐 → UI 状态。
 */
class SessionEngine(
    private val context: Context,
    private val ceremony: Ceremony,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null

    private var mel: MelBank? = null
    private var aligner: Aligner? = null

    private var player: MediaPlayer? = null
    private var recorder: AudioRecord? = null
    private var audioThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var mode = Mode.DEMO
    @Volatile private var segmentIndex = 0
    @Volatile private var segElapsedMs = 0L
    @Volatile private var lastTickElapsed = 0L
    @Volatile private var completedSegment = -1

    private val _state = MutableStateFlow(SessionState(totalMs = ceremony.totalMs))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun start(m: Mode) {
        if (sessionJob != null) return
        mode = m
        running = true
        paused = false
        _state.update {
            it.copy(
                running = true,
                paused = false,
                mode = m,
                status = if (m == Mode.LIVE) "正在准备声学模型…" else "",
            )
        }
        sessionJob = scope.launch {
            // 演示模式为录音回放，字幕由播放时钟推进，无需加载声学模型
            if (m == Mode.LIVE) loadModel()
            _state.update { it.copy(prepared = true, status = "") }
            if (m == Mode.DEMO) runDemoLoop() else runLiveLoop()
        }
    }

    private suspend fun loadModel() {
        if (mel != null) return
        mel = MelBank.load(context)
        aligner = Aligner(ReferenceSet(context, ceremony))
    }

    // ---------------- 演示模式：内置录音回放，字幕与音频同源 ----------------

    private suspend fun runDemoLoop() {
        segmentIndex = 0
        segElapsedMs = 0
        lastTickElapsed = SystemClock.elapsedRealtime()
        playSegment(0)
        publishPosition(0, 0L, 0f, true)

        while (running && scope.isActive) {
            if (paused) {
                lastTickElapsed = SystemClock.elapsedRealtime()
                delay(100)
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val delta = (now - lastTickElapsed).coerceIn(0L, 500L)
            lastTickElapsed = now

            val seg = ceremony.segments[segmentIndex]
            val playedMs = synchronized(playerLock) {
                runCatching { player?.currentPosition }.getOrNull()
            }
            if (playedMs != null) {
                segElapsedMs = playedMs.toLong()
            } else {
                segElapsedMs += delta
            }
            publishPosition(segmentIndex, segElapsedMs, 0f, true)

            val completed = completedSegment == segmentIndex
            if (completed || (playedMs == null && segElapsedMs >= seg.durationMs - 80L)) {
                if (segmentIndex >= ceremony.segments.lastIndex) {
                    finishInternal()
                    break
                }
                jumpTo(segmentIndex + 1)
            }
            delay(80)
        }
    }

    private val playerLock = Any()

    private fun playSegment(index: Int) {
        synchronized(playerLock) {
            player?.setOnCompletionListener(null)
            player?.release()
            player = null
            completedSegment = -1
            val seg = ceremony.segments[index]
            runCatching {
                val afd = context.assets.openFd(seg.audio)
                MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    setVolume(1f, 1f)
                    setOnCompletionListener { completedSegment = index }
                    prepare()
                    start()
                }
            }.onSuccess { player = it }
        }
    }

    // ---------------- 现场模式：麦克风实时切分 ----------------

    private fun runLiveLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            FeatureSpec.SR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBuf, FeatureSpec.SR / 5 * 4)

        val rec = createRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferSize)
            ?: createRecorder(MediaRecorder.AudioSource.MIC, bufferSize)
        if (rec == null) {
            _state.update { it.copy(status = "麦克风不可用，请改用演示模式", running = false) }
            running = false
            return
        }
        recorder = rec
        rec.startRecording()
        aligner?.reset()

        val extractor = FeatureExtractor(mel!!) { frame, rms ->
            val out = aligner?.push(frame, rms)
            if (out?.locked == true) {
                publishPosition(out.segment, out.positionMs, out.confidence, true)
            } else {
                _state.update {
                    it.copy(
                        locked = false,
                        confidence = out?.confidence ?: 0f,
                        status = "识别中 · 等待声学锁定",
                    )
                }
            }
        }

        val thread = Thread({
            val buffer = ShortArray(1600)
            while (running) {
                if (paused) {
                    Thread.sleep(60)
                    continue
                }
                val n = rec.read(buffer, 0, buffer.size)
                if (n > 0) {
                    extractor.feed(buffer, n)
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = buffer[i] / 32768.0
                        sum += v * v
                    }
                    val rms = sqrt(sum / n).toFloat()
                    _state.update { it.copy(micLevel = rms) }
                }
            }
        }, "zhiyue-audio")
        audioThread = thread
        thread.start()
    }

    private fun createRecorder(source: Int, bufferSize: Int): AudioRecord? = runCatching {
        val rec = AudioRecord(
            source,
            FeatureSpec.SR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            rec.release()
            null
        }
    }.getOrNull()

    // ---------------- 状态发布 ----------------

    private fun publishPosition(segIdx: Int, positionMs: Long, conf: Float, locked: Boolean) {
        val index = segIdx.coerceIn(0, ceremony.segments.lastIndex)
        val seg = ceremony.segments[index]
        val pos = positionMs.coerceIn(0L, seg.durationMs.toLong())
        val segProgress = if (seg.durationMs > 0) pos.toFloat() / seg.durationMs else 0f
        val line = seg.lineAt(pos)
        val charIndex = seg.charAt(line, pos)
        segmentIndex = index

        var before = 0L
        for (i in 0 until index) before += ceremony.segments[i].durationMs

        val total = ceremony.totalMs.coerceAtLeast(1L)
        val elapsed = before + pos
        _state.update {
            it.copy(
                segmentIndex = index,
                lineIndex = line,
                charIndex = charIndex,
                segmentProgress = segProgress,
                overallProgress = (elapsed.toFloat() / total).coerceIn(0f, 1f),
                elapsedMs = elapsed,
                confidence = conf,
                locked = locked,
                status = if (locked) "" else it.status,
            )
        }
    }

    // ---------------- 交互 ----------------

    fun togglePause() {
        paused = !paused
        if (mode == Mode.DEMO) {
            synchronized(playerLock) {
                if (paused) player?.pause() else player?.start()
            }
        } else if (paused) {
            aligner?.pause()
        }
        _state.update {
            it.copy(
                paused = paused,
                locked = if (mode == Mode.LIVE && paused) false else it.locked,
                confidence = if (mode == Mode.LIVE && paused) 0f else it.confidence,
            )
        }
    }

    fun jumpTo(index: Int) {
        if (index !in ceremony.segments.indices) return
        segmentIndex = index
        segElapsedMs = 0L
        lastTickElapsed = SystemClock.elapsedRealtime()
        aligner?.seekToSegment(index)
        if (mode == Mode.DEMO) {
            playSegment(index)
        }
        paused = false
        publishPosition(index, 0L, if (mode == Mode.DEMO) 1f else 0f, mode == Mode.DEMO)
        _state.update { it.copy(paused = false) }
    }

    fun nextSegment() = jumpTo(segmentIndex + 1)

    fun prevSegment() = jumpTo(segmentIndex - 1)

    fun finish() = finishInternal()

    private fun finishInternal() {
        running = false
        synchronized(playerLock) {
            player?.setOnCompletionListener(null)
            runCatching { player?.stop() }
            player?.release()
            player = null
        }
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        val st = _state.value
        _state.update {
            it.copy(
                running = false,
                finished = true,
                overallProgress = if (st.elapsedMs >= ceremony.totalMs - 1500) 1f else it.overallProgress,
            )
        }
    }

    fun release() {
        finishInternal()
        scope.cancel()
        sessionJob = null
    }
}
