package io.legado.app.data.entities

/**
 * 分享卡片渲染入参 VO（非 Room 实体）
 * 移植自 readdai `ShareCardData`。40+ 字段涵盖 9 大类：基本信息/进度/统计/日期/评分/书摘/主角/标签/书源。
 */
data class ShareCardData(
    // 基本信息（11）
    val bookName: String = "",
    val author: String = "",
    val coverUrl: String = "",
    val intro: String = "",
    val kind: String = "",
    val wordCount: String = "",
    val originName: String = "",
    val totalChapterNum: Int = 0,
    val latestChapterTitle: String = "",
    val typeText: String = "",
    val charset: String = "",

    // 阅读状态与进度（7）
    val readingStatusText: String = "",
    val readingProgress: String = "",
    val readChapters: String = "",
    val unreadChapters: Int = 0,
    val readIteration: Int = 0,
    val readIterationText: String = "",
    val durChapterTitle: String = "",

    // 阅读统计（8）
    val totalReadTime: String = "",
    val totalReadHours: Long = 0,
    val totalReadMinutes: Long = 0,
    val readingDays: Int = 0,
    val maxDayReadTime: String = "",
    val maxDayReadDate: String = "",
    val totalReadWords: String = "",
    val remainingWords: String = "",

    // 日期时间（6）
    val firstReadTime: String = "",
    val lastReadTime: String = "",
    val finishReadTime: String = "",
    val addBookshelfTime: String = "",
    val lastCheckTime: String = "",
    val lastReadTimeRelative: String = "",

    // 评分书评（4）
    val rating: Float = 0f,
    val ratingStars: String = "",
    val ratingMax: Int = 5,
    val reviewContent: String = "",

    // 书摘想法（5）
    val annotationCount: Int = 0,
    val thoughtCount: Int = 0,
    val latestAnnotation: String = "",
    val latestAnnotationNote: String = "",
    val latestAnnotationChapter: String = "",

    // 主角（1）
    val protagonists: String = "",

    // 标签（2）
    val tags: String = "",
    val tagCount: Int = 0,

    // 书源（2）
    val bookSourceName: String = "",
    val bookSourceGroup: String = "",

    // 全局统计（1）
    val readTimeRank: String = "",
)
