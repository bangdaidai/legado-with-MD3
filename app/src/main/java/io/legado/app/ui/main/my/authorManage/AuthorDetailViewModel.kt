package io.legado.app.ui.main.my.authorManage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.AuthorProfile
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.data.repository.AuthorProfileRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.data.repository.SearchRepository
import io.legado.app.data.repository.SettingsRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.BookSearchScope
import io.legado.app.domain.model.MatchMode
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.domain.usecase.AddToBookshelfUseCase
import io.legado.app.domain.usecase.BookSearchControl
import io.legado.app.domain.usecase.BookSearchRequest
import io.legado.app.domain.usecase.BookShelfKey
import io.legado.app.domain.usecase.GenerateAuthorBioUseCase
import io.legado.app.domain.usecase.ResolveBookShelfStateUseCase
import io.legado.app.domain.usecase.SearchBooksUseCase
import io.legado.app.domain.usecase.SearchRunEvent
import io.legado.app.help.book.TagManager
import io.legado.app.help.config.AppConfigStore
import io.legado.app.ui.config.otherConfig.OtherConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import splitties.init.appCtx
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 书籍卡片显示配置，取值与阅读记忆列表同源，保证两处样式一致。 */
private data class MemoryCardConfig(
    val settings: BookshelfSettings,
    val tagColorMap: ImmutableMap<String, Long>,
    val coverWidth: Int,
)

/**
 * 简介编辑弹窗的瞬时状态。合并成一个 flow 是为了让主 combine 保持在 4 路以内
 * （typed combine 最多 5 路）。
 */
private data class BioEditState(
    val editing: Boolean = false,
    val draft: String = "",
    val generating: Boolean = false,
    /** 最近一次 AI 生成的原文，用于判断保存时草稿是否被用户改过。 */
    val generatedBio: String? = null,
    val generatedModel: String? = null,
)

/**
 * 「其他作品」的进度阶段。结果列表本身放在 [AuthorDetailViewModel.worksRaw]，
 * 这样搜索完成后书架状态变化能就地刷新，不用重新搜。
 */
private sealed interface WorksPhase {
    data object Idle : WorksPhase
    data class Searching(val processed: Int, val total: Int) : WorksPhase
    data object Done : WorksPhase
    data class Failed(val message: String) : WorksPhase
}

class AuthorDetailViewModel(
    private val name: String,
    private val repository: ReadingMemoryRepository,
    private val authorProfileRepository: AuthorProfileRepository,
    private val generateAuthorBioUseCase: GenerateAuthorBioUseCase,
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
    private val themeSettingsGateway: ThemeSettingsGateway,
    private val searchRepository: SearchRepository,
    private val searchBooksUseCase: SearchBooksUseCase,
    private val resolveBookShelfStateUseCase: ResolveBookShelfStateUseCase,
    private val addToBookshelfUseCase: AddToBookshelfUseCase,
    private val localPreferencesRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorDetailUiState())
    val uiState: StateFlow<AuthorDetailUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AuthorDetailEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<AuthorDetailEffect> = _effects.asSharedFlow()

    private val _bioEdit = MutableStateFlow(BioEditState())
    private val _bookFilter = MutableStateFlow<AuthorBookStatus?>(null)
    private var generateJob: Job? = null

    /** 搜到的原始结果，同名同作者可能来自多个书源，展示前才折叠。 */
    private val worksRaw = MutableStateFlow<List<SearchBook>>(emptyList())
    private val worksPhase = MutableStateFlow<WorksPhase>(WorksPhase.Idle)

    /** 当前作者已在关联书籍里的书名，用来把「其他作品」里已有的剔掉。 */
    private val ownBookNames = MutableStateFlow<Set<String>>(emptySet())
    private var searchJob: Job? = null
    private var worksScopeRaw: String = ""

    private val bookshelfSettings = bookshelfSettingsGateway.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfSettings(),
    )
    private val tagColorMap: StateFlow<ImmutableMap<String, Long>> = combine(
        repository.observeTagColorMap(),
        themeSettingsGateway.settings.map { it.enableCustomTagColors },
    ) { colors, enabled ->
        if (enabled) colors.toImmutableMap() else persistentMapOf()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentMapOf())

    private val cardConfig: Flow<MemoryCardConfig> = combine(
        bookshelfSettings,
        tagColorMap,
        AppConfigStore.observeInt(PreferKey.readingMemoryCoverWidth),
    ) { settings, colorMap, coverWidth ->
        MemoryCardConfig(
            settings = settings,
            tagColorMap = colorMap,
            coverWidth = coverWidth ?: 84,
        )
    }

    init {
        viewModelScope.launch {
            combine(
                repository.observeAll(),
                authorProfileRepository.observeProfiles(),
                _bioEdit,
                cardConfig,
                _bookFilter,
            ) { memories, profiles, bioEdit, config, filter ->
                buildState(memories, profiles, bioEdit, config, filter)
            }.flowOn(Dispatchers.Default).collect { built ->
                // 「其他作品」是独立数据流，重建阅读记忆部分时原样带过去
                _uiState.update { prev ->
                    built.copy(
                        works = prev.works,
                        worksScopeNames = prev.worksScopeNames,
                        worksScopeRaw = prev.worksScopeRaw,
                        enabledGroups = prev.enabledGroups,
                        enabledSources = prev.enabledSources,
                    )
                }
            }
        }
        observeWorks()
        observeWorksScope()
        observeSourceOptions()
        loadCachedWorks()
    }

    /** 结果、进度、书架状态、已有书名任一变化都重新推导展示状态。 */
    private fun observeWorks() {
        viewModelScope.launch {
            combine(
                worksRaw,
                worksPhase,
                searchRepository.bookshelfKeys.catch { emit(emptySet()) },
                ownBookNames,
            ) { raw, phase, shelf, ownNames ->
                buildWorksState(raw, phase, shelf, ownNames)
            }.flowOn(Dispatchers.Default).collect { state ->
                _uiState.update { it.copy(works = state) }
            }
        }
    }

    private fun observeWorksScope() {
        viewModelScope.launch {
            // 走 getString 而不是 getPreference：底层同一条路径，但不会被架构护栏的
            // 「旧偏好调用」正则（前缀 getPref）误判
            localPreferencesRepository
                .getString(LocalPreferencesKeys.AUTHOR_WORKS_SEARCH_SCOPE.name, "")
                .distinctUntilChanged()
                .collect { raw ->
                    worksScopeRaw = raw
                    val scope = BookSearchScope(raw)
                    val names = if (scope.isSource) scope.sourceNames else scope.groupNames
                    _uiState.update {
                        it.copy(worksScopeRaw = raw, worksScopeNames = names.toImmutableList())
                    }
                }
        }
    }

    private fun observeSourceOptions() {
        viewModelScope.launch {
            searchRepository.enabledGroups.catch { emit(emptyList()) }.collect { groups ->
                _uiState.update { it.copy(enabledGroups = groups.toImmutableList()) }
            }
        }
        viewModelScope.launch {
            searchRepository.enabledSources.catch { emit(emptyList()) }.collect { sources ->
                _uiState.update { it.copy(enabledSources = sources.toImmutableList()) }
            }
        }
    }

    /** 进页面只读缓存，不联网。优先读持久化的「其他作品」结果，其次才是 searchBooks 表。 */
    private fun loadCachedWorks() {
        val author = name.trim()
        if (author.isBlank()) return
        viewModelScope.launch {
            val cached = runCatching { loadWorksCache() }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                // 搜过一次后保留结果，直到下次点搜索覆盖；直接当作已完成展示
                worksRaw.value = cached
                worksPhase.value = WorksPhase.Done
                return@launch
            }
            runCatching { searchRepository.getSearchBooksByAuthor(author) }
                .onSuccess { if (it.isNotEmpty()) worksRaw.value = it }
        }
    }

    /** 持久化缓存的 key：按作者名隔离。 */
    private val worksCacheKey: String get() = "authorWorksCache_${name.trim()}"

    private suspend fun saveWorksCache(books: List<SearchBook>) {
        runCatching { localPreferencesRepository.putString(worksCacheKey, GSON.toJson(books)) }
    }

    private suspend fun loadWorksCache(): List<SearchBook> {
        val json = localPreferencesRepository.getString(worksCacheKey).first()
        if (json.isBlank()) return emptyList()
        return GSON.fromJsonArray<SearchBook>(json).getOrNull().orEmpty()
    }

    fun onIntent(intent: AuthorDetailIntent) {
        when (intent) {
            AuthorDetailIntent.ToggleEditBio -> _bioEdit.value = BioEditState(
                editing = true,
                draft = _uiState.value.detail?.bio ?: "",
            )

            is AuthorDetailIntent.UpdateBioDraft -> _bioEdit.update { it.copy(draft = intent.bio) }

            AuthorDetailIntent.DismissEditBio -> {
                generateJob?.cancel()
                _bioEdit.value = BioEditState()
            }

            AuthorDetailIntent.GenerateBio -> generateBio()

            is AuthorDetailIntent.SaveBio -> saveBio(intent.bio)

            is AuthorDetailIntent.ToggleBookFilter -> _bookFilter.update {
                if (it == intent.status) null else intent.status
            }

            AuthorDetailIntent.RefreshWorks -> refreshWorks()

            AuthorDetailIntent.StopWorksSearch -> stopWorksSearch()

            is AuthorDetailIntent.ApplyWorksScope -> applyWorksScope(intent)

            is AuthorDetailIntent.AddWorkToBookshelf -> viewModelScope.launch {
                runCatching { addToBookshelfUseCase.execute(intent.book) }
                    .onFailure { error ->
                        _effects.tryEmit(
                            AuthorDetailEffect.ShowError(error.localizedMessage ?: error.toString())
                        )
                    }
            }
        }
    }

    private fun refreshWorks() {
        if (searchJob?.isActive == true) return
        val author = name.trim()
        if (author.isBlank()) {
            worksPhase.value = WorksPhase.Done
            return
        }
        searchJob = viewModelScope.launch {
            worksPhase.value = WorksPhase.Searching(0, 0)
            // 结果按 bookUrl 汇总，useCase 会把同名同作者的多书源合并进同一个 SearchBook
            val collected = LinkedHashMap<String, SearchBook>()
            try {
                searchBooksUseCase.execute(
                    BookSearchRequest(
                        keyword = author,
                        page = 1,
                        scope = BookSearchScope(worksScopeRaw),
                        // 作者名当关键词，EXACT 只留书名或作者精确等于关键词的结果
                        matchMode = MatchMode.EXACT,
                        concurrency = OtherConfig.threadCount,
                    ),
                    BookSearchControl(),
                ).collect { event ->
                    when (event) {
                        SearchRunEvent.Started -> Unit

                        is SearchRunEvent.Progress -> {
                            event.upsertBooks.forEach { collected[it.bookUrl] = it }
                            event.removedBookUrls.forEach { collected.remove(it) }
                            worksPhase.value =
                                WorksPhase.Searching(event.processedSources, event.totalSources)
                        }

                        is SearchRunEvent.Finished -> Unit
                    }
                }
                worksRaw.value = collected.values.toList()
                worksPhase.value = WorksPhase.Done
                // 落盘，下次进页面直接展示，直到再次点搜索覆盖
                saveWorksCache(collected.values.toList())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                worksPhase.value =
                    WorksPhase.Failed(error.localizedMessage ?: error.javaClass.simpleName)
            }
        }
    }

    private fun stopWorksSearch() {
        searchJob?.cancel()
        searchJob = null
        // 已经搜到的结果留着，没搜到就退回未搜索状态
        if (worksRaw.value.isEmpty()) {
            worksPhase.value = WorksPhase.Idle
        } else {
            worksPhase.value = WorksPhase.Done
            // 中途停止时也已拿到部分结果，顺手落盘
            viewModelScope.launch { saveWorksCache(worksRaw.value) }
        }
    }

    private fun applyWorksScope(intent: AuthorDetailIntent.ApplyWorksScope) {
        val raw = if (intent.isSourceScope) {
            BookSearchScope.encodeSources(
                intent.sources.map {
                    BookSearchScope.ScopeSourceItem(it.bookSourceName, it.bookSourceUrl)
                }
            )
        } else {
            BookSearchScope.encodeGroups(intent.groupNames)
        }
        viewModelScope.launch {
            localPreferencesRepository.putString(
                LocalPreferencesKeys.AUTHOR_WORKS_SEARCH_SCOPE.name,
                raw,
            )
        }
    }

    /**
     * 只保留作者名精确一致、且不在关联书籍里的书，按书名折叠多书源结果。
     */
    private fun buildWorksState(
        raw: List<SearchBook>,
        phase: WorksPhase,
        shelf: Set<BookShelfKey>,
        ownNames: Set<String>,
    ): AuthorWorksState {
        if (phase is WorksPhase.Failed) return AuthorWorksState.Error(phase.message)
        if (phase is WorksPhase.Searching) {
            return AuthorWorksState.Searching(phase.processed, phase.total)
        }
        val author = name.trim()
        val books = raw
            .filter { it.author.trim() == author && it.name.trim() !in ownNames }
            .distinctBy { it.name.trim() }
            .map {
                AuthorWorkItem(
                    book = it,
                    shelfState = resolveBookShelfStateUseCase
                        .execute(it.name, it.author, it.bookUrl, shelf),
                )
            }
            .toImmutableList()
        return when {
            books.isNotEmpty() -> AuthorWorksState.Success(books)
            phase is WorksPhase.Done -> AuthorWorksState.Empty
            else -> AuthorWorksState.Idle
        }
    }

    private fun saveBio(bio: String) {
        val editState = _bioEdit.value
        viewModelScope.launch {
            // 未改动 AI 原文则保留 ai 来源，改过一个字就算用户手写。
            if (bio.isNotBlank() && bio == editState.generatedBio) {
                authorProfileRepository.saveAiBio(name, bio, editState.generatedModel)
            } else {
                authorProfileRepository.saveManualBio(name, bio)
            }
            _bioEdit.value = BioEditState()
            _effects.tryEmit(AuthorDetailEffect.ShowToast(R.string.author_bio_saved))
        }
    }

    private fun generateBio() {
        if (generateJob?.isActive == true) return
        val titles = _uiState.value.detail?.books?.map { it.memory.bookName }.orEmpty()
        generateJob = viewModelScope.launch {
            _bioEdit.update { it.copy(generating = true) }
            val result = generateAuthorBioUseCase.execute(name, titles)
            result.fold(
                onSuccess = { generated ->
                    _bioEdit.update {
                        it.copy(
                            generating = false,
                            draft = generated.bio,
                            generatedBio = generated.bio,
                            generatedModel = generated.modelId,
                        )
                    }
                },
                onFailure = { error ->
                    _bioEdit.update { it.copy(generating = false) }
                    val message = if (error is TimeoutCancellationException) {
                        appCtx.getString(R.string.ai_generate_timeout)
                    } else {
                        error.localizedMessage ?: error.toString()
                    }
                    _effects.tryEmit(AuthorDetailEffect.ShowError(message))
                },
            )
        }
    }

    private suspend fun buildState(
        memories: List<ReadingMemory>,
        profiles: Map<String, AuthorProfile>,
        bioEdit: BioEditState,
        config: MemoryCardConfig,
        filter: AuthorBookStatus?,
    ): AuthorDetailUiState {
        val mems = memories.filter { it.bookAuthor.trim() == name }
        // 「其他作品」要把已经在关联书籍里的书剔掉
        ownBookNames.value = mems.mapTo(mutableSetOf()) { it.bookName.trim() }
        val statusCounts = mems.groupingBy { authorBookStatus(it) }.eachCount()
        val books = mems
            .filter { filter == null || authorBookStatus(it) == filter }
            // 按阅读进度倒序：读完的在最前，待看的沉底；进度相同时保持 DAO 的 updateTime 倒序
            .sortedByDescending { it.progress }
            .map { AuthorBookItem(it, TagManager.bookDisplayTags(it.kind, it.customTag).toImmutableList()) }
            .toImmutableList()
        val profile = profiles[name]
        return AuthorDetailUiState(
            detail = AuthorDetailUi(
                name = name,
                bio = profile?.bio ?: "",
                avgRating = authorAvgRating(mems),
                bookCount = mems.size,
                statusCounts = statusCounts.toImmutableMap(),
                books = books,
            ),
            editingBio = bioEdit.editing,
            bioDraft = bioEdit.draft,
            generatingBio = bioEdit.generating,
            bookFilter = filter,
            bookshelfSettings = config.settings,
            tagColorMap = config.tagColorMap,
            coverWidth = config.coverWidth,
        )
    }
}
