package io.github.abe_ma.headachediary.ui.export

import android.content.ActivityNotFoundException
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.ui.openUriSafely
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun presetTag(p: ExportPreset) = "export_preset_${p.name}"
fun kindTag(k: ExportKind) = "export_kind_${k.name}"
const val TAG_EXPORT_START = "export_custom_start"
const val TAG_EXPORT_END = "export_custom_end"
const val TAG_EXPORT_RANGE_INVALID = "export_range_invalid"
const val TAG_EXPORT_COUNT = "export_attack_count"
const val TAG_EXPORT_RUN = "export_run"
const val TAG_EXPORT_PROGRESS = "export_progress"
const val TAG_EXPORT_FAILED = "export_failed"
const val TAG_EXPORT_DONE = "export_done"
const val TAG_EXPORT_SHARE_AGAIN = "export_share_again"
const val TAG_EXPORT_ATTRIBUTION = "export_attribution"
const val OPEN_METEO_URL = "https://open-meteo.com/"

@Composable
fun ExportScreen(onBack: () -> Unit, viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val title = stringResource(R.string.export_share_title)
    val noApp = stringResource(R.string.export_share_no_app)

    // 生成が終わったら OS の共有シートを開く (FR-402)。同じ要求は一度だけ。
    LaunchedEffect(state.share) {
        val request = state.share ?: return@LaunchedEffect
        try {
            context.startActivity(buildShareIntent(request.uri, request.kind, title))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, noApp, Toast.LENGTH_LONG).show()
        }
        viewModel.onShareLaunched()
    }

    ExportContent(
        state = state,
        onBack = onBack,
        onPreset = viewModel::setPreset,
        onKind = viewModel::setKind,
        onCustomStart = viewModel::setCustomStart,
        onCustomEnd = viewModel::setCustomEnd,
        onExport = viewModel::export,
        onShareAgain = viewModel::shareAgain,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportContent(
    state: ExportUiState,
    onBack: () -> Unit,
    onPreset: (ExportPreset) -> Unit,
    onKind: (ExportKind) -> Unit,
    onCustomStart: (LocalDate) -> Unit,
    onCustomEnd: (LocalDate) -> Unit,
    onExport: () -> Unit,
    onShareAgain: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val uriHandler = LocalUriHandler.current
    var pickStart by rememberSaveable { mutableStateOf(false) }
    var pickEnd by rememberSaveable { mutableStateOf(false) }
    val generating = state.status == ExportStatus.Generating

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onExport,
                    enabled = state.canExport,
                    modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp).heightIn(min = 56.dp).testTag(TAG_EXPORT_RUN),
                ) { Text(stringResource(R.string.export_action), style = MaterialTheme.typography.titleMedium) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading(stringResource(R.string.export_section_period))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportPreset.entries.forEach { p ->
                    FilterChip(
                        selected = state.preset == p,
                        onClick = { onPreset(p) },
                        label = { Text(stringResource(p.labelRes()), style = MaterialTheme.typography.bodyLarge) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(presetTag(p)),
                    )
                }
            }
            if (state.preset == ExportPreset.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { pickStart = true },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(TAG_EXPORT_START),
                    ) { Text(stringResource(R.string.export_custom_start) + " " + dateText(state.customStart)) }
                    FilledTonalButton(
                        onClick = { pickEnd = true },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(TAG_EXPORT_END),
                    ) { Text(stringResource(R.string.export_custom_end) + " " + dateText(state.customEnd)) }
                }
            }
            if (!state.isRangeValid) {
                Text(
                    stringResource(R.string.export_range_invalid),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TAG_EXPORT_RANGE_INVALID),
                )
            } else {
                Text(
                    stringResource(R.string.export_range_summary, dateText(state.rangeStart), dateText(state.rangeEnd)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (state.attackCount == 0) stringResource(R.string.export_attack_count_zero)
                    else stringResource(R.string.export_attack_count, state.attackCount),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(TAG_EXPORT_COUNT),
                )
            }

            Heading(stringResource(R.string.export_section_format))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = state.kind == k,
                        onClick = { onKind(k) },
                        shape = SegmentedButtonDefaults.itemShape(i, ExportKind.entries.size),
                        modifier = Modifier.heightIn(min = 56.dp).testTag(kindTag(k)),
                    ) { Text(stringResource(if (k == ExportKind.CSV) R.string.export_format_csv else R.string.export_format_pdf), style = MaterialTheme.typography.titleSmall) }
                }
            }
            Text(
                stringResource(if (state.kind == ExportKind.CSV) R.string.export_format_csv_desc else R.string.export_format_pdf_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 進捗・結果
            when (val st = state.status) {
                ExportStatus.Generating -> Row(
                    Modifier.testTag(TAG_EXPORT_PROGRESS), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(stringResource(R.string.export_generating), style = MaterialTheme.typography.bodyLarge)
                }
                ExportStatus.Failed -> Text(
                    stringResource(R.string.export_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TAG_EXPORT_FAILED),
                )
                is ExportStatus.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.export_done), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TAG_EXPORT_DONE))
                    OutlinedButton(
                        onClick = onShareAgain,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_EXPORT_SHARE_AGAIN),
                    ) { Text(stringResource(R.string.export_share_again)) }
                }
                ExportStatus.Idle -> Unit
            }

            Text(
                stringResource(R.string.export_privacy_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // FR-403: 書き出し画面にも Open-Meteo の帰属表示
            TextButton(
                onClick = { uriHandler.openUriSafely(OPEN_METEO_URL) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_EXPORT_ATTRIBUTION),
            ) { Text(stringResource(R.string.export_attribution), style = MaterialTheme.typography.titleSmall) }
        }
    }

    // 期間指定は最初の発作の日より前を選べない (それ以前は「全期間」で足りる)。発作が無いときは1年前まで。
    val minDate = state.earliestDate ?: today.minusYears(1)
    if (pickStart) {
        ExportDatePicker(state.customStart, minDate, today, onPick = { onCustomStart(it); pickStart = false }, onDismiss = { pickStart = false })
    }
    if (pickEnd) {
        ExportDatePicker(state.customEnd, minDate, today, onPick = { onCustomEnd(it); pickEnd = false }, onDismiss = { pickEnd = false })
    }
}

private fun ExportPreset.labelRes(): Int = when (this) {
    ExportPreset.THIS_MONTH -> R.string.export_preset_this_month
    ExportPreset.LAST_3_MONTHS -> R.string.export_preset_last_3_months
    ExportPreset.LAST_YEAR -> R.string.export_preset_last_year
    ExportPreset.ALL -> R.string.export_preset_all
    ExportPreset.CUSTOM -> R.string.export_preset_custom
}

@Composable
private fun dateText(d: LocalDate): String = stringResource(R.string.export_date_format, d.year, d.monthValue, d.dayOfMonth)

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportDatePicker(initial: LocalDate, minDate: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(
        // DatePicker は UTC 0:00 のミリ秒で日付を表す。
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return !d.isAfter(today) && !d.isBefore(minDate)
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    ) { DatePicker(pickerState) }
}
