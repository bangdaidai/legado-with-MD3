package io.legado.app.ui.book.explore

import android.content.res.Configuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.R
import io.legado.app.data.repository.ExploreRepository
import io.legado.app.domain.model.BookShelfState
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.data.repository.SettingsRepository
import io.legado.app.domain.gateway.CoverSettingsGateway
import io.legado.app.domain.usecase.AddToBookshelfUseCase
import io.legado.app.domain.usecase.BookShelfKey
import io.legado.app.domain.usecase.ExploreBooksUseCase
import io.legado.app.domain.usecase.ResolveBookShelfStateUseCase
import io.legado.app.domain.usecase.SaveSearchBooksUseCase
import io.legado.app.utils.stackTraceStr
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import splitties.init.appCtx

private data class ExploreShowLoadState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isEnd: Boolean = false,
    val errorMsg: String? = null,
)

private data class ExploreShowKindState(
    val kinds: List<ExploreKind> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val selectedKindTitle: String? = null,
)

/**
 * 涔︽簮瑙勫垯閲屾病鏈夋樉寮忕殑鍒嗙粍瀛楁锛岀害瀹氾細鍗犳弧鏁磋銆佹棦娌℃湁 url 涔熸病鏈?action 鐨?kind 鏄ぇ鍒嗙被鏍囬锛? * 瀹冨悗闈㈢殑鍒嗙被閮藉綊灞炰簬瀹冿紝鐩村埌閬囧埌涓嬩竴涓ぇ鍒嗙被鏍囬銆? */
private fun isExploreGroupHeader(kind: ExploreKind): Boolean {
    if (!kind.url.isNullOrBlank()) return false
    if (!kind.action.isNullOrBlank()) return false
    val style = kind.style()
    return style.layout_flexBasisPercent >= 0.95f ||
        (style.layout_flexGrow >= 1f && style.layout_flexBasisPercent < 0f)
}

/** 鍘绘帀鍒嗙被鏍囬涓や晶鐨勮楗扮鍙凤紝渚嬪銆屸梿 鎺掕 鈼嗐€嶁啋銆屾帓琛屻€?*/
private fun resolveExploreGroupTitle(kind: ExploreKind): String {
    val raw = kind.title.trim()
    return raw.replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$"), "").ifBlank { raw }
}

private fun groupExploreKinds(kinds: List<ExploreKind>, ungroupedLabel: String): Map<String, List<ExploreKind>> {
    val grouped = LinkedHashMap<String, MutableList<ExploreKind>>()
    var currentGroup: String? = null
    kinds.forEach { kind ->
        if (isExploreGroupHeader(kind)) {
            currentGroup = resolveExploreGroupTitle(kind).ifBlank { ungroupedLabel }
            return@forEach
        }
        // 鍙湁 url 绫诲瀷鐨勫垎绫昏兘鐩存帴鍒囨崲锛屽叾浣欑被鍨嬩粛璧?ExploreKindSelectSheet
        if (kind.type != ExploreKind.Type.url || kind.url.isNullOrBlank()) return@forEach
        grouped.getOrPut(currentGroup ?: ungroupedLabel) { mutableListOf() }.add(kind)
    }
    return grouped
}

private data class ExploreShowDisplayState(
    val sourceUrl: String? = null,
    val layoutState: Int,
    val gridCount: Int,
    val sheet: ExploreShowSheet = ExploreShowSheet.None,
)

class ExploreShowViewModel(
    private val repository: ExploreRepository,
    private val resolveBookShelfStateUseCase: ResolveBookShelfStateUseCase,
    private val exploreBooksUseCase: ExploreBooksUseCase,
    private val saveSearchBooksUseCase: SaveSearchBooksUseCase,
    private val addToBookshelfUseCase: AddToBookshelfUseCase,
    private val localPreferencesRepository: SettingsRepository,
    private val coverSettingsGateway: CoverSettingsGateway,
) : ViewModel() {

    private val _rawBooks = MutableStateFlow<List<SearchBook>>(emptyList())
    private val _bookshelf = MutableStateFlow<Set<BookShelfKey>>(emptySet())
    private val _loadState = MutableStateFlow(ExploreShowLoadState())
    private val _kindState = MutableStateFlow(ExploreShowKindState())
    private val _displayState = MutableStateFlow(
        ExploreShowDisplayState(
            layoutState = 0,
            gridCount = if (appCtx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 7 else 3,
        )
    )

    private var sourceUrl: String? = null
    private var exploreUrl: String? = null
    private var initialExploreUrl: String? = null
    private var initialized = false
    private var page = 1
    private var autoPageCount = 0
    private var groupedKinds: Map<String, List<ExploreKind>> = emptyMap()

    companion object {
        private const val MAX_AUTO_PAGES = 3
        private const val AUTO_PAGE_DELAY_MS = 500L
    }

    private val _uiState = MutableStateFlow(
        ExploreShowUiState(
            layoutState = _displayState.value.layoutState,
            gridCount = _displayState.value.gridCount,
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ExploreShowEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        observeBookshelf()
        combineUiState()
        loadLayoutMode()
        loadGridCount()
    }

    fun onIntent(intent: ExploreShowIntent) {
        when (intent) {
            is ExploreShowIntent.InitData -> initData(intent.sourceUrl, intent.exploreUrl)
            ExploreShowIntent.LoadMore -> loadMore()
            ExploreShowIntent.ForceLoadNext -> loadMore(forceLoad = true)
            ExploreShowIntent.Refresh -> loadMore(isRefresh = true)
            is ExploreShowIntent.SwitchKind -> switchKind(intent.kind)
            is ExploreShowIntent.SwitchGroup -> switchGroup(intent.group)
            ExploreShowIntent.ToggleLayout -> toggleLayout()
            is ExploreShowIntent.SaveGridCount -> saveGridCount(intent.count)
            is ExploreShowIntent.ShowSheet -> _displayState.update { it.copy(sheet = intent.sheet) }
            ExploreShowIntent.DismissSheet -> _displayState.update { it.copy(sheet = ExploreShowSheet.None) }
            is ExploreShowIntent.OpenBook -> emitEffect(
                ExploreShowEffect.OpenBookInfo(
                    name = intent.book.name,
                    author = intent.book.author,
                    bookUrl = intent.book.bookUrl,
                    origin = intent.book.origin,
                    coverPath = intent.book.coverUrl,
                    sharedCoverKey = intent.sharedCoverKey,
                )
            )

            is ExploreShowIntent.AddToShelf -> viewModelScope.launch {
                addToBookshelfUseCase.execute(intent.book)
            }
        }
    }

    private fun observeBookshelf() {
        viewModelScope.launch {
            repository.getBookshelfItems().collect { list ->
                _bookshelf.value = list.map {
                    BookShelfKey(it.name, it.author, it.bookUrl)
                }.toSet()
            }
        }
    }

    /**
     * 棰勮 sheet 鐢細鐩存帴鎸夊綋鍓嶄功鏋跺揩鐓у疄鏃惰绠椼€?     * 涓嶈浠庡睍绀哄垪琛ㄥ弽鏌モ€斺€旂瓫閫夋妸璇ヤ功闅愯棌鍚庡氨鏌ヤ笉鍒帮紝浼氳鍒ゆ垚涓嶅湪涔︽灦銆?     */
    fun getCurrentBookShelfState(book: SearchBook): BookShelfState =
        resolveBookShelfStateUseCase.execute(
            name = book.name,
            author = book.author,
            url = book.bookUrl,
            shelf = _bookshelf.value,
        )

    private fun combineUiState() {
        viewModelScope.launch {
            val displayAndCoverSettings = combine(
                _displayState,
                coverSettingsGateway.settings,
            ) { displayState, coverSettings -> displayState to coverSettings }
            combine(
                _rawBooks,
                _bookshelf,
                _loadState,
                _kindState,
                displayAndCoverSettings,
            ) { rawBooks, bookshelf, loadState, kindState, displayAndCover ->
                val (displayState, coverSettings) = displayAndCover
                val books = rawBooks.map { item ->
                    ExploreBookItemUi(
                        book = item,
                        shelfState = resolveBookShelfStateUseCase.execute(
                            name = item.name,
                            author = item.author,
                            url = item.bookUrl,
                            shelf = bookshelf,
                        )
                    )
                }

                ExploreShowUiState(
                    sourceUrl = displayState.sourceUrl,
                    books = books.toImmutableList(),
                    kinds = kindState.kinds.toImmutableList(),
                    kindGroups = kindState.groups.toImmutableList(),
                    selectedGroup = kindState.selectedGroup,
                    selectedKindTitle = kindState.selectedKindTitle,
                    layoutState = displayState.layoutState,
                    gridCount = displayState.gridCount,
                    isLoading = loadState.isLoading,
                    isRefreshing = loadState.isRefreshing,
                    isEnd = loadState.isEnd,
                    errorMsg = loadState.errorMsg,
                    sheet = displayState.sheet,
                    filterStateId = coverSettings.exploreFilterState,
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    private fun initData(incomingSourceUrl: String, incomingExploreUrl: String?) {
        if (initialized && sourceUrl == incomingSourceUrl && initialExploreUrl == incomingExploreUrl) {
            return
        }
        initialized = true
        sourceUrl = incomingSourceUrl
        initialExploreUrl = incomingExploreUrl
        exploreUrl = incomingExploreUrl
        page = 1
        autoPageCount = 0
        _rawBooks.value = emptyList()
        _loadState.value = ExploreShowLoadState()
        _kindState.value = ExploreShowKindState()
        _displayState.update {
            it.copy(
                sourceUrl = incomingSourceUrl,
                sheet = ExploreShowSheet.None,
            )
        }

        viewModelScope.launch {
            loadKinds(incomingSourceUrl)
            if (incomingExploreUrl != null) {
                val entry = groupedKinds.entries
                    .firstOrNull { (_, tags) -> tags.any { it.url == incomingExploreUrl } }
                if (entry != null) {
                    val matchedKind = entry.value.first { it.url == incomingExploreUrl }
                    _kindState.update {
                        it.copy(
                            selectedGroup = entry.key,
                            kinds = entry.value,
                            selectedKindTitle = matchedKind.title,
                        )
                    }
                } else {
                    // 閫氳繃 java.open("explore", ...) 浼犲叆鐨勮嚜瀹氫箟 URL(渚嬪 data:page 涔﹀崟/璇勮鍒楄〃),
                    // 涓嶅睘浜庝功婧愬垎绫讳綋绯? 闅愯棌椤堕儴鍒嗙被鏍囩鏍? 鍙樉绀轰功鍗曟湰韬?                    groupedKinds = emptyMap()
                    _kindState.value = ExploreShowKindState()
                }
            }
        }

        loadMore(isRefresh = true)
    }

    private suspend fun loadKinds(sourceUrl: String) {
        val raw = repository.getSourceExploreKinds(sourceUrl)
        val grouped = groupExploreKinds(raw, appCtx.getString(R.string.all))
        groupedKinds = grouped
        // 鍙湁瀛樺湪澶氫釜澶у垎绫绘椂鎵嶆樉绀哄垎缁勮锛屽惁鍒欓€€鍖栦负鍗曡鏍囩鏉?        val groups = if (grouped.size > 1) grouped.keys.toList() else emptyList()
        val defaultGroup = grouped.keys.firstOrNull()
        _kindState.update {
            it.copy(
                groups = groups,
                selectedGroup = defaultGroup,
                kinds = grouped[defaultGroup] ?: emptyList(),
            )
        }
    }

    private fun switchGroup(group: String) {
        _kindState.update {
            it.copy(selectedGroup = group, kinds = groupedKinds[group] ?: emptyList())
        }
    }

    private fun switchKind(kind: ExploreKind) {
        _kindState.update { it.copy(selectedKindTitle = kind.title) }
        exploreUrl = kind.url
        _loadState.update { it.copy(isEnd = false) }
        autoPageCount = 0
        loadMore(isRefresh = true)
    }

    private fun toggleLayout() {
        _displayState.update {
            val layoutState = if (it.layoutState == 0) 1 else 0
            viewModelScope.launch {
                localPreferencesRepository.updatePreference(LocalPreferencesKeys.EXPLORE_LAYOUT_MODE, layoutState)
            }
            it.copy(layoutState = layoutState)
        }
    }

    private fun loadLayoutMode() {
        viewModelScope.launch {
            val mode = localPreferencesRepository.getPreference(LocalPreferencesKeys.EXPLORE_LAYOUT_MODE, 0).first()
            _displayState.update { it.copy(layoutState = mode) }
        }
    }

    private fun loadGridCount() {
        viewModelScope.launch {
            val isLandscape = appCtx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val key = if (isLandscape) {
                LocalPreferencesKeys.EXPLORE_LAYOUT_GRID_LANDSCAPE
            } else {
                LocalPreferencesKeys.EXPLORE_LAYOUT_GRID_PORTRAIT
            }
            val default = if (isLandscape) 7 else 3
            val count = localPreferencesRepository.getPreference(key, default).first()
            _displayState.update { it.copy(gridCount = count) }
        }
    }

    private fun saveGridCount(count: Int) {
        val isLandscape = appCtx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (isLandscape) {
            LocalPreferencesKeys.EXPLORE_LAYOUT_GRID_LANDSCAPE
        } else {
            LocalPreferencesKeys.EXPLORE_LAYOUT_GRID_PORTRAIT
        }
        viewModelScope.launch {
            localPreferencesRepository.updatePreference(key, count)
        }
        _displayState.update { it.copy(gridCount = count) }
    }

    private fun loadMore(isRefresh: Boolean = false, forceLoad: Boolean = false) {
        val source = sourceUrl
        val url = exploreUrl
        val loadState = _loadState.value
        if (source == null || loadState.isLoading || (loadState.isEnd && !isRefresh && !forceLoad)) return

        _loadState.update {
            it.copy(
                isLoading = true,
                isRefreshing = isRefresh,
                isEnd = if (isRefresh || forceLoad) false else it.isEnd,
                errorMsg = null,
            )
        }

        viewModelScope.launch {
            if (isRefresh) {
                page = 1
                autoPageCount = 0
                _rawBooks.value = emptyList()
            }

            if (forceLoad) {
                autoPageCount = 0
            }

            fetchPage(source, url)
        }
    }

    private suspend fun fetchPage(sourceUrl: String, url: String?) {
        kotlin.runCatching {
            exploreBooksUseCase.execute(sourceUrl, url, args = null, page)
        }.onSuccess { result ->
            val currentList = _rawBooks.value
            val existingUrls = currentList.map { it.bookUrl }.toSet()
            val uniqueNewBooks = result.books
                .filter { it.bookUrl !in existingUrls }
                .distinctBy { it.bookUrl }

            if (result.books.isNotEmpty()) {
                saveSearchBooksUseCase.save(result.books)
            }

            if (uniqueNewBooks.isEmpty()) {
                fetchNextAutoPageOrFinish(sourceUrl, url)
            } else {
                _rawBooks.value = currentList + uniqueNewBooks
                page++
                autoPageCount = 0
                _loadState.update { it.copy(isEnd = false) }
                finishLoading()
            }
        }.onFailure { throwable ->
            _loadState.update { it.copy(errorMsg = throwable.stackTraceStr) }
            finishLoading()
        }
    }

    private suspend fun fetchNextAutoPageOrFinish(sourceUrl: String, url: String?) {
        page++
        autoPageCount++
        if (autoPageCount >= MAX_AUTO_PAGES) {
            _loadState.update { it.copy(isEnd = true) }
            finishLoading()
        } else {
            delay(AUTO_PAGE_DELAY_MS)
            fetchPage(sourceUrl, url)
        }
    }

    private fun finishLoading() {
        _loadState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
            )
        }
    }

    private fun emitEffect(effect: ExploreShowEffect) {
        _effects.tryEmit(effect)
    }
}
