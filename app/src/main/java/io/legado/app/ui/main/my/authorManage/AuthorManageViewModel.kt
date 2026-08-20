package io.legado.app.ui.main.my.authorManage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.help.book.TagManager
import io.legado.app.ui.book.readingmemory.ReadingMemoryStatusFilter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AuthorManageViewModel(
    private val repository: ReadingMemoryRepository,
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
    private val themeSettingsGateway: ThemeSettingsGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorManageUiState())
    val uiState: StateFlow<AuthorManageUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AuthorManageEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<AuthorManageEffect> = _effects.asSharedFlow()

    private val _sortBy = MutableStateFlow(AuthorSort.Rating)
    private val _selectedAuthor = MutableStateFlow<String?>(null)
    private val _detailStatus = MutableStateFlow(ReadingMemoryStatusFilter.Finished)
    private val _editingBio = MutableStateFlow(false)

    private val bookshelfSettings = bookshelfSettingsGateway.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfSettings(),
    )
    private val tagColorMap = combine(
        repository.observeTagColorMap(),
        themeSettingsGateway.settings.map { it.enableCustomTagColors },
    ) { colors, enabled -> if (enabled) colors else emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        viewModelScope.launch {
            combine(
                listOf<kotlinx.coroutines.flow.Flow<Any?>>(
                    repository.observeAll(),
                    AuthorProfileStore.observeBios(),
                    _sortBy,
                    _selectedAuthor,
                    _detailStatus,
                    _editingBio,
                    bookshelfSettings,
                    tagColorMap,
                )
            ) { a ->
                val memories = a[0] as List<ReadingMemory>
                val bios = a[1] as Map<String, String>
                val sortBy = a[2] as AuthorSort
                val selected = a[3] as String?
                val detailStatus = a[4] as ReadingMemoryStatusFilter
                val editingBio = a[5] as Boolean
                val settings = a[6] as BookshelfSettings
                val colorMap = a[7] as Map<String, Long>
                buildState(memories, bios, sortBy, selected, detailStatus, editingBio, settings, colorMap)
            }.collect { _uiState.value = it }
        }
    }

    fun onIntent(intent: AuthorManageIntent) {
        when (intent) {
            is AuthorManageIntent.SetSort -> _sortBy.value = intent.sort
            is AuthorManageIntent.ClickAuthor -> _selectedAuthor.value = intent.name
            AuthorManageIntent.Back -> _selectedAuthor.value = null
            is AuthorManageIntent.SetDetailStatus -> _detailStatus.value = intent.status
            is AuthorManageIntent.ToggleEditBio -> _editingBio.value = intent.show
            is AuthorManageIntent.SaveBio -> {
                AuthorProfileStore.saveBio(intent.name, intent.bio)
                _editingBio.value = false
                _effects.tryEmit(AuthorManageEffect.ShowToast("简介已保存"))
            }
        }
    }

    private suspend fun buildState(
        memories: List<ReadingMemory>,
        bios: Map<String, String>,
        sortBy: AuthorSort,
        selected: String?,
        detailStatus: ReadingMemoryStatusFilter,
        editingBio: Boolean,
        settings: BookshelfSettings,
        colorMap: Map<String, Long>,
    ): AuthorManageUiState {
        val authors = buildAuthors(memories, bios, sortBy)
        val detail = if (selected != null) buildDetail(selected, memories, bios) else null
        return AuthorManageUiState(
            authors = authors,
            sortBy = sortBy,
            selectedAuthorName = selected,
            detailStatus = detailStatus,
            detail = detail,
            editingBio = editingBio,
            bookshelfSettings = settings,
            tagColorMap = colorMap,
        )
    }

    private fun statusOf(m: ReadingMemory): ReadingMemoryStatusFilter = when {
        m.abandoned -> ReadingMemoryStatusFilter.Abandoned
        m.progress >= 1f -> ReadingMemoryStatusFilter.Finished
        m.progress > 0f -> ReadingMemoryStatusFilter.Reading
        else -> ReadingMemoryStatusFilter.ToRead
    }

    /** 该作者已读书籍评分的平均分（仅统计有评分的已读书，否则为 0）。 */
    private fun avgRating(mems: List<ReadingMemory>): Float {
        val rated = mems.filter { it.rating > 0f }
        if (rated.isEmpty()) return 0f
        return (rated.sumOf { it.rating.toDouble() } / rated.size).toFloat()
    }

    private fun buildAuthors(
        memories: List<ReadingMemory>,
        bios: Map<String, String>,
        sortBy: AuthorSort,
    ): ImmutableList<AuthorItemUi> {
        val byAuthor = memories.filter { it.bookAuthor.isNotBlank() }
            .groupBy { it.bookAuthor }
        val list = byAuthor.map { (name, mems) ->
            val finished = mems.filter { statusOf(it) == ReadingMemoryStatusFilter.Finished }
            AuthorItemUi(
                name = name,
                bookCount = mems.size,
                readBookCount = finished.size,
                avgRating = avgRating(finished),
                bio = bios[name] ?: "",
            )
        }
        val sorted = when (sortBy) {
            AuthorSort.Name -> list.sortedBy { it.name }
            AuthorSort.BookCount -> list.sortedByDescending { it.bookCount }
            AuthorSort.Rating -> list.sortedByDescending { it.avgRating }
        }
        return sorted.toImmutableList()
    }

    private suspend fun buildDetail(
        name: String,
        memories: List<ReadingMemory>,
        bios: Map<String, String>,
    ): AuthorDetailUi {
        val mems = memories.filter { it.bookAuthor == name }
        val finished = mems.filter { statusOf(it) == ReadingMemoryStatusFilter.Finished }
        val booksByStatus = ReadingMemoryStatusFilter.entries
            .filter { it != ReadingMemoryStatusFilter.All }
            .associateWith { status ->
                mems.filter { statusOf(it) == status }
                    .map { AuthorBookItem(it, TagManager.bookDisplayTags(it.kind, it.customTag).toImmutableList()) }
                    .toImmutableList()
            }
        return AuthorDetailUi(
            name = name,
            bio = bios[name] ?: "",
            avgRating = avgRating(finished),
            readBookCount = finished.size,
            bookCount = mems.size,
            booksByStatus = booksByStatus,
        )
    }
}
