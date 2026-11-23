package io.github.abe_ma.headachediary.ui.export

import android.net.Uri
import app.cash.turbine.test
import io.github.abe_ma.headachediary.data.fake.FakeRepositories
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.ExportRepository
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.ui.MainDispatcherRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Uri.parse を使うため Robolectric で実行する (素の JUnit では Uri が null になる)。
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // SDK 36 の Robolectric は Java 21 を要求するため (既存テストと同じ)
class ExportViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Tokyo")
    private val clock = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), zone)

    private class RecordingExport(var fail: Boolean = false) : ExportRepository {
        val calls = mutableListOf<Pair<String, ClosedRange<Instant>>>()
        override suspend fun exportCsv(range: ClosedRange<Instant>): Uri {
            calls += "csv" to range
            if (fail) error("disk full")
            return Uri.parse("content://x/a.csv")
        }
        override suspend fun exportPdf(range: ClosedRange<Instant>): Uri {
            calls += "pdf" to range
            if (fail) error("disk full")
            return Uri.parse("content://x/a.pdf")
        }
    }

    private fun attack(iso: String) = Attack(0, Instant.parse(iso), Severity.MILD, null, emptyList())
    private fun vm(f: FakeRepositories, ex: ExportRepository) = ExportViewModel(f.attacks, ex, clock, zone)

    @Test
    fun FR400_期間の既定は今月で_プリセットごとに範囲が決まる() = runTest {
        val vm = vm(FakeRepositories(), RecordingExport())
        vm.uiState.test {
            var s = expectMostRecentItem()
            assertEquals(ExportPreset.THIS_MONTH, s.preset)
            assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 23), s.rangeStart to s.rangeEnd)
            vm.setPreset(ExportPreset.LAST_3_MONTHS)
            s = expectMostRecentItem()
            assertEquals(LocalDate.of(2026, 8, 24) to LocalDate.of(2026, 11, 23), s.rangeStart to s.rangeEnd)
            vm.setPreset(ExportPreset.LAST_YEAR)
            s = expectMostRecentItem()
            assertEquals(LocalDate.of(2025, 11, 24), s.rangeStart)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR400_全期間は最初の発作の日から今日まで_発作が無ければ今日だけ() = runTest {
        val f = FakeRepositories()
        val vm = vm(f, RecordingExport())
        vm.uiState.test {
            vm.setPreset(ExportPreset.ALL)
            assertEquals(LocalDate.of(2026, 11, 23), expectMostRecentItem().rangeStart)
            f.attacks.save(attack("2026-03-05T00:00:00Z"))
            assertEquals(LocalDate.of(2026, 3, 5), expectMostRecentItem().rangeStart)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR400_期間指定は開始が終了より後なら出力できず_未来の終了日は今日に丸める() = runTest {
        val vm = vm(FakeRepositories(), RecordingExport())
        vm.uiState.test {
            vm.setCustomStart(LocalDate.of(2026, 11, 20))
            vm.setCustomEnd(LocalDate.of(2026, 11, 10))
            var s = expectMostRecentItem()
            assertEquals(ExportPreset.CUSTOM, s.preset)
            assertFalse(s.isRangeValid)
            assertFalse(s.canExport)
            vm.setCustomEnd(LocalDate.of(2030, 1, 1))
            s = expectMostRecentItem()
            assertEquals(LocalDate.of(2026, 11, 23), s.rangeEnd)
            assertTrue(s.canExport)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR402_期間内の発作件数を数え_0件でも出力できる() = runTest {
        val f = FakeRepositories()
        f.attacks.save(attack("2026-11-10T00:00:00Z"))
        f.attacks.save(attack("2026-10-01T00:00:00Z")) // 今月の外
        val vm = vm(f, RecordingExport())
        vm.uiState.test {
            assertEquals(1, expectMostRecentItem().attackCount)
            vm.setCustomStart(LocalDate.of(2026, 1, 1)); vm.setCustomEnd(LocalDate.of(2026, 1, 31))
            val s = expectMostRecentItem()
            assertEquals(0, s.attackCount)
            assertTrue(s.canExport)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR400_終了日の終わりまでを範囲に含めて形式どおりに生成し共有を要求する() = runTest {
        val ex = RecordingExport()
        val vm = vm(FakeRepositories(), ex)
        vm.uiState.test {
            vm.setKind(ExportKind.CSV)
            vm.export()
            val s = expectMostRecentItem()
            val (kind, range) = ex.calls.single()
            assertEquals("csv", kind)
            assertEquals(Instant.parse("2026-10-31T15:00:00Z"), range.start) // 11/1 0:00 JST
            assertEquals(Instant.parse("2026-11-23T14:59:59.999Z"), range.endInclusive) // 11/23 23:59:59.999 JST
            assertTrue(s.status is ExportStatus.Done)
            assertEquals(ExportKind.CSV, s.share?.kind)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR401_PDFを選ぶとPDFを生成し_共有は一度だけ消費される() = runTest {
        val ex = RecordingExport()
        val vm = vm(FakeRepositories(), ex)
        vm.uiState.test {
            vm.setKind(ExportKind.PDF)
            vm.export()
            assertEquals("pdf", ex.calls.single().first)
            assertNotNull(expectMostRecentItem().share)
            vm.onShareLaunched()
            assertNull(expectMostRecentItem().share)
            vm.shareAgain() // 生成済みのファイルをもう一度共有できる
            assertNotNull(expectMostRecentItem().share)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR402_生成に失敗したら失敗状態になり共有しない() = runTest {
        val vm = vm(FakeRepositories(), RecordingExport(fail = true))
        vm.uiState.test {
            vm.export()
            val s = expectMostRecentItem()
            assertEquals(ExportStatus.Failed, s.status)
            assertNull(s.share)
            assertTrue(s.canExport) // やり直せる
            cancelAndIgnoreRemainingEvents()
        }
    }
}
