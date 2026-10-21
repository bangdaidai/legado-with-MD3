package io.legado.app.help.readaloud.cast

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

/**
 * 本书角色记忆 ↔ 官方人物档案 的读写口径。
 *
 * 记忆是一表『主名｜别名/身份｜关系｜池』的文本，人物档案是用户在人物详情页编辑的那一份。
 * 现在活着的是**档案 → 记忆**这一向（[applyProfileToMemory]，整行替换：那一行本来就代表这个人，
 * 用户在人物页改的就是最终口径）与改名/改池时的随行同步（[syncCharacterRow]）。
 *
 * 反方向（记忆 → 档案）随旧「AI 分配角色」那趟一起退役了：那段文本原本是 AI 每章重写回来的
 * 产物，现在没有产出方，权威在档案表。`book_cast_memory` 的彻底退役见方案 S9。
 */
object CastMemoryMirror {

    /** 记忆里的一行。[aliases] 是「别名/身份」那一栏拆出来的名单。 */
    data class Line(val name: String, val aliases: List<String>, val relation: String, val pool: String)

    fun parse(memory: String): List<Line> = memory.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split('｜', '|').map { it.trim() }
            Line(
                name = parts.firstOrNull().orEmpty(),
                aliases = parts.getOrNull(1).orEmpty().split('、', '，', ',')
                    .map { it.trim() }.filter { it.isNotEmpty() },
                relation = parts.getOrNull(2).orEmpty(),
                pool = parts.getOrNull(3).orEmpty(),
            )
        }
        .filter { it.name.isNotEmpty() }
        .toList()

    fun render(line: Line): String = buildString {
        append(line.name)
        append('｜').append(line.aliases.joinToString("、"))
        append('｜').append(line.relation)
        if (line.pool.isNotBlank()) append('｜').append(line.pool)
    }

    /**
     * 把 [memory] 里主名等于 [line.name] 的那一行**原地**换成 [line]；没有就追加到末尾。
     *
     * 原地而不是删了再加：这一表的顺序就是用户在悬浮窗里看到的顺序，
     * 每次在人物页改个人就把他挪到最底下，等于改一次乱一次。
     */
    fun replaceLine(memory: String, line: Line): String {
        val rendered = render(line)
        val lines = memory.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val at = lines.indexOfFirst { it.split('｜', '|').first().trim() == line.name }
        if (at >= 0) lines[at] = rendered else lines += rendered
        return lines.joinToString("\n")
    }

    /**
     * 只把记忆里 [name] 那一行的**池**那一栏换成 [pool]，别名与关系一个字都不动。
     *
     * 不能整行替换（[replaceLine]）：这一行是 AI 逐章滚出来的，用户在配音页或正文胶囊里
     * 改一次池就把 AI 写下的关系抹没了。
     * 返回 null = 这本书还没有记忆、没有这个主名的行，或者池本来就是这样。
     */
    fun replaceLinePool(memory: String, name: String, pool: String): String? {
        val mainName = name.trim()
        if (memory.isBlank() || mainName.isEmpty()) return null
        val lines = memory.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val at = lines.indexOfFirst { it.split('｜', '|').first().trim() == mainName }
        if (at < 0) return null
        val fields = lines[at].split('｜', '|').map { it.trim() }.toMutableList()
        if (fields.getOrNull(3).orEmpty() == pool) return null
        while (fields.size < 4) fields += ""
        fields[3] = pool
        if (pool.isBlank() && fields.size == 4) fields.removeAt(3)
        val rendered = fields.joinToString("｜")
        if (rendered == lines[at]) return null
        lines[at] = rendered
        return lines.joinToString("\n")
    }

    /**
     * 把记忆里 [from] 那一行的主名换成 [to]、池换成 [pool]，别名与关系照旧。
     *
     * 两个入口共用（都经 [syncCharacterRow]）：配音页改角色行、正文胶囊就地改名/换池——
     * 那两处的主名是用户填的，[from] 是改前的名字。
     *
     * - 已经有 [to] 那一行（改名前就分裂过，或根本没改名）：只动它的池，别去改别的行。
     * - 两边都查不到：这本书还没有这一行，返回 null，让 AI 下一趟自己写。
     * - 别名栏里恰好等于新名的那一个要摘掉：AI 常把全名挂在别名上
     *   （`星菲｜李星菲、…`），用户把主名改成 `李星菲` 后留着它就是同一个人两种写法，
     *   下一趟分配又会照别名建出第二个角色。
     */
    fun renameLinePool(memory: String, from: String, to: String, pool: String): String? {
        val target = to.trim()
        if (memory.isBlank() || target.isEmpty()) return null
        val lines = memory.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        fun mainNameOf(line: String) = line.split('｜', '|').first().trim()
        var at = lines.indexOfFirst { mainNameOf(it) == target }
        val origin = from.trim()
        if (at < 0 && origin.isNotEmpty() && origin != target) {
            at = lines.indexOfFirst { mainNameOf(it) == origin }
        }
        if (at < 0) return null
        val fields = lines[at].split('｜', '|').map { it.trim() }.toMutableList()
        while (fields.size < 4) fields += ""
        fields[0] = target
        fields[1] = fields[1].split('、', '，', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != target }
            .joinToString("、")
        fields[3] = pool
        if (pool.isBlank() && fields.size == 4) fields.removeAt(3)
        val rendered = fields.joinToString("｜")
        if (rendered == lines[at]) return null
        lines[at] = rendered
        return lines.joinToString("\n")
    }

    /**
     * 角色行改了主名或声音池 → 本书记忆里那一行跟着改（AI 下一趟才会按新名新池填这个人）。
     *
     * 只按新名去找那一行是找不到的：记忆按主名认人，于是它停留在旧名上，
     * 下一趟 AI 照旧名再建一个角色（用户看到的「同名角色分裂」「要删两次」）。
     */
    suspend fun syncCharacterRow(bookUrl: String, previousName: String, name: String, pool: String) {
        if (bookUrl.isBlank()) return
        val row = appDb.bookCastMemoryDao.get(bookUrl) ?: return
        val next = renameLinePool(row.memory, previousName, name, pool) ?: return
        appDb.bookCastMemoryDao.upsert(
            row.copy(memory = next, updatedAt = System.currentTimeMillis()),
        )
    }

    /**
     * 用户在记忆编辑器里改出来的差异：换主名 = 改名，主名没动而池栏动了 = 改池。
     *
     * 唯一消费它的是「AI 分配角色」弹窗里那个整表编辑器，已随入口退役（S7 切片D）；改名/改池
     * 现在走人物详情页与配音列表各自的入口。这一对纯文本函数留着给测试盯住解析口径，
     * 等 S9 把 `book_cast_memory` 文本整体退役时一并清掉。
     */
    data class UserEdits(
        val renames: List<Pair<String, String>> = emptyList(),
        val poolChanges: List<Pair<String, String>> = emptyList(),
    ) {
        val isEmpty: Boolean get() = renames.isEmpty() && poolChanges.isEmpty()
    }

    /**
     * 比对用户这一次编辑前后的两份记忆。
     *
     * 按**身份指纹**（别名栏 + 关系栏）配对，不按行号：用户在表里加一行、删一行，
     * 或 AI 在中间补了行，行号配对就会把「另一个人」认成改名，一次保存毁掉整本角色表。
     * 只有某条新行的指纹在旧表里**唯一命中**一条时才认作同一个人，再各自独立地判断
     * 改名与改池（用户经常在同一行里两件一起改）。指纹本身被改动（动了别名或关系）就不推断。
     */
    fun diffUserEdits(before: String, after: String): UserEdits {
        val old = parse(before)
        val next = parse(after)
        val byFingerprint = old.groupBy { it.aliases to it.relation }
        val used = HashSet<String>()
        val renames = ArrayList<Pair<String, String>>()
        val pools = ArrayList<Pair<String, String>>()
        for (to in next) {
            val matches = byFingerprint[to.aliases to to.relation].orEmpty()
                .filter { it.name !in used }
            val from = matches.singleOrNull() ?: continue
            used += from.name
            if (from.name != to.name) renames += from.name to to.name
            if (from.pool != to.pool) pools += to.name to to.pool
        }
        return UserEdits(renames, pools)
    }

    /** 档案 → 记忆：用户在人物详情页存过的内容就是这一行的最终口径，整行替换。 */
    suspend fun applyProfileToMemory(bookUrl: String, profile: BookCharacterProfile) {
        if (bookUrl.isBlank() || profile.name.isBlank()) return
        val row = appDb.bookCastMemoryDao.get(bookUrl) ?: return
        val aliases = GSON.fromJsonArray<String>(profile.aliasesJson).getOrNull().orEmpty()
        val pool = VoicePoolStore.poolLabelOf(profile)
        val next = replaceLine(
            row.memory,
            Line(profile.name, aliases, profile.summary, pool),
        )
        if (next == row.memory) return
        appDb.bookCastMemoryDao.upsert(row.copy(memory = next, updatedAt = System.currentTimeMillis()))
    }
}
