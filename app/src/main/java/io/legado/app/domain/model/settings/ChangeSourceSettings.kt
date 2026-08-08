package io.legado.app.domain.model.settings

import io.legado.app.domain.usecase.ChangeSourceMigrationOptions

data class ChangeSourceSettings(
    val searchScope: String = "",
    val checkAuthor: Boolean = false,
    val loadInfo: Boolean = false,
    val loadToc: Boolean = false,
    val loadWordCount: Boolean = false,
    val filterLowWordCount: Boolean = false,
    val wordCountThreshold: Int = DEFAULT_WORD_COUNT_THRESHOLD,
    val keepOfficialMeta: Boolean = true,
    val migrateChapters: Boolean = true,
    val migrateReadingProgress: Boolean = true,
    val migrateGroup: Boolean = true,
    val migrateCover: Boolean = true,
    val migrateCategory: Boolean = true,
    val migrateRemark: Boolean = true,
    val migrateReadConfig: Boolean = true,
    val deleteDownloadedChapters: Boolean = false,
) {
    fun migrationOptions() = ChangeSourceMigrationOptions(
        migrateChapters = migrateChapters,
        migrateReadingProgress = migrateReadingProgress,
        migrateGroup = migrateGroup,
        migrateCover = migrateCover,
        migrateCategory = migrateCategory,
        migrateRemark = migrateRemark,
        migrateReadConfig = migrateReadConfig,
        deleteDownloadedChapters = deleteDownloadedChapters,
        keepOfficialMeta = keepOfficialMeta,
    )
}

const val DEFAULT_WORD_COUNT_THRESHOLD = 3000
val WordCountThresholdSuggestions = listOf("1000", "2000", "3000", "5000", "10000")
