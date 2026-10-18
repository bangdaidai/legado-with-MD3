package io.legado.app.help.readaloud.cast

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

/**
 * 配音角色身份解析：一个角色名在本书对应哪些可挂音色绑定的主体 id。
 *
 * 背景：主体 id 有两套并存——`cast_characters.id`（分配表与 [CastSpeechOverlay] 读）与
 * `book_character_profiles.id`（配音页 [io.legado.app.ui.book.readaloud.casting.BookVoiceCastingViewModel]
 * 与官方分析链 [io.legado.app.domain.usecase.BuildSpeechPlanUseCase] 读）。两者通常相等
 * （[CastProfileMirror.ensure] 新建档案时直接用角色 id），但「先有人物档案、后来才配音」
 * 与 AI 分配先建行再挂档案这两条路径会让 id 分叉。
 *
 * 分叉时任何一侧的**单 id 查询都会静默掉进旁白/兜底链**（绑定挂在另一个 id 上读不到），
 * 所以读写两侧都应走本对象拿全量 id 集合，而不是各自推导——推导口径不一致正是
 * 「配音页显示的音色 ≠ 实际发声」的根因之一。
 */
object CastSubjectResolver {

    /**
     * 一个角色名在本书对应的全部主体 id：角色行 id ∪ 名字或别名命中的档案 id。
     *
     * 顺序即优先级：角色行 id 在前（[canonicalSubjectId] 取首个）。
     * 用**精确名/别名比对**而非 SQL LIKE：LIKE 会把「别名里恰好含该子串的别人」误命中，
     * 导致音色绑定挂到错误角色上。
     *
     * 档案按名字完全相等或别名（解析 aliasesJson 后逐个比对）命中，草稿角色也计入——
     * 配音页把草稿列成「临时说话人」，给它们选的音色同样要落绑定。
     * 档案列表有 500 条上限（与 [CastVoicePicker] 既有口径一致），超限角色的档案侧 id
     * 取不到；角色行侧不受影响。
     */
    suspend fun subjectIdsFor(bookUrl: String, name: String): List<String> {
        if (bookUrl.isBlank() || name.isBlank()) return emptyList()
        val castIds = appDb.castCharacterDao.getByBook(bookUrl)
            .filter { it.name == name }
            .map { it.id }
        val profileIds = appDb.bookKnowledgeDao
            .getCharacterProfiles(bookUrl, PROFILE_LOOKUP_LIMIT, includeDrafts = true)
            .filter { it.bookUrl == bookUrl && it.matchesName(name) }
            .map { it.id }
        return (castIds + profileIds).distinct()
    }

    /**
     * 首选主体 id：优先角色行 id（覆盖层与分配表读这一侧），没有角色行时退回档案 id。
     * 两者都没有返回 null（该名字在本书没有任何配音主体）。
     */
    suspend fun canonicalSubjectId(bookUrl: String, name: String): String? =
        subjectIdsFor(bookUrl, name).firstOrNull()

    /** 名字完全相等或命中任一别名（别名解析坏了当没有）。 */
    private fun BookCharacterProfile.matchesName(name: String): Boolean =
        this.name == name || aliasNames().contains(name)

    /** 档案里记的别名（JSON 字符串数组）；读坏了当没有。 */
    private fun BookCharacterProfile.aliasNames(): List<String> =
        GSON.fromJsonArray<String>(aliasesJson).getOrNull().orEmpty().map { it.trim() }

    /** 档案列表的扫描上限；超限角色的档案侧主体 id 取不到（角色行侧不受影响）。 */
    private const val PROFILE_LOOKUP_LIMIT = 500
}
