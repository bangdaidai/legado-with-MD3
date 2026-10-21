package io.legado.app.help.readaloud.cast

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookVoiceBindingEntity
import io.legado.app.data.entities.CastCharacter
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.help.readaloud.ReadAloudVoiceTraits
import io.legado.app.ui.config.readConfig.ReadConfig

/**
 * 角色音色的本地自动选择（不联网、不调 AI）：在该池**启用**的音色里按唯一的打分链
 * （[VoiceMatchScoring.order]：占用少 → 年龄段匹配 → 风格关键词 → 引擎 → 名字 → id）取最好的一
 * 条。以前这里是「用得最少的一批里**随机**取一个」，与朗读侧
 * [io.legado.app.domain.usecase.AutoAssignVoiceBindingsUseCase] 那套「年龄 → 关键词 → 引擎」
 * 各说各话：同一个角色拿到哪个声音取决于它先被哪条链碰到，重跑还会换一个（S4 收的就是这处）。
 *
 * 现在两条链折算成同一种候选、同一份比较器，结果全序可复现。
 * 「每章新建的男青年不要永远落在池子第一格」这一条不再靠随机承担，
 * 而是靠占用数：第一个角色占走 A，第二个角色看到 A 用过了就会排到 B。
 */
object CastVoicePicker {

    /** 角色没选音色、或音色已被删掉时按声音池就近补一个；无需/无法补选时原样返回。 */
    suspend fun ensureVoice(character: CastCharacter): CastCharacter {
        if (character.poolLabel.isBlank()) return character
        val picked = character.voiceId.isNotBlank()
        val stale = picked && appDb.readAloudVoiceDao.getVoice(character.voiceId) == null
        if (picked && !stale) return character
        // 没选过音色的角色只在多角色朗读/分配表开启时才补；音色被删掉的必须换，
        // 不换就永远卡在配音页那句「已指定的声音不可用」，朗读也拿不到角色音
        if (!picked && !ReadConfig.useMultiSpeaker && !ReadConfig.multiRoleCast) return character
        val poolVoices = VoicePoolStore.enabledVoiceIdsOfPool(character.poolLabel)
        if (poolVoices.isEmpty()) return character
        val voiceId = pickFromPool(character, poolVoices) ?: return character
        val updated = character.copy(voiceId = voiceId, updatedAt = System.currentTimeMillis())
        appDb.castCharacterDao.update(updated)
        syncBinding(updated, force = stale)
        return updated
    }

    /**
     * 在池内启用音色里按打分链取一个音色 id；一条都不可用时返回 null（调用方原样返回角色）。
     *
     * 角色那一侧的年龄段与风格词都来自它所在的**声音池**：`cast_characters` 没有年龄列，
     * 角色身份就是「书 + 名 + 池」，所以池的分类是这边唯一的结构化输入。
     * 音色那一侧的年龄段走 [VoiceLabelStore.classificationMap]（用户标注 > 池分类）。
     * 池本身已经是一个性别分组，因此不再按性别筛候选——那是朗读侧没有池时才需要的动作。
     */
    private suspend fun pickFromPool(character: CastCharacter, poolVoices: List<String>): String? {
        val wanted = poolVoices.toSet()
        val voices = appDb.readAloudVoiceDao.getEnabledVoices()
            .filter { it.id in wanted && it.available }
        if (voices.isEmpty()) return null
        val classification = VoiceLabelStore.classificationMap()
        val poolAgeBand = VoicePoolStore.poolByName(character.poolLabel)
            ?.let(VoicePoolStore::poolClassificationOf)?.second.orEmpty()
        val usage = appDb.castCharacterDao.getByBook(character.bookUrl)
            .filter { it.id != character.id && it.voiceId.isNotBlank() }
            .groupingBy { it.voiceId }
            .eachCount()
        val candidates = voices.map { voice ->
            VoiceMatchScoring.candidateOf(
                voice = voice,
                traits = ReadAloudVoiceTraits.of(voice),
                ageBand = classification[voice.id]?.second.orEmpty(),
                usage = usage[voice.id] ?: 0,
            )
        }
        // 引擎跟着这一池多数人在用的走（同池混引擎会让朗读协调器来回切换）；
        // 数量相同按引擎名排，保证重跑还是同一个结果
        val engineCount: Map<String, Int> = candidates.groupingBy { it.engineType }.eachCount()
        val preferredEngine = engineCount.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
            )
            .firstOrNull()?.key.orEmpty()
        return VoiceMatchScoring.best(
            candidates = candidates,
            characterAgeBand = poolAgeBand,
            keywords = VoiceMatchScoring.keywords(poolAgeBand, character.poolLabel),
            preferredEngine = preferredEngine,
        )?.id
    }

    /**
     * 用户亲自给角色挑的音色写进 book_voice_bindings。
     *
     * 绑定才是权威读取处：配音页显示的是它（`BookVoiceCastingViewModel`），发音链路取角色音
     * 走的也是它（`BuildSpeechPlanUseCase`、[CastSpeechOverlay]），`cast_characters.voiceId`
     * 只是分配表那一侧的副本。正文胶囊与配音页两条入口都必须调这里，否则各写一半就互相看不见。
     *
     * 主体 id 有两套并存：新建配音角色时档案 id 就是角色 id（见 [CastProfileMirror.ensure]），
     * 但先有人物档案、后来才配音的角色档案 id 与角色 id 不同，而配音页按档案 id 读绑定、
     * 朗读侧按角色 id 读——所以两个 id 都要写，改一次两边才都跟着变。
     * 按「用户锁定」写入（locked=true，source=user），自动补音 [syncBinding] 不会再改动它（音色被删时除外）。
     * 角色这一份没有音色（[CastCharacter.voiceId] 为空）就是清掉绑定：留着旧绑定，
     * 配音页与朗读都会继续显示/使用用户已经换掉或去掉的那一个。
     */
    suspend fun bindUserVoice(character: CastCharacter) {
        val bookUrl = character.bookUrl
        val voiceId = character.voiceId
        if (bookUrl.isBlank() || character.id.isBlank()) return
        // 主体 id 全集走 [CastSubjectResolver]：与 syncBinding 同一口径（角色行 id + 同名/别名档案 id），
        // 且用精确名/别名比对而非 SQL LIKE，避免别名里恰好含该子串的别人被误写绑定。
        val subjectIds = (
            listOf(character.id) +
                CastSubjectResolver.subjectIdsFor(bookUrl, character.name)
            ).distinct()
        val now = System.currentTimeMillis()
        subjectIds.forEach { subjectId ->
            val existing = appDb.readAloudVoiceDao.getBinding(
                bookUrl,
                BookVoiceBinding.SUBJECT_CHARACTER,
                subjectId,
            )
            if (voiceId.isBlank()) {
                existing?.let { appDb.readAloudVoiceDao.deleteBinding(it) }
                return@forEach
            }
            if (existing?.voiceId == voiceId && existing.locked) return@forEach
            appDb.readAloudVoiceDao.upsertBinding(
                existing?.copy(
                    voiceId = voiceId,
                    locked = true,
                    // 这一条是用户亲自挑的，必须写明 source=user：实体上 source 的默认值是 "user"，
                    // 之前只在新建时靠默认值蒙对，改绑定时代码没写这一栏，读起来像"用户选的"
                    // 但没人真正写过它（N3）。现在两条链各自写死自己的来源。
                    source = BookVoiceBinding.SOURCE_USER,
                    updatedAt = now,
                )
                    ?: BookVoiceBindingEntity(
                        bookUrl = bookUrl,
                        subjectType = BookVoiceBinding.SUBJECT_CHARACTER,
                        subjectId = subjectId,
                        voiceId = voiceId,
                        locked = true,
                        source = BookVoiceBinding.SOURCE_USER,
                    ),
            )
        }
    }

    /**
     * 把角色音色镜像到 book_voice_bindings（发音链路的权威读取处）。
     *
     * 主体 id 必须两套都写，理由与 [bindUserVoice] 相同：配音页按人物档案 id 读，
     * [io.legado.app.help.readaloud.cast.CastSpeechOverlay] 按角色 id 读，只写档案 id 的后果是
     * 分镜页显示档案的音色、实际发角色 id 那一侧的声音，两边对不上且没有任何提示。
     *
     * 档案 id 只在本书角色档案里存在同名/同 id 档案时才参与：凭空插入的绑定永远读不到，
     * 只会污染配音页。角色 id 那一侧始终写。
     * 用户手动锁定的绑定不覆盖（[force] 例外：那条音色已经被删掉了）。
     *
     * 来源一律写 [BookVoiceBinding.SOURCE_AUTO]：实体上 source 的默认值是 `user`，
     * 这一条链以前没写那一栏，机器补的声音因此被记成"用户选的"（N3）——
     * 配音页会显示成用户挑的，朗读侧的自动重挑
     * （`AutoAssignVoiceBindingsUseCase.needsAutoAssignment`）又永远跳过它，两头都错。
     */
    private suspend fun syncBinding(character: CastCharacter, force: Boolean) {
        // 与 bindUserVoice 同一口径：全集走 [CastSubjectResolver]，不再各自推导
        //（旧实现用 SQL LIKE 版单条查找，与 bindUserVoice 的精确名全量过滤不一致，
        //  两条链可能把绑定写到不同的 profile.id 上）。
        val subjectIds = (
            listOf(character.id) +
                CastSubjectResolver.subjectIdsFor(character.bookUrl, character.name)
            ).distinct()
        val now = System.currentTimeMillis()
        subjectIds.forEach { subjectId ->
            val existing = appDb.readAloudVoiceDao.getBinding(
                character.bookUrl,
                BookVoiceBinding.SUBJECT_CHARACTER,
                subjectId,
            )
            if (existing?.locked == true && !force) return@forEach
            if (existing == null || existing.voiceId != character.voiceId) {
                appDb.readAloudVoiceDao.upsertBinding(
                    existing?.copy(
                        voiceId = character.voiceId,
                        source = BookVoiceBinding.SOURCE_AUTO,
                        updatedAt = now,
                    )
                        ?: BookVoiceBindingEntity(
                            bookUrl = character.bookUrl,
                            subjectType = BookVoiceBinding.SUBJECT_CHARACTER,
                            subjectId = subjectId,
                            voiceId = character.voiceId,
                            source = BookVoiceBinding.SOURCE_AUTO,
                        ),
                )
            }
        }
    }
}
