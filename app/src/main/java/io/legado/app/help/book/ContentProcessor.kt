package io.legado.app.help.book

import android.os.Build
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.spaceRegex
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookContentProcess
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.BookContentProcessEngine
import io.legado.app.domain.model.TextProcessAction
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.domain.model.TextProjection
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.feature.reader.core.source.ReaderLocalPresentation
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.escapeRegex
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.replace
import io.legado.app.utils.replaceMapped
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ContentProcessor private constructor(
    private val bookName: String,
    private val bookOrigin: String
) {

    companion object {
        private val processors = hashMapOf<String, WeakReference<ContentProcessor>>()
        private val isAndroid8 = Build.VERSION.SDK_INT in 26..27
        private val revision = AtomicLong()
        private val ruleRevision = AtomicLong()
        private val bookRevisions = ConcurrentHashMap<String, Long>()
        private val wholeBookRevisions = ConcurrentHashMap<String, Long>()
        private val chapterRevisions = ConcurrentHashMap<Pair<String, Int>, Long>()
        private val specialImageRegex = Regex(
            """<img\b(?:"[^"]*"|'[^']*'|[^'">])*>""",
            RegexOption.IGNORE_CASE,
        )
        private val specialNewPageRegex = Regex("""(?m)^[ \t\u3000]*\[newpage][ \t\u3000]*$""")
        val rulesRevision: Long get() = ruleRevision.get()

        fun semanticRevision(bookUrl: String): Long = bookRevisions[bookUrl] ?: 0

        fun chapterRevision(bookUrl: String, chapterIndex: Int): Long = maxOf(
            wholeBookRevisions[bookUrl] ?: 0,
            chapterRevisions[bookUrl to chapterIndex] ?: 0,
        )

        fun invalidateSemanticContent(bookUrl: String, chapterIndex: Int? = null) {
            val next = revision.incrementAndGet()
            if (chapterIndex == null) {
                wholeBookRevisions.merge(bookUrl, next) { a, b -> maxOf(a, b) }
                // 整书修订取代章修订，移除旧项避免随阅读历史无限增长。
                chapterRevisions.keys.removeIf { it.first == bookUrl }
            } else {
                chapterRevisions.merge(bookUrl to chapterIndex, next) { a, b -> maxOf(a, b) }
            }
            bookRevisions.merge(bookUrl, next) { a, b -> maxOf(a, b) }
        }

        fun get(book: Book) = get(book.name, book.origin)

        @Synchronized
        fun get(bookName: String, bookOrigin: String): ContentProcessor {
            val processorWr = processors[bookName + bookOrigin]
            var processor: ContentProcessor? = processorWr?.get()
            if (processor == null) {
                processor = ContentProcessor(bookName, bookOrigin)
                processors[bookName + bookOrigin] = WeakReference(processor)
            }
            return processor
        }

        @Synchronized
        fun upReplaceRules() {
            processors.forEach {
                it.value.get()?.loadReplaceRules()
            }
            // 批量重载只发布一次修订，避免每个处理器重复触发缓存失效。
            ruleRevision.incrementAndGet()
        }

        private fun specialPlaceholder(index: Int): String =
            "\uE000LEGADO_SPECIAL_${index}\uE001"
    }

    private data class ProtectedSpecials(
        val token: SpecialContentProtector.Protected,
        val originals: Map<String, String>,
    )

    // 整表重读后原子换引用：读者只会看到旧快照或新快照，不会看到清空后的中间态。
    @Volatile
    private var titleReplaceRules: List<ReplaceRule> = emptyList()
    @Volatile
    private var contentReplaceRules: List<ReplaceRule> = emptyList()
    val removeSameTitleCache = hashSetOf<String>()

    private val otherGateway by lazy { GlobalContext.get().get<OtherSettingsGateway>() }
    private val readGateway by lazy { GlobalContext.get().get<ReadSettingsGateway>() }

    init {
        loadReplaceRules()
        upRemoveSameTitle()
    }

    @Synchronized
    fun upReplaceRules() {
        loadReplaceRules()
        ruleRevision.incrementAndGet()
    }

    private fun loadReplaceRules() {
        val titleRules = appDb.replaceRuleDao.findEnabledByTitleScope(bookName, bookOrigin)
        val contentRules = appDb.replaceRuleDao.findEnabledByContentScope(bookName, bookOrigin)
        titleReplaceRules = titleRules
        contentReplaceRules = contentRules
    }

    private fun upRemoveSameTitle() {
        val book = appDb.bookDao.getBookByOrigin(bookName, bookOrigin) ?: return
        removeSameTitleCache.clear()
        val files = BookHelp.getChapterFiles(book).filter {
            it.endsWith("nr")
        }
        removeSameTitleCache.addAll(files)
    }

    fun getTitleReplaceRules(): List<ReplaceRule> {
        return titleReplaceRules
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun getContentReplaceRules(): List<ReplaceRule> {
        return contentReplaceRules
    }

    data class SettingsSnapshot(
        val chineseConverterType: Int,
        val adaptSpecialStyle: Boolean,
        val replaceEnableDefault: Boolean,
    )

    /** 请求开始时捕获一次设置：请求中途用户改设置不影响本次正文的处理口径。 */
    fun captureSettings(): SettingsSnapshot {
        val read = readGateway.currentSettings
        return SettingsSnapshot(read.chineseConverterType, read.adaptSpecialStyle,
            otherGateway.currentSettings.replaceEnableDefault)
    }

    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true,
    ): BookContent = getContent(book, chapter, content, includeTitle, useReplace, chineseConvert,
        reSegment, paragraphIndent = null)

    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true,
        paragraphIndent: String?,
        settings: SettingsSnapshot = captureSettings(),
        includePresentation: Boolean = false,
    ): BookContent = getContent(book, chapter, content, includeTitle, useReplace, chineseConvert,
        reSegment, paragraphIndent, settings, includePresentation, localPresentationProvider = null)

    internal fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true,
        paragraphIndent: String?,
        settings: SettingsSnapshot = captureSettings(),
        includePresentation: Boolean = false,
        localPresentationProvider: (() -> ReaderLocalPresentation?)?,
    ): BookContent {
        val indent = paragraphIndent ?: ReadBookConfig.paragraphIndent
        val canonicalInput = content
        var presentation = if (includePresentation && !includeTitle && settings.adaptSpecialStyle &&
            (book.isEpub || book.isMobi || book.isMarkdown)) {
            try {
                if (localPresentationProvider != null) {
                    localPresentationProvider()
                } else {
                    io.legado.app.model.localBook.LocalBook.getPresentation(book, chapter)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.put("本地呈现读取失败: chapter=${chapter.index}, ${error.javaClass.simpleName}")
                null
            }
        } else {
            null
        }
        val originalPresentation = presentation
        presentation = presentation?.bind(canonicalInput)
        if (originalPresentation != null && presentation == null) {
            AppLog.put("本地呈现来源不匹配: chapter=${chapter.index}")
        }
        var projection = presentation?.let { TextProjection(canonicalInput.length) }
        var mContent = canonicalInput

        fun removeTitle(end: Int) {
            projection?.replace(0, end, 0)
            mContent = mContent.substring(end)
            val map = projection
            if (map != null) {
                presentation = presentation?.let { original ->
                    val starts = original.chapterStartAnchors +
                        original.anchors.filterValues { it < end }.keys
                    original.copy(chapterStartAnchors = starts)
                        .remapOrNull(map, mContent, keepDeletedAnchors = true)
                }
            }
            projection = presentation?.let { TextProjection(mContent.length) }
        }

        var sameTitleRemoved = false
        var effectiveReplaceRules: ArrayList<ReplaceRule>? = null
        var effectiveContentProcesses: List<BookContentProcess> = emptyList()
        if (content != "null") {
            // 去除重复标题；标题改写后的形式也必须能匹配正文开头。
            val fileName = chapter.getFileName("nr")
            if (!removeSameTitleCache.contains(fileName)) try {
                val name = Regex.escape(book.name)
                var title = chapter.title.escapeRegex().replace(spaceRegex, "\\\\s*")
                var match = if (chapter.title.isNotBlank()) {
                    Regex("^(\\s|\\p{P}|${name})*${title}(\\s)*").find(mContent)
                } else {
                    null
                }
                if (match != null) {
                    removeTitle(match.range.last + 1)
                    sameTitleRemoved = true
                } else if (useReplace && book.getUseReplaceRuleToc(settings.replaceEnableDefault)) {
                    val replacedTitle = chapter.getDisplayTitle(
                        getTitleReplaceRules(),
                        chineseConvert = false,
                        chineseConverterType = settings.chineseConverterType,
                    )
                    // 空标题会生成零长度正则并误删正文首段，必须先判空。
                    if (replacedTitle.isNotBlank()) {
                        title = Regex.escape(replacedTitle)
                        match = Regex("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                            .find(mContent)
                        if (match != null) {
                            removeTitle(match.range.last + 1)
                            sameTitleRemoved = true
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.put("去除重复标题出错\n${e.localizedMessage}", e)
            }
            if (reSegment && book.getReSegment()) {
                mContent = if (projection != null) {
                    ContentHelp.reSegmentMapped(mContent, chapter.title, projection)
                } else if (settings.adaptSpecialStyle) {
                    MixedContentSegments.transform(
                        mContent,
                        plain = { ContentHelp.reSegment(it, chapter.title) },
                        rich = { it },
                    )
                } else {
                    ContentHelp.reSegment(mContent, chapter.title)
                }
            }
            if (chineseConvert) {
                try {
                    val convert: ((String) -> String)? = when (settings.chineseConverterType) {
                        1 -> ChineseUtils::t2s
                        2 -> ChineseUtils::s2t
                        else -> null
                    }
                    if (convert != null) {
                        mContent = if (projection != null) {
                            MixedContentSegments.transformPlainTextMapped(mContent, projection) { text, map ->
                                ChineseUtils.convertMapped(text, settings.chineseConverterType, map)
                            }
                        } else {
                            MixedContentSegments.transform(
                                mContent,
                                plain = { MixedContentSegments.transformPlainText(it, convert) },
                                rich = { MixedContentSegments.transformHtmlText(it, convert) },
                            )
                        }
                    }
                } catch (_: Exception) {
                    appCtx.toastOnUi("简繁转换出错")
                }
            }
            if (useReplace && book.getUseReplaceRule(settings.replaceEnableDefault)) {
                val replaceBook = book.toSearchBook()
                effectiveReplaceRules = arrayListOf()
                fun trimLines(text: String) = text.lines().joinToString("\n") { it.trim() }
                val beforeTrim = mContent
                mContent = if (settings.adaptSpecialStyle) {
                    if (projection != null) {
                        transformOutsideRichMapped(mContent, projection) { text, map ->
                            val trimmed = trimLines(text)
                            map?.let { check(it.bindWhitespace(text, trimmed)) }
                            trimmed
                        }
                    } else {
                        MixedContentSegments.transform(
                            mContent,
                            plain = ::trimLines,
                            rich = { it },
                        )
                    }
                } else {
                    trimLines(mContent)
                }
                projection?.let { check(it.bindWhitespace(beforeTrim, mContent)) }

                // 图片与分页标记先替换为占位符；有呈现投影时同步重建来源映射。
                val specials = protectSpecialContent(mContent, projection)
                mContent = specials.token.content
                getContentReplaceRules().forEach { item ->
                    if (item.pattern.isEmpty()) return@forEach
                    try {
                        val ruleProjection = projection
                        val started = System.nanoTime()
                        val timeout = item.getValidTimeoutMillisecond()

                        fun replaceText(text: String, map: TextProjection?): String = if (item.isRegex) {
                            val remaining = timeout - (System.nanoTime() - started) / 1_000_000
                            if (remaining <= 0) {
                                throw RegexTimeoutException("替换规则超时: ${item.name}")
                            }
                            val edits = mutableListOf<TextProjection.Edit>()
                            val result = text.replace(
                                item.regex,
                                item.replacement,
                                remaining,
                                chapter,
                                replaceBook,
                                onReplace = if (map == null) null else { start, end, count ->
                                    edits += TextProjection.Edit(start, end, count)
                                },
                            )
                            map?.replace(edits)
                            result
                        } else {
                            text.replaceMapped(item.pattern, item.replacement, map)
                        }

                        // 无投影时只替换富文本标签外的正文并保留全部属性；有投影时
                        // 跳过富文本段，避免逐节点改写破坏呈现锚点。
                        fun replaceRichText(html: String): String =
                            if (ruleProjection != null) html
                            else MixedContentSegments.transformPlainText(html) { replaceText(it, null) }

                        val tmp = if (settings.adaptSpecialStyle) {
                            if (ruleProjection != null) {
                                transformOutsideRichMapped(mContent, ruleProjection, ::replaceText)
                            } else {
                                MixedContentSegments.transform(
                                    mContent,
                                    plain = { replaceText(it, null) },
                                    rich = ::replaceRichText,
                                )
                            }
                        } else {
                            replaceText(mContent, ruleProjection)
                        }
                        if (mContent != tmp) {
                            effectiveReplaceRules.add(item)
                            mContent = tmp
                        }
                    } catch (e: RegexTimeoutException) {
                        item.isEnabled = false
                        appDb.replaceRuleDao.update(item)
                        mContent = item.name + e.stackTraceStr
                        projection = null
                        presentation = null
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        AppLog.put("替换净化: 规则 ${item.name}替换出错.\n${mContent}", e)
                        appCtx.toastOnUi("替换净化: 规则 ${item.name}替换出错")
                    }
                }
                mContent = restoreSpecialContent(mContent, specials, projection)
            }
            val contentProcesses = appDb.bookContentProcessDao.getForChapterSync(
                bookUrl = book.bookUrl,
                chapterIndex = chapter.index,
            )
            // 用户划线/高亮笔记独立存于 book_marks，渲染时转成合成正文处理记录。
            val markings = appDb.bookMarkingDao.getForChapterSync(
                bookUrl = book.bookUrl,
                chapterIndex = chapter.index,
            ).map { it.toRenderProcess() }
            if (contentProcesses.isNotEmpty() || markings.isNotEmpty()) {
                val original = mContent
                val editInput = if (contentProcesses.any {
                        it.enabled && it.status == BookContentProcess.STATUS_ACTIVE &&
                            it.stage == BookContentProcess.STAGE_CONTENT &&
                            it.kind != BookContentProcess.KIND_USER_HIGHLIGHT &&
                            it.kind != BookContentProcess.KIND_USER_UNDERLINE
                    }) {
                    MixedContentSegments.toPlainText(mContent)
                } else {
                    mContent
                }
                if (editInput != original) {
                    projection = null
                    presentation = null
                }
                val applyResult = BookContentProcessEngine.apply(
                    editInput,
                    contentProcesses + markings,
                    onReplace = if (projection == null) null else { start, end, replacement ->
                        projection?.replace(start, end, replacement.length)
                    },
                )
                val edited = applyResult.effectiveProcesses.any {
                    it.kind != BookContentProcess.KIND_USER_HIGHLIGHT &&
                        it.kind != BookContentProcess.KIND_USER_UNDERLINE
                }
                mContent = if (editInput != original && !edited) original else applyResult.text
                effectiveContentProcesses = applyResult.effectiveProcesses
            }
        }
        if (includeTitle) {
            // 目录标题保留 fork 独立替换开关，并沿用本次请求的设置快照。
            mContent = chapter.getDisplayTitle(
                getTitleReplaceRules(),
                useReplace = useReplace && book.getUseReplaceRuleToc(settings.replaceEnableDefault),
                chineseConverterType = settings.chineseConverterType,
            ) + "\n" + mContent
        }
        if (isAndroid8) {
            mContent = if (projection != null) {
                mContent.replaceMapped("\u00A0", " ", projection)
            } else {
                mContent.replace('\u00A0', ' ')
            }
        }
        val contents = MixedContentSegments.paragraphs(
            mContent,
            indent,
            settings.adaptSpecialStyle,
            firstUnindented = includeTitle,
        )
        projection?.let { map ->
            val transport = if (contents.isEmpty()) "" else contents.joinToString("\n", postfix = "\n")
            if (map.bindWhitespace(mContent, transport)) {
                val remapped = presentation?.remapOrNull(map, transport)
                if (remapped == null && presentation != null) {
                    AppLog.put("本地呈现分段重映射失败: chapter=${chapter.index}")
                }
                presentation = remapped
            } else {
                AppLog.put("本地呈现分段映射失败: chapter=${chapter.index}")
                presentation = null
            }
        }
        return BookContent(
            sameTitleRemoved,
            contents,
            effectiveReplaceRules,
            effectiveContentProcesses,
            presentation,
        )
    }

    /** 只变换普通段，富文本外壳原样保留；每段使用切片投影，避免局部索引写到全局映射。 */
    private fun transformOutsideRichMapped(
        content: String,
        projection: TextProjection,
        transform: (String, TextProjection?) -> String,
    ): String {
        val parts = mutableListOf<TextProjection>()
        var cursor = 0
        val result = buildString {
            AppPattern.useHtmlRegex.findAll(content).forEach { match ->
                val before = projection.slice(cursor, match.range.first)
                append(transform(content.substring(cursor, match.range.first), before))
                parts += before
                append(match.value)
                parts += projection.slice(match.range.first, match.range.last + 1)
                cursor = match.range.last + 1
            }
            val tail = projection.slice(cursor, content.length)
            append(transform(content.substring(cursor), tail))
            parts += tail
        }
        projection.join(parts)
        return result
    }

    /** 复用上游保护器，并把占位符与原 token 的来源范围同步到 TextProjection。 */
    private fun protectSpecialContent(
        content: String,
        projection: TextProjection?,
    ): ProtectedSpecials {
        val token = SpecialContentProtector.protect(content)
        val replacements = linkedMapOf<IntRange, String>()
        val imageRanges = specialImageRegex.findAll(content).map { it.range }.toList()
        imageRanges.forEach { range ->
            replacements[range] = specialPlaceholder(replacements.size)
        }
        var imageIndex = 0
        val afterImages = specialImageRegex.replace(content) {
            specialPlaceholder(imageIndex++)
        }
        val protectedPageBreaks = specialNewPageRegex.findAll(afterImages).toList()
        val originalPageBreaks = specialNewPageRegex.findAll(content).filter { match ->
            imageRanges.none { match.range.first in it }
        }.toList()
        check(protectedPageBreaks.size == originalPageBreaks.size)
        protectedPageBreaks.zip(originalPageBreaks).forEach { (protected, original) ->
            replacements[original.range] = specialPlaceholder(replacements.size)
        }
        val sorted = replacements.toSortedMap(compareBy { it.first })
        val originals = sorted.entries.associate { (range, placeholder) ->
            placeholder to content.substring(range)
        }
        val expected = buildString {
            var cursor = 0
            sorted.forEach { (range, placeholder) ->
                append(content, cursor, range.first)
                append(placeholder)
                cursor = range.last + 1
            }
            append(content, cursor, content.length)
        }
        check(token.content == expected)
        if (projection != null) {
            val pieces = mutableListOf<TextProjection.Piece>()
            var cursor = 0
            sorted.forEach { (range, placeholder) ->
                pieces += TextProjection.Piece(cursor, range.first)
                pieces += TextProjection.Piece(range.first, range.last + 1, placeholder.length)
                cursor = range.last + 1
            }
            pieces += TextProjection.Piece(cursor, content.length)
            projection.rebuild(pieces)
        }
        return ProtectedSpecials(token, originals)
    }

    private fun restoreSpecialContent(
        content: String,
        specials: ProtectedSpecials,
        projection: TextProjection?,
    ): String {
        if (projection == null) return specials.token.restore(content)
        var restored = content
        specials.originals.forEach { (placeholder, original) ->
            restored = restored.replaceMapped(placeholder, original, projection)
        }
        return restored
    }

    /**
     * 把用户划线/高亮笔记转成「仅供渲染」的合成正文处理记录。
     *
     * 引擎对标记类不改文本，只按锚点能否解析决定是否计入 effectiveProcesses；
     * 渲染层用 anchorJson + styleJson 画线。id 加 mark: 前缀避免与真实记录冲突。
     */
    private fun BookMarking.toRenderProcess(): BookContentProcess {
        val style = styleJson?.let { GSON.fromJsonObject<TextProcessStyle>(it).getOrNull() }
        val derivedKind = if (style?.underlineMode != 0) {
            BookContentProcess.KIND_USER_UNDERLINE
        } else {
            BookContentProcess.KIND_USER_HIGHLIGHT
        }
        return BookContentProcess(
            id = "mark:$id",
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            kind = derivedKind,
            stage = BookContentProcess.STAGE_CONTENT,
            target = BookContentProcess.TARGET_SELECTION,
            anchorJson = anchorJson,
            actionJson = GSON.toJson(TextProcessAction(TextProcessAction.TYPE_MARK)),
            styleJson = styleJson,
            source = BookContentProcess.SOURCE_USER,
            enabled = enabled,
            status = BookContentProcess.STATUS_ACTIVE,
            sortOrder = 0,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
