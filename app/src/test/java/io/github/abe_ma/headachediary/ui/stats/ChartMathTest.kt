package io.github.abe_ma.headachediary.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.Duration

class ChartMathTest {
    private val start = Instant.parse("2026-11-16T00:00:00Z")
    private val end = Instant.parse("2026-11-26T00:00:00Z")

    @Test
    fun FR103_時刻は軸上の0から1に収まる() {
        assertEquals(0f, timeFraction(start, start, end), 0f)
        assertEquals(1f, timeFraction(end, start, end), 0f)
        assertEquals(0.5f, timeFraction(Instant.parse("2026-11-21T00:00:00Z"), start, end), 1e-6f)
        assertEquals(0f, timeFraction(start.minusSeconds(10), start, end), 0f)
        assertEquals(1f, timeFraction(end.plusSeconds(10), start, end), 0f)
    }

    @Test
    fun FR103_縦軸は変動が小さくても最小幅を確保し_データを含む() {
        val (lo, hi) = niceYRange(1012.0, 1013.0)
        assertTrue(hi - lo >= MIN_SPAN_HPA)
        assertTrue(lo <= 1012.0 && hi >= 1013.0)
        val (lo2, hi2) = niceYRange(990.0, 1020.0)
        assertTrue(lo2 < 990.0 && hi2 > 1020.0)
    }

    @Test
    fun FR103_縦軸の目盛りは5本以内で範囲内() {
        val ticks = yTicks(1000.0, 1020.0)
        assertTrue(ticks.size in 2..5)
        assertTrue(ticks.all { it in 1000.0..1020.0 })
        assertTrue(yTicks(980.0, 1040.0).size <= 6)
    }

    @Test
    fun FR301_横軸の目盛りは日付の境界で_長い期間は間引く() {
        val zone = ZoneId.of("Asia/Tokyo")
        val week = dayTicks(Instant.parse("2026-11-16T03:00:00Z"), Instant.parse("2026-11-26T03:00:00Z"), zone)
        assertEquals(10, week.size)
        week.forEach { assertEquals(0, it.atZone(zone).hour) }
        val month = dayTicks(Instant.parse("2026-10-24T03:00:00Z"), Instant.parse("2026-11-26T03:00:00Z"), zone)
        assertTrue(month.size < 10)
        assertEquals(5L, Duration.between(month[0], month[1]).toDays())
    }

    @Test
    fun FR102_欠測をはさむ点は線を分ける() {
        val t0 = Instant.parse("2026-11-20T00:00:00Z")
        val times = listOf(0L, 1, 2, 10, 11).map { t0.plus(Duration.ofHours(it)) }
        val segments = splitByGap(times, { it })
        assertEquals(listOf(3, 2), segments.map { it.size })
        assertTrue(splitByGap(emptyList<Instant>(), { it }).isEmpty())
    }
}
