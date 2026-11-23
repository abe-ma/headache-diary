package io.github.abe_ma.headachediary.ui.stats

import app.cash.turbine.test
import io.github.abe_ma.headachediary.data.fake.FakePressureRepository
import io.github.abe_ma.headachediary.data.fake.FakeRepositories
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.ui.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class StatsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = Instant.parse("2026-11-23T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun vm(f: FakeRepositories) = StatsViewModel(f.attacks, f.pressure, f.settings, clock)

    private fun attack(at: Instant) = Attack(0, at, Severity.MILD, null, emptyList())

    @Test
    fun FR300_月別回数を古い順に返し_月数を切り替えられる() = runTest {
        val f = FakeRepositories()
        f.attacks.save(attack(Instant.parse("2026-11-01T00:00:00Z")))
        val vm = vm(f)
        vm.uiState.test {
            val s = expectMostRecentItem()
            assertEquals(MonthSpan.SIX, s.monthSpan)
            assertEquals(6, s.monthlyCounts.size)
            assertTrue(s.monthlyCounts.zipWithNext().all { (a, b) -> a.yearMonth < b.yearMonth })
            vm.setMonthSpan(MonthSpan.TWELVE)
            assertEquals(12, expectMostRecentItem().monthlyCounts.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR301_気圧グラフの期間は過去7日か30日に予報3日を足した範囲() = runTest {
        val vm = vm(FakeRepositories())
        vm.uiState.test {
            val week = expectMostRecentItem()
            assertEquals(now.minus(Duration.ofDays(7)), week.windowStart)
            assertEquals(now.plus(Duration.ofDays(3)), week.windowEnd)
            vm.setPressurePeriod(PressurePeriod.MONTH)
            val month = expectMostRecentItem()
            assertEquals(PressurePeriod.MONTH, month.pressurePeriod)
            assertEquals(now.minus(Duration.ofDays(30)), month.windowStart)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR103_期間内の気圧と発作だけを重ねて返す() = runTest {
        val inside = now.minus(Duration.ofDays(2))
        val old = now.minus(Duration.ofDays(20))
        val pressure = FakePressureRepository(
            listOf(
                PressurePoint(now.minus(Duration.ofDays(1)), 1008.0),
                PressurePoint(now.plus(Duration.ofDays(1)), 1002.0), // 予報
                PressurePoint(old, 1015.0), // 期間外
            ),
        )
        val f = FakeRepositories(pressure = pressure)
        f.attacks.save(attack(inside))
        f.attacks.save(attack(old))
        val vm = vm(f)
        vm.uiState.test {
            val s = expectMostRecentItem()
            assertEquals(2, s.pressure.size)
            assertTrue(s.pressure.zipWithNext().all { (a, b) -> a.time < b.time })
            assertEquals(listOf(inside), s.attacks.map { it.startedAt })
            vm.setPressurePeriod(PressurePeriod.MONTH)
            assertEquals(2, expectMostRecentItem().attacks.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR100_地域が未設定なら案内用に未設定として返す() = runTest {
        val f = FakeRepositories()
        val vm = vm(f)
        vm.uiState.test {
            assertFalse(expectMostRecentItem().hasPrefecture)
            f.settings.update { it.copy(prefectureCode = "13") }
            assertTrue(expectMostRecentItem().hasPrefecture)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR105_最終取得時刻を返す() = runTest {
        val f = FakeRepositories()
        val vm = vm(f)
        vm.uiState.test {
            assertNull(expectMostRecentItem().lastFetchedAt)
            f.pressure.lastFetched.value = now
            assertEquals(now, expectMostRecentItem().lastFetchedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
