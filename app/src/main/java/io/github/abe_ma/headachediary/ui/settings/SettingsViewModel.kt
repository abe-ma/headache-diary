package io.github.abe_ma.headachediary.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.abe_ma.headachediary.domain.Prefecture
import io.github.abe_ma.headachediary.domain.PrefectureRepository
import io.github.abe_ma.headachediary.domain.PressureRepository
import io.github.abe_ma.headachediary.domain.Settings
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import javax.inject.Inject

/** 効果リマインドの遅延の選択肢 (分)。FR-500。 */
val EFFECT_DELAY_OPTIONS_MINUTES = listOf(30, 60, 120, 180, 360)

const val MIN_DROP_THRESHOLD_HPA = 1.0
const val MAX_DROP_THRESHOLD_HPA = 20.0
const val DROP_THRESHOLD_STEP_HPA = 1.0

data class SettingsUiState(
    val isLoading: Boolean = true,
    val settings: Settings = Settings(prefectureCode = null),
    /** 47 都道府県 (JIS 順)。 */
    val prefectures: List<Prefecture> = emptyList(),
    /** FR-105: 最終取得時刻。まだ取得していなければ null。 */
    val lastFetchedAt: Instant? = null,
) {
    val prefecture: Prefecture? get() = prefectures.firstOrNull { it.code == settings.prefectureCode }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    pressureRepository: PressureRepository,
    prefectureRepository: PrefectureRepository,
) : ViewModel() {
    private val prefectures = prefectureRepository.all()

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.observe(),
        pressureRepository.observeLastFetchedAt(),
    ) { settings, fetched ->
        SettingsUiState(isLoading = false, settings = settings, prefectures = prefectures, lastFetchedAt = fetched)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(prefectures = prefectures))

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    /**
     * FR-100: 地域の登録 (null = 未設定)。保存後の即時取得は SettingsRepository 側の責務。
     */
    fun setPrefecture(code: String?) = update { it.copy(prefectureCode = code) }

    fun setEffectReminderEnabled(enabled: Boolean) = update { it.copy(effectReminderEnabled = enabled) }

    fun setEffectReminderDelay(minutes: Int) = update { it.copy(effectReminderDelayMinutes = minutes) }

    fun setDropNotifyEnabled(enabled: Boolean) = update { it.copy(dropNotifyEnabled = enabled) }

    /** FR-501: しきい値は 1〜20 hPa の範囲に収める。 */
    fun changeDropThreshold(deltaSteps: Int) = update {
        val next = (it.dropThresholdHpa + deltaSteps * DROP_THRESHOLD_STEP_HPA)
            .coerceIn(MIN_DROP_THRESHOLD_HPA, MAX_DROP_THRESHOLD_HPA)
        it.copy(dropThresholdHpa = next)
    }

    fun setDropNotifyTime(time: LocalTime) = update { it.copy(dropNotifyTime = time) }
}
