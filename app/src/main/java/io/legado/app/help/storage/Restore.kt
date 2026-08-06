package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightTagRule
import io.legado.app.data.entities.HomepageCustomSet
import io.legado.app.data.entities.HomepageModule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TagGroupRule
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.BookTagRelation
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.entities.TagMapping
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.domain.gateway.AppLocaleGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.ui.book.read.ConfigUpdateAction
import io.legado.app.ui.book.read.ReadConfigUpdateBus
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.SettingsWriter
import io.legado.app.help.config.ThemeConfigStore
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.config.otherConfig.OtherConfig
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isUri
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * 恢复
 */
object Restore : KoinComponent {

    private const val TAG = "Restore"

    suspend fun restore(context: Context, uri: Uri) {
        BackupRestoreLock.withLock {
            LogUtils.d(TAG, "开始恢复备份 uri:$uri")
            val unzipResult = kotlin.runCatching {
                FileUtils.delete(Backup.backupPath)
                if (uri.isContentScheme()) {
                    DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                        ZipUtils.unZipToPath(it, Backup.backupPath)
                    }
                } else {
                    ZipUtils.unZipToPath(File(uri.path!!), Backup.backupPath)
                }
            }.onFailure {
                AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
            }
            if (unzipResult.isSuccess) {
                kotlin.runCatching {
                    restoreUnzipped(Backup.backupPath)
                    LocalConfig.lastBackup = System.currentTimeMillis()
                }.onFailure {
                    appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
                    AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
                }
            }
        }
    }

    suspend fun restoreLocked(path: String) {
        BackupRestoreLock.withLock {
            restoreUnzipped(path)
        }
    }

    internal suspend fun restoreUnzipped(path: String) {
        restore(path)
    }

    private suspend fun restore(path: String) {
        val aes = BackupAES()
        fileToListT<Book>(path, "bookshelf.json")?.let {
            it.forEach { book ->
                book.upType()
            }
            val restorePlan = planBookRestore(
                restoredBooks = it,
                existingBooks = appDb.bookDao.all,
                ignoreLocalBook = BackupConfig.ignoreLocalBook,
                locationStatus = ::localBookLocationStatus,
            )
            restorePlan.booksToUpsert
                .filter { book -> book.isLocal }
                .forEach { book -> book.coverUrl = LocalBook.getCoverPath(book) }
            appDb.runInTransaction {
                if (restorePlan.booksToDelete.isNotEmpty()) {
                    appDb.bookDao.delete(*restorePlan.booksToDelete.toTypedArray())
                }
                if (restorePlan.booksToUpdate.isNotEmpty()) {
                    appDb.bookDao.update(*restorePlan.booksToUpdate.toTypedArray())
                }
                if (restorePlan.booksToInsert.isNotEmpty()) {
                    appDb.bookDao.insert(*restorePlan.booksToInsert.toTypedArray())
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("bookmark")) {
            fileToListT<Bookmark>(path, "bookmark.json")?.let {
                try {
                    appDb.bookmarkDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("bookGroup")) {
            fileToListT<BookGroup>(path, "bookGroup.json")?.let {
                appDb.bookGroupDao.replaceAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("bookSource")) {
            fileToListT<BookSource>(path, "bookSource.json")?.let {
                try {
                    appDb.bookSourceDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            } ?: run {
                val bookSourceFile = File(path, "bookSource.json")
                if (bookSourceFile.exists()) {
                    val json = bookSourceFile.readText()
                    ImportOldData.importOldSource(json)
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("rssSource")) {
            fileToListT<RssSource>(path, "rssSources.json")?.let {
                try {
                    appDb.rssSourceDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("rssStar")) {
            fileToListT<RssStar>(path, "rssStar.json")?.let {
                try {
                    appDb.rssStarDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("replaceRule")) {
            fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
                try {
                    appDb.replaceRuleDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("searchHistory")) {
            fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
                try {
                    appDb.searchKeywordDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("sourceSub")) {
            fileToListT<RuleSub>(path, "sourceSub.json")?.let {
                try {
                    appDb.ruleSubDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("txtTocRule")) {
            fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
                try {
                    appDb.txtTocRuleDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("httpTTS")) {
            fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
                try {
                    appDb.httpTTSDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("dictRule")) {
            fileToListT<DictRule>(path, "dictRule.json")?.let {
                try {
                    appDb.dictRuleDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("keyboardAssists")) {
            fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
                try {
                    appDb.keyboardAssistsDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("homepageModules")) {
            fileToListT<HomepageModule>(path, "homepageModules.json")?.let {
                appDb.homepageModuleDao.replaceAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("homepageCustomSets")) {
            fileToListT<HomepageCustomSet>(path, "homepageCustomSets.json")?.let {
                appDb.homepageCustomSetDao.replaceAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("highlightRule")) {
            fileToListT<HighlightRule>(path, "highlightRule.json")?.let {
                appDb.highlightRuleDao.replaceAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("bookCharacterProfile")) {
            fileToListT<BookCharacterProfile>(path, "bookCharacterProfile.json")?.let {
                appDb.bookKnowledgeDao.insertCharacterProfiles(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("highlightTagRule")) {
            fileToListT<HighlightTagRule>(path, "highlightTagRule.json")?.let {
                appDb.highlightTagRuleDao.replaceAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("tagGroupRule")) {
            fileToListT<TagGroupRule>(path, "tagGroupRule.json")?.let {
                appDb.tagGroupRuleDao.replaceAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("bookTag")) {
            fileToListT<BookTag>(path, "bookTag.json")?.let {
                appDb.bookTagDao.insertAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("bookTagGroup")) {
            fileToListT<BookTagGroup>(path, "bookTagGroup.json")?.let {
                appDb.bookTagGroupDao.insertAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("bookTagRelation")) {
            fileToListT<BookTagRelation>(path, "bookTagRelation.json")?.let {
                appDb.bookTagRelationDao.insertAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("excludedTag")) {
            fileToListT<ExcludedTag>(path, "excludedTag.json")?.let {
                appDb.excludedTagDao.insertAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("tagMapping")) {
            fileToListT<TagMapping>(path, "tagMapping.json")?.let {
                appDb.tagMappingDao.insertAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("readingMemory")) {
            fileToListT<ReadingMemory>(path, "readingMemory.json")?.let {
                appDb.readingMemoryDao.insertAll(it)
            }
        }
        if (BackupConfig.dbIsNotIgnored("readRecord")) {
            // readRecord.json 走兼容 DTO：md 自己的备份字段名是 bookAuthor，r 项目 (readdai) 是 author，
            // 且 r 的备份不含 deviceId。用 DTO 统一接收，兼容两边字段名，deviceId 缺失时补空串，
            // 避免 md 原实体反序列化时因 deviceId=null 触发 NOT NULL 约束、整表恢复失败。
            fileToListT<RReadRecordDto>(path, "readRecord.json")?.let {
                it.forEach { dto ->
                    try {
                        restoreReadRecord(dto.toReadRecord())
                    } catch (_: SQLiteConstraintException) {
                    }
                }
            }
            fileToListT<ReadRecordDetail>(path, "readRecordDetail.json")?.let {
                it.forEach { detail ->
                    try {
                        restoreReadRecordDetail(detail)
                    } catch (_: SQLiteConstraintException) {
                    }
                }
            }
            fileToListT<ReadRecordSession>(path, "readRecordSession.json")?.let {
                it.forEach { session ->
                    try {
                        restoreReadRecordSession(session)
                    } catch (_: SQLiteConstraintException) {
                    }
                }
            }
            // r 项目备份特征：单表 readSession.json 存原始会话流，没有 readRecordDetail/Session.json。
            // 检测到 readSession.json 就额外拆分成 md 的 detail/session 两张表填充汇总/时间线视图；
            // 用 Throwable 兜底，任何异常都不阻断后续恢复步骤。md 自身备份不产 readSession.json，不会误触。
            if (File(path, "readSession.json").exists()) {
                try {
                    fileToListT<RReadSessionDto>(path, "readSession.json")?.let {
                        restoreFromRReadSessions(it)
                    }
                } catch (t: Throwable) {
                    AppLog.put("恢复 r 项目 readSession.json 兼容失败\n${t.localizedMessage}", t)
                }
            }
        }
        if (BackupConfig.dbIsNotIgnored("server")) {
            File(path, "servers.json").takeIf {
                it.exists()
            }?.runCatching {
                var json = readText()
                if (!json.isJsonArray()) {
                    json = aes.decryptStr(json)
                }
                GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                    try {
                        appDb.serverDao.insert(*it.toTypedArray())
                    } catch (_: SQLiteConstraintException) {
                    }
                }
            }?.onFailure {
                AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
            }
        }
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            ACache.get(cacheDir = false).put(DirectLinkUpload.ruleFileName, json)
        }?.onFailure {
            AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it)
        }
        //恢复主题配置
        if (!BackupConfig.ignoreThemeConfig) {
            File(path, ThemeConfigStore.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.copyFileAtomic(this, ThemeConfigStore.configFilePath)
                ThemeConfigStore.upConfig()
            }?.onFailure {
                AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
            }
        }
        File(path, BookCover.configFileName).takeIf {
            it.exists() && !BackupConfig.ignoreCoverConfig
        }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        if (!BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.copyFileAtomic(this, ReadBookConfig.configFilePath)
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.copyFileAtomic(this, ReadBookConfig.shareConfigFilePath)
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            // 两个文件都落地后再整体重读：分开重读会让 shareConfig 的兜底
            // （configList[5]）取到还没被覆盖的旧列表。refresh 顺带发布 state。
            get<ReadStyleGateway>().refresh()
            // refresh 只重建 Compose 侧 state；阅读器开着时渲染层的两份快照（RenderStyle/
            // TipStyle）与已排版内容不会跟着刷新，得走配置总线让 controller 重建并重排。
            // 阅读器没开时无人消费，重开由 ReadView.init 的重建入口兜底。
            ReadConfigUpdateBus.post(
                setOf(
                    ConfigUpdateAction.UpdateBackground,
                    ConfigUpdateAction.UpdateStyle,
                    ConfigUpdateAction.ReloadContent,
                    ConfigUpdateAction.RebuildWholeBookPageIndex,
                )
            )
        }
        // 恢复配置文件 (手动解析 XML，替代反射逻辑)
        val configFile = File(path, "config.xml")
        if (configFile.exists()) {
            try {
                val map = readXmlToMap(configFile)
                if (map.isNotEmpty()) {
                    applyConfigMap(map, aes)
                }
            } catch (e: Exception) {
                AppLog.put("恢复配置 XML 出错\n${e.localizedMessage}", e)
            }
        }

        appCtx.toastOnUi(R.string.restore_success)
        withContext(Main) {
            delay(100)
            get<AppLocaleGateway>().setLanguage(OtherConfig.language)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            ThemeConfigStore.applyDayNight(appCtx)
        }
    }

    private fun localBookLocationStatus(bookUrl: String): LocalBookLocationStatus {
        val uri = bookUrl.takeIf { it.isUri() }?.toUri()
        if (uri?.isContentScheme() == true) {
            // Provider 离线、临时权限问题与文件确实删除无法可靠区分，失败时保守保留记录。
            return kotlin.runCatching {
                if (appCtx.contentResolver.openInputStream(uri)?.use { true } == true) {
                    LocalBookLocationStatus.Available
                } else {
                    LocalBookLocationStatus.Unknown
                }
            }.getOrDefault(LocalBookLocationStatus.Unknown)
        }

        val file = File(uri?.path ?: bookUrl)
        if (file.isFile) return LocalBookLocationStatus.Available
        return when (runCatching { Environment.getExternalStorageState(file) }.getOrNull()) {
            Environment.MEDIA_MOUNTED,
            Environment.MEDIA_MOUNTED_READ_ONLY -> LocalBookLocationStatus.Missing

            Environment.MEDIA_UNKNOWN -> LocalBookLocationStatus.Unknown
            null -> LocalBookLocationStatus.Unknown
            else -> LocalBookLocationStatus.Unknown
        }
    }

    private suspend fun restoreReadRecord(readRecord: ReadRecord) {
        val existing = appDb.readRecordDao.getReadRecord(
            readRecord.deviceId,
            readRecord.bookName,
            readRecord.bookAuthor
        )
        appDb.readRecordDao.insert(
            existing?.copy(
                readTime = maxOf(existing.readTime, readRecord.readTime),
                lastRead = maxOf(existing.lastRead, readRecord.lastRead)
            ) ?: readRecord
        )
    }

    private suspend fun restoreReadRecordDetail(detail: ReadRecordDetail) {
        val existing = appDb.readRecordDao.getDetail(
            detail.deviceId,
            detail.bookName,
            detail.bookAuthor,
            detail.date
        )
        appDb.readRecordDao.insertDetail(
            existing?.copy(
                readTime = maxOf(existing.readTime, detail.readTime),
                readWords = maxOf(existing.readWords, detail.readWords),
                firstReadTime = minPositive(existing.firstReadTime, detail.firstReadTime),
                lastReadTime = maxOf(existing.lastReadTime, detail.lastReadTime)
            ) ?: detail
        )
    }

    private suspend fun restoreReadRecordSession(session: ReadRecordSession) {
        val existing = appDb.readRecordDao.getSession(
            session.deviceId,
            session.bookName,
            session.bookAuthor,
            session.startTime,
            session.endTime
        )
        if (existing == null) {
            appDb.readRecordDao.insertSession(session)
        }
    }

    private fun minPositive(left: Long, right: Long): Long {
        return when {
            left <= 0L -> right
            right <= 0L -> left
            else -> minOf(left, right)
        }
    }

    private suspend fun applyConfigMap(map: Map<String, Any?>, aes: BackupAES) {
        val finalMap = normalizeConfigMap(
            map = map,
            keyIsNotIgnore = { BackupConfig.keyIsNotIgnore(it) },
            decryptWebDavPassword = { runCatching { aes.decryptStr(it) }.getOrNull() },
            hasLocalWebDavPassword = !appCtx.getPrefString(PreferKey.webDavPassword)
                .isNullOrBlank(),
        )
        // 经快照层批量恢复：立即对读侧生效（onRestoreFinish 的读取不再依赖回灌时机），单次原子 edit 落盘
        AppConfigStore.putAll(finalMap)
        // 恢复完成提示前等待落盘，dataStore.edit 返回即持久化完成
        SettingsWriter.awaitPendingWrites()
    }

    private fun readXmlToMap(file: File): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            FileInputStream(file).use { fis ->
                parser.setInput(fis, "UTF-8")
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        val name = parser.getAttributeValue(null, "name")
                        if (name != null) {
                            when (tagName) {
                                "string" -> map[name] = parser.nextText()
                                "int" -> map[name] = parser.getAttributeValue(null, "value").toInt()
                                "long" -> map[name] = parser.getAttributeValue(null, "value").toLong()
                                "float" -> map[name] = parser.getAttributeValue(null, "value").toFloat()
                                "boolean" -> map[name] = parser.getAttributeValue(null, "value").toBoolean()
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

    /**
     * readRecord.json 的兼容 DTO。兼容 md（bookAuthor/deviceId/bookType）与
     * r 项目（author，且无 deviceId/bookType）两种字段命名，缺失字段用安全默认值。
     */
    private data class RReadRecordDto(
        val deviceId: String? = null,
        val bookName: String = "",
        val bookAuthor: String? = null,
        val author: String? = null,
        val readTime: Long = 0L,
        val lastRead: Long = 0L,
        val bookType: Int = io.legado.app.constant.BookType.text
    ) {
        fun toReadRecord(): ReadRecord = ReadRecord(
            deviceId = deviceId ?: "",
            bookName = bookName,
            bookAuthor = bookAuthor ?: author ?: "",
            readTime = readTime,
            lastRead = lastRead,
            bookType = bookType
        )
    }

    /**
     * r 项目 (readdai) readSession.json 里每条 ReadSession 的 DTO。
     * 只保留恢复必需的字段，字段名严格对齐 r 项目的 JSON 输出（author / type）。
     */
    private data class RReadSessionDto(
        val bookName: String = "",
        val author: String = "",
        val startTime: Long = 0L,
        val endTime: Long = 0L,
        val words: Long = 0L,
        val type: Int = io.legado.app.constant.BookType.text
    )

    /**
     * 用 r 项目 readSession.json 的原始会话流合成 md 的三张表：
     * - readRecordSession：一条会话一行（deviceId 置空以匹配时间线视图 deviceId='' 的过滤）
     * - readRecordDetail：按 (bookName, author, 日期) 聚合，供汇总视图使用
     * - readRecord：按 (bookName, author) 聚合，供最新/时长视图使用
     * 复用已有的 restoreXxx 幂等 upsert 语义，重复导入不会重复累加。
     */
    private suspend fun restoreFromRReadSessions(dtos: List<RReadSessionDto>) {
        val valid = dtos.filter { it.startTime > 0L && it.endTime > it.startTime }
        if (valid.isEmpty()) return
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        valid.forEach { dto ->
            try {
                restoreReadRecordSession(
                    ReadRecordSession(
                        deviceId = "",
                        bookName = dto.bookName,
                        bookAuthor = dto.author,
                        startTime = dto.startTime,
                        endTime = dto.endTime,
                        words = dto.words,
                        bookType = dto.type
                    )
                )
            } catch (_: SQLiteConstraintException) {
            }
        }

        valid.groupBy { Triple(it.bookName, it.author, dateFormat.format(java.util.Date(it.startTime))) }
            .forEach { (key, group) ->
                try {
                    restoreReadRecordDetail(
                        ReadRecordDetail(
                            deviceId = "",
                            bookName = key.first,
                            bookAuthor = key.second,
                            date = key.third,
                            readTime = group.sumOf { it.endTime - it.startTime },
                            readWords = group.sumOf { it.words },
                            firstReadTime = group.minOf { it.startTime },
                            lastReadTime = group.maxOf { it.endTime },
                            bookType = group.first().type
                        )
                    )
                } catch (_: SQLiteConstraintException) {
                }
            }

        valid.groupBy { it.bookName to it.author }
            .forEach { (key, group) ->
                try {
                    restoreReadRecord(
                        ReadRecord(
                            deviceId = "",
                            bookName = key.first,
                            bookAuthor = key.second,
                            readTime = group.sumOf { it.endTime - it.startTime },
                            lastRead = group.maxOf { it.endTime },
                            bookType = group.first().type
                        )
                    )
                } catch (_: SQLiteConstraintException) {
                }
            }
    }

}
