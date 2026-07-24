package io.legado.app.constant

/**
 * 阅读状态枚举类
 * 定义书籍的阅读状态
 */
enum class ReadingStatus(val value: Int, val displayName: String) {
    /**
     * 待看状态
     */
    PENDING(0, "待看"),

    /**
     * 在看状态
     */
    READING(1, "在看"),

    /**
     * 看完状态
     */
    FINISHED(2, "看完"),

    /**
     * 弃状态
     */
    ABANDONED(3, "弃");

    companion object {
        /**
         * 根据值获取对应的阅读状态
         */
        fun fromValue(value: Int): ReadingStatus {
            return values().find { it.value == value } ?: PENDING
        }

        /**
         * 根据显示名称获取对应的阅读状态
         */
        fun fromDisplayName(displayName: String): ReadingStatus {
            return values().find { it.displayName == displayName } ?: PENDING
        }

        /**
         * 获取所有状态的显示名称列表
         */
        fun getAllDisplayNames(): List<String> {
            return values().map { it.displayName }
        }
    }
}
