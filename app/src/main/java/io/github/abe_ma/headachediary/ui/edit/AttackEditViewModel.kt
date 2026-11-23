package io.github.abe_ma.headachediary.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.AttackRepository
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Intake
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** 編集中の服薬。key は画面内の安定識別子 (LazyColumn 用)。id は保存済みなら非 0。 */
data class IntakeDraft(
    val key: Long,
    val id: Long,
    val medication: Medication,
    val tablets: Int,
    val effect: Effect,
)

data class AttackEditUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val startedAt: Instant = Instant.now(),
    val severity: Severity = Severity.MODERATE,
    val intakes: List<IntakeDraft> = emptyList(),
    val isBusy: Boolean = false,
    val saveFailed: Boolean = false,
    /** 編集対象が存在しない (削除済みなど)。 */
    val notFound: Boolean = false,
    /** 保存/削除が完了し画面を閉じてよい。 */
    val finished: Boolean = false,
)

const val MIN_TABLETS = 1
const val MAX_TABLETS = 99

@HiltViewModel
class AttackEditViewModel @Inject constructor(
    private val attackRepository: AttackRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    /** ナビゲーション引数 id。0 = 新規。 */
    private val attackId: Long = savedStateHandle.get<Long>(ARG_ID) ?: 0L

    private val _uiState = MutableStateFlow(AttackEditUiState(isNew = attackId == 0L))
    val uiState: StateFlow<AttackEditUiState> = _uiState.asStateFlow()

    private var original: Attack? = null
    private var nextKey = 1L

    init {
        if (attackId == 0L) {
            _uiState.update { it.copy(isLoading = false) }
        } else {
            viewModelScope.launch {
                val loaded = attackRepository.observeAttack(attackId).first()
                if (loaded == null) {
                    _uiState.update { it.copy(isLoading = false, notFound = true) }
                } else {
                    original = loaded
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isNew = false,
                            startedAt = loaded.startedAt,
                            severity = loaded.severity,
                            intakes = loaded.intakes.map { i ->
                                IntakeDraft(nextKey++, i.id, i.medication, i.tablets, i.effect)
                            },
                        )
                    }
                }
            }
        }
    }

    fun setStartedAt(at: Instant) = _uiState.update { it.copy(startedAt = at) }
    fun setSeverity(severity: Severity) = _uiState.update { it.copy(severity = severity) }

    /** FR-203: 薬を服薬に追加。錠数 1、効果は未入力から始める。 */
    fun addMedication(medication: Medication) = _uiState.update {
        it.copy(intakes = it.intakes + IntakeDraft(nextKey++, 0L, medication, MIN_TABLETS, Effect.UNRATED))
    }

    fun removeIntake(key: Long) = _uiState.update { s -> s.copy(intakes = s.intakes.filterNot { it.key == key }) }

    /** FR-205: 錠数は MIN_TABLETS..MAX_TABLETS に収める。 */
    fun changeTablets(key: Long, delta: Int) = updateIntake(key) {
        it.copy(tablets = (it.tablets + delta).coerceIn(MIN_TABLETS, MAX_TABLETS))
    }

    fun setEffect(key: Long, effect: Effect) = updateIntake(key) { it.copy(effect = effect) }

    private fun updateIntake(key: Long, transform: (IntakeDraft) -> IntakeDraft) = _uiState.update { s ->
        s.copy(intakes = s.intakes.map { if (it.key == key) transform(it) else it })
    }

    fun save() {
        val s = _uiState.value
        if (s.isBusy || s.isLoading || s.notFound) return
        _uiState.update { it.copy(isBusy = true, saveFailed = false) }
        viewModelScope.launch {
            val attack = Attack(
                id = attackId,
                startedAt = s.startedAt,
                severity = s.severity,
                // 日時を変えたら気圧を引き直すため null を渡す (保存時に Repository が解決する)。
                pressureHpa = original?.takeIf { it.startedAt == s.startedAt }?.pressureHpa,
                intakes = s.intakes.map { Intake(it.id, it.medication, it.tablets, it.effect) },
            )
            runCatching { attackRepository.save(attack) }
                .onSuccess { _uiState.update { it.copy(isBusy = false, finished = true) } }
                .onFailure { _uiState.update { it.copy(isBusy = false, saveFailed = true) } }
        }
    }

    fun delete() {
        val s = _uiState.value
        if (s.isBusy || attackId == 0L) return
        _uiState.update { it.copy(isBusy = true, saveFailed = false) }
        viewModelScope.launch {
            runCatching { attackRepository.delete(attackId) }
                .onSuccess { _uiState.update { it.copy(isBusy = false, finished = true) } }
                .onFailure { _uiState.update { it.copy(isBusy = false, saveFailed = true) } }
        }
    }

    companion object {
        const val ARG_ID = "id"
    }
}
