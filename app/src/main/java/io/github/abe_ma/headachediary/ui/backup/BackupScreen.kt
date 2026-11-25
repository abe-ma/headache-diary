package io.github.abe_ma.headachediary.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.BackupInspection
import io.github.abe_ma.headachediary.domain.RestoreResult
import io.github.abe_ma.headachediary.ui.formatDate
import io.github.abe_ma.headachediary.ui.formatListDateTime
import java.time.Instant
import java.time.ZoneId

const val TAG_BACKUP_CREATE = "backup_create"
const val TAG_BACKUP_RESTORE = "backup_restore"
const val TAG_BACKUP_PROGRESS = "backup_progress"
const val TAG_BACKUP_WARNING_ACCEPT = "backup_warning_accept"
const val TAG_BACKUP_WARNING_CANCEL = "backup_warning_cancel"
const val TAG_BACKUP_CONFIRM_ACCEPT = "backup_confirm_accept"
const val TAG_BACKUP_CONFIRM_CANCEL = "backup_confirm_cancel"
const val TAG_BACKUP_CREATED = "backup_created"
const val TAG_BACKUP_RESULT = "backup_result"
const val TAG_BACKUP_RESULT_SETTINGS = "backup_result_settings"
const val TAG_BACKUP_ERROR = "backup_error"

@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // SAF (保存先/ファイルの選択) はアプリの追加権限が不要 (FR-601)。結果は ViewModel が処理する。
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        viewModel.onCreateDocumentResult(it)
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.onOpenDocumentResult(it)
    }
    // 起動要求は一度だけ実行する (回転しても再実行しない)。
    LaunchedEffect(state.launch) {
        when (val l = state.launch) {
            is BackupLaunch.CreateDocument -> createLauncher.launch(l.suggestedName)
            // 保存先によって .json が application/octet-stream などで返るため、種類は絞らない。
            // 中身の検証は inspect が行い、バックアップ形式でなければ理由を表示して中止する。
            BackupLaunch.OpenDocument -> openLauncher.launch(arrayOf("*/*"))
            null -> return@LaunchedEffect
        }
        viewModel.onLaunchConsumed()
    }

    BackupContent(
        state = state,
        onBack = onBack,
        onCreate = viewModel::onCreateClicked,
        onWarningAccept = viewModel::onCreateWarningAccepted,
        onRestore = viewModel::onOpenClicked,
        onConfirmRestore = viewModel::onRestoreConfirmed,
        onDismiss = viewModel::onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupContent(
    state: BackupUiState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onWarningAccept: () -> Unit,
    onRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    val phase = state.phase
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading(stringResource(R.string.backup_section_create))
            Text(stringResource(R.string.backup_create_desc), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = onCreate,
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TAG_BACKUP_CREATE),
            ) { Text(stringResource(R.string.backup_create_button), style = MaterialTheme.typography.titleMedium) }

            Heading(stringResource(R.string.backup_section_restore), Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.backup_restore_desc), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(
                onClick = onRestore,
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TAG_BACKUP_RESTORE),
            ) { Text(stringResource(R.string.backup_restore_button), style = MaterialTheme.typography.titleMedium) }

            if (phase is BackupPhase.Working) {
                Row(
                    Modifier.testTag(TAG_BACKUP_PROGRESS).padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(
                        stringResource(
                            when (phase.operation) {
                                BackupOperation.CREATING -> R.string.backup_working_creating
                                BackupOperation.INSPECTING -> R.string.backup_working_inspecting
                                BackupOperation.RESTORING -> R.string.backup_working_restoring
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Text(
                stringResource(R.string.backup_note_separate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.backup_note_no_upload),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when (phase) {
        BackupPhase.CreateWarning -> CreateWarningDialog(onAccept = onWarningAccept, onCancel = onDismiss)
        BackupPhase.CreateDone -> InfoDialog(
            title = stringResource(R.string.backup_created_title),
            tag = TAG_BACKUP_CREATED,
            onClose = onDismiss,
        ) { Text(stringResource(R.string.backup_created_body)) }
        is BackupPhase.RestoreConfirm -> RestoreConfirmDialog(phase.inspection, zone, now, onConfirmRestore, onDismiss)
        is BackupPhase.RestoreDone -> RestoreResultDialog(phase.result, onBack = onBack, onClose = onDismiss)
        is BackupPhase.Failed -> ErrorDialog(phase, onClose = onDismiss)
        BackupPhase.Idle, is BackupPhase.Working -> Unit
    }
}

@Composable
private fun CreateWarningDialog(onAccept: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_warning_title)) },
        text = { Text(stringResource(R.string.backup_warning_body)) },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_BACKUP_WARNING_ACCEPT)) {
                Text(stringResource(R.string.backup_warning_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_BACKUP_WARNING_CANCEL)) {
                Text(stringResource(R.string.backup_warning_cancel))
            }
        },
    )
}

/** FR-603: 内容の概要。「読み込む」を押すまで何も変わらない。 */
@Composable
private fun RestoreConfirmDialog(
    inspection: BackupInspection.Valid,
    zone: ZoneId,
    now: Instant,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_confirm_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.backup_confirm_exported, formatListDateTime(inspection.exportedAt, zone, now), inspection.appVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (inspection.attackCount == 0) {
                    Text(stringResource(R.string.backup_confirm_attacks_none))
                } else {
                    Text(stringResource(R.string.backup_confirm_attacks, inspection.attackCount))
                    val start = inspection.periodStart
                    val end = inspection.periodEnd
                    if (start != null && end != null) {
                        Text(stringResource(R.string.backup_confirm_period, formatDate(start, zone), formatDate(end, zone)))
                    }
                    Text(stringResource(R.string.backup_confirm_new_duplicate, inspection.newAttackCount, inspection.duplicateCount))
                }
                Text(stringResource(R.string.backup_confirm_custom_meds, inspection.customMedicationCount))
                Text(
                    stringResource(
                        if (inspection.includesSettings) R.string.backup_confirm_settings_included else R.string.backup_confirm_settings_none,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_BACKUP_CONFIRM_ACCEPT)) {
                Text(stringResource(R.string.backup_confirm_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_BACKUP_CONFIRM_CANCEL)) {
                Text(stringResource(R.string.backup_confirm_cancel))
            }
        },
    )
}

/** FR-607: 結果 (追加/スキップ/薬/設定/通知オフ)。通知は権限が要るので、設定で有効にし直すよう案内する (FR-505)。 */
@Composable
private fun RestoreResultDialog(result: RestoreResult, onBack: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(TAG_BACKUP_RESULT),
        title = { Text(stringResource(R.string.backup_result_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_result_added, result.addedAttacks))
                Text(stringResource(R.string.backup_result_skipped, result.skippedDuplicates))
                Text(stringResource(R.string.backup_result_meds, result.addedMedications))
                Text(
                    stringResource(
                        if (result.settingsApplied) R.string.backup_result_settings_applied else R.string.backup_result_settings_none,
                    ),
                )
                if (result.notificationsTurnedOff) {
                    Text(stringResource(R.string.backup_result_notifications_off))
                }
            }
        },
        confirmButton = {
            if (result.notificationsTurnedOff) {
                TextButton(
                    onClick = { onClose(); onBack() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_BACKUP_RESULT_SETTINGS),
                ) { Text(stringResource(R.string.backup_result_open_settings)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_result_close))
            }
        },
    )
}

@Composable
private fun ErrorDialog(failed: BackupPhase.Failed, onClose: () -> Unit) {
    val reason = stringResource(
        when (failed.error) {
            BackupError.NOT_BACKUP -> R.string.backup_error_not_backup
            BackupError.CORRUPT -> R.string.backup_error_corrupt
            BackupError.TOO_LARGE -> R.string.backup_error_too_large
            BackupError.UNSUPPORTED_VERSION -> R.string.backup_error_unsupported_version
            BackupError.IO -> R.string.backup_error_io
        },
    )
    InfoDialog(
        title = stringResource(if (failed.duringCreate) R.string.backup_error_create_title else R.string.backup_error_title),
        tag = TAG_BACKUP_ERROR,
        onClose = onClose,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(reason)
            // 読み込みの失敗は、何も変更していないことを伝える (FR-606)。
            if (!failed.duringCreate) Text(stringResource(R.string.backup_error_unchanged))
        }
    }
}

@Composable
private fun InfoDialog(title: String, tag: String, onClose: () -> Unit, body: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(tag),
        title = { Text(title) },
        text = body,
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_error_close))
            }
        },
    )
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}
