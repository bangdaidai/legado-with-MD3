package io.legado.app.help.readaloud.cast

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookVoiceBindingEntity
import io.legado.app.data.entities.CastCharacter
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.help.readaloud.effect.VoiceEffectStore

/**
 * 同名多池折叠：一个人在一本书里只留一行配音角色、只留一个声音（方案 D6）。
 *
 * `cast_characters` 的身份曾经是「书 + 名 + 池」，于是「李星菲（女少女）」与
 * 「李星菲（女中年）」是两行，各有各的音色、变声器、气泡，胶囊上还得靠括号里的池名区分。
 * 但另外两张真相表早就一人一名了：`book_character_profiles` 按 (bookUrl, name) 唯一，
 * `book_cast_memory` 一行一个主名。三张表两套身份，结果是同一个人被 AI 认成两个、
 * 配音页显示一把嗓子、正文念另一把。现在把身份收到「书 + 名」，池只是这个人的一条属性。
 *
 * **代价（已批复接受）**：同名多池并成一条后，其中一个声音被丢掉。想换年龄音只能改
 * 留下来的那一条的池，不能再「同一个名字配两把嗓子」。被丢的池/音色/变声/气泡全部写进
 * 迁移报告，不做静默丢弃。
 *
 * **为什么是运行期折叠，不是迁移里的 SQL**：本 fork 的 `AppDatabase.version` 跟着上游走，
 * 已发布的手写迁移在真机上不会再跑第二遍，把 `UPDATE` 写进迁移里对现网库等于没写。
 * 折叠挂在进配音页/分配弹层的路径上（见 [CastAssignmentStore.migrateLegacyProfiles]），
 * 判据是「同名还有第二行吗」，改完就把多余的行真删掉——所以重复执行必然空转，
 * 这就是 D6③ 要求的幂等。
 *
 * **keeper 怎么选**：有音色绑定的 > 角色行自己填了音色的 > 最近改过的 > id 最小的。
 * 绑定最能代表「用户为这个人挑过的声音」，所以优先级最高；后两档只用来保证同一份数据
 * 每次跑出同一个结果。
 */
object CastIdentityFolder {

    /**
     * 折叠这本书的同名角色；没有可折叠的就什么都不做、不写日志。
     *
     * 返回这次并掉的组数，只给调用方判断「要不要重排正文」用。
     */
    suspend fun fold(bookUrl: String): Int {
        if (bookUrl.isBlank()) return 0
        val characters = appDb.castCharacterDao.getByBook(bookUrl)
        if (characters.size < 2) return 0
        val groups = characters
            .groupBy { it.name.trim() }
            .filter { (name, rows) -> name.isNotEmpty() && rows.size > 1 }
        if (groups.isEmpty()) return 0

        // 一次性把绑定与音色名读出来：选 keeper 与写报告都要用，逐行查会把 O(角色) 摊成 O(角色×查询)。
        // 必须是可变副本——搬绑定要当场写回，第二、第三个被丢的行才知道保留行已经有声音了。
        val bindings = HashMap(
            appDb.readAloudVoiceDao.getBindings(bookUrl)
                .filter { it.subjectType == BookVoiceBinding.SUBJECT_CHARACTER }
                .associateBy { it.subjectId },
        )
        val voiceNames = appDb.readAloudVoiceDao.getVoices().associate { it.id to it.displayName }
        val now = System.currentTimeMillis()
        val report = arrayListOf("共 ${groups.size} 个人被并成一行")

        groups.forEach { (name, rows) ->
            val keeper = rows.sortedWith(
                compareByDescending<CastCharacter> { bindings[it.id]?.voiceId?.isNotBlank() == true }
                    .thenByDescending { it.voiceId.isNotBlank() }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.id }
            ).first()
            val others = rows.filter { it.id != keeper.id }
            val merged = merge(keeper, others, now)
            report += "「${name}」${rows.size} 行 → 1 行，留「${poolOf(merged)}」，" +
                "声音 ${voiceName(voiceNames, merged.voiceId)}"
            others.forEach { lost ->
                report += "    丢掉「${name}（${poolOf(lost)}）」：声音 " +
                    "${voiceName(voiceNames, lost.voiceId)}、变声 ${lost.voiceEffect.ifBlank { "无" }}、" +
                    "气泡${if (lost.bubbleRuleJson.isBlank()) "无" else "有（只留下保留行那一份）"}"
                appDb.chapterRoleAssignmentDao.remapCharacter(bookUrl, lost.id, merged.id, now)
                appDb.chapterSpeechDao.remapCharacterId(bookUrl, lost.id, merged.id, now)
                moveBinding(bookUrl, lost.id, merged.id, bindings, now)
                VoiceEffectStore.forgetCharacter(lost.id)
                appDb.castCharacterDao.delete(lost.id)
            }
            // 冗余字段跟着保留行走：分配行上记的是名字 + 池，正文胶囊与标记都读那两栏
            appDb.chapterRoleAssignmentDao.updateForCharacter(
                bookUrl, merged.id, merged.name, merged.poolLabel, now,
            )
            // 本书记忆一行一个主名，池栏跟着保留行，否则 AI 下一趟照旧池把人分回被丢的那个
            CastMemoryMirror.syncCharacterRow(bookUrl, name, name, merged.poolLabel)
            CastProfileMirror.ensure(merged)
        }

        // 朗读侧的变声快照按角色 id 记着被丢掉的那些行，不失效就会继续给不存在的人套预设
        VoiceEffectStore.invalidateCharacters()
        BookCastStore.reloadReaderChapter(bookUrl)
        AppLog.put("配音角色同名折叠：\n" + report.joinToString("\n"))
        return groups.size
    }

    /**
     * 保留行的属性优先，空的那一栏从被丢的行里补第一个非空值。
     *
     * 只补空、不覆盖：用户在同名多行里改过哪一行，哪一行就赢；两行都填了不同内容时只能留一个，
     * 没留下的那个已经写进报告了。`createdAt` 取最早，别让「什么时候建的」在并档之后变新。
     */
    private fun merge(keeper: CastCharacter, others: List<CastCharacter>, now: Long): CastCharacter {
        fun firstFilled(get: (CastCharacter) -> String): String {
            get(keeper).takeIf { it.isNotBlank() }?.let { return it }
            return others.firstOrNull { get(it).isNotBlank() }?.let(get).orEmpty()
        }
        return keeper.copy(
            name = keeper.name.trim(),
            poolLabel = firstFilled { it.poolLabel },
            voiceId = firstFilled { it.voiceId },
            voiceEffect = firstFilled { it.voiceEffect },
            bubbleRuleJson = firstFilled { it.bubbleRuleJson },
            sortOrder = if (keeper.sortOrder != 0) keeper.sortOrder
            else others.map { it.sortOrder }.filter { it != 0 }.minOrNull() ?: 0,
            createdAt = (listOf(keeper) + others).minOf { it.createdAt },
            updatedAt = now,
        )
    }

    /**
     * 把挂在被丢角色上的音色绑定搬到保留行上；保留行已经有绑定时删掉被丢的那一条。
     *
     * 绑定才是声音的唯一权威（见 [CastVoicePicker.bindUserVoice]），漏搬就表现为
     * 「配音页显示的声音 ≠ 折叠前那个人在用的声音」。这里原样搬运 `locked/source`：
     * 用户手工锁定的那份不能因为并档就降成机器自动挑的。
     */
    private suspend fun moveBinding(
        bookUrl: String,
        droppedId: String,
        keeperId: String,
        bindings: HashMap<String, BookVoiceBindingEntity>,
        now: Long,
    ) {
        val lost = bindings.remove(droppedId) ?: return
        val keeper = bindings[keeperId]
        if (keeper == null) {
            val moved = lost.copy(subjectId = keeperId, updatedAt = now)
            appDb.readAloudVoiceDao.upsertBinding(moved)
            bindings[keeperId] = moved
        } else {
            appDb.readAloudVoiceDao.deleteBinding(lost)
        }
    }

    /** 报告里的可读身份：池名，没填池写「未分池」。 */
    private fun poolOf(character: CastCharacter): String =
        character.poolLabel.ifBlank { "未分池" }

    /** 报告里念得出名字的声音；没选写「未选」，音色已被删时退回显示那个 id。 */
    private fun voiceName(voiceNames: Map<String, String>, voiceId: String): String = when {
        voiceId.isBlank() -> "未选"
        else -> voiceNames[voiceId] ?: "${voiceId}（音色已不在目录里）"
    }
}
