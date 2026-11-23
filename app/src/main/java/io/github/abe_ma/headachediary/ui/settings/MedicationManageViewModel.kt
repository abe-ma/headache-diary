package io.github.abe_ma.headachediary.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.MedicationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MedicationManageViewModel @Inject constructor(
    private val medicationRepository: MedicationRepository,
) : ViewModel() {
    /** FR-207: 非表示も含む全件。薬の削除はしない。 */
    val medications: StateFlow<List<Medication>?> = medicationRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setVisible(id: Long, visible: Boolean) {
        viewModelScope.launch { medicationRepository.setHidden(id, hidden = !visible) }
    }
}
