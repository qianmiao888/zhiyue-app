package com.zhiyue.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiyue.app.model.Ceremony
import com.zhiyue.app.session.Mode

@Composable
fun HomeScreen(
    ceremonies: List<Ceremony>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    hint: String,
    onStart: (Mode) -> Unit,
) {
    var mode by remember { mutableStateOf(Mode.DEMO) }
    val selected = ceremonies.getOrNull(selectedIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("指月", color = Gold, fontSize = 42.sp)
        Spacer(Modifier.height(6.dp))
        Text("梵呗同行 · 唱诵进度同步", color = PaperDim, fontSize = 15.sp)
        Text("法会现场，知道此刻唱到哪一段、哪一句、哪一个字。", color = PaperDim, fontSize = 13.sp)

        Spacer(Modifier.height(26.dp))
        Text("选择法会", color = PaperDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        ceremonies.forEachIndexed { index, ceremony ->
            CeremonyCard(
                ceremony = ceremony,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(12.dp))
        Text("选择模式", color = PaperDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        ModeCard(
            title = "现场模式",
            desc = "麦克风跟随唱诵，逐字估计当前唱到哪个字（实验性）",
            selected = mode == Mode.LIVE,
            onClick = { mode = Mode.LIVE },
        )
        Spacer(Modifier.height(10.dp))
        ModeCard(
            title = "演示模式",
            desc = "内置录音回放，字幕与录音同源，逐字点亮，无需麦克风权限",
            selected = mode == Mode.DEMO,
            onClick = { mode = Mode.DEMO },
        )

        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(hint, color = GoldDim, fontSize = 12.sp)
        }

        Spacer(Modifier.height(26.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(if (selected == null) InkLine else Gold)
                .clickable(enabled = selected != null) { onStart(mode) }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (mode == Mode.DEMO) "开始演示" else "开始跟诵",
                color = if (selected == null) PaperDim else Ink,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "指月之指 · v1.2.1 · 免登录 · 不上传任何音频 · 飞行模式可用",
            color = PaperDim,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            selected?.source.orEmpty(),
            color = InkLine,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CeremonyCard(
    ceremony: Ceremony,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) InkSoft else Ink)
            .border(1.dp, if (selected) Gold else InkLine, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (selected) Gold else InkLine),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                ceremony.title,
                color = if (selected) Paper else PaperDim,
                fontSize = 18.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(ceremony.subtitle, color = PaperDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("${ceremony.segments.size} 段")
            Spacer(Modifier.size(8.dp))
            Pill("约 ${ceremony.totalMs / 60000} 分钟")
            Spacer(Modifier.size(8.dp))
            Pill("逐字同步")
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) InkSoft else Ink)
            .border(1.dp, if (selected) Gold else InkLine, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (selected) Gold else InkLine),
        )
        Column {
            Text(title, color = if (selected) Paper else PaperDim, fontSize = 16.sp)
            Spacer(Modifier.height(3.dp))
            Text(desc, color = PaperDim, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun Pill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(InkLine)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, color = PaperDim, fontSize = 11.sp)
    }
}
