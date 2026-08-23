package io.legado.app.ui.main.my.authorManage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.AuthorProfile
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.AuthorProfileRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.domain.usecase.GenerateAuthorBioUseCase
import io.legado.app.help.book.TagManager
import io.legado.app.help.config.AppConfigStore
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

class AuthorDetailViewModel(
    private val name: String,
    private val repository: ReadingMemoryRepository,
    private val authorProfileRepository: AuthorProfileRepository,
    private val generateAuthorBioUseCase: GenerateAuthorBioUseCase,
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
    private val themeSettingsGateway: ThemeSettingsGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorDetailUiState())
    val uiState: StateFlow<AuthorDetailUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AuthorDetailEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<AuthorDetailEffect> = _effects.asSharedFlow()

    private val _bioEdit = MutableStateFlow(BioEditState())
    private val _bookFilter = MutableStateFlow<AuthorBookStatus?>(null)
    private var generateJob: Job? = null

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
            }.flowOn(Dispatchers.Default).collect { _uiState.value = it }
        }
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
                    _effects.tryEmit(
                        AuthorDetailEffect.ShowError(
                            error.localizedMessage ?: error.toString()
                        )
                    )
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
