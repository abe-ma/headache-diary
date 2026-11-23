package io.github.abe_ma.headachediary.ui.stats

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** グラフ描画用の純粋な計算 (テストしやすいよう Compose から切り離す)。 */

/** 時刻の軸上の位置 (0..1)。範囲外は 0/1 に丸める。 */
fun timeFraction(t: Instant, start: Instant, end: Instant): Float {
    val total = Duration.between(start, end).toMillis().toDouble()
    if (total <= 0) return 0f
    val v = Duration.between(start, t).toMillis() / total
    return v.coerceIn(0.0, 1.0).toFloat()
}

/** 縦軸の範囲。上下に余白を付け、最小でも [MIN_SPAN_HPA] の幅を確保する (微小な変動を過大に見せない)。 */
const val MIN_SPAN_HPA = 8.0

fun niceYRange(min: Double, max: Double): Pair<Double, Double> {
    val pad = max((max - min) * 0.1, 1.0)
    var lo = floor(min - pad)
    var hi = ceil(max + pad)
    if (hi - lo < MIN_SPAN_HPA) {
        val extra = (MIN_SPAN_HPA - (hi - lo)) / 2
        lo = floor(lo - extra)
        hi = ceil(hi + extra)
    }
    return lo to hi
}

/** 縦軸の目盛り (3〜5 本)。刻みは 1/2/5/10/20 hPa のいずれか。 */
fun yTicks(lo: Double, hi: Double): List<Double> {
    val step = listOf(1.0, 2.0, 5.0, 10.0, 20.0).first { (hi - lo) / it <= 5 }
    val first = ceil(lo / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= hi + 1e-9 }.toList()
}

/** 横軸の目盛り: 範囲内のローカル 0:00。期間が長いときは間引く。 */
fun dayTicks(start: Instant, end: Instant, zone: ZoneId): List<Instant> {
    val days = Duration.between(start, end).toHours() / 24.0
    val stepDays = if (days <= 10) 1L else 5L
    var d: ZonedDateTime = start.atZone(zone).toLocalDate().atStartOfDay(zone)
    if (d.toInstant().isBefore(start)) d = d.plusDays(1)
    val out = mutableListOf<Instant>()
    while (!d.toInstant().isAfter(end)) {
        out += d.toInstant()
        d = d.plusDays(stepDays)
    }
    return out
}

/** 連続した点を、[maxGap] より離れたところで区切った線分に分ける (欠測をつながない)。 */
fun <T> splitByGap(points: List<T>, time: (T) -> Instant, maxGap: Duration = Duration.ofHours(3)): List<List<T>> {
    if (points.isEmpty()) return emptyList()
    val segments = mutableListOf<MutableList<T>>(mutableListOf(points.first()))
    for (i in 1 until points.size) {
        if (Duration.between(time(points[i - 1]), time(points[i])) > maxGap) segments += mutableListOf<T>()
        segments.last() += points[i]
    }
    return segments
}

/** 近接した発作マーカーを 1 つにまとめた結果。x は代表位置、count は束ねた件数。 */
data class MarkerCluster(val x: Float, val count: Int)

/**
 * 横位置が [minGap] 未満で並ぶマーカーをまとめる (密集時に重なって見えなくなるのを防ぐ)。
 * まとめたものは件数を添えて描く。入力は未ソートでよい。
 */
fun clusterMarkers(xs: List<Float>, minGap: Float): List<MarkerCluster> {
    if (xs.isEmpty()) return emptyList()
    val sorted = xs.sorted()
    val out = mutableListOf<MarkerCluster>()
    var group = mutableListOf(sorted.first())
    for (i in 1 until sorted.size) {
        if (sorted[i] - group.first() < minGap) group += sorted[i] else {
            out += MarkerCluster(group.average().toFloat(), group.size)
            group = mutableListOf(sorted[i])
        }
    }
    out += MarkerCluster(group.average().toFloat(), group.size)
    return out
}
