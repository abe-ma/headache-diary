package io.github.abe_ma.headachediary.ui

import androidx.annotation.StringRes
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Severity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@StringRes
fun Severity.labelRes(): Int = when (this) {
    Severity.MILD -> R.string.export_severity_mild
    Severity.MODERATE -> R.string.export_severity_moderate
    Severity.SEVERE -> R.string.export_severity_severe
}

@StringRes
fun Effect.labelRes(): Int = when (this) {
    Effect.UNRATED -> R.string.export_effect_unrated
    Effect.EFFECTIVE -> R.string.export_effect_effective
    Effect.SLIGHT -> R.string.export_effect_slight
    Effect.NONE -> R.string.export_effect_none
}

private val dateTimeNoYear = DateTimeFormatter.ofPattern("M月d日(E) H:mm", Locale.JAPAN)
private val dateTimeWithYear = DateTimeFormatter.ofPattern("yyyy年M月d日(E) H:mm", Locale.JAPAN)
private val dateWithYear = DateTimeFormatter.ofPattern("yyyy年M月d日(E)", Locale.JAPAN)
private val timeOnly = DateTimeFormatter.ofPattern("H:mm", Locale.JAPAN)

/** 一覧用。今年なら年を省く。 */
fun formatListDateTime(at: Instant, zone: ZoneId, now: Instant): String {
    val t = at.atZone(zone)
    return if (t.year == now.atZone(zone).year) dateTimeNoYear.format(t) else dateTimeWithYear.format(t)
}

fun formatDate(at: Instant, zone: ZoneId): String = dateWithYear.format(at.atZone(zone))
fun formatTime(at: Instant, zone: ZoneId): String = timeOnly.format(at.atZone(zone))
