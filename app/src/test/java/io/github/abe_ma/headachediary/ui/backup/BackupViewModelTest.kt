package io.github.abe_ma.headachediary.ui.backup

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.abe_ma.headachediary.data.fake.FakeBackupRepository
import io.github.abe_ma.headachediary.domain.BackupInspection
import io.github.abe_ma.headachediary.domain.InvalidReason
import io.github.abe_ma.headachediary.ui.MainDispatcherRule
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

// Uri.parse を使うため Robolectric で実行する (素の JUnit では Uri が null になる)。
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // SDK 36 の Robolectric は Java 21 を要求するため (既存テストと同じ)
class BackupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Tokyo")
    private val clock = Clock.fixed(Instant.parse("2026-11-25T03:05:00Z"), zone) // 2026-11-25 12:05 JST
    private val file = Uri.parse("content://docs/backup.json")

    /** メモリ上のファイル。失敗も再現できる。 */
    private class MemoryFiles : BackupFileAccess {
        val written = ByteArrayOutputStream()
        var failOpenInput = false
        var failOpenOutput = false
        var openedOutputs = 0
        val deleted = mutableListOf<Uri>()
        var failDelete = false
        /** close() で失敗する出力 (最後の書き込みが保存先に渡らなかった場合)。 */
        var failClose = false
        override fun openInput(uri: Uri): InputStream {
            if (failOpenInput) throw IOException("cannot open")
            return ByteArrayInputStream("{}".toByteArray())
        }
        override fun openOutput(uri: Uri): OutputStream {
            if (failOpenOutput) throw IOException("cannot open")
            openedOutputs++
            if (!failClose) return written
            return object : OutputStream() {
                override fun write(b: Int) = written.write(b)
                override fun close() = throw IOException("close failed")
            }
        }
        override fun deleteQuietly(uri: Uri) {
            deleted += uri
            if (failDelete) throw SecurityException("cannot delete")
        }
    }

    private fun vm(repo: FakeBackupRepository, files: MemoryFiles = MemoryFiles(), handle: SavedStateHandle = SavedStateHandle()) =
        BackupViewModel(repo, files, main.dispatcher, clock, zone, handle)

    // ---- 作成 (FR-601) ----

    @Test
    fun FR601_作成は注意を出して了承したら保存先の選択を起動する() = runTest {
        val vm = vm(FakeBackupRepository())
        vm.uiState.test {
            vm.onCreateClicked()
            assertEquals(BackupPhase.CreateWarning, expectMostRecentItem().phase)
            vm.onCreateWarningAccepted()
            val s = expectMostRecentItem()
            assertEquals(BackupPhase.Idle, s.phase)
            assertEquals(BackupLaunch.CreateDocument("headache-diary-backup-20261125-1205.json"), s.launch)
            vm.onLaunchConsumed()
            assertNull(expectMostRecentItem().launch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_注意でやめるなら保存先の選択に進まない() = runTest {
        val repo = FakeBackupRepository()
        val vm = vm(repo)
        vm.uiState.test {
            vm.onCreateClicked()
            vm.onDismiss()
            val s = expectMostRecentItem()
            assertEquals(BackupPhase.Idle, s.phase)
            assertNull(s.launch)
            assertEquals(0, repo.writeCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_保存先が選ばれたらバックアップを書き込んで完了する() = runTest {
        val repo = FakeBackupRepository()
        val files = MemoryFiles()
        val vm = vm(repo, files)
        vm.uiState.test {
            vm.onCreateDocumentResult(file)
            assertEquals(BackupPhase.CreateDone, expectMostRecentItem().phase)
            assertEquals(1, repo.writeCount)
            assertEquals(FakeBackupRepository.FIXED_JSON, files.written.toString(Charsets.UTF_8))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_保存先の選択をキャンセルしたら何もしない() = runTest {
        val repo = FakeBackupRepository()
        val vm = vm(repo)
        vm.uiState.test {
            vm.onCreateDocumentResult(null)
            assertEquals(BackupPhase.Idle, expectMostRecentItem().phase)
            assertEquals(0, repo.writeCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_保存先を開けないときは作成の失敗として理由を示す() = runTest {
        val files = MemoryFiles().apply { failOpenOutput = true }
        val vm = vm(FakeBackupRepository(), files)
        vm.uiState.test {
            vm.onCreateDocumentResult(file)
            assertEquals(BackupPhase.Failed(BackupError.IO, duringCreate = true), expectMostRecentItem().phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_既定のファイル名は端末のローカル時刻で付ける() {
        assertEquals("headache-diary-backup-20261125-1205.json", vm(FakeBackupRepository()).defaultFileName())
    }

    // ---- 読み込み (FR-603〜607) ----

    @Test
    fun FR603_ファイルを選ぶと検証して内容を確認に出し_まだ反映しない() = runTest {
        val repo = FakeBackupRepository()
        val vm = vm(repo)
        vm.uiState.test {
            vm.onOpenDocumentResult(file)
            val phase = expectMostRecentItem().phase
            assertTrue(phase is BackupPhase.RestoreConfirm)
            assertEquals(repo.nextInspection, (phase as BackupPhase.RestoreConfirm).inspection)
            assertEquals(1, repo.inspectCount)
            assertEquals(0, repo.restoreCount) // 「読み込む」を押すまで反映しない
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR603_確認でやめたら反映せずに戻る() = runTest {
        val repo = FakeBackupRepository()
        val vm = vm(repo)
        vm.uiState.test {
            vm.onOpenDocumentResult(file)
            vm.onDismiss()
            assertEquals(BackupPhase.Idle, expectMostRecentItem().phase)
            vm.onRestoreConfirmed() // 閉じたあとに押されても何も起きない
            assertEquals(0, repo.restoreCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR604_読み込むを押したときだけ反映して結果を出す() = runTest {
        val repo = FakeBackupRepository()
        val vm = vm(repo)
        vm.uiState.test {
            vm.onOpenDocumentResult(file)
            vm.onRestoreConfirmed()
            val phase = expectMostRecentItem().phase
            assertEquals(BackupPhase.RestoreDone(repo.nextRestoreResult, includedSettings = true), phase)
            assertEquals(1, repo.restoreCount)
            assertTrue(repo.nextRestoreResult.notificationsTurnedOff) // 通知はオフ (FR-605/607)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR606_バックアップ形式でないファイルは理由を示して反映しない() = runTest {
        InvalidReason.entries.forEach { reason ->
            val repo = FakeBackupRepository().apply { nextInspection = BackupInspection.Invalid(reason) }
            val vm = vm(repo)
            vm.uiState.test {
                vm.onOpenDocumentResult(file)
                val expected = when (reason) {
                    InvalidReason.NOT_BACKUP -> BackupError.NOT_BACKUP
                    InvalidReason.CORRUPT -> BackupError.CORRUPT
                    InvalidReason.TOO_LARGE -> BackupError.TOO_LARGE
                    InvalidReason.UNSUPPORTED_VERSION -> BackupError.UNSUPPORTED_VERSION
                }
                assertEquals(BackupPhase.Failed(expected, duringCreate = false), expectMostRecentItem().phase)
                assertEquals(0, repo.restoreCount)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun FR606_反映時の再検証で不正と分かったら理由を示す() = runTest {
        val repo = FakeBackupRepository().apply { nextRestoreFailure = InvalidReason.CORRUPT }
        val vm = vm(repo)
        vm.uiState.test {
            vm.onOpenDocumentResult(file)
            vm.onRestoreConfirmed()
            assertEquals(BackupPhase.Failed(BackupError.CORRUPT, duringCreate = false), expectMostRecentItem().phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR606_選んだファイルを開けないときは読み込みの失敗として扱う() = runTest {
        val files = MemoryFiles().apply { failOpenInput = true }
        val repo = FakeBackupRepository()
        val vm = vm(repo, files)
        vm.uiState.test {
            vm.onOpenDocumentResult(file)
            assertEquals(BackupPhase.Failed(BackupError.IO, duringCreate = false), expectMostRecentItem().phase)
            assertEquals(0, repo.inspectCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR603_ファイルの選択をキャンセルしたら何もしない() = runTest {
        val repo = FakeBackupRepository()
        val vm = vm(repo)
        vm.uiState.test {
            vm.onOpenDocumentResult(null)
            assertEquals(BackupPhase.Idle, expectMostRecentItem().phase)
            assertEquals(0, repo.inspectCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR603_読み込むを押すとファイル選択を起動する要求を出す() = runTest {
        val vm = vm(FakeBackupRepository())
        vm.uiState.test {
            vm.onOpenClicked()
            assertEquals(BackupLaunch.OpenDocument, expectMostRecentItem().launch)
            vm.onLaunchConsumed()
            assertNull(expectMostRecentItem().launch)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR607_処理中は別の操作を受け付けない() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = object : io.github.abe_ma.headachediary.domain.BackupRepository {
            var writes = 0
            override suspend fun write(out: OutputStream) { writes++; gate.await() }
            override suspend fun inspect(input: InputStream): BackupInspection = error("not used")
            override suspend fun restore(input: InputStream) = error("not used")
        }
        val vm = BackupViewModel(repo, MemoryFiles(), main.dispatcher, clock, zone, SavedStateHandle())
        vm.uiState.test {
            vm.onCreateDocumentResult(file)
            assertEquals(BackupPhase.Working(BackupOperation.CREATING), expectMostRecentItem().phase)
            vm.onCreateDocumentResult(file) // 二重起動しない
            vm.onCreateClicked()
            vm.onOpenClicked()
            vm.onDismiss()
            assertEquals(1, repo.writes)
            assertTrue(vm.uiState.value.isWorking)
            assertNull(vm.uiState.value.launch)
            gate.complete(Unit)
            assertEquals(BackupPhase.CreateDone, expectMostRecentItem().phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR607_確認中のファイルはプロセス死でも保持する() = runTest {
        val handle = SavedStateHandle()
        val repo = FakeBackupRepository()
        vm(repo, handle = handle).onOpenDocumentResult(file)
        assertEquals(file.toString(), handle.get<String>("pending_restore_uri"))
    }

    // ---- 作成の失敗時の後始末 (T-21d) ----

    /** write が途中で失敗する Repository。 */
    private class FailingWriteRepository(private val error: Exception) : io.github.abe_ma.headachediary.domain.BackupRepository {
        override suspend fun write(out: OutputStream) { out.write("{\"for".toByteArray()); throw error }
        override suspend fun inspect(input: InputStream): BackupInspection = throw error
        override suspend fun restore(input: InputStream) = throw error
    }

    private fun vmWith(repo: io.github.abe_ma.headachediary.domain.BackupRepository, files: MemoryFiles) =
        BackupViewModel(repo, files, main.dispatcher, clock, zone, SavedStateHandle())

    @Test
    fun FR601_書き込みが途中で失敗したら作成した保存先のファイルを削除する() = runTest {
        val files = MemoryFiles()
        val vm = vmWith(FailingWriteRepository(IOException("disk full")), files)
        vm.uiState.test {
            vm.onCreateDocumentResult(file)
            assertEquals(BackupPhase.Failed(BackupError.IO, duringCreate = true), expectMostRecentItem().phase)
            assertEquals(listOf(file), files.deleted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_保存先を開けなかったときも空の文書を削除する() = runTest {
        val files = MemoryFiles().apply { failOpenOutput = true }
        val vm = vm(FakeBackupRepository(), files)
        vm.uiState.test {
            vm.onCreateDocumentResult(file)
            assertEquals(BackupPhase.Failed(BackupError.IO, duringCreate = true), expectMostRecentItem().phase)
            assertEquals(listOf(file), files.deleted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_最後に閉じるときの失敗も失敗として扱い削除する() = runTest {
        val files = MemoryFiles().apply { failClose = true }
        val vm = vm(FakeBackupRepository(), files)
        vm.uiState.test {
            vm.onCreateDocumentResult(file)
            assertEquals(BackupPhase.Failed(BackupError.IO, duringCreate = true), expectMostRecentItem().phase)
            assertEquals(listOf(file), files.deleted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_作成に成功したときは削除しない() = runTest {
        val files = MemoryFiles()
        val vm = vm(FakeBackupRepository(), files)
        vm.uiState.test {
            vm.onCreateDocumentResult(file)
            assertEquals(BackupPhase.CreateDone, expectMostRecentItem().phase)
            assertTrue(files.deleted.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_削除に失敗しても例外を握りつぶして失敗の表示を続ける() = runTest {
        val files = MemoryFiles().apply { failDelete = true }
        val vm = vmWith(FailingWriteRepository(IOException("disk full")), files)
        vm.uiState.test {
            vm.onCreateDocumentResult(file)
            assertEquals(BackupPhase.Failed(BackupError.IO, duringCreate = true), expectMostRecentItem().phase)
            assertEquals(1, files.deleted.size) // 削除は試みた
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR601_作成が中断されたときも保存先のファイルを削除する() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val files = MemoryFiles()
        val repo = object : io.github.abe_ma.headachediary.domain.BackupRepository {
            override suspend fun write(out: OutputStream) { out.write("{".toByteArray()); gate.await() }
            override suspend fun inspect(input: InputStream): BackupInspection = error("not used")
            override suspend fun restore(input: InputStream) = error("not used")
        }
        val vm = vmWith(repo, files)
        vm.onCreateDocumentResult(file)
        assertEquals(BackupPhase.Working(BackupOperation.CREATING), vm.uiState.value.phase)
        vm.viewModelScope.cancel() // 画面を離れて ViewModel が破棄された場合
        main.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(file), files.deleted)
    }

    // ---- IOException の扱い (T-21 minor-5) ----

    @Test
    fun FR606_確認中のファイルの読み取りに失敗したら読み込めませんでしたとして扱う() = runTest {
        val vm = vmWith(FailingWriteRepository(IOException("read failed")), MemoryFiles())
        vm.uiState.test {
            vm.onOpenDocumentResult(file)
            assertEquals(BackupPhase.Failed(BackupError.IO, duringCreate = false), expectMostRecentItem().phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR606_反映中の読み取り失敗も読み込めませんでしたとして扱い_保存先は削除しない() = runTest {
        // inspect は成功し、restore で IOException になる
        val repo = object : io.github.abe_ma.headachediary.domain.BackupRepository {
            override suspend fun write(out: OutputStream) = Unit
            override suspend fun inspect(input: InputStream): BackupInspection = FakeBackupRepository().nextInspection
            override suspend fun restore(input: InputStream) = throw IOException("read failed")
        }
        val files = MemoryFiles()
        val vm = vmWith(repo, files)
        vm.uiState.test {
            vm.onOpenDocumentResult(file)
            vm.onRestoreConfirmed()
            assertEquals(BackupPhase.Failed(BackupError.IO, duringCreate = false), expectMostRecentItem().phase)
            assertTrue(files.deleted.isEmpty()) // 読み込みでは選んだファイルを削除しない
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR606_ファイルへのアクセス権が失われたときも読み込めませんでしたとして扱う() = runTest {
        val files = object : BackupFileAccess {
            override fun openInput(uri: Uri): InputStream = throw SecurityException("permission revoked")
            override fun openOutput(uri: Uri): OutputStream = throw SecurityException("permission revoked")
            override fun deleteQuietly(uri: Uri) = Unit
        }
        val vm = BackupViewModel(FakeBackupRepository(), files, main.dispatcher, clock, zone, SavedStateHandle())
        vm.uiState.test {
            vm.onOpenDocumentResult(file)
            assertEquals(BackupPhase.Failed(BackupError.IO, duringCreate = false), expectMostRecentItem().phase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR607_結果には確認時に設定を含んでいたかを持たせる() = runTest {
        val repo = FakeBackupRepository().apply {
            nextInspection = (nextInspection as BackupInspection.Valid).copy(includesSettings = false)
            nextRestoreResult = nextRestoreResult.copy(settingsApplied = false, notificationsTurnedOff = false)
        }
        val vm = vm(repo)
        vm.uiState.test {
            vm.onOpenDocumentResult(file)
            vm.onRestoreConfirmed()
            val done = expectMostRecentItem().phase as BackupPhase.RestoreDone
            assertEquals(false, done.includedSettings)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
