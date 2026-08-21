package io.legado.app.ui.main.my.authorManage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.help.book.TagManager
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
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
    private val tagColorMap: StateFlow<ImmutableMap<String, Long>> = combine(
        repository.observeTagColorMap(),
        themeSettingsGateway.settings.map { it.enableCustomTagColors },
    ) { colors, enabled ->
        if (enabled) colors.toImmutableMap() else persistentMapOf()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentMapOf())

    init {
        viewModelScope.launch {
            combine(
                repository.observeAll(),
                AuthorProfileStore.observeBios(),
                _editingBio,
                bookshelfSettings,
                tagColorMap,
            ) { memories, bios, editingBio, settings, colorMap ->
                buildState(memories, bios, editingBio, settings, colorMap)
            }.flowOn(Dispatchers.Default).collect { _uiState.value = it }
        }
    }

    fun onIntent(intent: AuthorDetailIntent) {
        when (intent) {
            AuthorDetailIntent.ToggleEditBio -> _editingBio.value = true
            AuthorDetailIntent.DismissEditBio -> _editingBio.value = false
            is AuthorDetailIntent.SaveBio -> {
                AuthorProfileStore.saveBio(name, intent.bio)
                _editingBio.value = false
                _effects.tryEmit(AuthorDetailEffect.ShowToast(R.string.author_bio_saved))
            }
        }
    }

    private suspend fun buildState(
        memories: List<ReadingMemory>,
        bios: Map<String, String>,
        editingBio: Boolean,
        settings: BookshelfSettings,
        colorMap: ImmutableMap<String, Long>,
    ): AuthorDetailUiState {
        val mems = memories.filter { it.bookAuthor.trim() == name }
        val books = mems
            .map { AuthorBookItem(it, TagManager.bookDisplayTags(it.kind, it.customTag).toImmutableList()) }
            .toImmutableList()
        return AuthorDetailUiState(
            detail = AuthorDetailUi(
                name = name,
                bio = bios[name] ?: "",
                avgRating = authorAvgRating(mems),
                readBookCount = mems.count { isAuthorBookFinished(it) },
                bookCount = mems.size,
                books = books,
            ),
            editingBio = editingBio,
            bookshelfSettings = settings,
            tagColorMap = colorMap,
        )
    }
}
