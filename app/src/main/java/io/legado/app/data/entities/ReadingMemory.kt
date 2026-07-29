package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 阅读记忆 — 单本书独立快照。
 * 在架时封面/简介/标签/阅读数据以 Book/Bookmark/BookCharacterProfile/ReadRecordSession 为唯一源；
 * 快照字段仅在书籍从书架删除时写入，作为删除后页面展示回退。
 */
@Entity(tableName = "readingMemory")
data class ReadingMemory(
    @PrimaryKey
    val bookUrl: String = "",

    /* 展示核心（书架删除后仍需显示） */
    val bookName: String = "",
    val bookAuthor: String = "",
    val coverUrl: String? = null,
    /** 缓存简介，在架以 Book.intro 为准，删除后回落此处 */
    val intro: String? = null,
    /** 用户是否手动修改过简介 */
    @ColumnInfo(defaultValue = "0")
    val userModifiedIntro: Boolean = false,
    /** 书源分类字段，用于生成标签 */
    val kind: String? = null,
    /** 用户自定义标签，删除书架后仍保留 */
    val customTag: String? = null,
    /** 字数字符串展示 */
    val wordCount: String? = null,
    /** Book.type */
    val type: Int = 0,
    /** 阅读进度 (0f..1f) */
    @ColumnInfo(defaultValue = "0.0")
    val progress: Float = 0f,
    /** 总章节数 */
    @ColumnInfo(defaultValue = "0")
    val totalChapterNum: Int = 0,
    /** 当前章节索引 */
    @ColumnInfo(defaultValue = "0")
    val durChapterIndex: Int = 0,
    /** 当前章节阅读位置 */
    @ColumnInfo(defaultValue = "0")
    val durChapterPos: Int = 0,

    /* 用户可编辑（始终以 memory 为源） */
    /** 五星评分 0..5 */
    @ColumnInfo(defaultValue = "0.0")
    val rating: Float = 0f,
    /** 书评文本 */
    val review: String? = null,
    /** 弃文覆盖标记 */
    @ColumnInfo(defaultValue = "0")
    val abandoned: Boolean = false,

    /* 时间戳 */
    val firstReadTime: Long = 0,
    val finishReadTime: Long = 0,
    val lastReadTime: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val createTime: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updateTime: Long = System.currentTimeMillis(),

    /* 统计数据缓存 */
    /** 含笔记的书签条数 */
    @ColumnInfo(defaultValue = "0")
    val annotationCount: Int = 0,

    /* ---- 书架删除时快照字段（在架读实时表，删除回落此处） ---- */
    /** 主角名 JSON 数组 */
    val protagonistsJson: String? = null,
    /** 书摘条目 JSON：[{chapterName,content,bookText}] */
    val excerptsJson: String? = null,
    /** 累计阅读时长 (ms) */
    @ColumnInfo(defaultValue = "0")
    val statTotalReadTime: Long = 0,
    /** 阅读天数 */
    @ColumnInfo(defaultValue = "0")
    val statReadingDays: Int = 0,
    /** 单日最久阅读时长 (ms) */
    @ColumnInfo(defaultValue = "0")
    val statMaxDayReadTime: Long = 0,
    /** 单日最久阅读日期 (yyyy-MM-dd) */
    val statMaxDayReadDate: String? = null,
    /** 阅读总字数 */
    @ColumnInfo(defaultValue = "0")
    val statTotalWords: Long = 0,
) {
    companion object {
        /** 创建最小 stub，确保 readingMemory 表中存在该 bookUrl 的行 */
        fun defaultStub(bookUrl: String) = ReadingMemory(
            bookUrl = bookUrl,
            createTime = System.currentTimeMillis(),
        )
    }
}
