package io.legado.app.ui.book.readaloud.casting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.ReadAloudVoiceTraits
import io.legado.app.help.readaloud.cast.BookCastStore
import io.legado.app.help.readaloud.cast.VoicePoolStore
import io.legado.app.help.readaloud.playback.VoicePreviewSynthesizer
import io.legado.app.model.ReadAloudSessionStore
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.knowledge.deleteCharacterAvatar
import io.legado.app.ui.book.readaloud.player.restartReadAloudPipeline
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    private val readAloudSessionStore: ReadAloudSessionStore,
    /** 只为拿排版配置名列表：角色气泡弹层里「应用排版」那一节与高亮规则用同一份候选。 */
    private val styleGateway: ReadStyleGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookVoiceCastingUiState(bookUrl = bookUrl))
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<BookVoiceCastingEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private var profiles: List<BookCharacterProfile> = emptyList()
    private var castRows: Map<String, BookCastStore.BookCharacterRow> = emptyMap()
    private var voices: List<ReadAloudVoice> = emptyList()
    private var bindings: List<BookVoiceBinding> = emptyList()

    /** 音色 → 引擎显示名，进页面/刷新时取一次，给旁白的音色候选当副标题。 */
    private var engineNames: Map<String, String> = emptyMap()

    /** 档案 id → 声音池显示名；进页面/刷新时在 IO 上解析一次，publish 不再逐条查库。 */
    private var poolLabels: Map<String, String> = emptyMap()
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
            is BookVoiceCastingIntent.SetVoiceGenderFilter -> {
                // 再点一次已选中的性别就回到不筛选
                val gender = intent.gender.takeUnless { it == _uiState.value.voiceGenderFilter }
                    .orEmpty()
                _uiState.update { it.copy(voiceGenderFilter = gender) }
                publishState()
            }
            is BookVoiceCastingIntent.AssignVoice -> assignVoice(intent.voiceId)
            is BookVoiceCastingIntent.PreviewVoice -> previewVoice(intent.voiceId)
            BookVoiceCastingIntent.ClearBinding -> clearBinding()
            is BookVoiceCastingIntent.PromoteCharacter -> promoteCharacter(intent.subjectId)
            is BookVoiceCastingIntent.SetRowVoice ->
                setRowVoice(intent.subjectType, intent.subjectId, intent.voiceId)
            is BookVoiceCastingIntent.SaveCharacterOrder -> saveCharacterOrder(intent.characterIds)
            is BookVoiceCastingIntent.SetAvatar -> setAvatar(intent.characterId, intent.avatarUri)
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val (liveProfiles, castRowsById) = withContext(Dispatchers.IO) {
                    // 人物档案与配音角色是同一批人：rowsForBook 会双向补齐缺的那一边
                    // （只有档案的补成角色、只有角色的补出档案），所以先跑它再读列表，
                    // 否则老角色会在这页显示成「还没有人物档案」。
                    val rows = BookCastStore.rowsForBook(bookUrl).associateBy { it.id }
                    engineNames = VoicePoolStore.voiceEngineNames()
                    val profilesNow = bookKnowledgeGateway
                        .getCharacterProfiles(bookUrl, 200, includeDrafts = true)
                        .filter {
                            it.status == BookCharacterProfile.STATUS_ACTIVE ||
                                it.status == BookCharacterProfile.STATUS_DRAFT
                        }
                    poolLabels = profilesNow.associate { it.id to VoicePoolStore.poolLabelOf(it) }
                    profilesNow to rows
                }
                profiles = liveProfiles
                castRows = castRowsById
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
        // 只列旁白：不列「未知男/未知女/未知」三条兜底行——旁白这里只需要选一个音色
        val specialItems = listOf(
            specialItem(BookVoiceBinding.SUBJECT_NARRATOR, CastingSubjectKind.Narrator),
        )
        val characterItems = profiles.map { profile ->
            val row = castRows[profile.id]
            VoiceCastingItemUi(
                subjectType = BookVoiceBinding.SUBJECT_CHARACTER,
                subjectId = profile.id,
                kind = if (profile.status == BookCharacterProfile.STATUS_DRAFT) {
                    CastingSubjectKind.TemporaryCharacter
                } else {
                    CastingSubjectKind.Character
                },
                name = row?.name ?: profile.name,
                // 档案的 role 存的是 male_lead 这类英文键，直接显示就成了英文；
                // 这里翻成中文名，未知值原样保留以便暴露新增取值
                description = castRoleLabelRes(profile.role)?.let(appCtx::getString)
                    ?: profile.role,
                role = profile.role,
                avatarUri = profile.avatarUri,
                poolLabel = row?.poolLabel
                    ?: poolLabels[profile.id].orEmpty(),
                voiceEffect = row?.voiceEffect.orEmpty(),
                chapterCount = row?.chapterCount ?: 0,
                lineCount = row?.lineCount ?: 0,
                sortOrder = row?.sortOrder ?: 0,
                bubbleRuleJson = row?.bubbleRuleJson.orEmpty(),
            )
        }.sortedWith(
            // 拖过排序的（sortOrder > 0）就按用户排的那份走；没排过的排在后面，
            // 其中男女主 / 男女配默认置顶（角色档次 ordinal 小的在前），再按台词数、名字。
            compareBy<VoiceCastingItemUi> { if (it.sortOrder > 0) it.sortOrder else Int.MAX_VALUE }
                .thenBy { castRoleTierOf(it.role).ordinal }
                .thenByDescending { it.lineCount }
                .thenBy { it.name }
        )
        val items = (specialItems + characterItems).map { item ->
            val binding = bindingsBySubject[item.subjectType to item.subjectId]
            val voice = binding?.voiceId?.let(voicesById::get)
            item.copy(
                hasBinding = binding != null,
                voiceId = binding?.voiceId.orEmpty(),
                voiceName = voice?.displayName.orEmpty(),
                // excluded 的音色不进选音候选（见 ReadAloudVoiceDao.getEnabledVoices），
                // 这里漏判会让"已绑定"看着是好的，实际早就不发声了
                voiceAvailable = voice?.let { it.available && it.enabled && !it.excluded } == true,
                // 这一行的声音是谁定的：机器补的一律 source=auto；
                // 没锁定的老行（那批由补音链写下、当时 source 还是默认值 user 的）也算机器补的，
                // 否则配音页会把自动选出来的音色一路显示成"你选的"。
                bindingSourceAuto = binding != null &&
                    (binding.source == BookVoiceBinding.SOURCE_AUTO || !binding.locked),
            )
        }
        val voiceOptions = voices.map { voice ->
            val traits = ReadAloudVoiceTraits.of(voice)
            VoiceOptionUi(
                id = voice.id,
                name = voice.displayName,
                engineType = voice.engineType,
                engineName = engineNames[voice.id] ?: voice.engineId,
                selectable = voice.available && voice.enabled && !voice.excluded,
                gender = traits.gender,
                // 一行装不下太多标签, 只取前 3 条
                descriptors = traits.descriptors.take(3).toImmutableList(),
            )
        }.sortedWith(
            compareByDescending<VoiceOptionUi> { it.selectable }
                .thenBy { it.engineType }
                .thenBy { it.name.lowercase() }
        ).filter { option ->
            val filter = _uiState.value.voiceGenderFilter
            filter.isEmpty() || option.gender == filter
        }
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
                configNames = styleGateway.currentState.items
                    .map { config -> config.name }
                    .filter { it.isNotBlank() }
                    .toImmutableList(),
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
                restartPipelineIfListening()
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
                restartPipelineIfListening()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showSaveError(e)
            }
        }
    }

    /**
     * 单音色面板的保存：只写传进来的那一个主体，不认死旁白。
     *
     * 旁白不是角色——没有名字、不进声音池、也没有角色级变声器那一列可写，它的有效状态
     * 就是 book_voice_bindings 里的一条 (narrator, narrator)。草稿角色卡共用同一张面板，
     * 但它的有效状态是 (character, 档案 id) 那一条，所以主体必须由界面一起传过来。
     *
     * 空 voiceId 是删绑定：旁白行回到朗读引擎自己的默认发音人，角色行回到自动选音。
     * 每次写入都把主体记进日志，这是"选一个音、整本书变声"的回归观察点——
     * 再出现那个症状，先看这一行写的是 (narrator, narrator) 还是 (character, ...)。
     */
    private fun setRowVoice(subjectType: String, subjectId: String, voiceId: String) {
        val rowLabel = rowLabel(subjectType, subjectId)
        val binding = bindings.firstOrNull {
            it.subjectType == subjectType && it.subjectId == subjectId
        }
        if (voiceId.isBlank()) {
            val doomed = binding ?: return
            AppLog.put("配音页: 取消「$rowLabel」的音色绑定（原来是 ${doomed.voiceId}）")
            viewModelScope.launch {
                try {
                    voiceGateway.deleteBinding(doomed)
                    restartPipelineIfListening()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    showSaveError(e)
                }
            }
            return
        }
        val voice = voices.firstOrNull { it.id == voiceId && it.available && it.enabled }
        if (voice == null) {
            AppLog.put("配音页: 「$rowLabel」要绑的音色不可用（$voiceId），本次没有写入")
            return
        }
        AppLog.put("配音页: 「$rowLabel」绑定音色 ${voice.displayName}（$subjectType/$subjectId）")
        viewModelScope.launch {
            try {
                voiceGateway.upsertBinding(
                    BookVoiceBinding(
                        bookUrl = bookUrl,
                        subjectType = subjectType,
                        subjectId = subjectId,
                        voiceId = voice.id,
                        locked = true,
                        source = BookVoiceBinding.SOURCE_USER,
                        confidence = 1f,
                        createdAt = binding?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                _uiState.update { it.copy(picker = null) }
                restartPipelineIfListening()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showSaveError(e)
            }
        }
    }

    /** 日志里说清这一行是谁：用界面上正在显示的名字，查不到才退回主体键。 */
    private fun rowLabel(subjectType: String, subjectId: String): String =
        _uiState.value.items.firstOrNull {
            it.subjectType == subjectType && it.subjectId == subjectId
        }?.name ?: "$subjectType/$subjectId"

    private fun saveCharacterOrder(characterIds: List<String>) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    BookCastStore.saveCharacterOrder(bookUrl, characterIds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showSaveError(e)
            }
        }
    }

    /**
     * 卡片菜单里换头像：只改档案上那一列，其余字段原样写回。
     *
     * 换下来的那份是本地图就删掉（已经没人引用它了）；正文胶囊上那张图是分页时
     * 定下来的地址，不重排一次就还是旧头像。
     */
    private fun setAvatar(characterId: String, avatarUri: String) {
        val profile = profiles.firstOrNull { it.id == characterId } ?: return
        val updated = profile.copy(avatarUri = avatarUri.trim().takeIf { it.isNotBlank() })
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    bookKnowledgeGateway.upsertCharacterProfile(updated)
                    if (profile.avatarUri != updated.avatarUri) {
                        deleteCharacterAvatar(appCtx, profile.avatarUri)
                    }
                    BookCastStore.reloadReaderChapter(bookUrl)
                }
                profiles = profiles.map { if (it.id == updated.id) updated else it }
                publishState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showSaveError(e)
            }
        }
    }

    /**
     * 正在朗读这本书时，绑定变化必须重启合成管线才能换掉当前章的声音。
     *
     * 朗读计划是章准备时一次性建好放进播放队列的，只写数据库的话界面显示会更新、
     * 声音要等下次重建计划才变，观感就是「显示变了声音没变」。换书朗读时不重启别人的管线。
     */
    private fun restartPipelineIfListening() {
        if (ReadBook.book?.bookUrl != bookUrl) return
        viewModelScope.launch {
            // 返回上一页会销毁本 ViewModel；重启必须跑完，否则只 stop 不 play，声音直接丢。
            withContext(NonCancellable) {
                restartReadAloudPipeline(readAloudSessionStore, appCtx)
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
