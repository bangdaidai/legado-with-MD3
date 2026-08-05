package io.legado.app.ui.book.readRecord

import io.legado.app.utils.formatReadDuration
import kotlin.time.Duration.Companion.milliseconds

object ReadRecordFormatter {
    data class HourMinuteDuration(val hours: Long, val minutes: Int)

    fun formatWords(words: Long): String {
        return if (words >= 10000) {
            String.format("%.1f万字", words / 10000f)
        } else {
            "${words}字"
        }
    }

    fun formatDuration(millis: Long): String = formatReadDuration(millis)

    fun hourMinuteDuration(millis: Long): HourMinuteDuration =
        millis.milliseconds.toComponents { hours, minutes, _, _ ->
            HourMinuteDuration(hours, minutes)
        }

    fun formatBarPeakDuration(millis: Long): String {
        val totalMinutes = (millis / 60_000).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }
}
