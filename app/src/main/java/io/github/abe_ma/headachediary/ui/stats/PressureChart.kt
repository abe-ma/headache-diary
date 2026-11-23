package io.github.abe_ma.headachediary.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.abe_ma.headachediary.domain.PressurePoint
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

const val TAG_PRESSURE_CHART = "stats_pressure_chart"

/**
 * FR-103/301: 気圧の推移 (過去 = 実線、予報 = 破線) に、発作を ▼ マーカー + 縦の細線で重ねる。
 * 現在時刻には点線を引く。実線/破線/マーカーの形で区別し、色だけに頼らない。
 */
@Composable
fun PressureChart(
    points: List<PressurePoint>,
    attackTimes: List<Instant>,
    start: Instant,
    end: Instant,
    now: Instant,
    zone: ZoneId,
    description: String,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val nowColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier.fillMaxWidth().height(240.dp).testTag(TAG_PRESSURE_CHART)
            .semantics { contentDescription = description },
    ) {
        val left = 44.dp.toPx()
        val right = 8.dp.toPx()
        val top = 22.dp.toPx() // 発作マーカーの置き場
        val bottom = 26.dp.toPx()
        val plotW = size.width - left - right
        val plotH = size.height - top - bottom
        val labelStyle = TextStyle(color = textColor, fontSize = 11.sp)

        fun x(t: Instant) = left + plotW * timeFraction(t, start, end)

        val (lo, hi) = if (points.isEmpty()) 1000.0 to 1020.0 else niceYRange(points.minOf { it.hpa }, points.maxOf { it.hpa })
        fun y(hpa: Double) = top + plotH * (1f - ((hpa - lo) / (hi - lo)).toFloat())

        // 縦軸の格子と数値
        yTicks(lo, hi).forEach { v ->
            val yy = y(v)
            drawLine(gridColor, Offset(left, yy), Offset(left + plotW, yy), strokeWidth = 1.dp.toPx())
            val t = textMeasurer.measure(String.format(Locale.JAPAN, "%.0f", v), labelStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2f))
        }
        // 横軸 (日付)
        dayTicks(start, end, zone).forEach { d ->
            val xx = x(d)
            drawLine(gridColor, Offset(xx, top), Offset(xx, top + plotH), strokeWidth = 0.5.dp.toPx())
            val local = d.atZone(zone)
            val t = textMeasurer.measure("${local.monthValue}/${local.dayOfMonth}", labelStyle)
            drawText(t, topLeft = Offset(xx - t.size.width / 2f, top + plotH + 4.dp.toPx()))
        }

        // 気圧: 過去 (実線) と予報 (破線)。欠測をまたいで線をつながない。
        val past = points.filter { !it.time.isAfter(now) }
        val forecast = points.filter { it.time.isAfter(now) }
        fun drawSeries(series: List<PressurePoint>, dashed: Boolean) {
            splitByGap(series, { it.time }).forEach { seg ->
                if (seg.size < 2) {
                    seg.forEach { drawCircle(lineColor, 2.5.dp.toPx(), Offset(x(it.time), y(it.hpa))) }
                    return@forEach
                }
                val path = Path().apply {
                    moveTo(x(seg[0].time), y(seg[0].hpa))
                    for (i in 1 until seg.size) lineTo(x(seg[i].time), y(seg[i].hpa))
                }
                drawPath(
                    path, lineColor,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())) else null,
                    ),
                )
            }
        }
        drawSeries(past, dashed = false)
        // 予報は直前の実測点から続けて描く。
        drawSeries(past.takeLast(1) + forecast, dashed = true)

        // 現在 (点線)
        if (now in start..end) {
            val nx = x(now)
            drawLine(
                nowColor, Offset(nx, top), Offset(nx, top + plotH), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
            )
        }

        // 発作マーカー: 縦の細線 + 上端の ▼
        attackTimes.forEach { t ->
            if (t < start || t > end) return@forEach
            val ax = x(t)
            drawLine(markerColor.copy(alpha = 0.55f), Offset(ax, top), Offset(ax, top + plotH), strokeWidth = 1.5.dp.toPx())
            val s = 7.dp.toPx()
            val tri = Path().apply {
                moveTo(ax - s, top - s * 1.6f)
                lineTo(ax + s, top - s * 1.6f)
                lineTo(ax, top)
                close()
            }
            drawPath(tri, markerColor)
        }
    }
}
