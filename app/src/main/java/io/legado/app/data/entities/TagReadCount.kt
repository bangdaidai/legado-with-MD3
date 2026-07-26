package io.legado.app.data.entities

/**
 * 标签关联书籍计数（查询视图数据类，非数据库表）。
 */
data class TagReadCount(
    val tagName: String = "",
    val readCount: Int = 0,
)
