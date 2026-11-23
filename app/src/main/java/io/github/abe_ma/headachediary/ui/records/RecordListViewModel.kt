package io.github.abe_ma.headachediary.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.AttackRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface RecordListUiState {
    data object Loading : RecordListUiState
    /** attacks は新しい順。 */
    data class Content(val attacks: List<Attack>) : RecordListUiState
}

@HiltViewModel
class RecordListViewModel @Inject constructor(
    attackRepository: AttackRepository,
) : ViewModel() {
    val uiState: StateFlow<RecordListUiState> = attackRepository.observeAttacks()
        .map<List<Attack>, RecordListUiState> { RecordListUiState.Content(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordListUiState.Loading)
}
