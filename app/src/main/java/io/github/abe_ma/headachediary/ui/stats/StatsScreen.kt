package io.github.abe_ma.headachediary.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.ui.formatListDateTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

const val TAG_REGION_GUIDANCE = "stats_region_guidance"
const val TAG_OPEN_SETTINGS = "stats_open_settings"
fun monthSpanTag(span: MonthSpan) = "stats_month_span_${span.months}"
fun periodTag(p: PressurePeriod) = "stats_period_${p.name}"
const val TAG_PRESSURE_EMPTY = "stats_pressure_empty"
const val TAG_ATTACK_COUNT = "stats_attack_count"
const val TAG_STATS_LAST_FETCHED = "stats_last_fetched"
const val TAG_OPEN_METEO = "stats_open_meteo_link"
const val OPEN_METEO_URL = "https://open-meteo.com/"

@Composable
fun StatsScreen(
    onOpenSettings: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatsContent(
        state = state,
        onMonthSpan = viewModel::setMonthSpan,
        onPeriod = viewModel::setPressurePeriod,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(
    state: StatsUiState,
    onMonthSpan: (MonthSpan) -> Unit,
    onPeriod: (PressurePeriod) -> Unit,
    onOpenSettings: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val uriHandler = LocalUriHandler.current
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.stats_title)) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.isLoading && !state.hasPrefecture) {
                RegionGuidance(onOpenSettings)
            }

            // --- 月別 (FR-300)
            Heading(stringResource(R.string.stats_section_monthly))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthSpan.entries.forEach { span ->
                    FilterChip(
                        selected = state.monthSpan == span,
                        onClick = { onMonthSpan(span) },
                        label = {
                            Text(
                                stringResource(
                                    when (span) {
                                        MonthSpan.SIX -> R.string.stats_month_span_6
                                        MonthSpan.TWELVE -> R.string.stats_month_span_12
                                        MonthSpan.TWENTY_FOUR -> R.string.stats_month_span_24
                                    },
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(monthSpanTag(span)),
                    )
                }
            }
            MonthlyBarChart(state.monthlyCounts)

            // --- 気圧 + 発作 (FR-103, 301)
            Heading(stringResource(R.string.stats_section_pressure))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PressurePeriod.entries.forEach { p ->
                    FilterChip(
                        selected = state.pressurePeriod == p,
                        onClick = { onPeriod(p) },
                        label = {
                            Text(
                                stringResource(if (p == PressurePeriod.WEEK) R.string.stats_period_week else R.string.stats_period_month),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(periodTag(p)),
                    )
                }
            }
            Text(
                stringResource(R.string.stats_period_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.pressure.isEmpty()) {
                // 地域未設定は上の案内カードで誘導する (同じ文を二重に出さない)。設定済みでデータが無いときだけここで示す。
                if (state.hasPrefecture) {
                    Text(
                        stringResource(R.string.stats_pressure_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp).testTag(TAG_PRESSURE_EMPTY),
                    )
                }
            } else {
                val hpa = state.pressure.map { it.hpa }
                val description = stringResource(
                    R.string.stats_pressure_chart_description,
                    String.format(Locale.JAPAN, "%.0f", hpa.min()),
                    String.format(Locale.JAPAN, "%.0f", hpa.max()),
                    state.attacks.size,
                )
                PressureChart(
                    points = state.pressure,
                    attackTimes = state.attacks.map { it.startedAt },
                    start = state.windowStart,
                    end = state.windowEnd,
                    now = state.now,
                    zone = zone,
                    description = description,
                )
                Legend()
            }
            Text(
                stringResource(R.string.stats_attack_count, state.attacks.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(TAG_ATTACK_COUNT),
            )
            Text(
                state.lastFetchedAt?.let { stringResource(R.string.stats_last_fetched, formatListDateTime(it, zone, state.now)) }
                    ?: stringResource(R.string.stats_last_fetched_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_STATS_LAST_FETCHED),
            )
            // FR-106: 気圧グラフの近くに出典リンク (CC BY 4.0)
            TextButton(
                onClick = { uriHandler.openUri(OPEN_METEO_URL) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_OPEN_METEO),
            ) { Text(stringResource(R.string.stats_open_meteo_link), style = MaterialTheme.typography.titleSmall) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
    )
}

@Composable
private fun RegionGuidance(onOpenSettings: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(TAG_REGION_GUIDANCE),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.stats_region_guidance),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_OPEN_SETTINGS)) {
                Text(stringResource(R.string.stats_open_settings))
            }
        }
    }
}

/** 実線 = 取得済み / 破線 = 予報 / ▼ = 発作 / 点線 = 現在。文字で示すので色だけに頼らない。 */
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem("━", stringResource(R.string.stats_legend_actual))
        LegendItem("┅", stringResource(R.string.stats_legend_forecast))
        LegendItem("▼", stringResource(R.string.stats_legend_attack))
        LegendItem("┆", stringResource(R.string.stats_legend_now))
    }
    Text(
        stringResource(R.string.stats_axis_unit),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LegendItem(symbol: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(symbol, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
