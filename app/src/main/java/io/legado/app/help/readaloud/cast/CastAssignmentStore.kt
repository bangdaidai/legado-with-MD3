package io.legado.app.help.readaloud.cast

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.CastCharacter
import io.legado.app.data.entities.ChapterRoleAssignment
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.legado.app.help.readaloud.effect.VoiceEffectStore
import io.legado.app.feature.reader.core.cast.CastMarkers
import io.legado.app.feature.reader.core.source.ReaderChapterSourceParser
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import java.util.UUID

/**
 * 多角色分配标记的读写出口（多角色分配功能的数据入口）。
 *
 * 读取侧供 ReaderChapterSourceParser.parse 注入 `<<名字（池）>>` 标记；
 * 写入侧供分配悬浮窗的「确认 / 创建 / 取消分配」落库。
 * 角色状态权威在 cast_characters 表（**身份 = 书 + 名字**，池只是这个人的一条属性，
 * 同名不再并存——见 [CastIdentityFolder]），分配关系权威在 chapter_role_assignments，
 * 声音池本身在 voice_pools（VoicePoolStore）。
 * UI/ViewModel 一律经本 Store 访问 DAO（架构护栏：ui 层禁止 DAO 直连）。
 */
object CastAssignmentStore {

    /** 章内「开引号序号 → 已分配标记全文」映射；分配为空时返回空映射（注入零开销）。 */
    suspend fun labelsForChapter(bookUrl: String, chapterIndex: Int): Map<Int, String> {
        // 标记文本按用户配置的符号生成，注入前必须先装载
        CastSyntaxStore.current()
        val rows = appDb.chapterRoleAssignmentDao.getForChapter(bookUrl, chapterIndex)
        if (rows.isEmpty()) return emptyMap()
        return rows.asSequence()
            .filter { it.characterId.isNotBlank() && it.characterName.isNotBlank() }
            .mapNotNull { row ->
                CastMarkers.markerText(row.characterName, row.voicePoolLabel)
                    ?.let { row.quoteOrdinal to it }
            }
            .toMap()
    }

    /** 候选角色（弹层「角色名」下拉项，带已保存状态用于选中即回填池与音色）。 */
    data class CastCandidate(
        val id: String,
        val name: String,
        val poolLabel: String,
        val voiceId: String,
        /** 该角色当前绑定的变声器预设名，空 = 不变声。 */
        val voiceEffect: String,
    )

    /** 候选音色：poolNames = 所属声音池集合（选了池时音色下拉只显示该池成员）。 */
    data class VoiceOption(
        val id: String,
        val displayName: String,
        val poolNames: Set<String>,
    )

    /** 分配弹层一次性数据（候选 + 当前分配回填 + 池/音色全集供行内编辑）。 */
    data class SheetData(
        val bookUrl: String,
        val characters: List<CastCandidate>,
        val pools: List<String>,
        val voices: List<VoiceOption>,
        val assigned: Boolean,
        val initialCharacterId: String,
        val initialName: String,
        val initialPool: String,
        val initialVoiceId: String,
        /** 变声器候选（启用的预设名，含「无变声」空值）。 */
        val effects: List<String>,
        val initialVoiceEffect: String,
    )

    /** 确认 / 创建的结果。 */
    enum class CastResult { OK, INVALID_NAME, NOT_FOUND, EXISTS }

    /**
     * 幂等导入：把多角色朗读档案（含旧版本弹层「确认」创建的、AI 识别出的）
     * 转为配音角色，保留原 id，使既有 chapter_role_assignments.characterId 链接继续有效。
     *
     * 停用（status=DISABLED）的档案不参与：那是从配音列表删掉的 AI 人物，
     * 人物页里资料还在，但不能再把它长回配音角色。
     *
     * 草稿（DRAFT，AI 新识别出的临时人物）也不在这里转正：跑一次分析就把所有临时人物
     * 加成配音角色太重了。它们由分配弹层的下拉按需转正（见 [sheetData] / [resolveCharacter]），
     * 用户点中这个名字才代表「这个人要进配音链路」。
     *
     * 开头先跑 [CastIdentityFolder.fold]：同名多池是历史遗留，一人一行是这张表往后的前提。
     * 折叠必须**在档案导入之前**——否则这一趟会照着档案把刚并掉的那个人再长回来。
     *
     * 末尾再跑一次反方向（[CastProfileMirror.backfillAll]）：档案镜像上线前建的角色只有
     * `cast_characters` 行，官方「人物」一节会当他们不存在。
     */
    suspend fun migrateLegacyProfiles(bookUrl: String) {
        val folded = CastIdentityFolder.fold(bookUrl)
        val profiles = appDb.bookKnowledgeDao.getCharacterProfiles(bookUrl, 500)
            .filter { it.status == BookCharacterProfile.STATUS_ACTIVE }
        var renamed = false
        for (profile in profiles) {
            // 先按 id 认，认不到再按**名字**认：并档留下的可能是另一行的 id，而档案主键不动。
            // 只按 id 找会把刚刚并掉的那个人照档案重新建一行出来，折叠就白跑了。
            val character = appDb.castCharacterDao.getById(profile.id)
                ?: appDb.castCharacterDao.getByName(bookUrl, profile.name).firstOrNull()
            if (character == null) {
                val voiceId = appDb.readAloudVoiceDao
                    .getBinding(bookUrl, "character", profile.id)?.voiceId.orEmpty()
                appDb.castCharacterDao.insertIgnore(
                    CastCharacter(
                        id = profile.id,
                        bookUrl = bookUrl,
                        name = profile.name,
                        poolLabel = VoicePoolStore.poolLabelOf(profile),
                        voiceId = voiceId,
                        createdAt = profile.createdAt,
                        updatedAt = profile.updatedAt,
                    ),
                )
                continue
            }
            // 官方那侧改了名字或声音池（人物详情、AI 识别人物、事记、关系图都写档案），
            // 这一侧要跟着改，否则正文胶囊、分配行和朗读用的还是旧名字。
            // 档案与配音角色同 id（新建角色时 [CastProfileMirror] 就用角色 id 建档案），
            // 所以按 id 抄档案的 name / 声音池就是双向同步的另一半。
            val pool = VoicePoolStore.poolLabelOf(profile)
            val trimmed = profile.name.trim()
            if (trimmed.isNotEmpty() && CastMarkers.isValidName(trimmed) &&
                (character.name != trimmed || character.poolLabel != pool) &&
                appDb.castCharacterDao.getByName(bookUrl, trimmed).none { it.id != character.id }
            ) {
                val now = System.currentTimeMillis()
                appDb.castCharacterDao.update(
                    character.copy(name = trimmed, poolLabel = pool, updatedAt = now),
                )
                BookCastStore.syncRenamedReferences(
                    bookUrl, character.id, trimmed, pool, now,
                )
                renamed = true
            }
        }
        CastProfileMirror.backfillAll(bookUrl)
        if (renamed || folded > 0) {
            // 胶囊上写的就是这个名字与池：正在读的这章要重取，否则旧写法一直挂着
            BookCastStore.reloadReaderChapter(bookUrl)
        }
    }

    suspend fun sheetData(
        bookUrl: String,
        chapterIndex: Int,
        ordinal: Int,
        loadCharacters: Boolean = true,
    ): SheetData {
        CastSyntaxStore.current()
        migrateLegacyProfiles(bookUrl)
        val castRows = if (loadCharacters) {
            appDb.castCharacterDao.getByBook(bookUrl)
        } else {
            emptyList()
        }
        val characters = if (loadCharacters) {
            // AI 在分镜里认出的新人先是**草稿档案**（见 BookVoiceCastingViewModel 的「临时角色」），
            // 还没有 cast_characters 行，所以光读角色表这一行下拉是空的。这里把草稿并进来：
            // 分镜里已经有人名的那一句，用户应该能在下拉里直接点到他，而不是重新打一遍名字。
            // 选中后由 [resolveCharacter] 当场把草稿加成配音角色（id 沿用档案 id）。
            val castIds = castRows.mapTo(HashSet()) { it.id }
            val castNames = castRows.mapTo(HashSet()) { it.name }
            // 默认查询会过滤草稿，这里要显式带上 includeDrafts：草稿正是我们要找的那批人
            val drafts = appDb.bookKnowledgeDao
                .getCharacterProfiles(bookUrl, 500, includeDrafts = true)
                .filter {
                    it.status == BookCharacterProfile.STATUS_DRAFT && it.name.isNotBlank()
                }
                .filter { it.id !in castIds && it.name !in castNames }
                .distinctBy { it.name }
                .map { profile ->
                    CastCandidate(
                        id = profile.id,
                        name = profile.name,
                        poolLabel = VoicePoolStore.poolLabelOf(profile),
                        voiceId = appDb.readAloudVoiceDao
                            .getBinding(
                                bookUrl,
                                BookVoiceBinding.SUBJECT_CHARACTER,
                                profile.id,
                            )
                            ?.voiceId.orEmpty(),
                        voiceEffect = "",
                    )
                }
            castRows.map {
                CastCandidate(it.id, it.name, it.poolLabel, it.voiceId, it.voiceEffect)
            } + drafts
        } else {
            emptyList()
        }
        val pools = VoicePoolStore.enabledPoolNames()
        val poolOf = VoicePoolStore.voicePoolMap()
        val effects = VoiceEffectStore.enabledNames()
        val voices = appDb.readAloudVoiceDao.getEnabledVoices()
            .map { VoiceOption(it.id, it.displayName, poolOf[it.id].orEmpty()) }
        val assignment = appDb.chapterRoleAssignmentDao.getOne(bookUrl, chapterIndex, ordinal)
        val character = assignment?.let { appDb.castCharacterDao.getById(it.characterId) }
        // 分配表里还没有这一句时，把 AI 在分镜里判给它的说话人预填进名字/池：
        // 分镜上写着这张三，点开胶囊却是一个空名字框，等于让用户把 AI 已经做完的事再做一遍。
        // 只预填、不落库——按下「确认」才成为这一句的结论。
        val inferred = if (assignment == null) {
            CastSegmentFinalizer.inferredSpeaker(bookUrl, chapterIndex, ordinal)
        } else {
            null
        }
        val inferredCharacter = inferred?.let { speaker ->
            characters.firstOrNull {
                (speaker.characterId.isNotBlank() && it.id == speaker.characterId) ||
                    it.name == speaker.characterName
            }
        }
        return SheetData(
            bookUrl = bookUrl,
            characters = characters,
            pools = pools,
            voices = voices,
            assigned = assignment != null,
            initialCharacterId = character?.id
                ?: inferredCharacter?.id
                ?: inferred?.characterId.orEmpty(),
            initialName = character?.name
                ?: assignment?.characterName
                ?: inferred?.characterName.orEmpty(),
            initialPool = character?.poolLabel
                ?: assignment?.voicePoolLabel
                ?: inferredCharacter?.poolLabel
                ?: inferred?.poolLabel.orEmpty(),
            initialVoiceId = character?.voiceId ?: inferredCharacter?.voiceId.orEmpty(),
            effects = effects,
            // 这一栏回填的是**这一句**的段级值；空着就是「跟随角色全局」，
            // 不能拿角色的全局值来填，否则确认一次就把全局值抄成这一句的固定值。
            initialVoiceEffect = assignment?.voiceEffect.orEmpty(),
        )
    }

    /**
     * 本章第 [ordinal] 个一级对话的原文（试听用）。
     *
     * 锚点计数与注入/胶囊同源（同一个 [CastMarkers.CastQuoteTracker] 规则），但取的是
     * 内容处理之后、标记注入之前的文本，所以念出来的就是正文那句话、不带标记。
     */
    suspend fun quoteText(book: Book, chapterIndex: Int, ordinal: Int): String? {
        if (ordinal < 0) return null
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return null
        val content = BookHelp.getContent(book, chapter) ?: return null
        // 视图与注入/胶囊同源：直接喂原始段落会在含图、含 HTML 的章里多算锚点，试听念错句
        val paragraphs = ReaderChapterSourceParser.castAnchorText(
            paragraphs = ContentProcessor.get(book)
                .getContent(book, chapter, content, includeTitle = false)
                .textList,
            adaptSpecialStyle = AppConfig.adaptSpecialStyle,
        )
        val tracker = CastMarkers.CastQuoteTracker()
        for (paragraph in paragraphs) {
            val hits = ArrayList<Pair<Int, Int>>()
            for ((pos, ch) in paragraph.withIndex()) {
                if (tracker.feed(ch)) hits += tracker.lastCastOrdinal to pos
            }
            val index = hits.indexOfFirst { it.first == ordinal }
            if (index < 0) continue
            val start = hits[index].second
            val end = hits.getOrNull(index + 1)?.second ?: paragraph.length
            return paragraph.substring(start, end).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * 确认 = 分配这句话 + 更新已有角色的状态（永不创建）。
     * 目标解析顺序：下拉中刚选中的角色 → 同名且同池 → 同名唯一那一行；
     * 都没命中返回 NOT_FOUND，由弹层提示改点「创建」。
     *
     * [voiceEffect] 只写这一句（`chapter_role_assignments.voiceEffect`），不动
     * `cast_characters.voiceEffect`：那一列是角色级的全局值，入口在「人物与角色配音」页
     * （见 [BookCastStore.updateCharacter]）。
     */
    suspend fun confirm(
        bookUrl: String,
        chapterIndex: Int,
        quoteOrdinal: Int,
        selectedCharacterId: String,
        characterName: String,
        voicePoolLabel: String,
        voiceId: String,
        /** 这一句的变声器预设名，空 = 跟随角色的全局值（单引号心声那一类建行时默认给混响）。 */
        voiceEffect: String = "",
    ): CastResult {
        CastSyntaxStore.current()
        val name = characterName.trim()
        if (name.isEmpty() || !CastMarkers.isValidName(name)) return CastResult.INVALID_NAME
        val pool = voicePoolLabel.trim().take(12)
        val target = resolveCharacter(bookUrl, selectedCharacterId, name, pool)
            ?: return CastResult.NOT_FOUND
        val base = target.copy(
            name = name,
            poolLabel = pool,
            voiceId = voiceId,
            updatedAt = System.currentTimeMillis(),
        )
        // 没手动选音色时本地按池补一个（选了池就一定有声线，不用用户再点一遍）
        val updated = CastVoicePicker.ensureVoice(base)
        appDb.castCharacterDao.update(updated)
        CastProfileMirror.ensure(updated)
        // 音色要落到 book_voice_bindings（配音页与朗读读它），本书记忆那一行要跟着改主名与池
        // （AI 下一趟照记忆里的名字认人填池），只写 cast_characters 就成了「胶囊改了别处看不见」
        CastVoicePicker.bindUserVoice(updated)
        CastMemoryMirror.syncCharacterRow(bookUrl, target.name, updated.name, updated.poolLabel)
        // 角色改名/换池后同步所有引用他的冗余字段：分配行（正文胶囊与标记）+ 说话人分段
        // （分镜页那一行、正文里没分配过的那几句显示的都是它），少一处就是「这里改了别处没变」
        BookCastStore.syncRenamedReferences(
            bookUrl, updated.id, updated.name, updated.poolLabel, updated.updatedAt,
        )
        assign(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            quoteOrdinal = quoteOrdinal,
            characterId = updated.id,
            characterName = updated.name,
            voicePoolLabel = updated.poolLabel,
            voiceEffect = voiceEffect,
        )
        return CastResult.OK
    }

    /**
     * 创建 = 新增角色（身份 = 书 + 名字，**同名不再并存**）并分配给这句话。
     * 这个名字已经有角色时返回 EXISTS，由弹层提示改点「确认」——那一条才会更新这个人
     * 自己的池与音色（见 [confirm]）。
     *
     * [voiceEffect] 同 [confirm]：只记在这一句的分配行上，新角色自己的全局变声器留空。
     */
    suspend fun create(
        bookUrl: String,
        chapterIndex: Int,
        quoteOrdinal: Int,
        characterName: String,
        voicePoolLabel: String,
        voiceId: String,
        /** 这一句的变声器预设名，空 = 跟随角色的全局值（单引号心声那一类建行时默认给混响）。 */
        voiceEffect: String = "",
    ): CastResult {
        CastSyntaxStore.current()
        val name = characterName.trim()
        if (name.isEmpty() || !CastMarkers.isValidName(name)) return CastResult.INVALID_NAME
        // 折叠器进页面时已经把同名多行并成一行，所以这里命中的那一行就是这个人唯一的一行。
        // 换池不再是「再建一个同名角色」，而是在下拉里点他、由确认那一趟改他的池。
        if (appDb.castCharacterDao.getByName(bookUrl, name).isNotEmpty()) return CastResult.EXISTS
        // AI 识别出的新人只有草稿档案、还没有配音角色：这里复用档案 id 建角色，
        // 否则角色与档案各拿一个 id，人物页/音色绑定会和正文胶囊对不上同一个人。
        val draftId = appDb.bookKnowledgeDao
            .getCharacterProfiles(bookUrl, 500, includeDrafts = true)
            .firstOrNull { it.status == BookCharacterProfile.STATUS_DRAFT && it.name == name }
            ?.id
        val character = CastCharacter(
            id = draftId ?: UUID.randomUUID().toString(),
            bookUrl = bookUrl,
            name = name,
            poolLabel = voicePoolLabel.trim().take(12),
            voiceId = voiceId,
        )
        if (appDb.castCharacterDao.insertIgnore(character) <= 0L) return CastResult.EXISTS
        val withVoice = CastVoicePicker.ensureVoice(character)
        CastProfileMirror.ensure(withVoice)
        // 与 [confirm] 同理：手工挑的音色只有写进绑定才进得了配音页与朗读链路
        CastVoicePicker.bindUserVoice(withVoice)
        assign(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            quoteOrdinal = quoteOrdinal,
            characterId = withVoice.id,
            characterName = withVoice.name,
            voicePoolLabel = withVoice.poolLabel,
            voiceEffect = voiceEffect,
        )
        return CastResult.OK
    }

    /**
     * 弹层「确认」要落到的那一行：下拉里刚选中的 → 名字相同且池相同的 → 名字相同的那一行。
     *
     * 名字是唯一入口（一人一名），池只用来在少数历史脏数据里挑得更准：折叠前留下的两行同名
     * 不同池时，池相同的那一行才是用户这句话里写的那个人。
     */
    private suspend fun resolveCharacter(
        bookUrl: String,
        selectedCharacterId: String,
        name: String,
        poolLabel: String,
    ): CastCharacter? {
        if (selectedCharacterId.isNotBlank()) {
            appDb.castCharacterDao.getById(selectedCharacterId)
                ?.takeIf { it.bookUrl == bookUrl }
                ?.let { return it }
            // 下拉里选中的是 AI 识别出的草稿人物（只有档案、还没配音角色）：
            // 用户在**这一句**里明确点了他，就在这一刻转成配音角色（id 沿用档案 id），
            // 由外层照常写分配行——不点就永远只是分镜里的临时人物。
            materializeDraft(bookUrl, selectedCharacterId)?.let { return it }
        }
        val sameName = appDb.castCharacterDao.getByName(bookUrl, name)
        return sameName.firstOrNull { it.poolLabel == poolLabel } ?: sameName.singleOrNull()
    }

    /**
     * 把一条草稿档案（AI 识别出的临时说话人）落成配音角色，id 沿用档案 id。
     *
     * 只给 [resolveCharacter] 用：用户在分配弹层里从下拉点中了这个名字，才代表
     * 「这个人要进配音链路」。分镜页只读不改，不会因为跑了一次分析就把临时人物全部转正。
     */
    private suspend fun materializeDraft(bookUrl: String, profileId: String): CastCharacter? {
        val profile = appDb.bookKnowledgeDao.getCharacterProfile(bookUrl, profileId)
            ?.takeIf {
                it.bookUrl == bookUrl && it.status == BookCharacterProfile.STATUS_DRAFT
            }
            ?: return null
        val voiceId = appDb.readAloudVoiceDao
            .getBinding(bookUrl, BookVoiceBinding.SUBJECT_CHARACTER, profile.id)
            ?.voiceId.orEmpty()
        appDb.castCharacterDao.insertIgnore(
            CastCharacter(
                id = profile.id,
                bookUrl = bookUrl,
                name = profile.name,
                poolLabel = VoicePoolStore.poolLabelOf(profile),
                voiceId = voiceId,
                createdAt = profile.createdAt,
                updatedAt = profile.updatedAt,
            ),
        )
        return appDb.castCharacterDao.getById(profile.id)
    }

    /**
     * 记录一条分配；同键覆盖。
     *
     * 写完分配行紧接着把这一句的归属写进真相表（[CastSegmentFinalizer.finalize]），
     * 所以点完「确认／新建」分镜页立刻是那一个角色，不必等本章重新准备。
     * 调用方只负责刷新自己那页的显示。
     *
     * [voiceEffect] 是这一句的段级变声器（空 = 跟随角色全局），朗读侧的取值规则见
     * [io.legado.app.help.readaloud.effect.VoiceEffectStore.ofSpeech]。
     *
     * 单引号台词（心声那一类）在**建行**那一刻默认套上 [VoiceEffectStore.THOUGHT_EFFECT]：
     * 是不是单引号现扫正文（[thoughtOrdinals]，尺子与注入/胶囊同一把），只在还没有这条分配行时扫，
     * 覆盖旧行不再补默认值。
     * 用户后来在胶囊里清空或改掉就永不再补——空串在读取时区分不出「没设过」和「清过」，
     * 所以这份默认只能写一次，落点就是这个唯一的建行出口。
     */
    suspend fun assign(
        bookUrl: String,
        chapterIndex: Int,
        quoteOrdinal: Int,
        characterId: String,
        characterName: String,
        voicePoolLabel: String,
        voiceEffect: String = "",
    ) {
        val now = System.currentTimeMillis()
        val dao = appDb.chapterRoleAssignmentDao
        val existing = dao.getOne(bookUrl, chapterIndex, quoteOrdinal)
        val effect = voiceEffect.trim().take(24).ifBlank {
            // 只有第一次建行才需要知道这一句是不是心声：覆盖旧行时一律不补默认混响
            val isThought = existing == null &&
                quoteOrdinal in thoughtOrdinals(bookUrl, chapterIndex)
            if (isThought) {
                VoiceEffectStore.usableName(VoiceEffectStore.THOUGHT_EFFECT)
            } else {
                VoiceEffectStore.NONE
            }
        }
        dao.upsert(
            ChapterRoleAssignment(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                quoteOrdinal = quoteOrdinal,
                characterId = characterId,
                characterName = characterName,
                voicePoolLabel = voicePoolLabel,
                voiceEffect = effect,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        // 分配当场写进真相表：分镜页读的就是 `chapter_speech_segments` 那一份，写完立刻是那一个角色，
        // 不用等本章重新准备。对不上桥（这一章还没分析过／库里那份不是当前设置算的）时这里什么都不写，
        // 出计划那一次由 replay 补——所以这一步失败不影响这一次分配成立。见 [CastSegmentFinalizer]。
        CastSegmentFinalizer.finalize(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            quoteOrdinal = quoteOrdinal,
            characterId = characterId,
            characterName = characterName,
        )
    }

    /**
     * 本章以**单引号**开口的锚点序号（= 心声那一类台词）。
     *
     * 尺子与标记注入、正文胶囊完全同一个：内容处理之后、标记注入之前的段落逐字符喂
     * [CastMarkers.CastQuoteTracker]，`feed` 返回 true 的那个字符就是该锚点的开引号
     * （判档口径见 [CastMarkers.SingleQuoteOpens]）。
     * 消费方只有 [assign]：新建分配行时决定要不要默认写「心声混响」。
     */
    suspend fun thoughtOrdinals(bookUrl: String, chapterIndex: Int): Set<Int> {
        CastSyntaxStore.current()
        val book = appDb.bookDao.getBook(bookUrl) ?: return emptySet()
        val chapter = appDb.bookChapterDao.getChapter(bookUrl, chapterIndex) ?: return emptySet()
        val content = BookHelp.getContent(book, chapter) ?: return emptySet()
        val paragraphs = ReaderChapterSourceParser.castAnchorText(
            paragraphs = ContentProcessor.get(book)
                .getContent(book, chapter, content, includeTitle = false)
                .textList,
            adaptSpecialStyle = AppConfig.adaptSpecialStyle,
        )
        val tracker = CastMarkers.CastQuoteTracker()
        val ordinals = HashSet<Int>()
        paragraphs.forEach { paragraph ->
            paragraph.forEach { ch ->
                if (tracker.feed(ch) && ch in CastMarkers.SingleQuoteOpens) {
                    ordinals += tracker.lastCastOrdinal
                }
            }
        }
        return ordinals
    }

    /** 订阅：这本书分配过角色的章节号（目录页那枚多角色图标用）。 */
    fun flowAssignedChapters(bookUrl: String): Flow<Set<Int>> =
        appDb.chapterRoleAssignmentDao.flowAssignedChapters(bookUrl).map { it.toSet() }

    /**
     * 删除整章分配（目录长按菜单「删除分配」，见 `TocViewModel.deleteAssignmentsForSelected`）。
     *
     * 先放开定稿再删行，理由同 [unassign]：正文里那一句的角色标记就是这些行注入的。
     */
    suspend fun deleteChapter(bookUrl: String, chapterIndex: Int) {
        CastSegmentFinalizer.releaseChapter(bookUrl, chapterIndex)
        appDb.chapterRoleAssignmentDao.deleteChapter(bookUrl, chapterIndex)
    }

    /**
     * 撤销一句话的分配（回到「未分配」占位胶囊）。
     *
     * 必须在删行之前放开 segments 上那一份定稿：分配写进真相表时把这一段锁成了用户结论
     * （见 [CastSegmentFinalizer]），而这一句的坐标要靠**这一行注入的角色标记**才量得到。
     * 行先删掉就再也量不到，那一段会一直挂着旧归属和锁定标记。
     */
    suspend fun unassign(bookUrl: String, chapterIndex: Int, quoteOrdinal: Int) {
        CastSegmentFinalizer.release(bookUrl, chapterIndex, quoteOrdinal)
        appDb.chapterRoleAssignmentDao.deleteOne(bookUrl, chapterIndex, quoteOrdinal)
    }
}
