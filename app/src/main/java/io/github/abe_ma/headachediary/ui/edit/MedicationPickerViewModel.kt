package io.github.abe_ma.headachediary.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.abe_ma.headachediary.domain.InputLimits
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.MedicationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class MedicationPickerUiState(
    val query: String = "",
    /** 非表示を除く・使用回数順 (Repository の並びを保つ)。query で絞り込み済み。 */
    val candidates: List<Medication> = emptyList(),
    /** 検索語が既存の薬名と完全一致しないときだけ、自由入力で追加できる名前。 */
    val customName: String? = null,
)

@HiltViewModel
class MedicationPickerViewModel @Inject constructor(
    private val medicationRepository: MedicationRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val uiState: StateFlow<MedicationPickerUiState> =
        combine(medicationRepository.observeCandidates(), query) { all, q ->
            val needle = q.trim()
            val filtered = if (needle.isEmpty()) all else all.filter {
                it.name.contains(needle, ignoreCase = true) ||
                    (it.ingredient?.contains(needle, ignoreCase = true) == true)
            }
            val custom = needle.takeIf { n -> n.isNotEmpty() && all.none { it.name == n } }
            MedicationPickerUiState(q, filtered, custom)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedicationPickerUiState())

    /** FR-608a: 入力欄は薬名の上限までに制限する (貼り付けでも切り詰める)。 */
    fun setQuery(value: String) = query.update { value.take(InputLimits.MAX_MEDICATION_NAME_LENGTH) }

    fun clearQuery() = query.update { "" }

    /** FR-203: 自由入力で追加 (同名は既存が返る)。 */
    suspend fun addCustom(name: String): Medication =
        medicationRepository.addCustom(name.trim().take(InputLimits.MAX_MEDICATION_NAME_LENGTH))
}
