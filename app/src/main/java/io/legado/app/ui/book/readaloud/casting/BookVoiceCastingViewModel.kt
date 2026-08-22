package io.legado.app.ui.book.readaloud.casting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.ReadAloudVoiceTraits
import io.legado.app.help.readaloud.playback.VoicePreviewSynthesizer
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

class BookVoiceCastingViewModel(
    private val bookUrl: String,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
    private val voiceGateway: ReadAloudVoiceGateway,
    private val previewSynthesizer: VoicePreviewSynthesizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookVoiceCastingUiState(bookUrl = bookUrl))
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<BookVoiceCastingEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private var profiles: List<BookCharacterProfile> = emptyList()
    private var voices: List<ReadAloudVoice> = emptyList()
    private var bindings: List<BookVoiceBinding> = emptyList()
    private var loadJob: Job? = null
    private var previewJob: Job? = null

    init {
        load()
    }

    fun onIntent(intent: BookVoiceCastingIntent) {
        when (intent) {
            BookVoiceCastingIntent.Refresh -> load()
            is BookVoiceCastingIntent.OpenVoicePicker -> openVoicePicker(intent)
            BookVoiceCastingIntent.DismissVoicePicker -> {
                _uiState.update { it.copy(picker = null) }
            }
            is BookVoiceCastingIntent.AssignVoice -> assignVoice(intent.voiceId)
            is BookVoiceCastingIntent.PreviewVoice -> previewVoice(intent.voiceId)
            BookVoiceCastingIntent.ClearBinding -> clearBinding()
            is BookVoiceCastingIntent.PromoteCharacter -> promoteCharacter(intent.subjectId)
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                profiles = withContext(Dispatchers.IO) {
                    bookKnowledgeGateway.getCharacterProfiles(bookUrl, 200, includeDrafts = true)
                        .filter {
                            it.status == BookCharacterProfile.STATUS_ACTIVE ||
                                it.status == BookCharacterProfile.STATUS_DRAFT
                        }
                }
                combine(
                    voiceGateway.observeVoices(),
                    voiceGateway.observeBindings(bookUrl),
                ) { latestVoices, latestBindings -> latestVoices to latestBindings }
                    .collect { (latestVoices, latestBindings) ->
                        voices = latestVoices
                        bindings = latestBindings
                        publishState()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false) }
                _effects.tryEmit(
                    BookVoiceCastingEffect.ShowToast(
                        e.localizedMessage ?: appCtx.getString(R.string.load_failed)
                    )
                )
            }
        }
    }

    private fun publishState() {
        val voicesById = voices.associateBy(ReadAloudVoice::id)
        val bindingsBySubject = bindings.associateBy { it.subjectType to it.subjectId }
        val specialItems = listOf(
            specialItem(BookVoiceBinding.SUBJECT_NARRATOR, CastingSubjectKind.Narrator),
            specialItem(BookVoiceBinding.SUBJECT_UNKNOWN_MALE, CastingSubjectKind.UnknownMale),
            specialItem(BookVoiceBinding.SUBJECT_UNKNOWN_FEMALE, CastingSubjectKind.UnknownFemale),
            specialItem(BookVoiceBinding.SUBJECT_UNKNOWN, CastingSubjectKind.Unknown),
        )
        val characterItems = profiles.map { profile ->
            VoiceCastingItemUi(
                subjectType = BookVoiceBinding.SUBJECT_CHARACTER,
                subjectId = profile.id,
                kind = if (profile.status == BookCharacterProfile.STATUS_DRAFT) {
                    CastingSubjectKind.TemporaryCharacter
                } else {
                    CastingSubjectKind.Character
                },
                name = profile.name,
                description = profile.role,
                avatarUri = profile.avatarUri,
            )
        }
        val items = (specialItems + characterItems).map { item ->
            val binding = bindingsBySubject[item.subjectType to item.subjectId]
            val voice = binding?.voiceId?.let(voicesById::get)
            item.copy(
                hasBinding = binding != null,
                voiceName = voice?.displayName.orEmpty(),
                voiceAvailable = voice?.let { it.available && it.enabled } == true,
            )
        }
        val voiceOptions = voices.map { voice ->
            val traits = ReadAloudVoiceTraits.of(voice)
            VoiceOptionUi(
                id = voice.id,
                name = voice.displayName,
                engineType = voice.engineType,
                engineName = voice.engineId,
                selectable = voice.available && voice.enabled,
                gender = traits.gender,
                // 一行装不下太多标签, 只取前 3 条
                descriptors = traits.descriptors.take(3).toImmutableList(),
            )
        }.sortedWith(
            compareByDescending<VoiceOptionUi> { it.selectable }
                .thenBy { it.engineType }
                .thenBy { it.name.lowercase() }
        )
        val currentPicker = _uiState.value.picker?.let { picker ->
            val item = items.firstOrNull {
                it.subjectType == picker.subjectType && it.subjectId == picker.subjectId
            } ?: return@let null
            val selectedVoiceId = bindingsBySubject[item.subjectType to item.subjectId]?.voiceId
            picker.copy(selectedVoiceId = selectedVoiceId)
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                items = items.toImmutableList(),
                voices = voiceOptions.toImmutableList(),
                picker = currentPicker,
            )
        }
    }

    private fun specialItem(subject: String, kind: CastingSubjectKind) = VoiceCastingItemUi(
        subjectType = subject,
        subjectId = subject,
        kind = kind,
        name = "",
    )

    private fun openVoicePicker(intent: BookVoiceCastingIntent.OpenVoicePicker) {
        val item = _uiState.value.items.firstOrNull {
            it.subjectType == intent.subjectType && it.subjectId == intent.subjectId
        } ?: return
        val selectedVoiceId = bindings.firstOrNull {
            it.subjectType == item.subjectType && it.subjectId == item.subjectId
        }?.voiceId
        _uiState.update {
            it.copy(
                picker = VoicePickerUi(
                    subjectType = item.subjectType,
                    subjectId = item.subjectId,
                    kind = item.kind,
                    name = item.name,
                    selectedVoiceId = selectedVoiceId,
                )
            )
        }
    }

    private fun assignVoice(voiceId: String) {
        val picker = _uiState.value.picker ?: return
        val voice = voices.firstOrNull { it.id == voiceId && it.available && it.enabled } ?: return
        val old = bindings.firstOrNull {
            it.subjectType == picker.subjectType && it.subjectId == picker.subjectId
        }
        viewModelScope.launch {
            try {
                voiceGateway.upsertBinding(
                    BookVoiceBinding(
                        bookUrl = bookUrl,
                        subjectType = picker.subjectType,
                        subjectId = picker.subjectId,
                        voiceId = voice.id,
                        locked = true,
                        source = BookVoiceBinding.SOURCE_USER,
                        confidence = 1f,
                        createdAt = old?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                _uiState.update { it.copy(picker = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showSaveError(e)
            }
        }
    }

    /**
     * 试听音色表里的一条音色，不改绑定。
     *
     * 合成走 [VoicePreviewSynthesizer]，和真正朗读同一条路径；播放交给界面的 MediaPlayer。
     */
    private fun previewVoice(voiceId: String) {
        val voice = voices.firstOrNull { it.id == voiceId && it.available && it.enabled } ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _uiState.update { it.copy(previewingVoiceId = voiceId) }
            try {
                val file = File(appCtx.cacheDir, "voice_preview/${voiceId.hashCode()}.audio")
                val ok = previewSynthesizer.synthesize(
                    voice = voice,
                    text = appCtx.getString(R.string.voice_preview_text),
                    output = file,
                )
                if (ok) {
                    _effects.tryEmit(BookVoiceCastingEffect.PlayPreview(file.absolutePath))
                } else {
                    _effects.tryEmit(
                        BookVoiceCastingEffect.ShowToast(
                            appCtx.getString(R.string.voice_preview_failed)
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _effects.tryEmit(
                    BookVoiceCastingEffect.ShowToast(
                        e.localizedMessage ?: appCtx.getString(R.string.voice_preview_failed)
                    )
                )
            } finally {
                _uiState.update {
                    if (it.previewingVoiceId == voiceId) it.copy(previewingVoiceId = null) else it
                }
            }
        }
    }

    /**
     * 用户点「转正」才把草稿角色卡转成正式角色卡。
     *
     * 角色卡 id 不变，所以 [BookVoiceBinding.subjectId] 一个字都不用改，不存在绑定迁移。
     */
    private fun promoteCharacter(subjectId: String) {
        val draft = profiles.firstOrNull {
            it.id == subjectId && it.status == BookCharacterProfile.STATUS_DRAFT
        } ?: return
        viewModelScope.launch {
            try {
                val promoted = draft.copy(
                    status = BookCharacterProfile.STATUS_ACTIVE,
                    updatedAt = System.currentTimeMillis(),
                )
                withContext(Dispatchers.IO) {
                    bookKnowledgeGateway.upsertCharacterProfile(promoted)
                }
                profiles = profiles.map { if (it.id == promoted.id) promoted else it }
                publishState()
                _effects.tryEmit(
                    BookVoiceCastingEffect.ShowToast(
                        appCtx.getString(R.string.voice_promoted_to_character, promoted.name)
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showSaveError(e)
            }
        }
    }

    private fun clearBinding() {
        val picker = _uiState.value.picker ?: return
        val binding = bindings.firstOrNull {
            it.subjectType == picker.subjectType && it.subjectId == picker.subjectId
        } ?: run {
            _uiState.update { it.copy(picker = null) }
            return
        }
        viewModelScope.launch {
            try {
                voiceGateway.deleteBinding(binding)
                _uiState.update { it.copy(picker = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showSaveError(e)
            }
        }
    }

    private fun showSaveError(error: Throwable) {
        _effects.tryEmit(
            BookVoiceCastingEffect.ShowToast(
                error.localizedMessage ?: appCtx.getString(R.string.save_error)
            )
        )
    }
}
