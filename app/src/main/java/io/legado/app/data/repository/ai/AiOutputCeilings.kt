package io.legado.app.data.repository.ai

import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiHttpException
import io.legado.app.domain.model.AiModelConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * 各家供应商允许的 `max_tokens` 上限差别极大：智谱 GLM-4-Flash 只到 16384，DeepSeek 常见 8192，
 * 弱一点的模型甚至 4096。而分镜说话人分析这类任务必须按"这次要判多少个分段"放大输出预算
 * （预算不够会被硬截断，JSON 断尾必然解析失败），放大就可能撞上限。
 *
 * 撞上上限的后果不是"输出短一点"，而是供应商直接回 400 —— 整章分析当场失败，
 * AI 日志里只有一行 `max_tokens参数非法：限制数值范围[1,16384]`。这里管两件事：
 *
 * 1. [ceilingOf]：只要知道这个模型的上限（用户在模型编辑页填的「最大输出 tokens」，
 *    或本机上一次被供应商告知的），发请求前先把预算夹住，不带注定被拒的数字出门。
 * 2. [lowerAfterRejection]：真被拒了，把供应商报出来的数字读出来降一档重发。
 *    降成的数字要等重发**成功**才由 [remember] 记下，避免把误判的数字当成模型上限用一整个会话。
 *
 * 学到的上限只留在内存，重启即清：不写回模型配置，用户填的那一格仍然是唯一持久口径。
 */
internal object AiOutputCeilings {

    /** 降到这个数以下就别再降了：再小只会让输出立刻截断，等于白白发请求。 */
    private const val MIN_PRACTICAL_TOKENS = 1_024

    /** 解析出来的数字要像 token 上限，不像就当没读到。 */
    private const val PLAUSIBLE_MIN = 256
    private const val PLAUSIBLE_MAX = 2_000_000

    /**
     * 供应商在这类错误里一定会点名参数，否则不是"预算太大"。中英文都收着：
     * 智谱回 `max_tokens参数非法：限制数值范围[1,16384]`，OpenAI 系回
     * `max_tokens is too large: ...`，Anthropic 回 `max_tokens: value is not allowed ...`。
     */
    private val REJECTION_MARKERS = listOf(
        "max_tokens",
        "max_output_tokens",
        "max tokens",
        "max output tokens",
        "maximum output tokens",
        "输出上限",
        "输出令牌",
    )

    /** `[1,16384]` 这种区间写法：取上界。 */
    private val BRACKET_RANGE = Regex("""\[\s*(\d+)\s*[,，]\s*(\d+)\s*]""")

    /** "at most 8192" / "最大 16384" / "上限：16000" / "<= 4096" 这类关键词后面紧跟的数字。 */
    private val LIMIT_AFTER_KEYWORD = Regex(
        """(?:at\s+most|no\s+more\s+than|less\s+than\s+or\s+equal\s+to|too\s+large|<=|≤|""" +
            """maximum(?:\s+(?:value|limit))?(?:\s+is)?|max(?:imum)?\s+limit|""" +
            """限制(?:数值)?(?:范围)?|最多|最大|上限)[^\d]{0,16}(\d+)""",
        RegexOption.IGNORE_CASE
    )

    /** 本机被供应商告知过的模型上限，键见 [keyFor]。 */
    private val learned = ConcurrentHashMap<String, Int>()

    /**
     * 同一个模型的多次调用要共用一条上限，键取模型配置行的身份；
     * 没有 id（临时拼出来的请求）退到「供应商 + 模型号」。
     */
    fun keyFor(model: AiModelConfig): String =
        model.id.ifBlank { "${model.provider.id}:${model.modelId}" }

    /** 用户填的那一格优先，其次用本机学到的；都没给就是"不知道"。 */
    fun ceilingOf(key: String, declaredLimit: Int): Int? =
        declaredLimit.takeIf { it > 0 } ?: learned[key]

    /** 重发成功后才记，键已存在时取更小的那个（学错过的话别再放大回去）。 */
    fun remember(key: String, ceiling: Int) {
        if (ceiling < MIN_PRACTICAL_TOKENS) return
        learned.merge(key, ceiling) { old, new -> minOf(old, new) }
    }

    /** 只在"4xx 且错误里点名了 max_tokens"时认定为预算过大，别的 400 不该降档重试。 */
    fun isMaxTokensRejection(e: Throwable?): Boolean {
        val text = rejectionText(e)?.lowercase() ?: return false
        return REJECTION_MARKERS.any { it in text }
    }

    /** 供应商原文（在 [AiHttpException.providerMessage] 里，HTTP status message 通常没带）。 */
    fun rejectionText(e: Throwable?): String? = (e as? AiHttpException)
        // 429 不在此列：限流由 retryWithBackoff 退避重试管，降预算救不了它
        ?.takeIf { it.statusCode in 400..499 && it.statusCode != 429 }
        ?.let { it.providerMessage ?: it.message }

    /**
     * 该把预算降到多少；降不动（没填预算、供应商报的数字不比当前小、或已经到下限）返回 null，
     * 调用方就该把原始失败抛出去。
     */
    fun lowerAfterRejection(current: Int, rejectionText: String?): Int? {
        if (current <= MIN_PRACTICAL_TOKENS) return null
        val stated = statedLimit(rejectionText)
        return when {
            // 供应商报了明确的数：夹到那个数。报的数比本次请求还大＝撞的不是预算，降了也没用
            stated != null -> stated.takeIf { it in MIN_PRACTICAL_TOKENS until current }
            // 只说了非法没给数：对半砍，让长章的整批请求也能自己找出上限
            else -> (current / 2).takeIf { it >= MIN_PRACTICAL_TOKENS }
        }
    }

    /**
     * 从错误原文里读出来的模型上限，读不到或读到的不像 token 数就返回 null。
     *
     * 一条错误里经常同时出现两个数字：`max_tokens is too large: 22000 ... supports at most 16384`
     * 前半是"你请求了多少"、后半才是那条线。所以取读到的**最小**可信数——上限不会比自己的请求大，
     * 而其他被点名的数字（上下文长度、输入 token 数）都比上限大。
     */
    private fun statedLimit(text: String?): Int? {
        val raw = text?.takeIf { it.isNotBlank() } ?: return null
        BRACKET_RANGE.find(raw)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { return it }
        LIMIT_AFTER_KEYWORD.findAll(raw)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in PLAUSIBLE_MIN..PLAUSIBLE_MAX }
            .minOrNull()?.let { return it }
        // 兜底：关键词表没覆盖到的写法（"limit is 8192 tokens"），只认像 token 数的数字
        return raw.findAll("""\d{3,}""")
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in PLAUSIBLE_MIN..PLAUSIBLE_MAX }
            .minOrNull()
    }

    /** 把请求里的输出预算夹进 [ceiling]；本来就在上限内则原样返回。 */
    fun clampTo(request: AiGenerateRequest, ceiling: Int): AiGenerateRequest {
        val current = request.params.maxOutputTokens ?: return request
        if (current <= ceiling) return request
        return request.copy(params = request.params.copy(maxOutputTokens = ceiling))
    }
}
