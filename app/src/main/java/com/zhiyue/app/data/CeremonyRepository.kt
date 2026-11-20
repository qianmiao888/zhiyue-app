package com.zhiyue.app.data

import android.content.Context
import com.zhiyue.app.model.Ceremony
import com.zhiyue.app.model.CeremonyParser

/** 法会模板全部来自 APK 内置资源，不依赖网络与账号。 */
class CeremonyRepository(private val context: Context) {

    fun listCeremonies(): List<Ceremony> {
        val names = context.assets.list("ceremonies")?.sorted().orEmpty()
        return names.filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching {
                val text = context.assets.open("ceremonies/$name")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                CeremonyParser.parse(text)
            }.getOrNull()
        }.sortedBy { it.order }
    }
}
