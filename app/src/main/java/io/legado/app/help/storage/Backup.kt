package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfigStore
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 备份
 */
object Backup {

    private val readStyleGateway: ReadStyleGateway
        get() = GlobalContext.get().get()

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

    private val backupFileNames by lazy {
        arrayOf(
            "bookshelf.json",
            "bookmark.json",
            "bookGroup.json",
            "bookSource.json",
            "rssSources.json",
            "rssStar.json",
            "replaceRule.json",
            "readRecord.json",
            "readRecordDetail.json",
            "readRecordSession.json",
            "searchHistory.json",
            "sourceSub.json",
            "txtTocRule.json",
            "httpTTS.json",
            "keyboardAssists.json",
            "dictRule.json",
            "homepageModules.json",
            "homepageCustomSets.json",
            "highlightRule.json",
            "highlightTagRule.json",
            "tagGroupRule.json",
            "bookTag.json",
            "bookTagGroup.json",
            "bookTagRelation.json",
            "excludedTag.json",
            "tagMapping.json",
            "readingMemory.json",
            "authorProfiles.json",
            "bookMarking.json",
            "bookCharacterProfile.json",
            "bookCharacterEvents.json",
            "bookCharacterRelations.json",
            "bookKnowledgeEntries.json",
            "bookOutlineNodes.json",
            "bookContentProcesses.json",
            "readAloudVoices.json",
            "bookVoiceBindings.json",
            "aiChatConversations.json",
            "aiChatMessages.json",
            "aiMemory.json",
            "aiArtifacts.json",
            "removedAutoTags.json",
            "cloudTtsEngines.json",
            "title_bar_icons.xml",
            "tool_button_config.xml",
            "servers.json",
            "aiProviders.json",
            "aiModels.json",
            "aiTaskPresets.json",
            "aiPromptPresets.json",
            "shareCardTemplate.json",
            DirectLinkUpload.ruleFileName,
            ReadBookConfig.configFileName,
            ReadBookConfig.shareConfigFileName,
            ThemeConfigStore.configFileName,
            BookCover.configFileName,
            "config.xml"
        )
    }

    /**
     * 主题落在磁盘上的资源目录：已保存主题包、导航图标、字体、主题包解压出的背景/容器图。
     * config.xml 里存的只是指向这些文件的绝对路径，目录不一起打包，恢复后路径就是悬空的
     * （主题色能回来但背景、底栏图标是空的），已保存的主题也会整个丢失。
     * 恢复时按目录名回原位，所以备份与恢复两侧必须共用这份定义。
     */
    internal fun themeAssetDirs(context: Context): List<File> = listOf(
        File(context.filesDir, "saved_themes"),
        File(context.filesDir, "nav_icons"),
        File(context.filesDir, "fonts"),
        File(context.externalFiles, "theme_assets"),
    )

    /**
     * 阅读页背景图目录。用户导入的图片实体存在这里，阅读配置里存的只是文件名或路径。
     * 原先只有 [AppWebDav.upBgs] 把它们单独传到 WebDAV，本地备份包里没有，
     * 纯本地恢复背景图就丢了；现在一并打进 ZIP。
     */
    internal fun readBgDir(context: Context): File = File(context.externalFiles, "bg")

    /**
     * 其余用户资源实体目录：自定义封面、封面相册（含 albums.json 索引）、阅读字体、
     * 高亮规则背景图、阅读菜单/浮动图标自定义图标、人物头像。
     * 这些目录里的文件被数据库行或 config.xml 里的绝对路径引用，不打包恢复后引用就是悬空的。
     * 恢复时按目录名回原位（叶子名互不重复，与 [themeAssetDirs]/[readBgDir] 也不冲突），
     * 所以备份与恢复两侧必须共用这份定义。
     */
    internal fun userAssetDirs(context: Context): List<File> = listOf(
        File(context.externalFiles, "covers"),
        File(context.externalFiles, "cover_albums"),
        File(context.externalFiles, "font"),
        File(context.filesDir, "bg_images"),
        File(context.filesDir, "read_menu_icons"),
        File(context.filesDir, "title_bar_icons"),
        File(context.filesDir, "character_avatars"),
    )

    /**
     * 阅读页按钮/浮动图标配置存在两个独立的 SharedPreferences 里（不在 AppConfigStore，
     * 所以 config.xml 抓不到）。名称与 key 定义在 ReadButtonConfigDelegate，这里按同样的
     * 文件名把 xml 原样打包，恢复时拷回 shared_prefs。
     * 注意：SharedPreferences 实例是进程级缓存的，恢复后需重启应用才会读到新值。
     */
    internal val buttonConfigPrefsFileNames = listOf("title_bar_icons.xml", "tool_button_config.xml")

    internal fun sharedPrefsDir(context: Context): File = File(context.filesDir.parentFile, "shared_prefs")

    /**
     * 书签角标图片。用户选的图按原扩展名存成 filesDir/bookmark_badge.<ext>，
     * 阅读配置里存的是它的绝对路径，扩展名不固定所以按前缀匹配。
     */
    internal fun bookmarkBadgeFiles(dir: File): List<File> =
        dir.listFiles { f: File -> f.isFile && f.name.startsWith("bookmark_badge.") }?.toList().orEmpty()



    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val deviceName = AppConfig.webDavDeviceName
        return if (deviceName?.isNotBlank() == true) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    private fun shouldBackup(): Boolean {
        val lastBackup = LocalConfig.lastBackup
        return lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()
    }

    fun autoBack(context: Context) {
        if (shouldBackup()) {
            Coroutine.async {
                BackupRestoreLock.withLock {
                    if (shouldBackup()) {
                        val backupZipFileName = getNowZipFileName()
                        if (!AppWebDav.hasBackUp(backupZipFileName)) {
                            backup(context, AppConfig.backupPath)
                        } else {
                            LocalConfig.lastBackup = System.currentTimeMillis()
                        }
                    }
                }
            }.onError {
                AppLog.put("自动备份失败\n${it.localizedMessage}")
            }
        }
    }

    suspend fun backupLocked(context: Context, path: String?, mode: String = "both") {
        BackupRestoreLock.withLock {
            withContext(IO) {
                backup(context, path, mode)
            }
        }
    }

    private suspend fun backup(context: Context, path: String?, mode: String = "both") {
        LogUtils.d(TAG, "开始备份 path:$path")
        LocalConfig.lastBackup = System.currentTimeMillis()
        val aes = BackupAES()
        FileUtils.delete(backupPath)
        writeListToJson(
            appDb.bookDao.all.filterNot { BackupConfig.backupIgnoreLocalBook && it.isLocal },
            "bookshelf.json",
            backupPath,
        )
        if (BackupConfig.dbIsNotIgnored("bookmark", true)) {
            writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookGroup", true)) {
            writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookSource", true)) {
            writeListToJson(appDb.bookSourceDao.all, "bookSource.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("rssSource", true)) {
            writeListToJson(appDb.rssSourceDao.all, "rssSources.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("rssStar", true)) {
            writeListToJson(appDb.rssStarDao.all, "rssStar.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("replaceRule", true)) {
            writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("readRecord", true)) {
            writeListToJson(appDb.readRecordDao.all, "readRecord.json", backupPath)
            writeListToJson(appDb.readRecordDao.allDetail, "readRecordDetail.json", backupPath)
            writeListToJson(appDb.readRecordDao.allSession, "readRecordSession.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("searchHistory", true)) {
            writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("sourceSub", true)) {
            writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("txtTocRule", true)) {
            writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("httpTTS", true)) {
            writeListToJson(appDb.httpTTSDao.all, "httpTTS.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("keyboardAssists", true)) {
            writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("dictRule", true)) {
            writeListToJson(appDb.dictRuleDao.all, "dictRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("homepageModules", true)) {
            writeListToJson(appDb.homepageModuleDao.getAll(), "homepageModules.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("homepageCustomSets", true)) {
            writeListToJson(
                appDb.homepageCustomSetDao.getAll(),
                "homepageCustomSets.json",
                backupPath
            )
        }
        if (BackupConfig.dbIsNotIgnored("highlightRule", true)) {
            writeListToJson(appDb.highlightRuleDao.getAll(), "highlightRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookCharacterProfile", true)) {
            writeListToJson(
                appDb.bookKnowledgeDao.getAllCharacterProfilesSync(),
                "bookCharacterProfile.json",
                backupPath,
            )
        }
        if (BackupConfig.dbIsNotIgnored("highlightTagRule", true)) {
            writeListToJson(appDb.highlightTagRuleDao.getAll(), "highlightTagRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("tagGroupRule", true)) {
            writeListToJson(appDb.tagGroupRuleDao.getAll(), "tagGroupRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("shareCardTemplate", true)) {
            writeListToJson(appDb.shareCardTemplateDao.getAll(), "shareCardTemplate.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookTag", true)) {
            writeListToJson(appDb.bookTagDao.getAllSync(), "bookTag.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookTagGroup", true)) {
            writeListToJson(appDb.bookTagGroupDao.getAllSorted(), "bookTagGroup.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookTagRelation", true)) {
            writeListToJson(appDb.bookTagRelationDao.getAllSync(), "bookTagRelation.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("excludedTag", true)) {
            writeListToJson(appDb.excludedTagDao.getAllSync(), "excludedTag.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("tagMapping", true)) {
            writeListToJson(appDb.tagMappingDao.getAll(), "tagMapping.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("readingMemory", true)) {
            writeListToJson(appDb.readingMemoryDao.getAllSync(), "readingMemory.json", backupPath)
        }
        // 划线笔记（book_marks）。无忽略开关，随备份无条件导出。
        writeListToJson(appDb.bookMarkingDao.getAllSync(), "bookMarking.json", backupPath)
        // 作者简介。原先随 settings 备份，搬到 Room 后同样无条件导出。
        writeListToJson(appDb.authorProfileDao.getAllSync(), "authorProfiles.json", backupPath)
        // 以下几张表同样无忽略开关，随备份无条件导出。
        // 人物图谱剩余四张表（人物档案已在上面单独导出）
        writeListToJson(
            appDb.bookKnowledgeDao.getAllCharacterEventsSync(),
            "bookCharacterEvents.json",
            backupPath,
        )
        writeListToJson(
            appDb.bookKnowledgeDao.getAllCharacterRelationsSync(),
            "bookCharacterRelations.json",
            backupPath,
        )
        writeListToJson(
            appDb.bookKnowledgeDao.getAllKnowledgeEntriesSync(),
            "bookKnowledgeEntries.json",
            backupPath,
        )
        writeListToJson(
            appDb.bookKnowledgeDao.getAllOutlineNodesSync(),
            "bookOutlineNodes.json",
            backupPath,
        )
        // 书籍净化/内容处理规则
        writeListToJson(appDb.bookContentProcessDao.getAll(), "bookContentProcesses.json", backupPath)
        // 朗读音色与按书绑定
        writeListToJson(appDb.readAloudVoiceDao.getVoices(), "readAloudVoices.json", backupPath)
        writeListToJson(appDb.readAloudVoiceDao.getAllBindings(), "bookVoiceBindings.json", backupPath)
        // AI 对话记录、记忆、产物
        writeListToJson(appDb.aiChatDao.getAllConversations(), "aiChatConversations.json", backupPath)
        writeListToJson(appDb.aiChatDao.getAllMessages(), "aiChatMessages.json", backupPath)
        writeListToJson(appDb.aiMemoryDao.getAll(), "aiMemory.json", backupPath)
        writeListToJson(appDb.aiArtifactDao.getAll(), "aiArtifacts.json", backupPath)
        // 被用户移除的自动标签（不导出就会在恢复后重新被自动打上）
        writeListToJson(appDb.removedAutoTagDao.getAll(), "removedAutoTags.json", backupPath)
        // 云端 TTS 引擎行里 apiKey/secretKey 是明文存的，和 servers.json 一样先加密再落盘。
        GSON.toJson(appDb.cloudTtsEngineDao.getAll()).let { json ->
            aes.runCatching {
                encryptBase64(json)
            }.getOrDefault(json).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + "cloudTtsEngines.json")
                    .writeText(it)
            }
        }
        // 阅读页按钮/浮动图标配置的两个独立 SharedPreferences，原样拷贝 xml
        buttonConfigPrefsFileNames.forEach { name ->
            val source = File(sharedPrefsDir(context), name)
            if (source.isFile) {
                runCatching {
                    source.copyTo(File(backupPath, name), overwrite = true)
                }.onFailure {
                    AppLog.put("备份 $name 出错\n${it.localizedMessage}", it)
                }
            }
        }

        if (BackupConfig.dbIsNotIgnored("server", true)) {
            GSON.toJson(appDb.serverDao.all).let { json ->
                aes.runCatching {
                    encryptBase64(json)
                }.getOrDefault(json).let {
                    FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                        .writeText(it)
                }
            }
        }
        // AI 设置：服务商/模型/任务预设/提示词预设四张表。
        // 服务商行里 apiKey 是明文存的，和 servers.json 一样先加密再落盘；其余三张表无密钥。
        if (BackupConfig.dbIsNotIgnored("aiConfig", true)) {
            GSON.toJson(appDb.aiProfileDao.getAllProviders()).let { json ->
                aes.runCatching {
                    encryptBase64(json)
                }.getOrDefault(json).let {
                    FileUtils.createFileIfNotExist(backupPath + File.separator + "aiProviders.json")
                        .writeText(it)
                }
            }
            writeListToJson(appDb.aiProfileDao.getAllModels(), "aiModels.json", backupPath)
            writeListToJson(appDb.aiProfileDao.getAllPresets(), "aiTaskPresets.json", backupPath)
            writeListToJson(appDb.aiPromptPresetDao.getAll(), "aiPromptPresets.json", backupPath)
        }
        currentCoroutineContext().ensureActive()
        if (!BackupConfig.backupIgnoreReadConfig) {
            readStyleGateway.exportConfigsJson().let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.configFileName)
                    .writeText(it)
            }
            readStyleGateway.exportShareConfigJson().let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.shareConfigFileName)
                    .writeText(it)
            }
        }
        if (!BackupConfig.backupIgnoreThemeConfig) {
            GSON.toJson(ThemeConfigStore.configList).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfigStore.configFileName)
                    .writeText(it)
            }
        }
        DirectLinkUpload.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + DirectLinkUpload.ruleFileName)
                .writeText(GSON.toJson(it))
        }
        if (!BackupConfig.backupIgnoreCoverConfig) {
            BookCover.getConfig()?.let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                    .writeText(GSON.toJson(it))
            }
        }
        currentCoroutineContext().ensureActive()
        val configMap = AppConfigStore.preferences.asMap()
            .mapKeys { it.key.name }
        val xmlBuilder = StringBuilder()
        xmlBuilder.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        xmlBuilder.append("<map>\n")
        configMap.forEach { (key, value) ->
            if (BackupConfig.keyIsNotIgnore(key, true)) {
                val finalValue = if (key == PreferKey.webDavPassword) {
                    aes.runCatching { encryptBase64(value.toString()) }.getOrDefault(value.toString())
                } else value

                when (finalValue) {
                    is String -> xmlBuilder.append("    <string name=\"$key\">${finalValue.replace("&", "&amp;").replace("<", "&lt;")}</string>\n")
                    is Int -> xmlBuilder.append("    <int name=\"$key\" value=\"$finalValue\" />\n")
                    is Long -> xmlBuilder.append("    <long name=\"$key\" value=\"$finalValue\" />\n")
                    is Float -> xmlBuilder.append("    <float name=\"$key\" value=\"$finalValue\" />\n")
                    is Boolean -> xmlBuilder.append("    <boolean name=\"$key\" value=\"$finalValue\" />\n")
                }
            }
        }
        xmlBuilder.append("</map>")
        FileUtils.createFileIfNotExist(backupPath + File.separator + "config.xml")
            .writeText(xmlBuilder.toString())

        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        val paths = backupFileNames
            .map { File(backupPath, it) }
            .filter(File::isFile)
            .map(File::getAbsolutePath)
            .toMutableList()
        // 资源实体目录直接从原位打包（zipFile 递归目录，条目以目录名为前缀）。
        // 配置里存的只是指向这些文件的路径，不一起打包恢复后就是悬空的。
        // 各自跟随对应的忽略开关：主题资源跟主题配置，背景图跟阅读配置。
        val assetDirs = buildList {
            if (!BackupConfig.backupIgnoreThemeConfig) addAll(themeAssetDirs(context))
            if (!BackupConfig.backupIgnoreReadConfig) add(readBgDir(context))
            // 封面/相册/字体/背景图/自定义图标/头像不归属主题或阅读配置，无条件打包
            addAll(userAssetDirs(context))
        }
        assetDirs.forEach { dir ->
            if (dir.isDirectory && !dir.listFiles().isNullOrEmpty()) {
                paths.add(dir.absolutePath)
            }
        }
        // 书签角标图片是 filesDir 下的单个文件，按前缀取到后直接以文件名进 ZIP 根目录
        bookmarkBadgeFiles(context.filesDir).forEach { paths.add(it.absolutePath) }

        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = if (AppConfig.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        if (ZipUtils.zipFiles(paths, zipFilePath)) {
            if (mode == "both" || mode == "local") {
                when {
                    path.isNullOrBlank() -> {
                        copyBackup(context.getExternalFilesDir(null)!!, backupFileName)
                    }

                    path.isContentScheme() -> {
                        copyBackup(context, path.toUri(), backupFileName)
                    }

                    else -> {
                        copyBackup(File(path), backupFileName)
                    }
                }
            }
            if (mode == "both" || mode == "webdav") {
                try {
                    AppWebDav.backUpWebDav(zipFileName)
                } catch (e: Exception) {
                    AppLog.put("上传备份至webdav失败\n$e", e)
                }
            }
        }
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
        readStyleGateway.allBackgroundImagePaths().map {
            if (it.contains(File.separator)) {
                File(it)
            } else {
                appCtx.externalFiles.getFile("bg", it)
            }
        }.let {
            AppWebDav.upBgs(it.toTypedArray())
        }
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            if (list.isNotEmpty()) {
                LogUtils.d(TAG, "阅读备份 $fileName 列表大小 ${list.size}")
                val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, list)
                }
                LogUtils.d(TAG, "阅读备份 $fileName 写入大小 ${file.length()}")
            } else {
                LogUtils.d(TAG, "阅读备份 $fileName 列表为空")
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(context: Context, uri: Uri, fileName: String) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)!!
        treeDoc.findFile(fileName)?.delete()
        val fileDoc = treeDoc.createFile("", fileName)
            ?: throw NoStackTraceException("创建文件失败")
        val outputS = fileDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        outputS.use {
            FileInputStream(zipFilePath).use { inputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(rootFile: File, fileName: String) {
        FileInputStream(File(zipFilePath)).use { inputS ->
            val file = FileUtils.createFileIfNotExist(rootFile, fileName)
            FileOutputStream(file).use { outputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    fun clearCache() {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
    }
}
