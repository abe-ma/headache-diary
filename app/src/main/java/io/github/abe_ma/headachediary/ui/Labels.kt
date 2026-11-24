package io.github.abe_ma.headachediary.ui

import androidx.annotation.StringRes
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.MedicationUnit
import io.github.abe_ma.headachediary.domain.PressureFormat
import io.github.abe_ma.headachediary.domain.Severity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

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

/** FR-209: 数量の単位 (錠/包)。書き出しと同じ文言を共用する。 */
@StringRes
fun MedicationUnit.labelRes(): Int = when (this) {
    MedicationUnit.TABLET -> R.string.export_unit_tablet
    MedicationUnit.PACKET -> R.string.export_unit_packet
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

/** 気圧 (hPa) を小数1桁で。 */
fun formatPressure(hpa: Double): String = String.format(Locale.JAPAN, "%.1f", hpa)

/**
 * FR-404: 前日との気圧差を符号付き小数1桁で (例: `+1.2` / `-3.4` / `0.0`)。
 * 小数1桁に丸めて 0.0 になるものは符号なしの `0.0` にする (`-0.0` と出さない)。
 */
fun formatPressureDelta(deltaHpa: Double): String = PressureFormat.delta(deltaHpa) // 規則は domain.PressureFormat に集約 (CSV/PDF と共用)

/** 読み上げ用の前日差の向き。丸めて 0.0 なら「同じ」。 */
enum class DeltaDirection { LOWER, HIGHER, SAME }

fun pressureDeltaDirection(deltaHpa: Double): DeltaDirection = when {
    formatPressureDelta(deltaHpa) == "0.0" -> DeltaDirection.SAME
    deltaHpa < 0 -> DeltaDirection.LOWER
    else -> DeltaDirection.HIGHER
}

/** 差の大きさ (符号なし、小数1桁)。 */
fun formatPressureDeltaMagnitude(deltaHpa: Double): String = PressureFormat.magnitude(deltaHpa)
