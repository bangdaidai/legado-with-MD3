package io.legado.app.ui.book.toc.rule.preview

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
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
import io.legado.app.utils.quoteReplacementJs
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private var ruleWatchJob: Job? = null

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
                observeReplaceRuleChanges(loadedBook)
            } else {
                loadRules(bookUrl, currentTocRegex)
            }
        }
    }

    /**
     * 规则可能在管理页、编辑页、AI 采用甚至导入里被改，回到本页不一定有 result 回调可用。
     * 所以直接盯规则表：任何入口改完，刷新 [ContentProcessor] 缓存再重算命中。
     * 第一次发射是当前状态，[loadNetworkPreview] 刚读过，跳过。
     */
    private fun observeReplaceRuleChanges(book: Book) {
        ruleWatchJob?.cancel()
        ruleWatchJob = viewModelScope.launch(Dispatchers.IO) {
            replaceRuleRepository.flowContentSignature()
                .drop(1)
                .collect {
                    runCatching { ContentProcessor.upReplaceRules() }
                    loadNetworkPreview(book)
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
                // TXT 去目录正则管理页；网络书这里的规则本体是替换净化，去替换净化管理页
                _effects.tryEmit(
                    if (_uiState.value.isTxt) {
                        TxtTocRulePreviewEffect.OpenManagePage
                    } else {
                        TxtTocRulePreviewEffect.OpenReplaceRuleManagePage
                    }
                )
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
            is TxtTocRulePreviewIntent.ToggleAiTitleDraft -> {
                _uiState.update { state ->
                    val sheet = state.activeSheet as? TxtTocRulePreviewSheet.AiTitleDrafts
                        ?: return@update state
                    val items = sheet.items.mapIndexed { index, item ->
                        if (index == intent.index) item.copy(selected = !item.selected) else item
                    }
                    state.copy(
                        activeSheet = TxtTocRulePreviewSheet.AiTitleDrafts(items.toImmutableList())
                    )
                }
            }
            is TxtTocRulePreviewIntent.AdoptSelectedAiTitleDrafts -> {
                val sheet = _uiState.value.activeSheet as? TxtTocRulePreviewSheet.AiTitleDrafts
                    ?: return
                val selected = sheet.items.filter { it.selected }
                if (selected.isEmpty()) return
                viewModelScope.launch(Dispatchers.IO) { adoptAiTitleDrafts(selected) }
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
        // 与目录页同一个条件：本书自己的目录开关优先，没设置过才看全局默认；和正文那个开关无关
        val useReplace = book.getUseReplaceRuleToc(AppConfig.replaceEnableDefault)
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
                jsSampleLimit = if (rule.isJsReplacement()) {
                    minOf(JS_SAMPLE_LIMIT, chapters.size)
                } else 0,
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
                // @js: 只在前几章试跑：每个匹配点都要起一次 Rhino，全书跑一遍既慢又可能有副作用
                val jsLimit = if (rule.isJsReplacement()) minOf(JS_SAMPLE_LIMIT, chapters.size) else 0
                for (i in chapters.indices) {
                    val before = current[i]
                    val after = if (jsLimit > 0) {
                        if (i < jsLimit) {
                            runJsReplace(rule, pattern, before, chapters[i]) ?: before
                        } else before
                    } else {
                        applySingleReplaceRule(rule, pattern, before)
                    }
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
                // 示范只有一章，@js: 在这里照常试跑，让脚本规则的效果也能看见
                val after = if (rule.isJsReplacement()) {
                    runJsReplace(rule, compiled[index], before, chapters[demoIndex]) ?: before
                } else {
                    applySingleReplaceRule(rule, compiled[index], before)
                }
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
     * `@js:` 走 [runJsReplace] 单独试跑，不进这里。
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

    private fun ReplaceRule.isJsReplacement(): Boolean =
        isRegex && pattern.isNotEmpty() && replacement.startsWith("@js:")

    /**
     * 预览里的 `@js:` 试跑：语义与 [io.legado.app.utils.replace] 一致（每个匹配点把命中文本交给脚本），
     * 但**不复用**那条路径——它的看门狗超时会弹窗并在 3 秒后重启应用，一个预览页面不能承担这种后果。
     *
     * 这里改成：把协程上下文交给 Rhino，让脚本能被取消；再用 [withTimeoutOrNull] 兜住超时，
     * 超时或脚本报错都返回 null，调用方按“未改变”处理。只对样本章节调用，见 [JS_SAMPLE_LIMIT]。
     */
    private suspend fun runJsReplace(
        rule: ReplaceRule,
        pattern: Pattern?,
        input: String,
        chapter: BookChapter,
    ): String? {
        val p = pattern ?: return null
        val script = rule.replacement.substring(4)
        val searchBook = book?.toSearchBook()
        return withTimeoutOrNull(rule.getValidTimeoutMillisecond()) {
            try {
                val matcher = p.matcher(input)
                val sb = StringBuffer()
                while (matcher.find()) {
                    ensureActive()
                    val bindings = ScriptBindings()
                    bindings["result"] = matcher.group()
                    bindings["chapter"] = chapter
                    bindings["book"] = searchBook
                    val jsResult = RhinoScriptEngine.eval(
                        script,
                        RhinoScriptEngine.getRuntimeScope(bindings),
                        currentCoroutineContext(),
                    )?.toString().orEmpty()
                    matcher.appendReplacement(sb, jsResult.quoteReplacementJs())
                }
                matcher.appendTail(sb)
                sb.toString().takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                // 脚本来源不可控，取消会由 Rhino 抛成 Error，报错也不该带崩预览页；
                // 统一按“这条规则没改动标题”处理，真正的取消由外层循环的 ensureActive 兜住
                null
            }
        }
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
     * 两种模式产出不同：网络书籍给标题净化规则（在弹窗里多选后一次采用），
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
            // 有命中的默认勾上，用户按需取消，避免一条条点
            selected = matchCount > 0,
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

    /**
     * 一次采用多条草稿：按勾选顺序排到链条末尾，落库后只刷一次缓存和预览。
     * 单条校验不过就跳过它并提示，不连坐其它草稿。
     */
    private suspend fun adoptAiTitleDrafts(items: List<AiTitleDraftItem>) {
        val currentBook = book ?: run {
            _effects.tryEmit(TxtTocRulePreviewEffect.ShowToast(context.getString(R.string.no_book)))
            return
        }
        // 与替换规则编辑页同一套校验：除了正则能编译，还挡住结尾裸 | 这类会替换超时的写法
        val (valid, invalid) = items
            .map { it.draft.toReplaceRule(scope = currentBook.name) }
            .partition { it.isValid() }
        if (valid.isEmpty()) {
            toastAiFailure(context.getString(R.string.replace_rule_invalid))
            return
        }
        runCatching {
            // 新规则排在链条末尾，和编辑页新建规则的口径一致，不要抢在既有规则前面
            var order = replaceRuleRepository.getNextOrder()
            valid.forEach { rule ->
                rule.order = order
                order++
            }
            replaceRuleRepository.insert(*valid.toTypedArray())
            ContentProcessor.upReplaceRules()
        }.onFailure {
            toastAiFailure(it.localizedMessage)
            return
        }
        if (invalid.isNotEmpty()) {
            toastAiFailure(context.getString(R.string.replace_rule_invalid))
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

/**
 * `@js:` 规则在预览里的试跑章节数。只看前几章就够判断这条规则有没有在动标题，
 * 而每个匹配点都要起一次 Rhino，全书跑一遍既慢又可能有副作用。
 */
private const val JS_SAMPLE_LIMIT = 10
