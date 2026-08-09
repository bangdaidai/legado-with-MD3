package io.legado.app.help.book

import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.BookplateData
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 从 ReadingMemory（阅读记忆）构建藏书票渲染数据。
 * MD3 的 ReadingMemory 已包含大多数字段（书名/作者/进度/评分/书评/书摘/主角等）。
 */
object BookplateDataBuilder {

    private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

    fun build(memory: ReadingMemory): BookplateData {
        val totalMs = memory.statTotalReadTime
        val hours = TimeUnit.MILLISECONDS.toHours(totalMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMs) % 60

        return BookplateData(
            bookName = memory.bookName,
            author = memory.bookAuthor,
            coverUrl = memory.coverUrl.orEmpty(),
            intro = memory.intro.orEmpty(),
            kind = memory.kind.orEmpty(),
            wordCount = memory.wordCount.orEmpty(),
            totalChapterNum = memory.totalChapterNum,
            readingProgress = String.format("%.1f%%", memory.progress * 100),
            readingStatusText = if (memory.abandoned) "已弃读" else if (memory.finishReadTime > 0) "已完结" else "阅读中",
            readChapters = "${memory.durChapterIndex + 1}/${memory.totalChapterNum}",
            durChapterTitle = "",
            totalReadTime = formatReadTime(totalMs),
            totalReadHours = hours,
            totalReadMinutes = minutes,
            readingDays = memory.statReadingDays,
            firstReadTime = formatTime(memory.firstReadTime),
            lastReadTime = formatTime(memory.lastReadTime),
            finishReadTime = formatTime(memory.finishReadTime),
            rating = memory.rating,
            ratingStars = buildStars(memory.rating),
            reviewContent = memory.review.orEmpty(),
            annotationCount = memory.annotationCount,
            latestAnnotation = parseFirstExcerpt(memory.excerptsJson),
            protagonists = memory.protagonistsJson.orEmpty(),
            tags = "",
            tagCount = 0,
            readTimeRank = "",
        )
    }

    /**
     * 从书签/书摘构建藏书票数据。
     * bookmark 提供摘录文字和笔记内容，memory 提供书的基本信息和统计。
     */
    fun buildFromBookmark(bookmark: Bookmark, memory: ReadingMemory?): BookplateData {
        val base = if (memory != null) build(memory) else BookplateData(
            bookName = bookmark.bookName,
            author = bookmark.bookAuthor,
        )
        return base.copy(
            latestAnnotation = bookmark.bookText.take(500),
            latestAnnotationNote = bookmark.content.take(500),
            latestAnnotationChapter = bookmark.chapterName,
            annotationCount = if (memory != null) memory.annotationCount else 1,
        )
    }

    /**
     * 从划线笔记（BookMarking）构建藏书票数据。
     * 原文取 anchorJson.selectedText，笔记取 note，章节取 chapterName。
     */
    fun buildFromMarking(marking: BookMarking, memory: ReadingMemory?): BookplateData {
        val base = if (memory != null) build(memory) else BookplateData(
            bookName = marking.bookName,
            author = marking.bookAuthor,
        )
        val selectedText = GSON.fromJsonObject<TextProcessAnchor>(marking.anchorJson)
            .getOrNull()?.selectedText.orEmpty()
        return base.copy(
            latestAnnotation = selectedText.take(500),
            latestAnnotationNote = marking.note.take(500),
            latestAnnotationChapter = marking.chapterName,
            annotationCount = if (memory != null) memory.annotationCount else 1,
        )
    }


    private fun formatTime(ts: Long): String {
        return if (ts > 0) dateFormat.format(Date(ts)) else "____/__/__"
    }

    private fun formatReadTime(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
    }

    private fun buildStars(rating: Float): String {
        val full = rating.toInt()
        val half = if (rating - full >= 0.5f) 1 else 0
        val empty = 5 - full - half
        return "★".repeat(full) + (if (half > 0) "☆" else "") + "☆".repeat(empty)
    }

    private fun parseFirstExcerpt(json: String?): String {
        if (json.isNullOrBlank()) return ""
        // excerptsJson is [{chapterName, content, bookText}] — just grab first bookText
        return try {
            val start = json.indexOf("\"bookText\"")
            if (start < 0) return ""
            val colonIdx = json.indexOf(':', start)
            val quoteStart = json.indexOf('"', colonIdx + 1)
            val quoteEnd = json.indexOf('"', quoteStart + 1)
            if (quoteEnd > quoteStart) json.substring(quoteStart + 1, quoteEnd).take(200) else ""
        } catch (_: Exception) { "" }
    }
}
