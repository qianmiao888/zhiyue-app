package com.zhiyue.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiyue.app.model.Ceremony
import com.zhiyue.app.session.Mode
import com.zhiyue.app.session.SessionState

@Composable
fun SessionScreen(
    ceremony: Ceremony,
    state: SessionState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
) {
    val index = state.segmentIndex.coerceIn(0, ceremony.segments.lastIndex)
    val segment = ceremony.segments[index]
    val positionMs = (state.segmentProgress * segment.durationMs).toLong()
    val next = ceremony.segments.getOrNull(index + 1)
    val listState = rememberLazyListState()

    LaunchedEffect(index, state.lineIndex) {
        val target = state.lineIndex.coerceIn(0, segment.lines.lastIndex)
        runCatching {
            listState.animateScrollToItem(target)
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.index == target }
            if (item != null) {
                val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                val delta = item.offset + item.size / 2 - viewportCenter
                listState.scroll { scrollBy(delta.toFloat()) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (state.mode == Mode.LIVE) "现场模式 · 实时识别" else "演示模式 · 内置录音驱动",
                    color = PaperDim,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(ceremony.title, color = Paper, fontSize = 16.sp)
            }
            Text(
                "结束",
                color = PaperDim,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onFinish() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            "第 ${index + 1} / ${ceremony.segments.size} 段 · ${segment.kind}",
            color = GoldDim,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(segment.title, color = Paper, fontSize = 28.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(InkSoft)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    "下一段 · " + (next?.title ?: "圆满回向"),
                    color = if (next != null) PaperDim else Jade,
                    fontSize = 11.sp,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(InkSoft)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(segment.action, color = GoldDim, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = maxHeight * 0.38f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(segment.lines, key = { i, _ -> "${segment.id}-$i" }) { i, line ->
                    val active = i == state.lineIndex
                    val sung = i < state.lineIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) InkSoft else Ink)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            "%02d".format(i + 1),
                            color = if (active) Gold else PaperDim,
                            fontSize = 11.sp,
                            lineHeight = if (active) 38.sp else 32.sp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = buildAnnotatedString {
                                line.forEachIndexed { ci, ch ->
                                    val charStart = segment.charStarts.getOrNull(i)?.getOrNull(ci) ?: 0
                                    val style = when {
                                        !active -> SpanStyle(color = if (sung) GoldDim else PaperDim)
                                        ci < state.charIndex || (ci == state.charIndex && positionMs >= charStart) ->
                                            SpanStyle(color = Gold)
                                        else -> SpanStyle(color = PaperDim)
                                    }
                                    withStyle(style) { append(ch) }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            fontSize = if (active) 24.sp else 19.sp,
                            lineHeight = if (active) 38.sp else 32.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(InkSoft)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(state.segmentProgress)
                Spacer(Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "第 ${state.lineIndex + 1}/${segment.lines.size} 句 · 第 ${state.charIndex + 1} 字",
                        color = Paper,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(segment.hint, color = PaperDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    if (state.mode == Mode.LIVE) {
                        ConfidenceBar(state.confidence, state.locked, state.mode, state.micLevel)
                    } else {
                        DemoSyncBar(state.segmentProgress)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            ChapterBar(ceremony, index, state.segmentProgress)
            Spacer(Modifier.height(8.dp))
            Text(
                "全程 ${timeText(state.elapsedMs)} / 约 ${timeText(state.totalMs)}",
                color = PaperDim,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("上一段", false, onPrev, Modifier.weight(1f))
                ActionButton(if (state.paused) "继续" else "暂停", false, onTogglePause, Modifier.weight(1f))
                ActionButton("下一段", true, onNext, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProgressRing(progress: Float) {
    Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            drawArc(
                color = InkLine,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = Gold,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", color = Paper, fontSize = 16.sp)
    }
}

@Composable
private fun DemoSyncBar(progress: Float) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(InkLine),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(Jade),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text("播放同步 · 字幕与录音同源", color = PaperDim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ConfidenceBar(confidence: Float, locked: Boolean, mode: Mode, micLevel: Float) {
    val label = when {
        !locked -> "识别中 · 等待锁定"
        confidence < 0.35f -> "已锁定 · 置信度偏低"
        confidence < 0.6f -> "已锁定 · 置信度中等"
        else -> "已锁定 · 置信度高"
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(InkLine),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(confidence.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(if (locked) Jade else GoldDim),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(label, color = PaperDim, fontSize = 10.sp)
        }
        if (mode == Mode.LIVE) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("麦克风", color = PaperDim, fontSize = 10.sp)
                Spacer(Modifier.size(6.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(InkLine),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((micLevel * 8f).coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .background(GoldDim),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterBar(ceremony: Ceremony, current: Int, progress: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ceremony.segments.forEachIndexed { i, seg ->
            val color = when {
                i < current -> GoldDim
                i > current -> InkLine
                else -> Gold
            }
            Box(
                modifier = Modifier
                    .weight(seg.durationMs.toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Gold else InkLine)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) Ink else Paper,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun timeText(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
