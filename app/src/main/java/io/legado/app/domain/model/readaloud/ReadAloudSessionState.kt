package io.legado.app.domain.model.readaloud

enum class ReadAloudSessionStatus {
    Idle,

    /** 已接到朗读请求、还在生成朗读计划（含 AI 分析），此时一个字都还没念出来 */
    Preparing,
    Playing,
    Paused,
}

data class ReadAloudSessionState(
    val status: ReadAloudSessionStatus = ReadAloudSessionStatus.Idle,
    val playback: ReadAloudPlaybackInfo = ReadAloudPlaybackInfo(),
    val timerMinutes: Int = 0,
)
