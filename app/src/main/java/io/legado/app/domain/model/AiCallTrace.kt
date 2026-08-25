package io.legado.app.domain.model

/**
 * 轻量的 AI 调用过程记录器。Repository 在每次调用前创建一个 [RecordingTrace]，
 * 顺着协议处理器一路标记关键节点（发送请求、建立连接、首字符到达），
 * 调用结束后再把这些步骤写进 AI 日志，方便用户复盘「到底卡在哪一步、
 * 有没有连上、是慢慢生成还是一直没反应」。
 */
interface AiCallTrace {
    fun mark(label: String)
    fun markFirstToken()
    val steps: List<AiLogStep>
}

/** 单次 AI 调用中的一个过程节点：相对调用开始的毫秒数 + 说明。 */
data class AiLogStep(
    val relativeMs: Long,
    val label: String,
)

/** 不记录任何过程的占位实现，供不需要日志的调用方使用。 */
object NoOpTrace : AiCallTrace {
    override fun mark(label: String) = Unit
    override fun markFirstToken() = Unit
    override val steps: List<AiLogStep> get() = emptyList()
}

/** 真正的记录实现，节点按发生顺序追加。 */
class RecordingTrace(
    private val base: Long = System.currentTimeMillis()
) : AiCallTrace {
    private val _steps = mutableListOf<AiLogStep>()
    private var firstTokenMarked = false

    override val steps: List<AiLogStep> get() = _steps

    override fun mark(label: String) {
        _steps.add(AiLogStep(System.currentTimeMillis() - base, label))
    }

    override fun markFirstToken() {
        if (!firstTokenMarked) {
            firstTokenMarked = true
            mark("首字符到达")
        }
    }
}
