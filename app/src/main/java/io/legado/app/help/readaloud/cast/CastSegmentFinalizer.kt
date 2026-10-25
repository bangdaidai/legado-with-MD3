package io.legado.app.help.readaloud.cast

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.feature.reader.core.cast.CastMarkers
import io.legado.app.help.readaloud.analysis.ChapterSpeechBatchAnalyzer
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

/**
 * 定稿写入：把分配表（`chapter_role_assignments`）那一份「哪句话归谁」写进说话人的真相表
 * （`chapter_speech_segments`）。
 *
 * 两张表的锚点不是一种东西——分配表按「章内第几个开引号」，分段表按「章内坐标」。桥不存进库里，
 * 而是**当场搭**：按当前设置重建这一章的带标记正文（与出计划时同一套 splitPolicy），量出每句台词的
 * 范围，再按区间重叠对上分段，过一道同源核对（那几段还得量得现在这份正文）。分配表因此不需要存段的
 * id，也就不动表结构。
 *
 * 两个入口，写的是同一套列：
 * - [finalize]：用户在正文胶囊点「确认／新建」的当下。库里已有这一章的分段时当场就写进去，
 *   分镜页立刻是那一个角色，不用等本章重新准备。
 * - [replay]：每次出计划时（[io.legado.app.domain.usecase.PrepareChapterSpeechPlanUseCase]）整章回放
 *   一遍。它是兜底，负责 [finalize] 那一刻**没有可写目标**的情形：这一章还没分析过（库里没有分段）、
 *   或当场读正文失败。
 * 判据都是「这一句已经是这个角色的定稿就什么都不写」，所以两边重复跑不会把 `updatedAt` 一直往前推。
 *
 * 为什么要这座桥：播出时"谁说话"由覆盖层按标记现场认领（历史做法），等于让播放队列自己又判了一遍
 * 归属，界面与耳朵各拿一份真相。写进 segments 之后，朗读计划、分镜页、正文胶囊都读同一段结论，
 * 而 `userLocked = true` 让重跑分析跳过这一段——用户手工分的句子不会被 AI 下一次改回去
 * （现成的六处跳过判断分布在 `RefineSpeechWithAiUseCase`、`ResolveLocalSpeakersUseCase`、
 * `LocalCharacterSpeakerResolver`，缺的只是写入方，就是这里）。
 *
 * 冲突口径写死：**后一次用户动作赢**。分配覆盖已有归属时照写，并把被顶掉的那一个记进日志；
 * 要回 AI 原来的判断，取消分配后重新分析这一章。
 */
object CastSegmentFinalizer {

    private val chapterSpeechGateway: ChapterSpeechGateway by lazy {
        GlobalContext.get().get<ChapterSpeechGateway>()
    }

    /**
     * 回放到这一章刚算好的那份 [segments] 上，返回改完的那一份（调用方直接拿它选音，
     * 不用为了看到定稿再读一次库）。
     *
     * 一条分配都没有（绝大多数书都是这样）时原样返回，不碰库也不碰 [CastSpeechOverlay]。
     * 正文里没有角色标记时同样原样返回：标记只由「显示角色」注入，关掉它就是没有按句的分配可读，
     * 这与历史上覆盖层的口径一致，不是这次新加的门槛。
     */
    suspend fun replay(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        segments: List<ChapterSpeechSegment>,
    ): List<ChapterSpeechSegment> {
        if (segments.isEmpty() || paragraphs.isEmpty()) return segments
        val rows = appDb.chapterRoleAssignmentDao.getForChapter(bookUrl, chapterIndex)
            .filter { it.characterId.isNotBlank() && it.characterName.isNotBlank() }
            .associateBy { it.quoteOrdinal }
        if (rows.isEmpty()) return segments
        // 手里这几段量不到这份正文（正文换过了）时不写：坐标对不上，写了就是把归属钉在错的那句话上。
        // 换了划分方式不算对不上——旧粒度的那几段仍落在同一页正文的同一些字上。
        if (!SpeechIdentity.alignsWithParagraphs(paragraphs, segments)) return segments
        val spans = CastSpeechOverlay.spansOf(paragraphs).filter { it.ordinal in rows }
        if (spans.isEmpty()) return segments
        var result = segments
        var overwritten = 0
        val names = LinkedHashSet<String>()
        spans.forEach { span ->
            val row = rows[span.ordinal] ?: return@forEach
            val pending = pendingOf(result, paragraphs, span, row.characterId, row.characterName)
            if (pending.toWrite.isEmpty()) return@forEach
            pending.pushedOutOf.forEach { segment ->
                overwritten++
                names += segment.characterName
            }
            pending.toWrite.forEach { segment ->
                chapterSpeechGateway.setSegmentAttribution(
                    segmentId = segment.id,
                    roleType = pending.roleType,
                    characterId = row.characterId,
                    characterName = row.characterName,
                    userLocked = true,
                )
                result = result.map {
                    if (it.id == segment.id) {
                        it.copy(
                            roleType = pending.roleType,
                            characterId = row.characterId,
                            characterName = row.characterName,
                            userLocked = true,
                        )
                    } else {
                        it
                    }
                }
            }
        }
        if (overwritten > 0) {
            // 顶掉别人的结论必须留话：不记就查不出"这句明明是张三念的，怎么听着是旁白"
            // 到底是分配没写进去，还是别的句子也分配给了同一个角色。
            AppLog.put(
                "定稿回放: 第${chapterIndex + 1}章有 $overwritten 段原记「${names.joinToString("、")}」，" +
                    "按分配表改给最新那一位（要回原来的判断，取消分配后重新分析这一章）"
            )
        }
        return result
    }

    /** 这一句在说话人分析里的结论（正文胶囊与分配弹层都用它）。 */
    data class InferredSpeaker(
        val characterId: String,
        val characterName: String,
        val poolLabel: String = "",
    )

    /**
     * 一章「第几个开引号 → AI 判给的说话人」。
     *
     * 正文胶囊以前只认分配表（用户结论），AI 在分镜里认出的角色不显示名字，正文是一排空图标；
     * 分镜页与正文各读一张表，看起来就是"分镜有人、正文没人"。这里从**分镜同一张真相表**
     * （`chapter_speech_segments`）倒推每个引号序号，正文与分镜从此显示同一份结论；
     * 用户分配仍然是更高优先级（调用方先读分配表，命中就不再问这里）。
     *
     * 为什么不用 [bridgeOf] 量坐标：那座桥只认带标记的引号（分配表注入的），AI 认出的句子
     * 没有标记，量不出范围；而且标记长度会让正文坐标与库里那份分段错位。这里改按**段落顺序**
     * 数锚点：一段段把正文喂进 [CastMarkers.CastQuoteTracker]（与注入/测量同一个跟踪器、同一条
     * maxDepth 规则），喂到哪句就是哪句的序号，完全不依赖坐标和标记。
     *
     * 只收角色/心声段；旁白段不产胶囊名字。声音池优先取配音角色的池，其次取人物档案的池，
     * 都没有就只显示名字。
     */
    suspend fun inferredLabels(
        bookUrl: String,
        chapterIndex: Int,
    ): Map<Int, InferredSpeaker> {
        val segments = chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
        if (segments.isEmpty()) return emptyMap()
        val ordered = segments.sortedBy { it.chapterPosition }
        val tracker = CastMarkers.CastQuoteTracker()
        // 引号序号 → 说话人；只记角色/心声段覆盖到的锚点
        val ordinalSpeakers = LinkedHashMap<Int, ChapterSpeechSegment>()
        ordered.forEach { segment ->
            var first = -1
            var last = -1
            for (ch in segment.text) {
                if (tracker.feed(ch)) {
                    val ordinal = tracker.lastCastOrdinal
                    if (first < 0) first = ordinal
                    last = ordinal
                }
            }
            if (first < 0 || segment.roleType == SpeechRoleType.Narrator ||
                segment.characterName.isBlank()
            ) {
                return@forEach
            }
            for (ordinal in first..last) ordinalSpeakers.putIfAbsent(ordinal, segment)
        }
        if (ordinalSpeakers.isEmpty()) return emptyMap()
        val castRows = appDb.castCharacterDao.getByBook(bookUrl)
        val profiles = appDb.bookKnowledgeDao
            .getCharacterProfiles(bookUrl, 500, includeDrafts = true)
            .associateBy { it.id }
        val poolByCharacter = HashMap<String, String>()
        val poolByName = HashMap<String, String>()
        suspend fun poolOf(segment: ChapterSpeechSegment): String {
            val id = segment.characterId.orEmpty()
            if (id.isNotBlank()) {
                poolByCharacter[id]?.let { return it }
                castRows.firstOrNull { it.id == id }?.poolLabel
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        poolByCharacter[id] = it
                        return it
                    }
                profiles[id]?.let { profile ->
                    VoicePoolStore.poolLabelOf(profile).takeIf { it.isNotBlank() }?.let {
                        poolByCharacter[id] = it
                        return it
                    }
                }
            }
            val name = segment.characterName
            poolByName[name]?.let { return it }
            val fromCast = castRows.firstOrNull { it.name == name }?.poolLabel.orEmpty()
            val resolved = fromCast.takeIf { it.isNotBlank() }
                ?: profiles.values.firstOrNull { it.name == name }
                    ?.let { VoicePoolStore.poolLabelOf(it) }
                    .orEmpty()
            poolByName[name] = resolved
            return resolved
        }
        return ordinalSpeakers.mapValues { (_, segment) ->
            InferredSpeaker(
                characterId = segment.characterId.orEmpty(),
                characterName = segment.characterName,
                poolLabel = poolOf(segment),
            )
        }
    }

    /** 正文第 [quoteOrdinal] 个一级对话在说话人分析里的结论；没分析过或这句是旁白时为 null。 */
    suspend fun inferredSpeaker(
        bookUrl: String,
        chapterIndex: Int,
        quoteOrdinal: Int,
    ): InferredSpeaker? = inferredLabels(bookUrl, chapterIndex)[quoteOrdinal]

    /**
     * 库里这一段覆盖到的**开引号序号**（= 分配表的锚点）。
     *
     * 尺子与 [inferredLabels] 同一条：把库里那几段按章内位置顺序逐字符喂给
     * [CastMarkers.CastQuoteTracker]，数哪些锚点落在这一段的文字里。不量坐标、不认标记，
     * 所以那一句有没有注入角色标记都量得到——分镜页改归属时用户点的就是这一段本身。
     */
    suspend fun quoteOrdinalsOf(
        bookUrl: String,
        chapterIndex: Int,
        segmentId: String,
    ): Set<Int> {
        val segments = chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
        if (segments.isEmpty()) return emptySet()
        val tracker = CastMarkers.CastQuoteTracker()
        val ordinals = LinkedHashSet<Int>()
        segments.sortedBy { it.chapterPosition }.forEach { segment ->
            val mine = segment.id == segmentId
            for (ch in segment.text) {
                if (tracker.feed(ch) && mine) ordinals += tracker.lastCastOrdinal
            }
        }
        return ordinals
    }

    /**
     * 分镜页改某一段的说话人：写真相表，并把这几句**已有**的分配行一起改掉。
     *
     * 为什么不能只写 segments：出计划时 [replay] 会照分配表把真相表顶一遍（[pendingOf] 只认
     * 「这一段已经是这个人」，不比谁更晚）。分镜页只写自己那一张表的话，下一次准备这一章就被
     * 旧的那一行改回去，用户看到的就是「在这里改了等于没改」。本文件的冲突口径是**后一次用户
     * 动作赢**（见类注释），所以这一趟以分镜页这次的点选为准。
     *
     * 只改已经存在的行、**不新建**：新建会给正文注入角色标记，正文一变长，库里那几段就量不到
     * 现在这份正文（[SpeechIdentity.alignsWithParagraphs] 判不同源，覆盖层整章跳过）。
     * 「还给旁白」则删掉这几句的行，与正文胶囊的「取消分配」同一落点：标记跟着消失，
     * 那一句回到没有说话人的状态。
     *
     * 两种写法都把 `userLocked` 立成 true：那是用户当场做的决定，重跑分析不再动它。
     * 要回 AI 那份原判断，用分镜页的「重新分析本章」（它先删掉这一章的分析行）。
     *
     * 真动了分配行就重排一遍正在读的这章（[BookCastStore.reloadReaderChapter]）：胶囊与角色标记
     * 是分页时按分配行写进去的，不重排正文会一直挂着旧的那一位——与正文胶囊确认那条链同一个动作。
     */
    suspend fun reattribute(
        bookUrl: String,
        chapterIndex: Int,
        segmentId: String,
        roleType: SpeechRoleType,
        characterId: String?,
        characterName: String,
        voicePoolLabel: String,
    ) {
        chapterSpeechGateway.setSegmentAttribution(
            segmentId = segmentId,
            roleType = roleType,
            characterId = characterId,
            characterName = characterName,
            userLocked = true,
        )
        val ordinals = quoteOrdinalsOf(bookUrl, chapterIndex, segmentId)
        if (ordinals.isEmpty()) return
        val dao = appDb.chapterRoleAssignmentDao
        val rows = dao.getForChapter(bookUrl, chapterIndex).filter { it.quoteOrdinal in ordinals }
        if (rows.isEmpty()) return
        val toNarration = characterId.isNullOrBlank() || characterName.isBlank()
        if (toNarration) {
            // 还给旁白：这几句已经没有说话人，分配行与它注入的标记一起撤掉
            rows.forEach { dao.deleteOne(bookUrl, chapterIndex, it.quoteOrdinal) }
        } else {
            val now = System.currentTimeMillis()
            rows.forEach { row ->
                dao.upsert(
                    row.copy(
                        characterId = characterId,
                        characterName = characterName,
                        voicePoolLabel = voicePoolLabel,
                        updatedAt = now,
                    ),
                )
            }
        }
        // 分配行就是正文那几枚胶囊与 `<<名字（池）>>` 标记的来源：改完不重排一遍，
        // 正文上挂着的还是旧写法（与正文胶囊确认那条链同款动作，不是本章在读时才生效）
        BookCastStore.reloadReaderChapter(bookUrl)
        AppLog.put(
            if (toNarration) {
                "分镜改归属: 第${chapterIndex + 1}章这一段还给旁白，跟着撤掉 ${rows.size} 句的分配" +
                    "（正文里那几句的角色标记一起没了，要回来在分镜页重新点人）"
            } else {
                "分镜改归属: 第${chapterIndex + 1}章这一段改给「$characterName」，" +
                    "跟着改了 ${rows.size} 句的分配（正文那几句的胶囊跟着换人，" +
                    "要回 AI 原来的判断就重新分析这一章）"
            }
        )
    }

    /**
     * 用户在正文胶囊点「确认／新建」的当下：把这一句的归属立刻写进库里那几段并锁住。
     *
     * 不必等本章重新准备——分镜页读的就是那一份分段，写完这一行显示的就是那一个角色。
     * 判据与 [replay] 同一条，所以随后出计划时是空转（一行都不重刷）。
     *
     * 量不到坐标（这一章还没分析过，或正文换过了、库里那几段量不到现在这一页）时**什么都不做**：
     * 分配行照样落库，出计划那一次由 [replay] 补上，所以这里对不上不影响用户这一次点击成立。
     */
    suspend fun finalize(
        bookUrl: String,
        chapterIndex: Int,
        quoteOrdinal: Int,
        characterId: String,
        characterName: String,
    ) {
        val bridge = bridgeOf(bookUrl, chapterIndex)
        val span = bridge?.spans?.firstOrNull { it.ordinal == quoteOrdinal }
        if (bridge == null || span == null) {
            AppLog.put(
                "定稿: 第${chapterIndex + 1}章这一句暂时对不上库里那份分段" +
                    "（这一章还没分析过，或正文换过了），下次出计划时补"
            )
            return
        }
        val pending = pendingOf(
            bridge.segments, bridge.paragraphs, span, characterId, characterName,
        )
        if (pending.toWrite.isEmpty()) return
        pending.toWrite.forEach { segment ->
            chapterSpeechGateway.setSegmentAttribution(
                segmentId = segment.id,
                roleType = pending.roleType,
                characterId = characterId,
                characterName = characterName,
                userLocked = true,
            )
        }
        val pushed = pending.pushedOutOf.map { it.characterName }.distinct()
            .joinToString("、") { "「$it」" }
            .let { if (it.isEmpty()) "" else "，顶掉原记$it" }
        AppLog.put(
            "定稿: 第${chapterIndex + 1}章第 ${quoteOrdinal + 1} 句改给「$characterName」，" +
                "写进 ${pending.toWrite.size} 段并锁住$pushed" +
                "（要回原来的判断，取消分配后重新分析这一章）"
        )
    }

    /**
     * 一句台词对应到分段之后的结果：写成什么角色类型、哪几段真的要写、哪几段是被顶掉的那个结论。
     *
     * 「已经是这个角色的定稿」的段要从 [Pending.toWrite] 里滤掉——回放每次出计划都跑，
     * 逐行重刷会把 `updatedAt` 一直往前推，分镜页的"本章何时更新"就再也不能看。
     */
    private fun pendingOf(
        segments: List<ChapterSpeechSegment>,
        paragraphs: List<CanonicalSpeechParagraph>,
        span: CastSpeechOverlay.Span,
        characterId: String,
        characterName: String,
    ): Pending {
        val roleType = if (span.isThoughtQuote(paragraphs)) {
            SpeechRoleType.Thought
        } else {
            SpeechRoleType.Character
        }
        val hit = targetsOf(segments, span)
        val toWrite = hit.filterNot {
            it.characterId == characterId && it.characterName == characterName &&
                it.roleType == roleType && it.userLocked
        }
        return Pending(
            roleType = roleType,
            toWrite = toWrite,
            pushedOutOf = toWrite.filter {
                it.characterName.isNotBlank() && it.characterId != characterId
            },
        )
    }

    private class Pending(
        val roleType: SpeechRoleType,
        val toWrite: List<ChapterSpeechSegment>,
        val pushedOutOf: List<ChapterSpeechSegment>,
    )

    /**
     * 取消一句分配时放开它的定稿：解锁 + 回落旁白。
     *
     * **调用时机必须在删分配行之前**——这一句的标记就是那一行注入的，行没了正文里就量不到这句的范围。
     * 库里那一份量不到现在这份正文（坐标对不上）时，放开不了也只是那一行留在那一份旧结果里：
     * 播出读的是这一章那份结果，而写新分析时旧的会被删掉，不会留下一个永久锁死的孤儿段。
     */
    suspend fun release(bookUrl: String, chapterIndex: Int, quoteOrdinal: Int) {
        val bridge = bridgeOf(bookUrl, chapterIndex) ?: return
        bridge.spans.filter { it.ordinal == quoteOrdinal }.forEach { span ->
            targetsOf(bridge.segments, span).forEach { unlock(it.id) }
        }
    }

    /** 删掉整章分配（目录长按菜单「删除分配」）：本章被定稿过的段一并放开。 */
    suspend fun releaseChapter(bookUrl: String, chapterIndex: Int) {
        val rows = appDb.chapterRoleAssignmentDao.getForChapter(bookUrl, chapterIndex)
            .mapTo(hashSetOf()) { it.quoteOrdinal }
        if (rows.isEmpty()) return
        val bridge = bridgeOf(bookUrl, chapterIndex) ?: return
        bridge.spans.filter { it.ordinal in rows }
            .flatMap { targetsOf(bridge.segments, it) }
            .map { it.id }
            .distinct()
            .forEach { unlock(it) }
    }

    /**
     * 事后重算一次这座桥：重建带标记正文 + 与库里那份同源核对 + 量出每句台词的范围。
     *
     * 与 [replay] 的区别只在段落是谁给的：回放用调用方手里那份，用户当场点「确认／取消」时手上没有
     * 那份正文，只能按当前设置重建一遍。核对走 [SpeechIdentity.alignsWithParagraphs]——只看那几段
     * 还量得到现在这份正文，**不比内容哈希**：换了划分方式之后重建的段落和库里旧粒度的那份哈希必然
     * 不等，可那几段的章内坐标照样对得上，桥照搭；真会判不同源的只有正文换过（重新下载、替换规则、
     * 开关「显示角色」），那种情况对不上就当桥不存在，等 [replay] 兜底。
     */
    private suspend fun bridgeOf(
        bookUrl: String,
        chapterIndex: Int,
    ): Bridge? = runCatching {
        val policy = ChapterSpeechBatchAnalyzer.splitPolicy()
        val stored = chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
        if (stored.isEmpty()) return@runCatching null
        val paragraphs = ChapterSpeechBatchAnalyzer.paragraphsOf(bookUrl, chapterIndex, policy)
            ?: return@runCatching null
        if (!SpeechIdentity.alignsWithParagraphs(paragraphs, stored)) return@runCatching null
        Bridge(stored, CastSpeechOverlay.spansOf(paragraphs), paragraphs)
    }.onFailure {
        if (it is CancellationException) throw it
    }.getOrNull()

    /** 一座桥的三件东西：库里那几段、量出来的每句台词范围、量它用的那份带标记正文。 */
    private class Bridge(
        val segments: List<ChapterSpeechSegment>,
        val spans: List<CastSpeechOverlay.Span>,
        val paragraphs: List<CanonicalSpeechParagraph>,
    )

    private suspend fun unlock(segmentId: String) {
        chapterSpeechGateway.setSegmentAttribution(
            segmentId = segmentId,
            roleType = SpeechRoleType.Narrator,
            characterId = null,
            characterName = "",
            userLocked = false,
        )
    }

    /**
     * 这段台词覆盖到的分段。
     *
     * 按「有交集」取，不是只取开引号那一段：一句台词常被标点切成好几段（按符号划分时尤其明显），
     * 只定稿第一段的话，后半句还是 AI 那份判断，听着就是同一句话说一半换一个人的声音。
     * 整段划分下一句台词和它前后的叙述本来就在同一段里，于是整段一起定稿——那是划分方式本身的粒度，
     * 分析链自己给归属时也是这个粒度，不是这里额外造成的。
     */
    private fun targetsOf(
        segments: List<ChapterSpeechSegment>,
        span: CastSpeechOverlay.Span,
    ): List<ChapterSpeechSegment> = segments.filter {
        it.chapterPosition < span.end && span.start < it.chapterPosition + it.text.length
    }

    /**
     * 这一句是**单引号**台词（心声那一类）吗。
     *
     * 判据就是范围起点那个字符：[CastSpeechOverlay.Span] 的 start 取的就是开引号。
     * 走正文而不是 [CastAssignmentStore.thoughtOrdinals]，是因为回放每次出计划都要跑，
     * 为此再读一遍正文不值当。当场定稿那一路也不需要：桥里本来就带着量坐标用的那份段落，
     * 两边共用这一个判据，才不会「当场写成台词、回放又改成心声」。
     */
    private fun CastSpeechOverlay.Span.isThoughtQuote(
        paragraphs: List<CanonicalSpeechParagraph>,
    ): Boolean {
        val paragraph = paragraphs.lastOrNull { it.chapterPosition <= start } ?: return false
        val offset = start - paragraph.chapterPosition
        val ch = paragraph.text.getOrNull(offset) ?: return false
        return ch in CastMarkers.SingleQuoteOpens
    }
}
