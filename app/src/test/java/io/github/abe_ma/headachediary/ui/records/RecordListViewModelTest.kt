package io.github.abe_ma.headachediary.ui.records

import app.cash.turbine.test
import io.github.abe_ma.headachediary.data.fake.FakeRepositories
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.ui.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class RecordListViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun FR200_記録は新しい順に並び_追加が反映される() = runTest {
        val fakes = FakeRepositories()
        val vm = RecordListViewModel(fakes.attacks)
        vm.uiState.test {
            var s = awaitItem()
            if (s is RecordListUiState.Loading) s = awaitItem()
            assertTrue((s as RecordListUiState.Content).attacks.isEmpty())

            val old = fakes.attacks.save(Attack(0, Instant.parse("2026-11-01T00:00:00Z"), Severity.MILD, null, emptyList()))
            val new = fakes.attacks.save(Attack(0, Instant.parse("2026-11-10T00:00:00Z"), Severity.SEVERE, null, emptyList()))
            val last = expectMostRecentItem() as RecordListUiState.Content
            assertEquals(listOf(new, old), last.attacks.map { it.id })
        }
    }
}
