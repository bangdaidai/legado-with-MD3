package io.legado.app.feature.reader.legacy

import io.legado.app.data.dao.BookKnowledgeDao
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * 「跟随主角高亮」的正则模式构建与缓存（对照旧引擎 TextChapterLayout.buildProtagonistPattern）。
 *
 * 按 "$bookUrl|$role" 缓存展开结果（null=该书该角色下无人，缓存负结果避免每次排版都查库）。
 * 旧引擎靠 TextChapterLayout.invalidateRegexCache() 整体失效；新引擎规则本身每次排版现编译，
 * 只有这里的 DB 展开结果需要显式失效——人物资料（主角标记/别名）变化不会改变规则列表的哈希。
 */
object HighlightProtagonistPatterns : KoinComponent {

    private val bookKnowledgeDao: BookKnowledgeDao by inject()

    private val cache = ConcurrentHashMap<String, String?>()

    private val generationCounter = AtomicLong()

    /**
     * 人物数据的代次。跟随主角的高亮结果不写在规则里，规则列表的哈希对此毫无感知，
     * 所以排版窗口缓存的身份键必须并入这个值——否则「正文里选词设主角」后当前章直接
     * 复用旧页表，要翻到下一章（章节身份变了）才看得到新高亮。
     */
    val generation: Long get() = generationCounter.get()

    /** 返回展开后的 alternation 正则；该角色下没有任何人物时返回 null，调用方跳过整条规则。 */
    fun patternFor(bookUrl: String, role: String?): String? =
        cache.getOrPut("$bookUrl|${role.orEmpty()}") { build(bookUrl, role) }

    /** 人物资料变更后调用：清缓存，下次排版重新查库展开。 */
    fun invalidate() {
        generationCounter.incrementAndGet()
        cache.clear()
    }

    private fun build(bookUrl: String, role: String?): String? = runBlocking {
        val profiles = if (role.isNullOrBlank()) {
            // 未筛选：跟随主角标记
            bookKnowledgeDao.getProtagonists(bookUrl)
        } else {
            // 指定了角色分类：按 role 取人，配角也算
            bookKnowledgeDao.getCharactersByRole(bookUrl, role)
        }
        val names = profiles.flatMap { profile ->
            // 主名和详情页配置的别名一起参与高亮；别名解析失败时只保留主名
            val aliases = runCatching {
                GSON.fromJsonArray<String>(profile.aliasesJson).getOrNull().orEmpty()
            }.getOrElse { emptyList() }
            listOf(profile.name) + aliases
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (names.isEmpty()) return@runBlocking null
        names.joinToString("|") { Pattern.quote(it) }
    }
}
