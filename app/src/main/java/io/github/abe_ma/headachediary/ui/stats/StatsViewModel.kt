package io.github.abe_ma.headachediary.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.AttackRepository
import io.github.abe_ma.headachediary.domain.MonthCount
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.PressureRepository
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** 月別グラフに出す月数 (FR-300: 切り替えて遡れる)。 */
enum class MonthSpan(val months: Int) { SIX(6), TWELVE(12), TWENTY_FOUR(24) }

/** 気圧グラフの期間 (FR-301)。過去 [pastDays] 日 + 予報 [FORECAST_DAYS] 日。 */
enum class PressurePeriod(val pastDays: Long) { WEEK(7), MONTH(30) }

const val FORECAST_DAYS = 3L

data class StatsUiState(
    val isLoading: Boolean = true,
    val monthSpan: MonthSpan = MonthSpan.SIX,
    /** 古い月 → 新しい月。 */
    val monthlyCounts: List<MonthCount> = emptyList(),
    val pressurePeriod: PressurePeriod = PressurePeriod.WEEK,
    val windowStart: Instant = Instant.EPOCH,
    val windowEnd: Instant = Instant.EPOCH,
    val now: Instant = Instant.EPOCH,
    /** 気圧の1時間値 (過去 + 予報)。時刻順。 */
    val pressure: List<PressurePoint> = emptyList(),
    /** 期間内の発作 (新しい順)。 */
    val attacks: List<Attack> = emptyList(),
    /** 地域を登録済みか。 */
    val hasPrefecture: Boolean = false,
    /** FR-105 */
    val lastFetchedAt: Instant? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel internal constructor(
    attackRepository: AttackRepository,
    pressureRepository: PressureRepository,
    settingsRepository: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    @Inject constructor(
        attackRepository: AttackRepository,
        pressureRepository: PressureRepository,
        settingsRepository: SettingsRepository,
    ) : this(attackRepository, pressureRepository, settingsRepository, Clock.systemDefaultZone())

    private val monthSpan = MutableStateFlow(MonthSpan.SIX)
    private val period = MutableStateFlow(PressurePeriod.WEEK)

    private val monthly = monthSpan.flatMapLatest { span ->
        attackRepository.observeMonthlyCounts(span.months).map { span to it }
    }

    private data class Window(val period: PressurePeriod, val now: Instant, val start: Instant, val end: Instant)

    private val window = period.map { p ->
        val now = clock.instant()
        Window(p, now, now.minus(Duration.ofDays(p.pastDays)), now.plus(Duration.ofDays(FORECAST_DAYS)))
    }

    private val pressureAndAttacks = window.flatMapLatest { w ->
        combine(
            pressureRepository.observeSeries(w.start..w.end),
            attackRepository.observeAttacks(w.start..w.end),
        ) { series, attacks -> Triple(w, series.sortedBy { it.time }, attacks) }
    }

    val uiState: StateFlow<StatsUiState> = combine(
        monthly,
        pressureAndAttacks,
        settingsRepository.observe(),
        pressureRepository.observeLastFetchedAt(),
    ) { (span, counts), (w, series, attacks), settings, fetched ->
        StatsUiState(
            isLoading = false,
            monthSpan = span,
            monthlyCounts = counts,
            pressurePeriod = w.period,
            windowStart = w.start,
            windowEnd = w.end,
            now = w.now,
            pressure = series,
            attacks = attacks,
            hasPrefecture = settings.prefectureCode != null,
            lastFetchedAt = fetched,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setMonthSpan(span: MonthSpan) = monthSpan.update { span }
    fun setPressurePeriod(p: PressurePeriod) = period.update { p }
}
