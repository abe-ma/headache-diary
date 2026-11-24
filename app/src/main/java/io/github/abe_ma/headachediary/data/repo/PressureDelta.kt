package io.github.abe_ma.headachediary.data.repo

import io.github.abe_ma.headachediary.domain.PressureRepository
import java.time.Duration
import java.time.Instant

/** 前日との気圧差 (FR-404): p(t) − p(t−24h)。マイナスが低下。 */
object PressureDelta {
    private val DAY: Duration = Duration.ofHours(24)

    /** 純粋な差。どちらかが欠損なら null。 */
    fun diff(now: Double?, dayBefore: Double?): Double? = if (now == null || dayBefore == null) null else now - dayBefore

    /**
     * 発作時刻 t の気圧差。p(t) は解決済みの [hpa] (保存値/pressureAt)、24 時間前は pressureAt と同じ最寄り ±1 時間。
     * どちらかが取れなければ null。
     */
    suspend fun resolve(pressure: PressureRepository, t: Instant, hpa: Double?): Double? =
        if (hpa == null) null else diff(hpa, pressure.pressureAt(t.minus(DAY)))
}
