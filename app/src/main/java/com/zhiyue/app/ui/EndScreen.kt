package com.zhiyue.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiyue.app.model.Ceremony
import com.zhiyue.app.session.SessionState

@Composable
fun EndScreen(
    ceremony: Ceremony,
    state: SessionState,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text("回向", color = Gold, fontSize = 30.sp)
        Spacer(Modifier.height(20.dp))
        Text(
            "愿以此功德，庄严佛净土。\n上报四重恩，下济三途苦。\n若有见闻者，悉发菩提心。\n尽此一报身，同生极乐国。",
            color = Paper,
            fontSize = 18.sp,
            lineHeight = 34.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(InkSoft)
                .padding(vertical = 20.dp),
        ) {
            Stat("完成", "${(state.overallProgress * 100).toInt()}%", Modifier.weight(1f))
            Stat("用时", timeText(state.elapsedMs), Modifier.weight(1f))
            Stat("段落", "${state.segmentIndex + 1}/${ceremony.segments.size}", Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))
        Text(
            if (state.overallProgress >= 0.98f) "本场圆满" else "本次已结束，下次可从任一章节继续",
            color = PaperDim,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(36.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(Gold)
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("回到首页", color = Ink, fontSize = 16.sp)
        }

        Spacer(Modifier.height(20.dp))
        Text(ceremony.source, color = InkLine, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Paper, fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = PaperDim, fontSize = 11.sp)
    }
}
