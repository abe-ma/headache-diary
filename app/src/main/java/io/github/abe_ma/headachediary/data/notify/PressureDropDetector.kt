package io.github.abe_ma.headachediary.data.notify

import io.github.abe_ma.headachediary.domain.PressurePoint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 気圧低下の判定 (FR-501、設計 3 章)。
 * 対象日 (ローカル 0:00〜24:00) に含まれる各時刻 t について、t より前 24 時間以内の最大値と p(t) の差を下降幅とし、
 * その最大値を返す。対象日にデータが無い、または比較対象の過去データが無い場合は null (判定不能 = 通知しない)。
 */
object PressureDropDetector {
    private val WINDOW = Duration.ofHours(24)

    fun maxDrop(series: List<PressurePoint>, day: LocalDate, zone: ZoneId): Double? {
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val sorted = series.sortedBy { it.time }
        var best: Double? = null
        for (p in sorted) {
            if (p.time < start || p.time >= end) continue
            val from: Instant = p.time.minus(WINDOW)
            val windowMax = sorted.asSequence()
                .filter { it.time >= from && it.time < p.time }
                .maxOfOrNull { it.hpa } ?: continue
            val drop = windowMax - p.hpa
            if (best == null || drop > best) best = drop
        }
        return best
    }

    /** しきい値以上か (境界を含む。浮動小数の誤差は 1e-9 で吸収)。 */
    fun isDrop(drop: Double?, thresholdHpa: Double): Boolean = drop != null && drop + 1e-9 >= thresholdHpa
}
