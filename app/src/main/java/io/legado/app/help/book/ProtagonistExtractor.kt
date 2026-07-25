package io.legado.app.help.book

import java.util.regex.Pattern

/**
 * 主角（人设）提取器 —— 整体移植自 readdai 的 ProtagonistExtractor。
 *
 * readdai 仅从书籍简介中的「主角：xxx」模式提取，本实现保持同构。
 * 提取出的名字由调用方决定如何入库（详情页提供手动新增 / 一键提取）。
 */
object ProtagonistExtractor {

    private val pattern = Pattern.compile("主角[\\s:：]*([\\u4e00-\\u9fa5·]{1,8})")
    private val splitPattern = Pattern.compile("[、，,\\s/]+")

    /**
     * 从书籍简介中提取主角名列表（去重、保持出现顺序）。
     */
    fun extractProtagonists(intro: String?): List<String> {
        if (intro.isNullOrBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        val matcher = pattern.matcher(intro)
        while (matcher.find()) {
            val group = matcher.group(1) ?: continue
            val parts = splitPattern.split(group).filter { it.isNotBlank() }
            result.addAll(parts)
        }
        return result.toList()
    }
}
