package io.github.abe_ma.headachediary.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.ui.formatListDateTime
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

const val TAG_REGION_ROW = "settings_region_row"
const val TAG_REGION_CLEAR = "settings_region_clear"
fun regionItemTag(code: String) = "settings_region_$code"
const val TAG_EFFECT_SWITCH = "settings_effect_switch"
const val TAG_DROP_SWITCH = "settings_drop_switch"
fun delayChipTag(minutes: Int) = "settings_delay_$minutes"
const val TAG_THRESHOLD_MINUS = "settings_threshold_minus"
const val TAG_THRESHOLD_PLUS = "settings_threshold_plus"
const val TAG_THRESHOLD_VALUE = "settings_threshold_value"
const val TAG_DROP_TIME = "settings_drop_time"
const val TAG_MEDICATIONS_ROW = "settings_medications_row"
const val TAG_EXPORT_ROW = "settings_export_row"
const val TAG_ABOUT_ROW = "settings_about_row"
const val TAG_PERMISSION_DENIED = "settings_permission_denied"
const val TAG_PERMISSION_MISSING = "settings_permission_missing"
const val TAG_OPEN_NOTIFICATION_SETTINGS = "settings_open_notification_settings"
const val TAG_LAST_FETCHED = "settings_last_fetched"

/** 通知の種類。許可要求の保留に使う。 */
private const val KIND_EFFECT = "effect"
private const val KIND_DROP = "drop"


/** 画面が受け取る操作。 */
data class SettingsActions(
    val onSelectPrefecture: (String?) -> Unit = {},
    val onEffectReminderToggle: (Boolean) -> Unit = {},
    val onEffectDelayChange: (Int) -> Unit = {},
    val onDropNotifyToggle: (Boolean) -> Unit = {},
    val onThresholdStep: (Int) -> Unit = {},
    val onDropTimeChange: (LocalTime) -> Unit = {},
    val onOpenMedications: () -> Unit = {},
    val onOpenExport: () -> Unit = {},
    val onOpenAbout: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    onOpenMedications: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var granted by remember2(context)
    var denied by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }

    // 端末の設定で許可を変えて戻ってきた場合に反映する。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = notificationsGranted(context) }

    fun apply(kind: String) = when (kind) {
        KIND_EFFECT -> viewModel.setEffectReminderEnabled(true)
        else -> viewModel.setDropNotifyEnabled(true)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        val kind = pending
        pending = null
        if (ok) {
            denied = false
            if (kind != null) apply(kind)
        } else {
            // FR-503: 拒否されたらスイッチはオフのまま理由を案内する。記録機能には影響しない。
            denied = true
        }
    }

    /** 通知をオンにする操作の時点で POST_NOTIFICATIONS を要求する (FR-503)。 */
    fun requestEnable(kind: String) {
        if (granted) {
            apply(kind)
        } else {
            pending = kind
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val actions = SettingsActions(
        onSelectPrefecture = viewModel::setPrefecture,
        onEffectReminderToggle = { on -> if (on) requestEnable(KIND_EFFECT) else viewModel.setEffectReminderEnabled(false) },
        onEffectDelayChange = viewModel::setEffectReminderDelay,
        onDropNotifyToggle = { on -> if (on) requestEnable(KIND_DROP) else viewModel.setDropNotifyEnabled(false) },
        onThresholdStep = viewModel::changeDropThreshold,
        onDropTimeChange = viewModel::setDropNotifyTime,
        onOpenMedications = onOpenMedications,
        onOpenExport = onOpenExport,
        onOpenAbout = onOpenAbout,
        onOpenNotificationSettings = {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
    val enabledButNotGranted = !granted && (state.settings.effectReminderEnabled || state.settings.dropNotifyEnabled)
    SettingsContent(state, actions, permissionDenied = denied, permissionMissing = enabledButNotGranted)
}

@Composable
private fun remember2(context: android.content.Context) =
    androidx.compose.runtime.remember { mutableStateOf(notificationsGranted(context)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    permissionDenied: Boolean = false,
    permissionMissing: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    var showRegion by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val s = state.settings

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_section_region))
            NavRow(
                title = stringResource(R.string.settings_region_label),
                value = state.prefecture?.name ?: stringResource(R.string.settings_region_unset),
                onClick = { showRegion = true },
                tag = TAG_REGION_ROW,
            )
            if (state.settings.prefectureCode == null) {
                Hint(stringResource(R.string.settings_region_hint))
            }
            Hint(
                state.lastFetchedAt?.let { stringResource(R.string.settings_last_fetched, formatListDateTime(it, zone, now)) }
                    ?: stringResource(R.string.settings_last_fetched_none),
                modifier = Modifier.testTag(TAG_LAST_FETCHED),
            )

            SectionHeader(stringResource(R.string.settings_section_notifications))
            SwitchRow(
                title = stringResource(R.string.settings_effect_reminder),
                description = stringResource(R.string.settings_effect_reminder_desc),
                checked = s.effectReminderEnabled,
                onCheckedChange = actions.onEffectReminderToggle,
                tag = TAG_EFFECT_SWITCH,
            )
            if (s.effectReminderEnabled) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_effect_delay_label), style = MaterialTheme.typography.bodyLarge)
                    val options = (EFFECT_DELAY_OPTIONS_MINUTES + s.effectReminderDelayMinutes).distinct().sorted()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { minutes ->
                            FilterChip(
                                selected = s.effectReminderDelayMinutes == minutes,
                                onClick = { actions.onEffectDelayChange(minutes) },
                                label = { Text(delayLabel(minutes), style = MaterialTheme.typography.bodyLarge) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag(delayChipTag(minutes)),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SwitchRow(
                title = stringResource(R.string.settings_drop_notify),
                description = stringResource(R.string.settings_drop_notify_desc),
                checked = s.dropNotifyEnabled,
                onCheckedChange = actions.onDropNotifyToggle,
                tag = TAG_DROP_SWITCH,
            )
            if (s.dropNotifyEnabled) {
                ThresholdRow(s.dropThresholdHpa, actions.onThresholdStep)
                NavRow(
                    title = stringResource(R.string.settings_drop_time_label),
                    value = s.dropNotifyTime.format(java.time.format.DateTimeFormatter.ofPattern("H:mm", Locale.JAPAN)),
                    onClick = { showTime = true },
                    tag = TAG_DROP_TIME,
                )
            }
            if (permissionDenied) {
                Hint(
                    stringResource(R.string.settings_notification_permission_denied),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TAG_PERMISSION_DENIED),
                )
            }
            if (permissionMissing) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_notification_permission_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TAG_PERMISSION_MISSING),
                    )
                    OutlinedButton(
                        onClick = actions.onOpenNotificationSettings,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_OPEN_NOTIFICATION_SETTINGS),
                    ) { Text(stringResource(R.string.settings_open_notification_settings)) }
                }
            }

            SectionHeader(stringResource(R.string.settings_section_data))
            NavRow(
                title = stringResource(R.string.settings_medications),
                description = stringResource(R.string.settings_medications_desc),
                onClick = actions.onOpenMedications,
                tag = TAG_MEDICATIONS_ROW,
            )
            NavRow(
                title = stringResource(R.string.settings_export),
                description = stringResource(R.string.settings_export_desc),
                onClick = actions.onOpenExport,
                tag = TAG_EXPORT_ROW,
            )
            NavRow(
                title = stringResource(R.string.settings_about),
                description = stringResource(R.string.settings_about_desc),
                onClick = actions.onOpenAbout,
                tag = TAG_ABOUT_ROW,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
        }
    }

    if (showRegion) {
        RegionSheet(
            state = state,
            onSelect = { code -> actions.onSelectPrefecture(code); showRegion = false },
            onDismiss = { showRegion = false },
        )
    }
    if (showTime) {
        val timeState = rememberTimePickerState(s.dropNotifyTime.hour, s.dropNotifyTime.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(
                    onClick = { actions.onDropTimeChange(LocalTime.of(timeState.hour, timeState.minute)); showTime = false },
                    modifier = Modifier.heightIn(min = 48.dp),
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

@Composable
private fun delayLabel(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) {
        stringResource(R.string.settings_delay_hours, minutes / 60)
    } else {
        stringResource(R.string.settings_delay_minutes, minutes)
    }

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Hint(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun NavRow(
    title: String,
    onClick: () -> Unit,
    tag: String,
    description: String? = null,
    value: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick, role = Role.Button).padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    // 行全体をタップ対象にして、スイッチ単体より大きな領域で操作できるようにする。
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ThresholdRow(thresholdHpa: Double, onStep: (Int) -> Unit) {
    val minus = stringResource(R.string.settings_drop_threshold_decrease)
    val plus = stringResource(R.string.settings_drop_threshold_increase)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_drop_threshold_label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = { onStep(-1) },
            enabled = thresholdHpa > MIN_DROP_THRESHOLD_HPA,
            modifier = Modifier.size(56.dp).testTag(TAG_THRESHOLD_MINUS).semantics { contentDescription = minus },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("−", style = MaterialTheme.typography.headlineSmall) }
        Text(
            stringResource(R.string.settings_drop_threshold_value, String.format(Locale.JAPAN, "%.0f", thresholdHpa)),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 80.dp).testTag(TAG_THRESHOLD_VALUE),
        )
        OutlinedButton(
            onClick = { onStep(+1) },
            enabled = thresholdHpa < MAX_DROP_THRESHOLD_HPA,
            modifier = Modifier.size(56.dp).testTag(TAG_THRESHOLD_PLUS).semantics { contentDescription = plus },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionSheet(state: SettingsUiState, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.settings_region_picker_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        FilledTonalButton(
            onClick = { onSelect(null) },
            modifier = Modifier.padding(16.dp).heightIn(min = 48.dp).testTag(TAG_REGION_CLEAR),
        ) { Text(stringResource(R.string.settings_region_clear)) }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.prefectures, key = { it.code }) { pref ->
                val selected = pref.code == state.settings.prefectureCode
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(pref.code) })
                        .padding(horizontal = 16.dp).testTag(regionItemTag(pref.code)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(pref.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
