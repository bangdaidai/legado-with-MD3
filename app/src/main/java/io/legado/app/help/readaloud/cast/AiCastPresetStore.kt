package io.legado.app.help.readaloud.cast

import android.content.Context
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiPromptPreset
import io.legado.app.domain.model.AiTaskType
import splitties.init.appCtx

/**
 * AI 识别背景音乐的提示词，与「这一本书上次跑到哪儿」的记账出口。
 *
 * 「AI 分配角色」那一套（分配要求预设列表、判断规则、角色输出契约、本书角色记忆编辑器）已随
 * 入口退役（S7 切片D）：逐句归因的提示词住在 `ANALYZE_SPEECH` 任务预设里（AI 提示词管理页可改），
 * 书级记忆的权威改读人物档案表。这里只剩下背景音乐这一趟。
 *
 * **提示词可编辑，底层不留死文本**：发给模型的整段提示词就是库里 `ai_prompt_presets` 的一条
 * 可编辑行（taskType=[AiTaskType.CAST_ASSIGN]，固定 id [SCENE_CONTRACT_ID]；[DEFAULT_SCENE_PROMPT]
 * 只用于「恢复默认」）。缺行时下次读取自动补回出厂值，用户改过的内容不会被代码覆盖。
 *
 * 旧的分配要求行与角色那几条固定附加行**留在库里、不再被读**：那是用户写过的文本，静默删除
 * 不可逆；要清理由后续切片显式处理。
 *
 * 架构护栏：DAO 访问只出现在本 Store，UI/VM 一律经此读写。
 */
object AiCastPresetStore {

    private const val PREFS = "ai_cast"
    private const val KEY_REASONING_LEVEL = "reasoningLevel"

    /** AI 识别背景音乐的提示词（预设行 [SCENE_CONTRACT_ID] 的出厂值，整段可改）。 */
    const val DEFAULT_SCENE_PROMPT =
        "你在为中文有声书安排背景音乐场景。用户给你：可用的背景音乐池名单 pools、本章正文段落" +
            "（每段带序号 i）、上一章末尾在用的池 previousPool、前后章节选。\n" +
            "规则：\n" +
            "1. 只在场景、时间地点、情绪或情节转折的那一段上换池，包括第 0 段（本章开头，必须给）。\n" +
            "2. pool 必须严格取自 pools 里的名字，一个字都不要改；不要发明池名，不要用标点包起来。\n" +
            "3. 没有转折就沿用上一段的池，不要每段都换；一整章情绪平稳时给 1-3 个切换点就够，" +
            "相邻两个切换点之间至少隔 10 个段落（隔得更近的会被程序丢掉，等于白写）。\n" +
            "4. previousPool 是上一章末尾在用的池，本章开头没有明显转折就延续它。\n" +
            "只输出一个 JSON 对象，不要解释、不要代码块：\n" +
            """{"scenes":[{"i":0,"pool":"池名"},{"i":17,"pool":"池名"}]}"""

    /**
     * 旧场景提示词开头的界面说明前缀，[scenePrompt] 命中后删掉，只留纯提示词。
     *
     * 这段原文**必须逐字保留**（含「管理 → 固定附加的提示词」那串旧路径）：它是拿来匹配存量行的
     * 键，改一个字老库里的说明就删不掉、还会照样发给模型。
     */
    private const val LABELLED_SCENE_PROMPT_PREFIX =
        "（这一整段都存在「管理 → 固定附加的提示词 → 输出格式要求（背景音乐）」里，可直接编辑，" +
            "软件底层没有另写的提示词。）\n"

    /** 场景提示词那一条预设行的固定 id：改名或换 id 会让用户改过的文本读不回来。 */
    const val SCENE_CONTRACT_ID = "scene_output_format"

    /**
     * 当前生效的场景提示词（背景音乐那一趟的整段 prompt）。
     *
     * 一次读取办三件事：缺行就补出厂值、存量行开头的**界面说明标注**顺手删掉（只删开头那段，
     * 用户写的内容不动）、然后返回要发出去的那一份。管理页已经退役，所以这里不再是纯读——
     * 补齐与清洗跟着读取走，用户第一次按「开始识别」也有行可改。
     */
    fun scenePrompt(): String {
        val row = appDb.aiPromptPresetDao.getSync(SCENE_CONTRACT_ID)
        if (row == null) {
            writeSceneSlot(DEFAULT_SCENE_PROMPT)
            return DEFAULT_SCENE_PROMPT
        }
        val stored = row.instruction.trim()
        if (stored.isEmpty()) return DEFAULT_SCENE_PROMPT
        if (stored.startsWith(LABELLED_SCENE_PROMPT_PREFIX)) {
            val cleaned = stored.removePrefix(LABELLED_SCENE_PROMPT_PREFIX).trim()
            writeSceneSlot(cleaned)
            return cleaned.ifBlank { DEFAULT_SCENE_PROMPT }
        }
        return stored
    }

    /** 出厂提示词：界面那颗「恢复默认」直接用这一条，代码里不留第二份文本。 */
    fun defaultScenePrompt(): String = DEFAULT_SCENE_PROMPT

    /** 保存场景提示词（空文本不写：发一段空白给模型只会得到一段空白）。 */
    fun saveScenePrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        writeSceneSlot(trimmed)
    }

    private fun writeSceneSlot(text: String) {
        val old = appDb.aiPromptPresetDao.getSync(SCENE_CONTRACT_ID)
        appDb.aiPromptPresetDao.upsertAllSync(
            listOf(
                old?.copy(instruction = text, updatedAt = System.currentTimeMillis())
                    ?: AiPromptPreset(
                        id = SCENE_CONTRACT_ID,
                        taskType = AiTaskType.CAST_ASSIGN,
                        name = "输出格式要求（背景音乐）",
                        instruction = text,
                        builtIn = true,
                        sortNumber = 1000,
                    ),
            ),
        )
    }

    /**
     * 一次性把旧「AI 分配角色」弹窗自己存的那份推理强度搬到朗读设置里，搬完即删。
     *
     * 这个键**只在用户真的在弹窗里点过强度**时才存在（默认值从不落盘），所以返回 null 表示
     * "用户没表过态"，不该覆盖设置页的默认「不思考」。删除后本文件再无读取方：推理强度从此
     * 只有 [io.legado.app.domain.model.settings.ReadAloudSettings.speechAnalysisReasoningLevel]
     * 一份，不会出现"弹窗改一次、设置页改一次、各存各的"。
     */
    fun takeLegacyReasoningLevel(): String? {
        val p = prefs()
        val value = p.getString(KEY_REASONING_LEVEL, null) ?: return null
        p.edit().remove(KEY_REASONING_LEVEL).apply()
        return value
    }

    /** [AiCastRunState.resumeChapter] 的「没有要续的章」取值（读取侧据此决定要不要给「继续」入口）。 */
    const val NO_RESUME_CHAPTER = -1

    /**
     * 一次 AI 识别跑到哪儿了（按书记在 prefs，与推理强度同一个文件）。
     *
     * 存在的理由：逐章请求是长任务，用户中途点取消、或者一路断网跑不完时，下次打开悬浮窗要能
     * 直接看到「停在第几章 / 哪几章失败了」，而不是让他重填一遍范围。
     * 章号一律是**落库与朗读用的 0 基下标**（`bgm_scene_marks.chapterIndex` 同一口径）；界面显示
     * 从 1 开始的章号，换算在读写两侧各做一次（写入侧 `ReadAloudCastDelegate`，读取侧悬浮窗）。
     */
    data class AiCastRunState(
        val startChapter: Int = -1,
        val endChapter: Int = -1,
        /** 中断时正在处理的那一章；[NO_RESUME_CHAPTER] = 这一趟跑完了，没有要续的章。 */
        val resumeChapter: Int = NO_RESUME_CHAPTER,
        val failedChapters: List<Int> = emptyList(),
    )

    /** 本书上一次识别跑到哪儿（没跑过就是全默认值的 [AiCastRunState]）。 */
    fun loadCastRunState(bookUrl: String): AiCastRunState {
        val p = prefs()
        return AiCastRunState(
            startChapter = p.getInt(runKey(bookUrl, "start"), -1),
            endChapter = p.getInt(runKey(bookUrl, "end"), -1),
            resumeChapter = p.getInt(runKey(bookUrl, "resume"), NO_RESUME_CHAPTER),
            failedChapters = p.getString(runKey(bookUrl, "failed"), "")
                .orEmpty()
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() },
        )
    }

    /** 进度落盘（apply 异步写，不卡识别循环）。 */
    fun saveCastRunState(bookUrl: String, state: AiCastRunState) {
        prefs().edit()
            .putInt(runKey(bookUrl, "start"), state.startChapter)
            .putInt(runKey(bookUrl, "end"), state.endChapter)
            .putInt(runKey(bookUrl, "resume"), state.resumeChapter)
            .putString(runKey(bookUrl, "failed"), state.failedChapters.joinToString(","))
            .apply()
    }

    private fun runKey(bookUrl: String, field: String) = "castRun_$field|$bookUrl"

    private fun prefs(): android.content.SharedPreferences =
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
