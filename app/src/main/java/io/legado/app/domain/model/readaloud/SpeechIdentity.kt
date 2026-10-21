package io.legado.app.domain.model.readaloud

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SpeechIdentity {

    fun voiceId(engineType: String, engineId: String, speakerId: String): String =
        "voice:${sha256("$engineType\u0000$engineId\u0000$speakerId").take(32)}"

    fun chapterContentHash(paragraphs: List<CanonicalSpeechParagraph>): String = sha256(
        paragraphs.joinToString("\u0001") {
            "${it.index}\u0000${it.chapterPosition}\u0000${it.text}"
        }
    )

    fun analysisId(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): String = "speech-analysis:${sha256("$bookUrl\u0000$chapterIndex\u0000$contentHash\u0000$resolverVersion").take(32)}"

    fun segmentId(
        analysisId: String,
        paragraphIndex: Int,
        start: Int,
        end: Int,
    ): String = "speech-segment:${sha256("$analysisId\u0000$paragraphIndex\u0000$start\u0000$end").take(32)}"

    fun characterRevision(characters: List<SpeakerCharacter>): String = sha256(
        characters.sortedBy(SpeakerCharacter::id).joinToString("\u0001") { character ->
            listOf(
                character.id,
                character.name,
                character.aliases.sorted().joinToString("\u0002"),
                character.role,
                character.voiceGender,
                character.voiceAgeBand,
                character.updatedAt.toString(),
            ).joinToString("\u0000")
        }
    )

    /**
     * 库里那一份分段是不是量在当前这份正文上——**同源判定的唯一口径**。
     *
     * 逐段核对「章内起点 + 原文」在正文的同一处绝对位置上对得上。它刻意不比
     * [chapterContentHash]：那份哈希把划分单元的下标与起点都算进去了，**只是换一种划分方式**
     * 就会算出不一样的值，过去拿它当同源判据，等于用户改一下设置就把做好的分镜作废、
     * 再烧一次 AI。粒度不影响绝对坐标：任何划分方式都只是在同一份正文上换切点，
     * 单元既不跨行也不重叠（换行永远落在单元边界上），所以「某个位置上是哪个字」与怎么切无关。
     *
     * 真会判不相等的只有正文本身换过：重新下载过这一章、改过替换规则、开关过「显示角色」
     * （角色标记进正文，坐标整体后移）。这些确实要重跑，调用方据此决定。
     *
     * 分段为空按「不同源」处理：没有可核对的东西，也就不能声称它对得上。
     */
    fun alignsWithParagraphs(
        paragraphs: List<CanonicalSpeechParagraph>,
        segments: List<ChapterSpeechSegment>,
    ): Boolean {
        if (paragraphs.isEmpty() || segments.isEmpty()) return false
        val length = paragraphs.maxOf { it.chapterPosition + it.text.length }
        if (length <= 0) return false
        // 位置 → 该位置上的字；没被任何单元覆盖的位置留 '\u0000'，那就是正文里的换行
        val chapter = CharArray(length)
        paragraphs.forEach { paragraph ->
            if (paragraph.chapterPosition < 0) return false
            paragraph.text.forEachIndexed { offset, char ->
                chapter[paragraph.chapterPosition + offset] = char
            }
        }
        return segments.all { segment ->
            val from = segment.chapterPosition
            val to = from + segment.text.length
            from >= 0 && to <= length && segment.text.withIndex().all { (offset, char) ->
                chapter[from + offset] == char
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
