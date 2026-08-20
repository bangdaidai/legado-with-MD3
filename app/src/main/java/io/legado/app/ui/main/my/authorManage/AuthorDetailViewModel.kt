package io.legado.app.ui.main.my.authorManage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.help.book.TagManager
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
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

class AuthorDetailViewModel(
    private val name: String,
    private val repository: ReadingMemoryRepository,
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
    private val themeSettingsGateway: ThemeSettingsGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorDetailUiState())
    val uiState: StateFlow<AuthorDetailUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AuthorDetailEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<AuthorDetailEffect> = _effects.asSharedFlow()

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
                listOf<Flow<Any?>>(
                    repository.observeAll(),
                    AuthorProfileStore.observeBios(),
                    _editingBio,
                    bookshelfSettings,
                    tagColorMap,
                )
            ) { a ->
                val memories = a[0] as List<ReadingMemory>
                val bios = a[1] as Map<String, String>
                val editingBio = a[2] as Boolean
                val settings = a[3] as BookshelfSettings
                val colorMap = a[4] as Map<String, Long>
                buildState(memories, bios, editingBio, settings, colorMap)
            }.collect { _uiState.value = it }
        }
    }

    fun onIntent(intent: AuthorDetailIntent) {
        when (intent) {
            AuthorDetailIntent.ToggleEditBio -> _editingBio.value = true
            AuthorDetailIntent.DismissEditBio -> _editingBio.value = false
            is AuthorDetailIntent.SaveBio -> {
                AuthorProfileStore.saveBio(name, intent.bio)
                _editingBio.value = false
                _effects.tryEmit(AuthorDetailEffect.ShowToast("简介已保存"))
            }
        }
    }

    private suspend fun buildState(
        memories: List<ReadingMemory>,
        bios: Map<String, String>,
        editingBio: Boolean,
        settings: BookshelfSettings,
        colorMap: Map<String, Long>,
    ): AuthorDetailUiState {
        val mems = memories.filter { it.bookAuthor == name }
        val finished = mems.filter { isAuthorBookFinished(it) }
        val books = mems
            .map { AuthorBookItem(it, TagManager.bookDisplayTags(it.kind, it.customTag).toImmutableList()) }
            .toImmutableList()
        return AuthorDetailUiState(
            detail = AuthorDetailUi(
                name = name,
                bio = bios[name] ?: "",
                avgRating = authorAvgRating(finished),
                readBookCount = finished.size,
                bookCount = mems.size,
                books = books,
            ),
            editingBio = editingBio,
            bookshelfSettings = settings,
            tagColorMap = colorMap,
        )
    }
}
