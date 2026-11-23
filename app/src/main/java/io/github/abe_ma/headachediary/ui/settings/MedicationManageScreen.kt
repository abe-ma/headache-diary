package io.github.abe_ma.headachediary.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.Medication

fun medicationSwitchTag(id: Long) = "medication_switch_$id"

@Composable
fun MedicationManageScreen(onBack: () -> Unit, viewModel: MedicationManageViewModel = hiltViewModel()) {
    val meds by viewModel.medications.collectAsStateWithLifecycle()
    MedicationManageContent(meds, onBack, viewModel::setVisible)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationManageContent(
    medications: List<Medication>?,
    onBack: () -> Unit,
    onVisibleChange: (id: Long, visible: Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.medications_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item(key = "desc") {
                Text(
                    stringResource(R.string.medications_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(medications.orEmpty(), key = { it.id }) { med ->
                val visible = !med.isHidden
                val description = stringResource(R.string.medications_switch_description, med.name)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .toggleable(value = visible, role = Role.Switch, onValueChange = { onVisibleChange(med.id, it) })
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { contentDescription = description }
                        .testTag(medicationSwitchTag(med.id)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            med.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val sub = med.ingredient?.let { stringResource(R.string.picker_ingredient, it) }
                        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!visible) {
                            Text(stringResource(R.string.medications_hidden_badge), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(checked = visible, onCheckedChange = null)
                }
                HorizontalDivider()
            }
        }
    }
}
