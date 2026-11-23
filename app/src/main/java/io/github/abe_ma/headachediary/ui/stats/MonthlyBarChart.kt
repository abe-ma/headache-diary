package io.github.abe_ma.headachediary.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.MonthCount
import kotlin.math.max

const val TAG_MONTHLY_CHART = "stats_monthly_chart"

private val BAR_SLOT_WIDTH = 52.dp

/**
 * FR-300: 月別の発作回数の棒グラフ (Canvas 自作)。
 * 値は棒の上に数字で表示し、色だけに頼らない。1月と先頭の月には年を添える。月数が多いときは横にスクロールし、最新の月を右端に表示する。
 */
@Composable
fun MonthlyBarChart(counts: List<MonthCount>, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val subColor = MaterialTheme.colorScheme.onSurfaceVariant

    val monthFmt = stringResource(R.string.stats_month_label)
    val monthYearFmt = stringResource(R.string.stats_month_label_with_year)
    val unitFmt = stringResource(R.string.stats_monthly_count_unit)
    val itemFmt = stringResource(R.string.stats_monthly_item)
    fun label(m: MonthCount, withYear: Boolean) =
        if (withYear) monthYearFmt.format(m.yearMonth.year, m.yearMonth.monthValue) else monthFmt.format(m.yearMonth.monthValue)
    val summary = counts.joinToString("、") { itemFmt.format(label(it, true), unitFmt.format(it.count)) }
    val description = stringResource(R.string.stats_monthly_chart_description, summary)

    val scale = LocalDensity.current.fontScale.coerceIn(1f, 2f) // フォント拡大時に数字・ラベルが切れないよう余白と幅を広げる
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val contentWidth = max(maxWidth.value, BAR_SLOT_WIDTH.value * scale * counts.size).dp
        // 最新の月 (右端) が見える位置から始める。
        LaunchedEffect(counts.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        Canvas(
            Modifier.horizontalScroll(scroll).width(contentWidth).height(200.dp * scale)
                .testTag(TAG_MONTHLY_CHART)
                .semantics { contentDescription = description },
        ) {
            if (counts.isEmpty()) return@Canvas
            val labelH = 36.dp.toPx() * scale
            val topH = 22.dp.toPx() * scale // 数字の置き場
            val plotH = size.height - labelH - topH
            val slot = size.width / counts.size
            val barW = slot * 0.5f
            val maxCount = max(counts.maxOf { it.count }, 1)
            val labelStyle = TextStyle(color = subColor, fontSize = 12.sp)
            val valueStyle = TextStyle(color = textColor, fontSize = 14.sp)

            // 基準線
            drawLine(subColor, Offset(0f, topH + plotH), Offset(size.width, topH + plotH), strokeWidth = 1.dp.toPx())

            counts.forEachIndexed { i, m ->
                val cx = slot * i + slot / 2
                val h = plotH * (m.count / maxCount.toFloat())
                // 0 件は細い土台だけ。数字 "0" で値を示す。
                drawRoundRect(trackColor, Offset(cx - barW / 2, topH + plotH - 3.dp.toPx()), Size(barW, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                if (m.count > 0) {
                    drawRoundRect(barColor, Offset(cx - barW / 2, topH + plotH - h), Size(barW, h), CornerRadius(4.dp.toPx()))
                }
                val valueText = textMeasurer.measure(m.count.toString(), valueStyle)
                drawText(valueText, topLeft = Offset(cx - valueText.size.width / 2f, topH + plotH - h - valueText.size.height - 2.dp.toPx()))

                val withYear = i == 0 || m.yearMonth.monthValue == 1
                val lt = textMeasurer.measure(label(m, withYear), labelStyle)
                drawText(lt, topLeft = Offset(cx - lt.size.width / 2f, topH + plotH + 6.dp.toPx()))
            }
        }
    }
}
