package io.github.abe_ma.headachediary.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.Medication
import kotlinx.coroutines.launch

const val TAG_PICKER_SEARCH = "picker_search"
const val TAG_PICKER_CUSTOM = "picker_custom"
fun pickerItemTag(id: Long) = "picker_item_$id"

/** FR-203, 206: 薬選択ボトムシート。選んだら onSelected → 呼び出し側が閉じる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationPickerSheet(
    onSelected: (Medication) -> Unit,
    onDismiss: () -> Unit,
    viewModel: MedicationPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        MedicationPickerContent(
            state = state,
            onQueryChange = viewModel::setQuery,
            onSelect = onSelected,
            onAddCustom = { name -> scope.launch { onSelected(viewModel.addCustom(name)) } },
        )
    }
}

@Composable
fun MedicationPickerContent(
    state: MedicationPickerUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (Medication) -> Unit,
    onAddCustom: (String) -> Unit,
) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.picker_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag(TAG_PICKER_SEARCH),
        )
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            state.customName?.let { name ->
                item(key = "custom") {
                    PickerRow(
                        title = stringResource(R.string.picker_add_custom, name),
                        subtitle = null,
                        leading = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.testTag(TAG_PICKER_CUSTOM),
                    ) { onAddCustom(name) }
                    HorizontalDivider()
                }
            }
            if (state.candidates.isEmpty() && state.customName == null) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.picker_empty),
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.candidates, key = { it.id }) { med ->
                PickerRow(
                    title = med.name,
                    subtitle = med.ingredient?.let { stringResource(R.string.picker_ingredient, it) },
                    modifier = Modifier.testTag(pickerItemTag(med.id)),
                ) { onSelect(med) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
