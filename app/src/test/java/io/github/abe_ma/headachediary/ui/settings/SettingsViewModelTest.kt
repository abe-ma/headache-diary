package io.github.abe_ma.headachediary.ui.settings

import app.cash.turbine.test
import io.github.abe_ma.headachediary.data.fake.FakeRepositories
import io.github.abe_ma.headachediary.ui.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun vm(f: FakeRepositories = FakeRepositories()) = SettingsViewModel(f.settings, f.pressure, f.prefectures)

    @Test
    fun FR100_都道府県は47件から選べ_保存と解除ができる() = runTest {
        val f = FakeRepositories()
        val vm = vm(f)
        vm.uiState.test {
            val first = expectMostRecentItem()
            assertEquals(47, first.prefectures.size)
            assertNull(first.prefecture)

            vm.setPrefecture("13")
            assertEquals("13", f.settings.observe().first().prefectureCode)
            assertEquals("13", expectMostRecentItem().prefecture?.code)

            vm.setPrefecture(null)
            assertNull(f.settings.observe().first().prefectureCode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR105_最終取得時刻を表示用に公開する() = runTest {
        val f = FakeRepositories()
        val vm = vm(f)
        vm.uiState.test {
            assertNull(expectMostRecentItem().lastFetchedAt)
            val at = Instant.parse("2026-11-23T11:05:00Z")
            f.pressure.lastFetched.value = at
            assertEquals(at, expectMostRecentItem().lastFetchedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR500_効果リマインドのオンオフと遅延を保存できる() = runTest {
        val f = FakeRepositories()
        val vm = vm(f)
        vm.setEffectReminderEnabled(false)
        vm.setEffectReminderDelay(180)
        val s = f.settings.observe().first()
        assertFalse(s.effectReminderEnabled)
        assertEquals(180, s.effectReminderDelayMinutes)
        assertTrue(180 in EFFECT_DELAY_OPTIONS_MINUTES)
    }

    @Test
    fun FR501_気圧低下通知のオンオフ_しきい値_通知時刻を保存できる() = runTest {
        val f = FakeRepositories()
        val vm = vm(f)
        vm.setDropNotifyEnabled(true)
        vm.changeDropThreshold(+2)
        vm.setDropNotifyTime(LocalTime.of(7, 30))
        val s = f.settings.observe().first()
        assertTrue(s.dropNotifyEnabled)
        assertEquals(8.0, s.dropThresholdHpa, 0.0)
        assertEquals(LocalTime.of(7, 30), s.dropNotifyTime)
    }

    @Test
    fun FR501_しきい値は範囲外にならない() = runTest {
        val f = FakeRepositories()
        val vm = vm(f)
        vm.changeDropThreshold(-100)
        assertEquals(MIN_DROP_THRESHOLD_HPA, f.settings.observe().first().dropThresholdHpa, 0.0)
        vm.changeDropThreshold(+100)
        assertEquals(MAX_DROP_THRESHOLD_HPA, f.settings.observe().first().dropThresholdHpa, 0.0)
    }

    @Test
    fun FR503_通知は個別にオンオフでき_互いに影響しない() = runTest {
        val f = FakeRepositories()
        val vm = vm(f)
        vm.setDropNotifyEnabled(true)
        vm.setEffectReminderEnabled(false)
        val s = f.settings.observe().first()
        assertTrue(s.dropNotifyEnabled)
        assertFalse(s.effectReminderEnabled)
    }
}
