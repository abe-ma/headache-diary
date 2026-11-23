package io.github.abe_ma.headachediary.ui.edit

import app.cash.turbine.test
import io.github.abe_ma.headachediary.data.fake.FakeRepositories
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Intake
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.ui.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class MedicationPickerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun FR206_使用回数の多い薬が先頭に来る() = runTest {
        val fakes = FakeRepositories()
        val vm = MedicationPickerViewModel(fakes.medications)
        vm.uiState.test {
            val initial = awaitItem().let { if (it.candidates.isEmpty()) awaitItem() else it }
            val last = initial.candidates.last()
            fakes.attacks.save(
                Attack(0, Instant.parse("2026-11-01T00:00:00Z"), Severity.MILD, null, listOf(Intake(0, last, 1, Effect.UNRATED))),
            )
            assertEquals(last.id, expectMostRecentItem().candidates.first().id)
        }
    }

    @Test
    fun FR207_非表示の薬は候補に出ない() = runTest {
        val fakes = FakeRepositories()
        val vm = MedicationPickerViewModel(fakes.medications)
        vm.uiState.test {
            val first = awaitItem().let { if (it.candidates.isEmpty()) awaitItem() else it }.candidates.first()
            fakes.medications.setHidden(first.id, true)
            assertFalse(expectMostRecentItem().candidates.any { it.id == first.id })
        }
    }

    @Test
    fun FR203_検索で名前の部分一致に絞り込める() = runTest {
        val fakes = FakeRepositories()
        val vm = MedicationPickerViewModel(fakes.medications)
        vm.uiState.test {
            val any = awaitItem().let { if (it.candidates.isEmpty()) awaitItem() else it }.candidates.first()
            val needle = any.name.take(2)
            vm.setQuery(needle)
            val s = expectMostRecentItem()
            assertTrue(s.candidates.isNotEmpty())
            assertTrue(s.candidates.all { it.name.contains(needle, true) || it.ingredient?.contains(needle, true) == true })
        }
    }

    @Test
    fun FR203_既存名と完全一致しない検索語は自由入力で追加できる() = runTest {
        val fakes = FakeRepositories()
        val vm = MedicationPickerViewModel(fakes.medications)
        vm.uiState.test {
            val any = awaitItem().let { if (it.candidates.isEmpty()) awaitItem() else it }.candidates.first()
            vm.setQuery("  ")
            assertNull(expectMostRecentItem().customName)
            vm.setQuery(any.name)
            assertNull(expectMostRecentItem().customName)
            vm.setQuery("まったく新しい薬")
            assertEquals("まったく新しい薬", expectMostRecentItem().customName)
        }
        val created = vm.addCustom("まったく新しい薬")
        assertFalse(created.isPreset)
        assertEquals(created, vm.addCustom("まったく新しい薬"))
    }
}
