package io.legado.app.help.readaloud.cast

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.repository.configNames
import io.legado.app.feature.reader.core.layout.ReaderCastLabel
import io.legado.app.feature.reader.core.layout.ReaderCastOptions
import io.legado.app.feature.reader.core.layout.ReaderCastProfile
import io.legado.app.feature.reader.legacy.LegacyReaderStyleRangeMapper
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.config.readConfig.ReadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分页前的多角色分配渲染选项装配（开关 → 角色档案 → 头像预热 → 变声器状态）。
 *
 * core 层不触 DB，全部在此预取为 [ReaderCastOptions]；关闭开关时返回
 * [ReaderCastOptions.Disabled]，测量与分页走原版快路径。
 */
object CastRenderOptions {

    /**
     * 胶囊渲染签名：分页缓存的章节身份输入。
     *
     * 开关与变声器徽记都只在测量时读一次（见 [load]），所以它们变了而正文一个字没变时，
     * 按正文哈希算出的身份认不出「这一章要重排」，旧页会一直挂着：开着开关不出占位胶囊、
     * 给胶囊加了变声器徽记不亮，直到退出重进阅读器才重排一次。
     * 名字与池名写在注入的标记文本里，早已被 sourceHash 覆盖，这里只补它管不到的两样。
     */
    suspend fun signatureFor(bookUrl: String, chapterIndex: Int): Int =
        withContext(Dispatchers.IO) {
            if (!ReadConfig.multiRoleCast) return@withContext 0
            val chapterEffects = appDb.chapterRoleAssignmentDao
                .getForChapter(bookUrl, chapterIndex)
                .filter { it.voiceEffect.isNotBlank() }
                .associate { it.quoteOrdinal to it.voiceEffect }
            val characterEffects = appDb.castCharacterDao.getByBook(bookUrl)
                .filter { it.voiceEffect.isNotBlank() }
                .associate { it.name to it.voiceEffect }
            // 头像也要算进来：画胶囊时用的是分页那一刻解析出来的头像地址，
            // 不纳入身份的话换过头像回到正文，旧页仍显示分页时解析的头像。
            // includeDrafts：AI 新认出的临时人物也能有头像（正文现在直接显示他的名字，
            // 空头像就成了唯一一个还看得出「这是草稿、不是本人」的痕迹）。
            val avatars = appDb.bookKnowledgeDao
                .getCharacterProfiles(bookUrl, 500, includeDrafts = true)
                .map { "${it.id}|${it.avatarUri.orEmpty()}" }
                .sorted()
            // 胶囊样式（头像位移）参与宽度，改了就得重排；签名每次写自增
            // 角色气泡同理：换图/切线/偏移后不能继续用分页时解析的气泡
            val bubbles = appDb.castCharacterDao.getByBook(bookUrl)
                .map { "${it.name}|${it.bubbleRuleJson}" }
                .sorted()
            // 未分配句子的胶囊名字来自分段表：重新分析 / 分镜页改了归属，名字就变了，
            // 不纳入签名的话旧页会一直画着上一份名字。
            val labels = quoteLabels(bookUrl, chapterIndex)
            listOf(
                chapterEffects,
                characterEffects,
                avatars,
                bubbles,
                labels,
                CastCapsuleStyleStore.signature,
            ).hashCode()
        }

    /** 分段表里这一章的「引号序号 → AI 说话人」，正文与分镜页共用。 */
    private suspend fun quoteLabels(
        bookUrl: String,
        chapterIndex: Int,
    ): Map<Int, ReaderCastLabel> =
        CastSegmentFinalizer.inferredLabels(bookUrl, chapterIndex)
            .mapValues { (_, speaker) ->
                ReaderCastLabel(speaker.characterName, speaker.poolLabel)
            }

    suspend fun load(bookUrl: String, chapterIndex: Int): ReaderCastOptions {
        if (!ReadConfig.multiRoleCast) return ReaderCastOptions.Disabled
        // includeDrafts：正文胶囊现在直接显示分镜那份结论，AI 新认出的临时人物（草稿档案）
        // 也在其中。不带上草稿，这些人就只有名字没有头像，与人物页看着不像同一个人。
        val profiles = withContext(Dispatchers.IO) {
            appDb.bookKnowledgeDao.getCharacterProfiles(bookUrl, 500, includeDrafts = true)
        }
        // 预热头像（本地小图，串行足够；失败由占位圆兜底）
        profiles.asSequence()
            .mapNotNull { it.avatarUri }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(64)
            .forEach { CastAvatarCache.load(it) }
        // 胶囊底图同理：画的时候只同步取缓存，未命中就只铺底色
        CastCapsuleStyleStore.TYPES.forEach { type ->
            CastCapsuleStyleStore.preload(CastCapsuleStyleStore.current(type))
        }
        return withContext(Dispatchers.IO) {
            ReaderCastOptions(
                enabled = true,
                // 同名可能既有草稿又有正式档案（associate 后写覆盖前写）：草稿排前面，
                // 让正式档案那一份的头像与 id 赢。
                profiles = profiles
                    .sortedBy { if (it.status == BookCharacterProfile.STATUS_DRAFT) 0 else 1 }
                    .associate {
                        it.name to ReaderCastProfile(it.id, it.avatarUri.orEmpty())
                    },
                // 胶囊右端的变声器标记：段级看本章的分配行，全局看 cast_characters
                chapterEffects = appDb.chapterRoleAssignmentDao
                    .getForChapter(bookUrl, chapterIndex)
                    .filter { it.voiceEffect.isNotBlank() }
                    .associate { it.quoteOrdinal to it.voiceEffect },
                characterEffects = appDb.castCharacterDao.getByBook(bookUrl)
                    .filter { it.voiceEffect.isNotBlank() }
                    .associate { it.name to it.voiceEffect },
                // 没在分配表里的句子：正文胶囊显示分镜页那一份 AI 结论，不再是一排空图标
                quoteLabels = quoteLabels(bookUrl, chapterIndex),
                // 角色自己设的气泡：dp→px 与位图尺寸在这里换算完，core 层只拿现成的样式。
                // 位图走正文那一份 ReaderTextBackgroundLoader（与高亮规则同一套缓存与异步加载）。
                // 「应用排版」那一栏照样生效：绑定了排版的角色气泡只在那些排版下出现，
                // 口径与高亮规则的 matchesConfig 一致。
                bubbles = appDb.castCharacterDao.getByBook(bookUrl)
                    .mapNotNull { character ->
                        character.bubbleRule()
                            ?.takeIf { it.appliesToCurrentConfig() }
                            ?.let { character.name to LegacyReaderStyleRangeMapper.styleOf(it) }
                    }
                    .toMap(),
            )
        }
    }

    /** 这条规则有没有绑到当前正在用的排版上（`configName` 为空 = 全局，永远生效）。 */
    private fun HighlightRule.appliesToCurrentConfig(): Boolean {
        val bound = configName.orEmpty().configNames()
        return bound.isEmpty() || bound.contains(ReadBookConfig.durConfig.name)
    }
}
