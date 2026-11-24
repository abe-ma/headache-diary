package io.github.abe_ma.headachediary.data.repo

import io.github.abe_ma.headachediary.domain.PressureRepository
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** 前日との気圧差 (FR-404): p(t) − p(t−24h)。マイナスが低下。 */
object PressureDelta {
    private val DAY: Duration = Duration.ofHours(24)

    /** 保存済みの気圧が現在地域の p(t) と「同じ」とみなす許容差 (Open-Meteo は小数 1 桁)。 */
    const val SAME_PRESSURE_TOLERANCE_HPA = 0.1 + 1e-9

    /** 保存済みの気圧 [stored] が、現在の登録地域の p(t) [current] と 0.1 hPa 以内で一致するか。 */
    fun isSameRegionPressure(stored: Double?, current: Double?): Boolean =
        stored != null && current != null && abs(stored - current) <= SAME_PRESSURE_TOLERANCE_HPA

    /** 純粋な差。どちらかが欠損なら null。 */
    fun diff(now: Double?, dayBefore: Double?): Double? = if (now == null || dayBefore == null) null else now - dayBefore

    /**
     * 発作時刻 t の気圧差。p(t) は解決済みの [hpa] (保存値/pressureAt)、24 時間前は pressureAt と同じ最寄り ±1 時間。
     * どちらかが取れなければ null。
     */
    suspend fun resolve(pressure: PressureRepository, t: Instant, hpa: Double?): Double? =
        if (hpa == null) null else diff(hpa, pressure.pressureAt(t.minus(DAY)))

    /**
     * 気圧差を埋めてよいときだけ解決する (日次補完と編集保存の共通ガード)。
     * 保存済みの気圧が現在地域の p(t) と一致するときだけ (地域変更後に「旧地域の p(t) − 新地域の p(t−24h)」という
     * 無意味な差を作らない)。不一致なら null。
     */
    suspend fun resolveIfSameRegion(pressure: PressureRepository, t: Instant, storedHpa: Double?, currentHpa: Double?): Double? =
        if (isSameRegionPressure(storedHpa, currentHpa)) resolve(pressure, t, storedHpa) else null
}
