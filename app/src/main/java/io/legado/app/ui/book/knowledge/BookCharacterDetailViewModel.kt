package io.legado.app.ui.book.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.feature.reader.legacy.HighlightProtagonistPatterns
import io.legado.app.help.readaloud.cast.BookCastStore
import io.legado.app.help.readaloud.cast.CastMemoryMirror
import io.legado.app.help.readaloud.cast.VoicePoolStore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.uuid.Uuid

class BookCharacterDetailViewModel(
    bookUrl: String,
    characterId: String?,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CharacterDetailUiState(
            bookUrl = bookUrl,
            characterId = characterId,
            isLoading = true,
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CharacterDetailEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private var currentProfile: BookCharacterProfile? = null

    /** 落库串行锁：见 [save]，单栏动作连续触发时整行写入不能互相插队。 */
    private val saveMutex = Mutex()

    init {
        load()
    }

    fun onIntent(intent: CharacterDetailIntent) {
        when (intent) {
            is CharacterDetailIntent.ApplyProfile -> updateAndSave {
                it.copy(
                    name = intent.name,
                    aliasesText = intent.aliasesText,
                    role = intent.role,
                    voiceGender = intent.voiceGender,
                    voicePool = intent.voicePool,
                    isProtagonist = intent.isProtagonist,
                )
            }

            is CharacterDetailIntent.SetAvatarUri -> updateAndSave { it.copy(avatarUri = intent.value) }
            is CharacterDetailIntent.SetTagInput -> _uiState.update { it.copy(tagInput = intent.value) }
            is CharacterDetailIntent.AddTag -> updateAndSave {
                val tag = intent.tag.trim()
                if (tag.isNotBlank()) {
                    it.copy(
                        tags = (it.tags + tag).distinct().toImmutableList(),
                        tagInput = "",
                    )
                } else {
                    it
                }
            }

            is CharacterDetailIntent.RemoveTag -> updateAndSave {
                it.copy(tags = it.tags.filterIndexed { index, _ -> index != intent.index }
                    .toImmutableList())
            }

            is CharacterDetailIntent.SetPersonality -> updateAndSave { it.copy(personality = intent.value) }
            is CharacterDetailIntent.SetSummary -> updateAndSave { it.copy(summary = intent.value) }
            is CharacterDetailIntent.Delete -> delete(intent)
        }
    }

    /** 每个动作都即时落库，不再依赖统一保存入口；真正写库时读的是当时的最新快照 */
    private fun updateAndSave(update: (CharacterDetailUiState) -> CharacterDetailUiState) {
        _uiState.update(update)
        save()
    }

    private fun load() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val profile = state.characterId?.let {
                    withContext(Dispatchers.IO) {
                        bookKnowledgeGateway.getCharacterProfile(state.bookUrl, it)
                    }
                }
                currentProfile = profile
                // ⚠️ 临时诊断：进这一页时库里那一行到底是什么。跟「保存诊断」两条对照就能定性：
                // 保存后回读是对的、重进读取是旧的＝这一行被别的写入盖了；两条都旧＝根本没写进去。
                AppLog.put(
                    "人物档案·进页读取 " +
                        (profile?.describeForCastLog() ?: "按 id 查无此行 id=${state.characterId}")
                )
                val events = profile?.let {
                    withContext(Dispatchers.IO) {
                        bookKnowledgeGateway.getCharacterEvents(state.bookUrl, it.id, null, 30)
                    }
                }.orEmpty()
                val relations = profile?.let {
                    withContext(Dispatchers.IO) {
                        bookKnowledgeGateway.getCharacterRelations(state.bookUrl, it.id, 80)
                    }
                }.orEmpty()
                val profiles = withContext(Dispatchers.IO) {
                    bookKnowledgeGateway.getCharacterProfiles(state.bookUrl, 80)
                }
                val nameMap = profiles.associate { it.id to it.name }
                val poolNames = withContext(Dispatchers.IO) { VoicePoolStore.enabledPoolNames() }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        characterId = profile?.id ?: state.characterId,
                        name = profile?.name.orEmpty(),
                        aliasesText = profile?.aliasesJson.toAliasesText(),
                        avatarUri = profile?.avatarUri.orEmpty(),
                        tags = profile?.tagsJson.toTagList(),
                        role = profile?.role.orEmpty(),
                        voiceGender = profile?.voiceGender
                            ?: BookCharacterProfile.VOICE_GENDER_UNKNOWN,
                        voicePool = profile?.let { VoicePoolStore.poolLabelOf(it) }.orEmpty(),
                        poolNames = poolNames.toImmutableList(),
                        isProtagonist = profile?.isProtagonist ?: false,
                        personality = profile?.personality.orEmpty(),
                        summary = profile?.summary.orEmpty(),
                        events = events.map { event ->
                            CharacterEventUi(
                                id = event.id,
                                title = listOfNotNull(
                                    event.chapterTitle.takeIf { title -> title.isNotBlank() },
                                    event.eventTimeText.takeIf { time -> time.isNotBlank() },
                                ).joinToString(" · ")
                                    .ifBlank { appCtx.getString(R.string.character_events) },
                                content = event.content,
                            )
                        }.toImmutableList(),
                        relations = relations.map { relation ->
                            CharacterRelationUi(
                                id = relation.id,
                                fromName = nameMap[relation.fromCharacterId].orEmpty(),
                                toName = nameMap[relation.toCharacterId].orEmpty(),
                                relationType = relation.relationType,
                                summary = relation.summary,
                                attitude = relation.attitude,
                            )
                        }.toImmutableList(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false) }
                _effects.tryEmit(
                    CharacterDetailEffect.ShowToast(
                        e.localizedMessage ?: appCtx.getString(R.string.character_load_failed)
                    )
                )
            }
        }
    }

    /**
     * 整行落库。
     *
     * 弹窗里一次「应用」现在是一个 [CharacterDetailIntent.ApplyProfile] 动作，只触发一次写入；
     * [saveMutex] 盯的是另一类重叠：性格/简介这类单栏动作在输入时每敲一个字都会发一次整行写入，
     * 这些写入跑在不同线程上、谁最后进库不作保证，后一笔若带着前一笔之前的旧快照就会把刚存的覆盖掉。
     * 所以写入必须串行，并且快照在这瞬间才取。
     */
    private fun save() {
        viewModelScope.launch {
            saveMutex.withLock { saveNow() }
        }
    }

    private suspend fun saveNow() {
        val state = _uiState.value
        val name = state.name.trim()
        if (name.isBlank()) {
            // 新建人物尚未起名时没有可保存的内容，静默跳过；已有人物的名字不允许清空
            if (currentProfile == null) return
            _effects.tryEmit(CharacterDetailEffect.ShowToast(appCtx.getString(R.string.character_name_empty)))
            return
        }
        _uiState.update { it.copy(isSaving = true) }
        try {
            val now = System.currentTimeMillis()
            val existing = currentProfile
            // 池显示名 → 结构化外键：voicePoolId 存真正的池 id，voiceAgeBand 存枚举
            val poolEntity = VoicePoolStore.poolByName(state.voicePool)
            val profile = BookCharacterProfile(
                id = existing?.id ?: state.characterId ?: Uuid.random().toString(),
                bookUrl = state.bookUrl,
                name = name,
                aliasesJson = state.aliasesText.toAliasesJson(),
                avatarUri = state.avatarUri.trim().takeIf { it.isNotBlank() },
                tagsJson = state.tags.toTagsJson(),
                role = state.role,
                voiceGender = state.voiceGender,
                voicePoolId = poolEntity?.id.orEmpty(),
                voiceAgeBand = poolEntity?.ageBand ?: BookCharacterProfile.VOICE_AGE_UNKNOWN,
                isProtagonist = state.isProtagonist,
                personality = state.personality.trim(),
                summary = state.summary.trim(),
                status = existing?.status ?: BookCharacterProfile.STATUS_ACTIVE,
                source = BookCharacterProfile.SOURCE_USER,
                confidence = existing?.confidence ?: 1f,
                schemaVersion = existing?.schemaVersion ?: 1,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            val avatarChanged = existing?.avatarUri.orEmpty() != profile.avatarUri.orEmpty()
            withContext(Dispatchers.IO) {
                bookKnowledgeGateway.upsertCharacterProfile(profile)
                // 档案与配音角色是同一份信息（档案 id 就是角色 id）：这里改了名字或池，
                // 分配表与朗读音色要立刻跟着改，否则朗读还在用旧池。
                val castChanged = BookCastStore.syncFromProfile(
                    bookUrl = profile.bookUrl,
                    profileId = profile.id,
                    name = profile.name,
                    poolLabel = state.voicePool,
                )
                // 本书角色记忆也认这一份：AI 下一趟读的就是它，不改这里人物页补的别名
                // 与简介对下一次分配等于不存在。
                CastMemoryMirror.applyProfileToMemory(profile.bookUrl, profile)
                // 一次保存只重排一遍：头像与名字/池同时改时，两条各自 reload 会把同一章排两遍
                if (castChanged || avatarChanged) {
                    // 胶囊上那张图与那行字都是分页时定下来的，不换一次重排就还是旧的
                    BookCastStore.reloadReaderChapter(profile.bookUrl)
                }
                if (avatarChanged) {
                    // 换掉的本地头像到这一步才真没人引用了：若在选完新图就立刻删旧文件，
                    // 用户不保存退出后，档案里留着的会是已被删掉的地址。
                    currentProfile?.avatarUri?.let { deleteCharacterAvatar(appCtx, it) }
                }
            }
            // 主角标记/角色分类/别名可能变了，跟随主角的高亮正则展开缓存需失效
            HighlightProtagonistPatterns.invalidate()
            currentProfile = profile
            _uiState.update {
                it.copy(
                    characterId = profile.id,
                    isSaving = false,
                )
            }
            _effects.tryEmit(CharacterDetailEffect.ShowToast(appCtx.getString(R.string.character_saved)))
            // ⚠️ 临时诊断（定性「人物详情弹窗保存没生效」之后可删）：写完立刻按 id 回读库里那一行，
            // 把「界面要存的」和「库里真有」并排打出来。两者不一致＝这一行被别的写入盖掉了；
            // 一致＝库里是对的，问题出在重进页面时的读取或显示那一路。
            runCatching {
                val stored = withContext(Dispatchers.IO) {
                    bookKnowledgeGateway.getCharacterProfile(profile.bookUrl, profile.id)
                }
                AppLog.put(
                    "人物档案·保存诊断 写入[${profile.describeForCastLog()}] " +
                        "回读[${stored?.describeForCastLog() ?: "查无此行"}]"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _uiState.update { it.copy(isSaving = false) }
            _effects.tryEmit(
                CharacterDetailEffect.ShowToast(
                    e.localizedMessage ?: appCtx.getString(R.string.character_save_failed)
                )
            )
        }
    }

    private fun delete(intent: CharacterDetailIntent.Delete) {
        val profile = currentProfile ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    bookKnowledgeGateway.deleteCharacterProfile(
                        bookUrl = profile.bookUrl,
                        characterId = profile.id,
                        deleteRelations = intent.deleteRelations,
                        deleteEvents = intent.deleteEvents,
                    )
                    // 档案没了，以它建档的配音角色、分配句与音色绑定也不能留在表里
                    BookCastStore.deleteCharacter(profile.bookUrl, profile.id, profile.name)
                }
                // 删掉主角后，跟随主角高亮的展开缓存必须失效：否则正文仍命中已删人物
                // （查询本身过滤 status!=DELETED，但缓存里的旧 pattern 还带着这个名字）。
                HighlightProtagonistPatterns.invalidate()
                _effects.tryEmit(CharacterDetailEffect.NavigateBack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _effects.tryEmit(
                    CharacterDetailEffect.ShowToast(
                        e.localizedMessage ?: appCtx.getString(R.string.delete_failed)
                    )
                )
            }
        }
    }
}

/** ⚠️ 临时诊断用的一行摘要：三栏下拉值，加上认出「到底是哪一行」所需的 id / 名字 / 状态 / 来源。 */
private fun BookCharacterProfile.describeForCastLog(): String =
    "角色=[${role}] 声音性别=[${voiceGender}] 池=[${voicePoolId}]" +
        " 名字=[${name}] id=[${id}] 状态=[${status}] 来源=[${source}]"

private fun String?.toTagList(): ImmutableList<String> {
    return GSON.fromJsonArray<String>(this)
        .getOrNull()
        .orEmpty()
        .distinct()
        .toImmutableList()
}

private fun ImmutableList<String>.toTagsJson(): String {
    return GSON.toJson(this.toList())
}

private fun String?.toAliasesText(): String {
    return GSON.fromJsonArray<String>(this)
        .getOrNull()
        .orEmpty()
        .joinToString(", ")
}

private fun String.toAliasesJson(): String {
    return GSON.toJson(
        split(",", "，", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    )
}
