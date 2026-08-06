package io.legado.app.help.storage

import io.legado.app.constant.BookType

/**
 * r 格式备份(readdai)中的 ReadSession 实体。仅用于反序列化备份里的 readSession.json，
 * 不参与 Room 持久化，故不标注 @Entity，以免被 Room 当成数据表。
 */
data class RReadSession(
    val id: Long = 0,
    val bookName: String = "",
    val author: String = "",
    val bookUrl: String = "",
    val deviceId: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val duration: Long = 0L,
    val words: Long = 0L,
    val type: Int = BookType.text,
    val durChapterTitle: String = "",
    val coverUrl: String = "",
    val displayName: String = ""
)
