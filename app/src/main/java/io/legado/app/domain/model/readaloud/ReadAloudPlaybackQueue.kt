package io.legado.app.domain.model.readaloud

import io.legado.app.constant.AppPattern

data class ReadAloudPlaybackCue(
    val text: String,
    val chapterStart: Int,
    val chapterEnd: Int,
    val paragraphIndex: Int,
    val voice: ReadAloudVoice?,
    val fallbackVoices: List<ReadAloudVoice>,
    val roleType: SpeechRoleType,
    val characterId: String?,
    val emotion: String = "",
    val characterPerformance: CharacterPerformanceProfile? = null,
    val isChapterTitle: Boolean = false,
) {
    init {
        require(chapterStart >= 0)
        if (isChapterTitle) {
            require(chapterStart == 0 && chapterEnd == 0)
        } else {
            require(chapterEnd == chapterStart + text.length)
        }
    }
}

data class ReadAloudPlaybackCursor(
    val cueIndex: Int,
    val offset: Int,
)

data class ReadAloudPlaybackInfo(
    val chapterPosition: Int = 0,
    val chapterLength: Int = 0,
    val text: String = "",
    val engineName: String = "",
    val characterName: String = "",
    val roleType: SpeechRoleType = SpeechRoleType.Narrator,
)

/** Position-based playback queue independent from reader pagination. */
class ReadAloudPlaybackQueue private constructor(
    val cues: List<ReadAloudPlaybackCue>,
) {

    val isEmpty: Boolean get() = cues.isEmpty()
    val leadingTitleCueCount: Int
        get() = cues.indexOfFirst { !it.isChapterTitle }
            .let { if (it < 0) cues.size else it }

    fun cursorAt(chapterPosition: Int): ReadAloudPlaybackCursor? {
        val bodyStartIndex = leadingTitleCueCount
        if (bodyStartIndex >= cues.size) return null
        val position = chapterPosition.coerceAtLeast(0)
        val bodyCues = cues.subList(bodyStartIndex, cues.size)
        val containingIndex = bodyCues.binarySearch { cue ->
            when {
                cue.chapterEnd <= position -> -1
                cue.chapterStart > position -> 1
                else -> 0
            }
        }
        val index = if (containingIndex >= 0) {
            containingIndex
        } else {
            (-containingIndex - 1).coerceAtMost(bodyCues.lastIndex)
        }
        val cueIndex = bodyStartIndex + index
        val cue = cues[cueIndex]
        return ReadAloudPlaybackCursor(
            cueIndex = cueIndex,
            offset = (position - cue.chapterStart).coerceIn(0, cue.text.length),
        )
    }

    fun previous(cursor: ReadAloudPlaybackCursor): ReadAloudPlaybackCursor? =
        (cursor.cueIndex - 1).takeIf { it in cues.indices }
            ?.let { ReadAloudPlaybackCursor(it, 0) }

    fun next(cursor: ReadAloudPlaybackCursor): ReadAloudPlaybackCursor? =
        (cursor.cueIndex + 1).takeIf { it in cues.indices }
            ?.let { ReadAloudPlaybackCursor(it, 0) }

    fun withChapterTitle(title: String): ReadAloudPlaybackQueue {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty() || isEmpty || cues.first().isChapterTitle) return this
        val titleCue = ReadAloudPlaybackCue(
            text = normalizedTitle,
            chapterStart = 0,
            chapterEnd = 0,
            paragraphIndex = -1,
            voice = null,
            fallbackVoices = emptyList(),
            roleType = SpeechRoleType.Narrator,
            characterId = null,
            isChapterTitle = true,
        )
        return ReadAloudPlaybackQueue(listOf(titleCue) + cues)
    }

    companion object {
        val Empty = ReadAloudPlaybackQueue(emptyList())

        fun from(plan: List<SpeechPlanItem>): ReadAloudPlaybackQueue {
            if (plan.isEmpty()) return Empty
            val cues = plan.map { item ->
                val segment = item.segment
                ReadAloudPlaybackCue(
                    text = segment.text,
                    chapterStart = segment.chapterPosition,
                    chapterEnd = segment.chapterPosition + segment.text.length,
                    paragraphIndex = segment.paragraphIndex,
                    voice = item.voice,
                    fallbackVoices = item.fallbackVoices,
                    roleType = segment.roleType,
                    characterId = segment.characterId,
                    emotion = segment.emotion,
                    characterPerformance = item.characterPerformance,
                )
            }.sortedWith(compareBy(ReadAloudPlaybackCue::chapterStart, ReadAloudPlaybackCue::chapterEnd))
            require(cues.zipWithNext().none { (left, right) -> left.chapterEnd > right.chapterStart }) {
                "Playback cues must not overlap"
            }
            return ReadAloudPlaybackQueue(mergeSilentCues(cues))
        }

        /**
         * 纯标点/空白碎段归并。划分策略偶尔会把一行标点单独切成 cue，若按普通片段播出就是
         * "无声磕巴"的源头：无声片段照常合成（得到无声音频）、照常推进章节位置，听感上
         * 是"字在走、声不出"。这里在队列层面收口：连续碎段优先向后并入上一实义片段，
         * 接不上再向前并入下一实义片段，两头都接不上的孤立碎段直接丢弃——
         * cursorAt 本就会把位置缝隙对齐到下一 cue，丢弃不会产生落点空洞。
         */
        private fun mergeSilentCues(
            cues: List<ReadAloudPlaybackCue>,
        ): List<ReadAloudPlaybackCue> {
            val result = ArrayList<ReadAloudPlaybackCue>(cues.size)
            // 待定的一段连续静音碎稿：文本与其章节区间 [pendingStart, pendingEnd)
            var pendingText = ""
            var pendingStart = -1
            var pendingEnd = -1

            fun clearPending() {
                pendingText = ""
                pendingStart = -1
                pendingEnd = -1
            }

            // 尝试把待定碎段接到最近一个实义片段尾部；接不上返回 false（由调用方决定向前并或丢弃）
            fun appendBackward(): Boolean {
                if (pendingStart < 0) return true
                val last = result.lastOrNull() ?: return false
                if (last.isChapterTitle || last.chapterEnd != pendingStart) return false
                result[result.lastIndex] = last.copy(
                    text = last.text + pendingText,
                    chapterEnd = last.chapterEnd + pendingText.length,
                )
                clearPending()
                return true
            }

            for (cue in cues) {
                if (!cue.isChapterTitle && cue.text.matches(AppPattern.notReadAloudRegex)) {
                    if (pendingStart >= 0 && cue.chapterStart == pendingEnd) {
                        // 与待定碎段首尾相接，攒成同一段再一次性归并
                        pendingText += cue.text
                        pendingEnd = cue.chapterEnd
                    } else {
                        // 新碎段与旧待定之间有缝隙：旧待定已不可能向前接（后续 cue 起点更大），先向后归并再丢弃
                        appendBackward()
                        clearPending()
                        pendingText = cue.text
                        pendingStart = cue.chapterStart
                        pendingEnd = cue.chapterEnd
                    }
                    continue
                }
                if (pendingStart >= 0) {
                    if (!appendBackward() && pendingEnd == cue.chapterStart) {
                        // 向后接不上、但与前段起点严丝合缝：整段碎稿前置并入本 cue
                        result.add(
                            cue.copy(
                                text = pendingText + cue.text,
                                chapterStart = pendingStart,
                            )
                        )
                        clearPending()
                        continue
                    }
                    // 两头都接不上：孤立碎段直接丢弃
                    clearPending()
                }
                result.add(cue)
            }
            appendBackward()
            return result
        }
    }
}
