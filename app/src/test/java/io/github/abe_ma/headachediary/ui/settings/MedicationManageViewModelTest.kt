package io.github.abe_ma.headachediary.ui.settings

import app.cash.turbine.test
import io.github.abe_ma.headachediary.data.fake.FakeRepositories
import io.github.abe_ma.headachediary.ui.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MedicationManageViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun FR207_非表示にしても一覧に残り_再表示できる() = runTest {
        val f = FakeRepositories()
        val vm = MedicationManageViewModel(f.medications)
        vm.medications.test {
            val all = expectMostRecentItem()!!
            val target = all.first()
            assertFalse(target.isHidden)

            vm.setVisible(target.id, false)
            val hidden = expectMostRecentItem()!!
            assertEquals(all.size, hidden.size) // 削除はしない
            assertTrue(hidden.first { it.id == target.id }.isHidden)
            assertFalse(f.medications.observeCandidates().first().any { it.id == target.id })

            vm.setVisible(target.id, true)
            assertFalse(expectMostRecentItem()!!.first { it.id == target.id }.isHidden)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
