package io.github.abe_ma.headachediary.ui.edit

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.InputLimits
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.ui.DeltaDirection
import io.github.abe_ma.headachediary.ui.formatDate
import io.github.abe_ma.headachediary.ui.formatPressure
import io.github.abe_ma.headachediary.ui.formatPressureDelta
import io.github.abe_ma.headachediary.ui.formatPressureDeltaMagnitude
import io.github.abe_ma.headachediary.ui.pressureDeltaDirection
import io.github.abe_ma.headachediary.ui.formatTime
import io.github.abe_ma.headachediary.ui.labelRes
import io.github.abe_ma.headachediary.ui.settings.notificationPermissionName
import io.github.abe_ma.headachediary.ui.settings.notificationsGranted
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

const val TAG_NOTIFY_ACCEPT = "notify_prompt_accept"
const val TAG_NOTIFY_DECLINE = "notify_prompt_decline"
const val TAG_BACK = "edit_back"
const val TAG_DISCARD_CONFIRM = "edit_discard_confirm"
const val TAG_PRESSURE_INFO = "edit_pressure_info"
const val TAG_PRESSURE_VALUE = "edit_pressure_value"
const val TAG_PRESSURE_DELTA = "edit_pressure_delta"
const val TAG_PRESSURE_STALE = "edit_pressure_stale"
const val TAG_SAVE = "edit_save"
const val TAG_SAVE_REASON = "edit_save_reason"
const val TAG_SEVERITY_REQUIRED = "severity_required"
const val TAG_DELETE = "edit_delete"
const val TAG_DELETE_CONFIRM = "edit_delete_confirm"
const val TAG_ADD_MEDICATION = "edit_add_medication"
const val TAG_INTAKES_LIMIT = "edit_intakes_limit"
const val TAG_PICK_DATE = "edit_pick_date"
const val TAG_PICK_TIME = "edit_pick_time"
const val TAG_TIME_OK = "edit_time_ok"
fun severityTag(s: Severity) = "severity_${s.name}"
fun effectTag(key: Long, e: Effect) = "effect_${key}_${e.name}"
fun tabletsPlusTag(key: Long) = "tablets_plus_$key"
fun tabletsMinusTag(key: Long) = "tablets_minus_$key"
fun tabletsValueTag(key: Long) = "tablets_value_$key"
fun removeIntakeTag(key: Long) = "remove_intake_$key"

@Composable
fun AttackEditScreen(
    onClose: () -> Unit,
    viewModel: AttackEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.finished, state.notFound) {
        if (state.finished) onClose()
    }

    // FR-505(b): 「受け取る」で権限を要求し、結果を ViewModel に渡す。
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPromptAccepted(granted)
    }
    NotificationPromptDialog(
        stage = state.postSavePrompt,
        onAccept = {
            if (notificationsGranted(context)) viewModel.onPromptAccepted(true)
            else permissionLauncher.launch(notificationPermissionName())
        },
        onDecline = viewModel::onPromptDismissed,
    )

    AttackEditContent(
        state = state,
        onBack = onClose,
        onDateTimeChange = viewModel::setStartedAt,
        onSeverityChange = viewModel::setSeverity,
        onAddMedication = { showPicker = true },
        onRemoveIntake = viewModel::removeIntake,
        onTabletsChange = viewModel::changeTablets,
        onEffectChange = viewModel::setEffect,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
    )
    if (showPicker) {
        MedicationPickerSheet(
            onSelected = { med: Medication ->
                viewModel.addMedication(med)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttackEditContent(
    state: AttackEditUiState,
    onBack: () -> Unit,
    onDateTimeChange: (Instant) -> Unit,
    onSeverityChange: (Severity) -> Unit,
    onAddMedication: () -> Unit,
    onRemoveIntake: (Long) -> Unit,
    onTabletsChange: (key: Long, delta: Int) -> Unit,
    onEffectChange: (key: Long, effect: Effect) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    // 未保存の変更があるときは、戻る操作 (ボタン/ジェスチャ) の前に確認する。
    val requestBack = { if (state.isDirty && !state.isBusy) confirmDiscard = true else onBack() }
    BackHandler(enabled = state.isDirty && !state.isBusy) { confirmDiscard = true }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isNew) R.string.edit_title_new else R.string.edit_title_existing))
                },
                navigationIcon = {
                    IconButton(onClick = requestBack, modifier = Modifier.size(56.dp).testTag(TAG_BACK)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            // 保存は常に画面下で押せる位置に固定 (少タップ)。
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 保存できない理由を、無効なボタンの近くに文字で示す (色だけに頼らない)。
                    if (state.severity == null && !state.isLoading && !state.notFound) {
                        Text(
                            stringResource(R.string.edit_save_needs_severity),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(TAG_SAVE_REASON),
                        )
                    }
                    Button(
                        onClick = onSave,
                        enabled = state.canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TAG_SAVE),
                    ) { Text(stringResource(R.string.edit_save), style = MaterialTheme.typography.titleMedium) }
                }
            }
        },
    ) { padding ->
        if (state.notFound) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp)) {
                Text(stringResource(R.string.edit_not_found), style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            DateTimeSection(state.startedAt, zone, onDateTimeChange)
            // FR-404: 既存記録のみ、保存済みの気圧と前日差を読み取り専用で表示する。
            if (!state.isNew && !state.isLoading) PressureInfoSection(state)
            // 既存記録の読み込み中は強さがまだ入っていないので、未選択の警告は出さない。
            SeveritySection(state.severity, showRequired = !state.isLoading && !state.notFound, onChange = onSeverityChange)
            IntakeSection(state.intakes, state.canAddMedication, onAddMedication, onRemoveIntake, onTabletsChange, onEffectChange)
            if (state.saveFailed) {
                Text(stringResource(R.string.edit_save_failed), color = MaterialTheme.colorScheme.error)
            }
            if (!state.isNew) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TAG_DELETE),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(stringResource(R.string.edit_delete), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmDiscard = false; onBack() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_DISCARD_CONFIRM),
                ) { Text(stringResource(R.string.edit_discard_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.edit_discard_cancel))
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.edit_delete_confirm_title)) },
            text = { Text(stringResource(R.string.edit_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDelete() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_DELETE_CONFIRM),
                ) { Text(stringResource(R.string.edit_delete_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeSection(startedAt: Instant, zone: ZoneId, onChange: (Instant) -> Unit) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val local = startedAt.atZone(zone)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_section_datetime))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val dateText = formatDate(startedAt, zone)
            val timeText = formatTime(startedAt, zone)
            val dateDesc = "${stringResource(R.string.edit_pick_date)} $dateText"
            val timeDesc = "${stringResource(R.string.edit_pick_time)} $timeText"
            FilledTonalButton(
                onClick = { showDate = true },
                modifier = Modifier.weight(2f).heightIn(min = 56.dp).testTag(TAG_PICK_DATE)
                    .semantics { contentDescription = dateDesc },
            ) { Text(dateText, style = MaterialTheme.typography.titleMedium) }
            FilledTonalButton(
                onClick = { showTime = true },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(TAG_PICK_TIME)
                    .semantics { contentDescription = timeDesc },
            ) { Text(timeText, style = MaterialTheme.typography.titleMedium) }
        }
    }
    if (showDate) {
        val today = LocalDate.now(zone)
        val pickerState = rememberDatePickerState(
            // DatePicker は UTC 0:00 のミリ秒で日付を表す。
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // 未来の日付は選べない。
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(date.atTime(local.toLocalTime()).atZone(zone).toInstant())
                    }
                    showDate = false
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) { DatePicker(pickerState) }
    }
    if (showTime) {
        val timeState = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute, is24Hour = true)
        val candidate = local.toLocalDate().atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant()
        val isFuture = candidate.isAfter(Instant.now()) // FR-208: 未来の時刻は選べない
        AlertDialog(
            onDismissRequest = { showTime = false },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(timeState)
                    if (isFuture) {
                        Text(stringResource(R.string.edit_time_future), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onChange(candidate); showTime = false },
                    enabled = !isFuture,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_TIME_OK),
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** FR-404: 記録時の気圧と前日との気圧差 (読み取り専用)。取得できないときは「取得できませんでした」。 */
@Composable
private fun PressureInfoSection(state: AttackEditUiState) {
    val unavailable = stringResource(R.string.edit_pressure_unavailable)
    val hpa = state.savedPressureHpa
    val delta = state.savedPressureDeltaHpa
    val valueText = hpa?.let { stringResource(R.string.edit_pressure_value, formatPressure(it)) } ?: unavailable
    val deltaText = delta?.let { stringResource(R.string.edit_pressure_value, formatPressureDelta(it)) } ?: unavailable
    // 読み上げは記号に頼らず言葉で (助言なし)。
    val deltaSpoken = delta?.let {
        when (pressureDeltaDirection(it)) {
            DeltaDirection.LOWER -> stringResource(R.string.record_a11y_delta_lower, formatPressureDeltaMagnitude(it))
            DeltaDirection.HIGHER -> stringResource(R.string.record_a11y_delta_higher, formatPressureDeltaMagnitude(it))
            DeltaDirection.SAME -> stringResource(R.string.record_a11y_delta_same)
        }
    } ?: unavailable
    val label = stringResource(R.string.edit_pressure_label)
    val deltaLabel = stringResource(R.string.edit_pressure_delta_label)
    Column(Modifier.testTag(TAG_PRESSURE_INFO), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_section_pressure))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(label, valueText, Modifier.testTag(TAG_PRESSURE_VALUE).semantics(mergeDescendants = true) {
                    contentDescription = "$label $valueText"
                })
                InfoRow(deltaLabel, deltaText, Modifier.testTag(TAG_PRESSURE_DELTA).semantics(mergeDescendants = true) {
                    contentDescription = "$deltaLabel $deltaSpoken"
                })
                Text(
                    stringResource(R.string.edit_pressure_readonly),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.isPressureStale) {
            Text(
                stringResource(R.string.edit_pressure_stale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_PRESSURE_STALE),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SeveritySection(selected: Severity?, showRequired: Boolean, onChange: (Severity) -> Unit) {
    val unselectedDescription = stringResource(R.string.edit_severity_unselected_description)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.edit_section_severity))
        val all = Severity.entries
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().semantics { if (selected == null && showRequired) stateDescription = unselectedDescription },
        ) {
            all.forEachIndexed { index, severity ->
                SegmentedButton(
                    selected = severity == selected,
                    onClick = { onChange(severity) },
                    shape = SegmentedButtonDefaults.itemShape(index, all.size),
                    modifier = Modifier.heightIn(min = 56.dp).testTag(severityTag(severity)),
                ) { Text(stringResource(severity.labelRes()), style = MaterialTheme.typography.titleSmall) }
            }
        }
        if (selected == null && showRequired) {
            // 未選択であることを文字とアイコンで示す (色だけに頼らない)。TalkBack でも読まれる。
            Row(
                Modifier.testTag(TAG_SEVERITY_REQUIRED),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.edit_severity_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun IntakeSection(
    intakes: List<IntakeDraft>,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: (Long) -> Unit,
    onTabletsChange: (Long, Int) -> Unit,
    onEffectChange: (Long, Effect) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.edit_section_intakes))
        if (intakes.isEmpty()) {
            Text(
                stringResource(R.string.edit_intakes_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        intakes.forEach { draft ->
            IntakeCard(draft, onRemove, onTabletsChange, onEffectChange)
        }
        FilledTonalButton(
            onClick = onAdd,
            enabled = canAdd,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(TAG_ADD_MEDICATION),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.edit_add_medication), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
        }
        if (!canAdd) {
            // 無効な理由を文字で示す (色だけに頼らない)。
            Text(
                stringResource(R.string.edit_intakes_limit, InputLimits.MAX_INTAKES_PER_ATTACK),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_INTAKES_LIMIT),
            )
        }
    }
}

@Composable
private fun IntakeCard(
    draft: IntakeDraft,
    onRemove: (Long) -> Unit,
    onTabletsChange: (Long, Int) -> Unit,
    onEffectChange: (Long, Effect) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(draft.medication.name, style = MaterialTheme.typography.titleMedium)
                    draft.medication.ingredient?.let {
                        Text(
                            stringResource(R.string.picker_ingredient, it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { onRemove(draft.key) }, modifier = Modifier.size(56.dp).testTag(removeIntakeTag(draft.key))) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.edit_remove_intake, draft.medication.name))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.edit_tablets_label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                val minus = stringResource(R.string.edit_tablets_decrease)
                val plus = stringResource(R.string.edit_tablets_increase)
                OutlinedButton(
                    onClick = { onTabletsChange(draft.key, -1) },
                    enabled = draft.tablets > MIN_TABLETS,
                    modifier = Modifier.size(56.dp).testTag(tabletsMinusTag(draft.key)).semantics { contentDescription = minus },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text("−", style = MaterialTheme.typography.headlineSmall) }
                Text(
                    stringResource(R.string.edit_tablets_value, draft.tablets, stringResource(draft.medication.unit.labelRes())),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.widthIn(min = 56.dp).testTag(tabletsValueTag(draft.key)),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                OutlinedButton(
                    onClick = { onTabletsChange(draft.key, +1) },
                    enabled = draft.tablets < MAX_TABLETS,
                    modifier = Modifier.size(56.dp).testTag(tabletsPlusTag(draft.key)).semantics { contentDescription = plus },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
            }
            Text(stringResource(R.string.edit_effect_label), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Effect.entries.forEach { effect ->
                    FilterChip(
                        selected = draft.effect == effect,
                        onClick = { onEffectChange(draft.key, effect) },
                        label = { Text(stringResource(effect.labelRes()), style = MaterialTheme.typography.bodyLarge) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(effectTag(draft.key, effect)),
                    )
                }
            }
        }
    }
}

/** FR-505(b): 未評価の服薬を初めて保存したときの、通知の説明ダイアログ (一度だけ)。 */
@Composable
fun NotificationPromptDialog(stage: PostSavePrompt, onAccept: () -> Unit, onDecline: () -> Unit) {
    when (stage) {
        PostSavePrompt.NONE -> Unit
        PostSavePrompt.ASK -> AlertDialog(
            onDismissRequest = onDecline,
            title = { Text(stringResource(R.string.notify_prompt_title)) },
            text = { Text(stringResource(R.string.notify_prompt_body)) },
            confirmButton = {
                TextButton(onClick = onAccept, modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_NOTIFY_ACCEPT)) {
                    Text(stringResource(R.string.notify_prompt_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = onDecline, modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_NOTIFY_DECLINE)) {
                    Text(stringResource(R.string.notify_prompt_decline))
                }
            },
        )
        PostSavePrompt.DENIED -> AlertDialog(
            onDismissRequest = onDecline,
            title = { Text(stringResource(R.string.notify_prompt_denied_title)) },
            text = { Text(stringResource(R.string.notify_prompt_denied_body)) },
            confirmButton = {
                TextButton(onClick = onDecline, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}
