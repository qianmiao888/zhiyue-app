package com.zhiyue.app.model

import org.json.JSONObject

/** 一个唱诵段落：对应一段参考录音与一组法本行。 */
data class Segment(
    val id: String,
    val no: Int,
    val title: String,
    val kind: String,
    val audio: String,
    val features: String,
    val frames: Int,
    val durationMs: Int,
    val lines: List<String>,
    /** 每句在段内的起始毫秒（与 lines 一一对应，按唱诵气口对齐） */
    val lineStarts: List<Int>,
    val lineEnds: List<Int>,
    /** 每个汉字在段内的起始毫秒（按能量分布逐字对齐） */
    val charStarts: List<List<Int>>,
    val hint: String,
    val action: String,
) {
    /** 与端侧显示共用的逐句定位。 */
    fun lineAt(positionMs: Long): Int {
        if (lineStarts.size == lines.size && lines.isNotEmpty()) {
            var index = 0
            for (i in lineStarts.indices) {
                if (positionMs >= lineStarts[i]) index = i else break
            }
            return index
        }
        if (lines.isEmpty()) return 0
        val progress = positionMs.toFloat() / durationMs.coerceAtLeast(1)
        val target = progress * lines.sumOf { it.length }.coerceAtLeast(1)
        var acc = 0
        for ((i, line) in lines.withIndex()) {
            acc += line.length
            if (target <= acc) return i
        }
        return lines.lastIndex
    }

    /** 当前正在唱的内容字；标点回退到最近的内容字。 */
    fun charAt(line: Int, positionMs: Long): Int {
        val times = charStarts.getOrNull(line) ?: return 0
        var index = 0
        for (i in times.indices) {
            if (positionMs >= times[i]) index = i else break
        }
        val text = lines.getOrNull(line).orEmpty()
        while (index > 0 && index < text.length && !text[index].isLetterOrDigit()) index--
        return index
    }
}

/** 一场法会：若干段落按仪轨次第排列。新增法会只需新增一个同结构的 JSON。 */
data class Ceremony(
    val id: String,
    val order: Int = 0,
    val title: String,
    val subtitle: String,
    val source: String,
    val segments: List<Segment>,
) {
    val totalMs: Long get() = segments.sumOf { it.durationMs.toLong() }
    val totalFrames: Int get() = segments.sumOf { it.frames }
}

object CeremonyParser {
    fun parse(raw: String): Ceremony {
        val o = JSONObject(raw)
        val arr = o.getJSONArray("segments")
        val list = ArrayList<Segment>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val linesArr = s.getJSONArray("lines")
            val lines = ArrayList<String>(linesArr.length())
            for (k in 0 until linesArr.length()) lines.add(linesArr.getString(k))

            val starts = s.optJSONArray("lineStarts")?.let { arr ->
                List(arr.length()) { arr.getInt(it) }
            }.orEmpty()
            val ends = s.optJSONArray("lineEnds")?.let { arr ->
                List(arr.length()) { arr.getInt(it) }
            }.orEmpty()
            val chars = s.optJSONArray("charStarts")?.let { outer ->
                List(outer.length()) { i ->
                    val inner = outer.getJSONArray(i)
                    List(inner.length()) { k -> inner.getInt(k) }
                }
            }.orEmpty()

            list.add(
                Segment(
                    id = s.getString("id"),
                    no = s.optInt("no", i + 1),
                    title = s.getString("title"),
                    kind = s.optString("kind", ""),
                    audio = s.getString("audio"),
                    features = s.getString("features"),
                    frames = s.getInt("frames"),
                    durationMs = s.getInt("durationMs"),
                    lines = lines,
                    lineStarts = starts,
                    lineEnds = ends,
                    charStarts = chars,
                    hint = s.optString("hint", ""),
                    action = s.optString("action", ""),
                )
            )
        }
        return Ceremony(
            id = o.getString("id"),
            order = o.optInt("order", 0),
            title = o.getString("title"),
            subtitle = o.optString("subtitle", ""),
            source = o.optString("source", ""),
            segments = list,
        )
    }
}
