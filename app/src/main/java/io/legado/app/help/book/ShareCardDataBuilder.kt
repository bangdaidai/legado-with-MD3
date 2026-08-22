package io.legado.app.help.book

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.ShareCardData
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 从 ReadingMemory（阅读记忆）构建分享卡片渲染数据。
 * MD3 的 ReadingMemory 已包含大多数字段（书名/作者/进度/评分/书评/书摘/主角等），
 * 部分仅在 Book 上存在的字段（书源名称/最新章节/类型/编码/最近检查时间等）由 [book] 补全。
 */
object ShareCardDataBuilder {

    private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    suspend fun build(memory: ReadingMemory, book: Book? = null): ShareCardData {
        val totalMs = memory.statTotalReadTime
        val hours = TimeUnit.MILLISECONDS.toHours(totalMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalMs) % 60
        // 展示标签统一走 TagManager（SSOT = kind + customTag，含排除/归一/分组排序），
        // 与书架、书籍信息、阅读记忆页看到的标签一致。此前写死空串导致 {{tags}} 永远空白。
        val displayTags = TagManager.bookDisplayTags(memory.kind, memory.customTag)
        val totalWords = parseTotalWords(memory.wordCount)
        val readWords = memory.statTotalWords.coerceAtLeast(0L)

        return ShareCardData(
            bookName = memory.bookName,
            author = memory.bookAuthor,
            coverUrl = memory.coverUrl.orEmpty(),
            intro = memory.intro.orEmpty(),
            kind = memory.kind.orEmpty(),
            wordCount = memory.wordCount.orEmpty(),
            originName = book?.originName.orEmpty(),
            totalChapterNum = memory.totalChapterNum,
            latestChapterTitle = book?.latestChapterTitle.orEmpty(),
            typeText = book?.let { bookTypeText(it.type) }.orEmpty(),
            charset = book?.charset.orEmpty(),
            // 状态词全项目统一为 未读 / 在读 / 已读 / 弃文，CSS 里的 .status-tag.<状态> 依赖这几个词
            readingStatusText = when {
                memory.abandoned -> "弃文"
                memory.finishReadTime > 0 || memory.progress >= 1f -> "已读"
                memory.progress > 0f -> "在读"
                else -> "未读"
            },
            readingProgress = String.format("%.1f%%", memory.progress * 100),
            readChapters = "${memory.durChapterIndex + 1}/${memory.totalChapterNum}",
            unreadChapters = (memory.totalChapterNum - memory.durChapterIndex - 1).coerceAtLeast(0),
            durChapterTitle = book?.durChapterTitle.orEmpty(),
            totalReadTime = formatReadTime(totalMs),
            totalReadHours = hours,
            totalReadMinutes = minutes,
            readingDays = memory.statReadingDays,
            maxDayReadTime = formatReadTime(memory.statMaxDayReadTime),
            maxDayReadDate = memory.statMaxDayReadDate.orEmpty(),
            totalReadWords = formatWords(readWords),
            remainingWords = formatWords((totalWords - readWords).coerceAtLeast(0L)),
            firstReadTime = formatTime(memory.firstReadTime),
            lastReadTime = formatTime(memory.lastReadTime),
            finishReadTime = formatTime(memory.finishReadTime),
            lastCheckTime = book?.lastCheckTime?.let { formatTime(it) }.orEmpty(),
            lastReadTimeRelative = relativeTime(memory.lastReadTime),
            rating = memory.rating,
            ratingStars = buildStars(memory.rating),
            reviewContent = memory.review.orEmpty(),
            annotationCount = memory.annotationCount,
            latestAnnotation = parseFirstExcerpt(memory.excerptsJson),
            protagonists = memory.protagonistsJson.orEmpty(),
            tags = displayTags.joinToString("、"),
            tagCount = displayTags.size,
            bookSourceName = book?.originName.orEmpty(),
            readTimeRank = "",
        )
    }

    /**
     * 从书签/书摘构建分享卡片数据。
     * bookmark 提供摘录文字和笔记内容，memory 提供书的基本信息和统计，book 补全书源类字段。
     */
    suspend fun buildFromBookmark(
        bookmark: Bookmark,
        memory: ReadingMemory?,
        book: Book? = null,
    ): ShareCardData {
        val base = if (memory != null) build(memory, book) else ShareCardData(
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
     * 从划线笔记（BookMarking）构建分享卡片数据。
     * 原文取 anchorJson.selectedText，笔记取 note，章节取 chapterName。
     */
    suspend fun buildFromMarking(
        marking: BookMarking,
        memory: ReadingMemory?,
        book: Book? = null,
    ): ShareCardData {
        val base = if (memory != null) build(memory, book) else ShareCardData(
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

    private fun formatWords(words: Long): String {
        if (words <= 0) return ""
        return String.format(Locale.getDefault(), "%,d", words)
    }

    /** 书籍总字数是展示用字符串, 常见形如 "123456" / "12.5万字" / "约30万字"，
     *  直接 toLongOrNull() 只有纯数字才成功, 其余一律得到 0。按「万」单位换算, 并容忍前后缀文字。 */
    private fun parseTotalWords(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return 0L
        val number = text.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
        val scale = if (text.contains("万")) 10000 else 1
        return (number * scale).toLong().coerceAtLeast(0L)
    }

    private fun relativeTime(ts: Long): String {
        if (ts <= 0) return ""
        return StringUtils.dateConvert(dateTimeFormat.format(Date(ts)), "yyyy-MM-dd HH:mm:ss")
    }

    private fun bookTypeText(type: Int): String = when {
        type and BookType.video != 0 -> "视频"
        type and BookType.image != 0 -> "图片"
        type and BookType.audio != 0 -> "音频"
        else -> "文字"
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
