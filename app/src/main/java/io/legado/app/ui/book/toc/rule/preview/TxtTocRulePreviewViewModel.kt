package io.legado.app.ui.book.toc.rule.preview

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.ReplaceRuleRepository
import io.legado.app.data.repository.TxtTocRuleRepository
import io.legado.app.domain.model.AiTitleCleanRuleDraft
import io.legado.app.domain.usecase.GenerateTocRuleUseCase
import io.legado.app.help.DefaultData
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.Utf8BomUtils
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.coroutineContext

class TxtTocRulePreviewViewModel(
    private val app: Application,
    private val bookRepository: BookRepository,
    private val repository: TxtTocRuleRepository,
    private val replaceRuleRepository: ReplaceRuleRepository,
    private val generateTocRuleUseCase: GenerateTocRuleUseCase,
) : ViewModel() {

    private val context get() = app.applicationContext

    private val _uiState = MutableStateFlow(TxtTocRulePreviewUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<TxtTocRulePreviewEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private var book: Book? = null
    private var lazyComputeJob: Job? = null
    private var networkCountJob: Job? = null

    /**
     * 入口。本地 TXT 走目录正则规则预览，网络书籍走标题替换规则预览。
     * currentTocRegex 仅本地 TXT 场景使用。
     */
    fun init(bookUrl: String, currentTocRegex: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val loadedBook = runCatching { bookRepository.getBook(bookUrl) }.getOrNull()
            book = loadedBook
            if (loadedBook != null && !loadedBook.isLocalTxt) {
                loadNetworkPreview(loadedBook)
            } else {
                loadRules(bookUrl, currentTocRegex)
            }
        }
    }

    fun onIntent(intent: TxtTocRulePreviewIntent) {
        when (intent) {
            is TxtTocRulePreviewIntent.SelectRule -> {
                _uiState.update { it.copy(selectedRule = intent.rule) }
            }
            is TxtTocRulePreviewIntent.ShowChapterList -> {
                _uiState.update { it.copy(activeSheet = TxtTocRulePreviewSheet.ChapterList(intent.item)) }
            }
            is TxtTocRulePreviewIntent.ShowNetworkRuleChapters -> {
                _uiState.update {
                    it.copy(activeSheet = TxtTocRulePreviewSheet.NetworkRuleChapters(intent.item))
                }
            }
            is TxtTocRulePreviewIntent.DismissSheet -> {
                _uiState.update { it.copy(activeSheet = null) }
            }
            is TxtTocRulePreviewIntent.ToggleLayout -> {
                _uiState.update { it.copy(isGridLayout = !it.isGridLayout) }
            }
            is TxtTocRulePreviewIntent.OpenManagePage -> {
                _effects.tryEmit(TxtTocRulePreviewEffect.OpenManagePage)
            }
            is TxtTocRulePreviewIntent.EditRule -> {
                _uiState.update { it.copy(activeSheet = null, editingRule = intent.rule) }
            }
            is TxtTocRulePreviewIntent.DismissEditDialog -> {
                _uiState.update { it.copy(editingRule = null) }
            }
            is TxtTocRulePreviewIntent.SaveRule -> {
                viewModelScope.launch(Dispatchers.IO) {
                    saveRuleAndRefresh(intent.rule)
                }
            }
            is TxtTocRulePreviewIntent.ToggleSearch -> {
                _uiState.update {
                    it.copy(
                        showSearch = !it.showSearch,
                        searchQuery = if (it.showSearch) "" else it.searchQuery,
                    )
                }
            }
            is TxtTocRulePreviewIntent.UpdateSearchQuery -> {
                _uiState.update { it.copy(searchQuery = intent.query) }
            }
            is TxtTocRulePreviewIntent.GenerateWithAi -> generateWithAi()
            is TxtTocRulePreviewIntent.AdoptAiTitleDraft -> {
                viewModelScope.launch(Dispatchers.IO) { adoptAiTitleDraft(intent.item) }
            }
            is TxtTocRulePreviewIntent.ApplyRule -> {
                val selectedRule = _uiState.value.selectedRule
                if (selectedRule.isNotEmpty()) {
                    _effects.tryEmit(TxtTocRulePreviewEffect.ApplyRule(selectedRule))
                }
            }
            is TxtTocRulePreviewIntent.EditNetworkRule -> {
                _effects.tryEmit(TxtTocRulePreviewEffect.OpenReplaceRuleEditor(intent.ruleId))
            }
            is TxtTocRulePreviewIntent.Refresh -> {
                val currentBook = book ?: return
                viewModelScope.launch(Dispatchers.IO) {
                    if (currentBook.isLocalTxt) {
                        loadRules(currentBook.bookUrl, currentBook.tocUrl)
                    } else {
                        // 编辑替换规则后 ContentProcessor 仍持有旧缓存，先刷新再重新统计
                        runCatching { ContentProcessor.upReplaceRules() }
                        loadNetworkPreview(currentBook)
                    }
                }
            }
        }
    }

    // ===================== 网络书籍：标题替换规则预览 =====================

    private suspend fun loadNetworkPreview(book: Book) {
        _uiState.update { it.copy(loading = true, isTxt = false, emptyHint = "") }
        // 与阅读/目录页一致：替换净化总开关
        val useReplace = AppConfig.replaceEnableDefault
        val chapters = runCatching { bookRepository.getChapters(book.bookUrl) }
            .getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            _uiState.update {
                it.copy(
                    loading = false,
                    isTxt = false,
                    useReplace = useReplace,
                    chapterTotal = 0,
                    titleReplaceRuleCount = 0,
                    networkRuleItems = persistentListOf(),
                    chainDemo = null,
                    emptyHint = context.getString(R.string.toc_preview_no_cached_chapters),
                )
            }
            return
        }
        val titleRules = ContentProcessor.get(book).getTitleReplaceRules()
            .filter { it.isEnabled && it.scopeTitle }

        // 先立即展示界面（卡片显示统计中），命中数在后台逐条回填
        val initialItems = titleRules.mapIndexed { index, rule ->
            NetworkRulePreviewItem(
                rule = rule,
                order = index + 1,
                totalChapter = chapters.size,
                computed = false,
            )
        }
        _uiState.update {
            it.copy(
                loading = false,
                isTxt = false,
                useReplace = useReplace,
                chapterTotal = chapters.size,
                titleReplaceRuleCount = titleRules.size,
                networkRuleItems = initialItems.toImmutableList(),
                chainDemo = null,
                emptyHint = "",
            )
        }
        computeNetworkCounts(chapters, titleRules, useReplace)
    }

    /**
     * 后台统计每条标题替换规则的命中情况。
     *
     * 与 BookChapter.getDisplayTitle 对齐：先去换行 + 简繁转换预处理，
     * 之后每条章节只顺序应用一次规则链（O(N·M)），复用预编译的 java 正则，
     * 不走带超时的 runBlocking 替换，避免章节多时卡顿。
     */
    private fun computeNetworkCounts(
        chapters: List<BookChapter>,
        titleRules: List<ReplaceRule>,
        useReplace: Boolean,
    ) {
        networkCountJob?.cancel()
        networkCountJob = viewModelScope.launch(Dispatchers.IO) {
            if (titleRules.isEmpty() || !useReplace) {
                _uiState.update { state ->
                    state.copy(
                        networkRuleItems = state.networkRuleItems
                            .map { it.copy(computed = true) }
                            .toImmutableList()
                    )
                }
                return@launch
            }
            val compiled = titleRules.map { rule ->
                if (rule.isRegex && rule.pattern.isNotEmpty()) {
                    runCatching { Pattern.compile(rule.pattern) }.getOrNull()
                } else {
                    null
                }
            }
            // 每章“当前标题”，随规则链推进而更新
            val current = Array(chapters.size) { i -> preprocessTitle(chapters[i].title) }
            val matchCounts = IntArray(titleRules.size)
            val samples = Array(titleRules.size) { mutableListOf<Pair<String, String>>() }
            // 每章累计被改变的次数，用于挑出变化最多的章节做链条示范
            val changeCount = IntArray(chapters.size)

            titleRules.forEachIndexed { index, rule ->
                ensureActive()
                val pattern = compiled[index]
                for (i in chapters.indices) {
                    val before = current[i]
                    val after = applySingleReplaceRule(rule, pattern, before)
                    if (after != before) {
                        matchCounts[index]++
                        changeCount[i]++
                        if (samples[index].size < 200) {
                            samples[index].add(before to after)
                        }
                    }
                    current[i] = after
                }
                val example = samples[index].firstOrNull()?.let { (b, a) -> "$b → $a" }
                _uiState.update { state ->
                    val newItems = state.networkRuleItems.mapIndexed { i, item ->
                        if (i == index) {
                            item.copy(
                                matchCount = matchCounts[index],
                                chapters = samples[index].toImmutableList(),
                                example = example,
                                computed = true,
                            )
                        } else {
                            item
                        }
                    }.toImmutableList()
                    state.copy(networkRuleItems = newItems)
                }
            }

            // 用被改变次数最多的章节重建整条替换链，展示链式接力
            var demoIndex = 0
            for (i in changeCount.indices) {
                if (changeCount[i] > changeCount[demoIndex]) demoIndex = i
            }
            var title = preprocessTitle(chapters[demoIndex].title)
            val original = title
            val steps = titleRules.mapIndexed { index, rule ->
                val before = title
                val after = applySingleReplaceRule(rule, compiled[index], before)
                title = after
                ChainStep(
                    ruleId = rule.id,
                    ruleName = rule.name,
                    before = before,
                    after = after,
                    changed = after != before,
                )
            }
            _uiState.update { state ->
                state.copy(
                    chainDemo = ChainDemo(
                        originalTitle = original,
                        finalTitle = title,
                        steps = steps.toImmutableList(),
                    )
                )
            }
        }
    }

    /** 与 getDisplayTitle 的前置处理保持一致：去换行 + 简繁转换 */
    private fun preprocessTitle(title: String): String {
        var result = title.replace(AppPattern.rnRegex, "")
        when (AppConfig.chineseConverterType) {
            1 -> result = ChineseUtils.t2s(result)
            2 -> result = ChineseUtils.s2t(result)
        }
        return result
    }

    /**
     * 单条替换规则应用，语义与 getDisplayTitle 一致：替换结果为空则保留原标题。
     * `@js:` 替换在预览中不执行，按未变化处理，避免脚本副作用。
     */
    private fun applySingleReplaceRule(
        rule: ReplaceRule,
        pattern: Pattern?,
        input: String,
    ): String {
        if (rule.pattern.isEmpty()) return input
        val result = if (rule.isRegex) {
            if (rule.replacement.startsWith("@js:")) return input
            val p = pattern ?: return input
            try {
                val matcher = p.matcher(input)
                val sb = StringBuffer()
                while (matcher.find()) {
                    matcher.appendReplacement(sb, rule.replacement)
                }
                matcher.appendTail(sb)
                sb.toString()
            } catch (_: Exception) {
                input
            }
        } else {
            input.replace(rule.pattern, rule.replacement)
        }
        return if (result.isBlank()) input else result
    }

    // ===================== 本地 TXT：目录正则规则预览 =====================

    private suspend fun loadRules(bookUrl: String, currentTocRegex: String?) {
        _uiState.update { it.copy(loading = true, isTxt = true, emptyHint = "") }

        val book = runCatching { bookRepository.getBook(bookUrl) }.getOrNull()
        this.book = book
        val currentRule = currentTocRegex ?: book?.tocUrl ?: ""

        val allRules = getAllRules()

        // Placeholders: totalCount = -1 means not computed yet, 0 means no book / computed empty
        val previewItems = allRules.map { tocRule ->
            TocRulePreviewItem(rule = tocRule, totalCount = if (book != null) -1 else 0)
        }.toMutableList()

        _uiState.update {
            it.copy(
                loading = false,
                isTxt = true,
                rules = previewItems.toImmutableList(),
                currentRule = currentRule,
                selectedRule = currentRule,
            )
        }

        // Lazy compute chapter counts in background
        if (book != null) {
            computeChaptersLazy(book, allRules)
        }
    }

    private fun computeChaptersLazy(book: Book, rules: List<TxtTocRule>) {
        lazyComputeJob?.cancel()
        lazyComputeJob = viewModelScope.launch(Dispatchers.IO) {
            val resultMap = mutableMapOf<Long, TocRulePreviewItem>()
            for (tocRule in rules) {
                ensureActive()
                val item = computePreview(book, tocRule)
                resultMap[item.rule.id] = item
            }
            _uiState.update { state ->
                val newRules = state.rules.map { existing ->
                    resultMap[existing.rule.id] ?: existing
                }.toImmutableList()
                state.copy(rules = newRules)
            }
        }
    }

    private suspend fun computePreview(book: Book?, tocRule: TxtTocRule): TocRulePreviewItem {
        if (book == null) return TocRulePreviewItem(rule = tocRule)
        return try {
            val pattern = try {
                Regex(tocRule.chapterRule, RegexOption.MULTILINE)
            } catch (e: PatternSyntaxException) {
                return TocRulePreviewItem(rule = tocRule, totalCount = 0)
            }
            val (chapters, total) = analyzeWithPattern(book, pattern)
            TocRulePreviewItem(
                rule = tocRule,
                chapterCount = chapters.size,
                totalCount = total,
                chapters = chapters.take(500).toImmutableList(),
            )
        } catch (e: Exception) {
            TocRulePreviewItem(rule = tocRule, totalCount = 0)
        }
    }

    private suspend fun saveRuleAndRefresh(updatedRule: TxtTocRule) {
        // Validate
        if (updatedRule.name.isBlank() || updatedRule.chapterRule.isBlank()) {
            _effects.tryEmit(TxtTocRulePreviewEffect.ShowToast(context.getString(R.string.cannot_empty)))
            _uiState.update { it.copy(editingRule = null) }
            return
        }
        if (runCatching { Regex(updatedRule.chapterRule, RegexOption.MULTILINE) }.isFailure) {
            _effects.tryEmit(TxtTocRulePreviewEffect.ShowToast(context.getString(R.string.invalid_format)))
            _uiState.update { it.copy(editingRule = null) }
            return
        }

        // Save to DB
        val existing = runCatching { repository.findById(updatedRule.id) }.getOrNull()
        if (existing != null) {
            repository.update(updatedRule)
        } else {
            repository.insert(updatedRule)
        }

        // Cancel pending lazy compute to avoid race condition
        lazyComputeJob?.cancel()

        // Refresh the specific rule preview
        val currentRules = _uiState.value.rules.toMutableList()
        val index = currentRules.indexOfFirst { it.rule.id == updatedRule.id }
        val book = this.book
        if (index >= 0 && book != null) {
            val refreshed = computePreview(book, updatedRule)
            currentRules[index] = refreshed
            _uiState.update {
                it.copy(
                    rules = currentRules.toImmutableList(),
                    selectedRule = if (it.selectedRule == existing?.chapterRule) {
                        updatedRule.chapterRule
                    } else {
                        it.selectedRule
                    },
                    activeSheet = TxtTocRulePreviewSheet.ChapterList(refreshed),
                    editingRule = null,
                )
            }
        } else {
            _uiState.update { it.copy(editingRule = null) }
        }

        // Restart lazy compute for remaining rules
        if (book != null) {
            val remainingRules = currentRules.filter { it.totalCount < 0 }.map { it.rule }
            if (remainingRules.isNotEmpty()) {
                computeChaptersLazy(book, remainingRules)
            }
        }
    }

    // ===================== AI 读真实目录反推规则 =====================

    /**
     * 两种模式产出不同：网络书籍给标题净化规则（需要用户逐条采用），
     * 本地 TXT 给目录正则（直接填进既有编辑弹窗，复用它的校验与落库）。
     */
    private fun generateWithAi() {
        if (_uiState.value.generatingAi) return
        val currentBook = book ?: run {
            _effects.tryEmit(TxtTocRulePreviewEffect.ShowToast(context.getString(R.string.no_book)))
            return
        }
        _uiState.update { it.copy(generatingAi = true) }
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.isTxt) {
                generateTxtTocRule(currentBook)
            } else {
                generateTitleCleanRules(currentBook)
            }
            _uiState.update { it.copy(generatingAi = false) }
        }
    }

    private suspend fun generateTitleCleanRules(currentBook: Book) {
        val chapters = runCatching { bookRepository.getChapters(currentBook.bookUrl) }
            .getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            toastAiFailure(context.getString(R.string.toc_preview_no_cached_chapters))
            return
        }
        val titles = chapters.map { preprocessTitle(it.title) }
        generateTocRuleUseCase.titleCleanRules(currentBook.name, titles)
            .onSuccess { drafts ->
                if (drafts.isEmpty()) {
                    toastAiFailure(context.getString(R.string.ai_toc_rule_none))
                    return@onSuccess
                }
                val items = drafts.map { draft -> draft.previewOn(titles) }
                _uiState.update {
                    it.copy(
                        activeSheet = TxtTocRulePreviewSheet.AiTitleDrafts(items.toImmutableList())
                    )
                }
            }
            .onFailure { toastAiFailure(it.localizedMessage) }
    }

    /** 在真实标题上跑一遍，命中数和样例由这里算，不信模型自己说的命中情况。 */
    private fun AiTitleCleanRuleDraft.previewOn(titles: List<String>): AiTitleDraftItem {
        val rule = toReplaceRule()
        val pattern = if (rule.isRegex && rule.pattern.isNotEmpty()) {
            runCatching { Pattern.compile(rule.pattern) }.getOrNull()
        } else {
            null
        }
        val samples = mutableListOf<Pair<String, String>>()
        var matchCount = 0
        for (title in titles) {
            val after = applySingleReplaceRule(rule, pattern, title)
            if (after != title) {
                matchCount++
                if (samples.size < 200) samples.add(title to after)
            }
        }
        return AiTitleDraftItem(
            draft = this,
            matchCount = matchCount,
            totalChapter = titles.size,
            samples = samples.toImmutableList(),
        )
    }

    /**
     * 只作用于标题，不碰正文：这条规则是为净化目录生成的。
     * 作用范围默认限定到本书：规则是从这一本的真实标题反推出来的，
     * 放开成全局容易误伤别的书；用户要全局生效可以在替换规则管理页清空作用范围。
     */
    private fun AiTitleCleanRuleDraft.toReplaceRule(scope: String? = null) = ReplaceRule(
        name = name,
        pattern = pattern,
        replacement = replacement,
        isRegex = isRegex,
        scope = scope,
        scopeTitle = true,
        scopeContent = false,
        isEnabled = true,
    )

    private suspend fun adoptAiTitleDraft(item: AiTitleDraftItem) {
        val currentBook = book ?: run {
            _effects.tryEmit(TxtTocRulePreviewEffect.ShowToast(context.getString(R.string.no_book)))
            return
        }
        val rule = item.draft.toReplaceRule(scope = currentBook.name)
        // 与替换规则编辑页同一套校验：除了正则能编译，还挡住结尾裸 | 这类会替换超时的写法
        if (!rule.isValid()) {
            toastAiFailure(context.getString(R.string.replace_rule_invalid))
            return
        }
        runCatching {
            // 新规则排在链条末尾，和编辑页新建规则的口径一致，不要抢在既有规则前面
            rule.order = replaceRuleRepository.getNextOrder()
            replaceRuleRepository.insert(rule)
            ContentProcessor.upReplaceRules()
        }.onFailure {
            toastAiFailure(it.localizedMessage)
            return
        }
        _uiState.update { it.copy(activeSheet = null) }
        loadNetworkPreview(currentBook)
    }

    /**
     * TXT 取样直接复用 [analyzeWithPattern]：用「非空行」正则跑一遍就拿到前若干行，
     * 不必再写一份分块读文件的逻辑。
     */
    private suspend fun generateTxtTocRule(currentBook: Book) {
        val lines = runCatching {
            analyzeWithPattern(currentBook, Regex("^.+$", RegexOption.MULTILINE)).first
        }.getOrDefault(emptyList())
        if (lines.isEmpty()) {
            toastAiFailure(context.getString(R.string.invalid_format))
            return
        }
        generateTocRuleUseCase.txtTocRule(currentBook.name, lines.map { it.trim() })
            .onSuccess { draft ->
                // 交给既有编辑弹窗：用户能改，确认后走同一条校验 + 落库 + 重算路径
                _uiState.update {
                    it.copy(
                        activeSheet = null,
                        editingRule = TxtTocRule(
                            name = draft.name,
                            chapterRule = draft.chapterRule,
                            volumeRule = draft.volumeRule,
                            example = draft.reason.ifBlank { null },
                        ),
                    )
                }
            }
            .onFailure { toastAiFailure(it.localizedMessage) }
    }

    private fun toastAiFailure(message: String?) {
        _effects.tryEmit(
            TxtTocRulePreviewEffect.ShowToast(
                context.getString(
                    R.string.ai_toc_rule_failed,
                    message ?: context.getString(R.string.ai_toc_rule_unknown)
                )
            )
        )
    }

    private suspend fun getAllRules(): List<TxtTocRule> {
        var rules = repository.all()
        if (repository.count() == 0) {
            val defaultRules = DefaultData.txtTocRules
            repository.insert(*defaultRules.toTypedArray())
            rules = repository.all()
        }
        return rules.filter { it.chapterRule.isNotBlank() }.sortedBy { it.serialNumber }
    }

    private suspend fun analyzeWithPattern(book: Book, pattern: Regex): Pair<List<String>, Int> {
        val chapters = mutableListOf<String>()
        var totalCount = 0
        val charset = book.fileCharset()
        val blank = 0x0a.toByte()
        val bufferSize = 512000

        runCatching {
            LocalBook.getBookInputStream(book).use { bis ->
                val buffer = ByteArray(bufferSize)
                var bufferStart = 3
                bis.read(buffer, 0, 3)
                if (Utf8BomUtils.hasBom(buffer)) {
                    bufferStart = 0
                }
                var length: Int
                while (bis.read(buffer, bufferStart, bufferSize - bufferStart).also { length = it } > 0) {
                    coroutineContext.ensureActive()
                    var end = bufferStart + length
                    if (end == bufferSize) {
                        for (i in bufferStart + length - 1 downTo (bufferStart + length - 4096).coerceAtLeast(0)) {
                            if (buffer[i] == blank) {
                                end = i
                                break
                            }
                        }
                    }
                    val blockContent = String(buffer, 0, end, charset)
                    buffer.copyInto(buffer, 0, end, bufferStart + length)
                    bufferStart = bufferStart + length - end

                    for (m in pattern.findAll(blockContent)) {
                        totalCount++
                        if (chapters.size < 500) {
                            chapters.add(m.value)
                        }
                    }
                }
            }
        }
        return chapters to totalCount
    }
}
