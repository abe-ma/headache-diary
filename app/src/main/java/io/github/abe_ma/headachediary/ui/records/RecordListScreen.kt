package io.github.abe_ma.headachediary.ui.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.ui.formatListDateTime
import io.github.abe_ma.headachediary.ui.labelRes
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

const val TAG_ADD_ATTACK = "add_attack"
const val TAG_RECORD_EMPTY = "record_empty"
fun recordItemTag(id: Long) = "record_item_$id"

@Composable
fun RecordListScreen(
    onAddAttack: () -> Unit,
    onOpenAttack: (Long) -> Unit,
    viewModel: RecordListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecordListContent(state, onAddAttack, onOpenAttack)
}

@Composable
fun RecordListContent(
    state: RecordListUiState,
    onAddAttack: () -> Unit,
    onOpenAttack: (Long) -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.record_list_title)) }) },
        floatingActionButton = {
            // 大きく押しやすい拡張 FAB。頭痛中でも片手で押せる位置・サイズ。
            ExtendedFloatingActionButton(
                onClick = onAddAttack,
                modifier = Modifier.heightIn(min = 64.dp).testTag(TAG_ADD_ATTACK),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.record_list_add), style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        when (state) {
            RecordListUiState.Loading -> Unit
            is RecordListUiState.Content ->
                if (state.attacks.isEmpty()) {
                    EmptyState(Modifier.padding(padding))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        // FAB に最後の行が隠れないよう下に余白。
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.attacks, key = { it.id }) { attack ->
                            AttackRow(attack, zone, now) { onOpenAttack(attack.id) }
                        }
                    }
                }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp).testTag(TAG_RECORD_EMPTY),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.record_list_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.record_list_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AttackRow(attack: Attack, zone: ZoneId, now: Instant, onClick: () -> Unit) {
    val whenText = formatListDateTime(attack.startedAt, zone, now)
    val severityText = stringResource(attack.severity.labelRes())
    val sep = stringResource(R.string.intake_summary_separator)
    val intakeFormat = stringResource(R.string.intake_summary)
    val noMedication = stringResource(R.string.record_list_no_medication)
    val medsText = if (attack.intakes.isEmpty()) {
        noMedication
    } else {
        attack.intakes.joinToString(sep) { intakeFormat.format(it.medication.name, it.tablets) }
    }
    val pending = attack.intakes.any { it.effect == Effect.UNRATED }
    val description = stringResource(R.string.record_item_description, whenText, severityText, medsText)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag(recordItemTag(attack.id))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(whenText, style = MaterialTheme.typography.titleMedium)
                // 強さは色だけに頼らず文字で示す。
                Text(severityText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(medsText, style = MaterialTheme.typography.bodyLarge)
            if (pending) {
                Text(
                    stringResource(R.string.record_list_effect_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            attack.pressureHpa?.let {
                Text(
                    stringResource(R.string.record_list_pressure, String.format(Locale.JAPAN, "%.1f", it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
