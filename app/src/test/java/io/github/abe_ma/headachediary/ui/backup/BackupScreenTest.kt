package io.github.abe_ma.headachediary.ui.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abe_ma.headachediary.domain.BackupInspection
import io.github.abe_ma.headachediary.domain.RestoreResult
import io.github.abe_ma.headachediary.ui.theme.HeadacheDiaryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // SDK 36 の Robolectric は Java 21 を要求するため (既存テストと同じ)
class BackupScreenTest {
    @get:Rule val rule = createComposeRule()

    private class Calls {
        var create = 0; var warningAccept = 0; var restore = 0; var confirm = 0; var dismiss = 0; var back = 0
    }

    private fun show(state: BackupUiState, calls: Calls = Calls()) = rule.setContent {
        HeadacheDiaryTheme {
            BackupContent(
                state, onBack = { calls.back++ }, onCreate = { calls.create++ }, onWarningAccept = { calls.warningAccept++ },
                onRestore = { calls.restore++ }, onConfirmRestore = { calls.confirm++ }, onDismiss = { calls.dismiss++ },
                now = Instant.parse("2026-11-25T03:00:00Z"),
            )
        }
    }

    private val valid = BackupInspection.Valid(
        attackCount = 12, newAttackCount = 9, duplicateCount = 3,
        periodStart = Instant.parse("2026-01-05T00:00:00Z"), periodEnd = Instant.parse("2026-11-20T00:00:00Z"),
        customMedicationCount = 2, includesSettings = true,
        exportedAt = Instant.parse("2026-11-24T10:00:00Z"), appVersion = "0.1.0",
    )

    @Test
    fun FR600_作成と読み込みの操作があり_CSVとは別の機能だと案内する() {
        val calls = Calls()
        show(BackupUiState(), calls)
        rule.onNodeWithTag(TAG_BACKUP_CREATE).performClick()
        rule.onNodeWithTag(TAG_BACKUP_RESTORE).performClick()
        assertEquals(1, calls.create)
        assertEquals(1, calls.restore)
        rule.onNodeWithText("CSV・PDF の書き出しとは別の機能です", substring = true).assertIsDisplayed()
    }

    @Test
    fun FR601_作成前の注意で暗号化されないことを伝え_了承で進める() {
        val calls = Calls()
        show(BackupUiState(phase = BackupPhase.CreateWarning), calls)
        rule.onNodeWithText("暗号化されずに保存されます", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_BACKUP_WARNING_ACCEPT).performClick()
        assertEquals(1, calls.warningAccept)
    }

    @Test
    fun FR601_注意でやめられる() {
        val calls = Calls()
        show(BackupUiState(phase = BackupPhase.CreateWarning), calls)
        rule.onNodeWithTag(TAG_BACKUP_WARNING_CANCEL).performClick()
        assertEquals(1, calls.dismiss)
    }

    @Test
    fun FR603_確認画面に件数_期間_新規と重複_設定の有無を出し_押すまで反映しない() {
        val calls = Calls()
        show(BackupUiState(phase = BackupPhase.RestoreConfirm(valid)), calls)
        rule.onNodeWithText("発作の記録: 12件").assertIsDisplayed()
        rule.onNodeWithText("期間: ", substring = true).assertIsDisplayed()
        rule.onNodeWithText("うち追加される記録: 9件、重複のため読み込まない記録: 3件").assertIsDisplayed()
        rule.onNodeWithText("自由入力した薬: 2件").assertIsDisplayed()
        rule.onNodeWithText("通知のオン/オフは、読み込み後は必ずオフになります", substring = true).assertIsDisplayed()
        assertEquals(0, calls.confirm)
        rule.onNodeWithTag(TAG_BACKUP_CONFIRM_ACCEPT).performClick()
        assertEquals(1, calls.confirm)
    }

    @Test
    fun FR603_確認画面でやめられる_設定が無いバックアップはその旨を出す() {
        val calls = Calls()
        show(BackupUiState(phase = BackupPhase.RestoreConfirm(valid.copy(includesSettings = false, attackCount = 0, newAttackCount = 0, duplicateCount = 0, periodStart = null, periodEnd = null))), calls)
        rule.onNodeWithText("発作の記録: なし").assertIsDisplayed()
        rule.onNodeWithText("設定: 含まれていません").assertIsDisplayed()
        rule.onNodeWithTag(TAG_BACKUP_CONFIRM_CANCEL).performClick()
        assertEquals(1, calls.dismiss)
    }

    @Test
    fun FR607_結果に追加_スキップ_薬_設定_通知オフを出し_設定へ誘導する() {
        val calls = Calls()
        show(BackupUiState(phase = BackupPhase.RestoreDone(RestoreResult(9, 3, 2, settingsApplied = true, notificationsTurnedOff = true), includedSettings = true)), calls)
        rule.onNodeWithText("追加した発作の記録: 9件").assertIsDisplayed()
        rule.onNodeWithText("重複のため読み込まなかった記録: 3件").assertIsDisplayed()
        rule.onNodeWithText("追加した薬: 2件").assertIsDisplayed()
        rule.onNodeWithText("読み込んだ設定を反映しました", substring = true).assertIsDisplayed()
        rule.onNodeWithText("通知はオフになっています", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_BACKUP_RESULT_SETTINGS).performClick()
        assertEquals(1, calls.dismiss)
        assertEquals(1, calls.back)
    }

    @Test
    fun FR607_設定を反映していないときは通知オフの案内を出さない() {
        show(BackupUiState(phase = BackupPhase.RestoreDone(RestoreResult(1, 0, 0, settingsApplied = false, notificationsTurnedOff = false), includedSettings = false)))
        rule.onNodeWithText("設定: ファイルに含まれていないため、反映していません").assertIsDisplayed()
        rule.onNodeWithTag(TAG_BACKUP_RESULT_SETTINGS).assertDoesNotExist()
    }

    @Test
    fun FR607_通知オフの表示はnotificationsTurnedOffだけで決め_設定を反映していても嘘を言わない() {
        // 設定の反映に失敗した (settingsApplied=false) 場合は通知の状態を変えていないので、「通知はオフ」とは言わない
        show(BackupUiState(phase = BackupPhase.RestoreDone(RestoreResult(2, 0, 0, settingsApplied = false, notificationsTurnedOff = false), includedSettings = true)))
        rule.onNodeWithText("通知はオフになっています", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_BACKUP_RESULT_SETTINGS).assertDoesNotExist()
    }

    @Test
    fun FR607_設定を含んでいたのに反映できなかったときは反映していないと区別して伝える() {
        show(BackupUiState(phase = BackupPhase.RestoreDone(RestoreResult(5, 0, 0, settingsApplied = false, notificationsTurnedOff = false), includedSettings = true)))
        rule.onNodeWithText("設定: 反映できませんでした。記録は読み込み済みで、設定は変更されていません。").assertIsDisplayed()
        rule.onNodeWithText("設定: ファイルに含まれていないため、反映していません").assertDoesNotExist()
    }

    @Test
    fun FR607_処理中は戻るボタンを無効にする() {
        val calls = Calls()
        show(BackupUiState(phase = BackupPhase.Working(BackupOperation.CREATING)), calls)
        rule.onNodeWithTag(TAG_BACKUP_BACK).assertIsNotEnabled()
    }

    @Test
    fun FR607_待機中は戻るボタンが使える() {
        val calls = Calls()
        show(BackupUiState(), calls)
        rule.onNodeWithTag(TAG_BACKUP_BACK).assertIsEnabled().performClick()
        assertEquals(1, calls.back)
    }

    @Test
    fun FR606_失敗の理由を種類ごとに表示し_読み込みでは変更なしと伝える() {
        val cases = mapOf(
            BackupError.NOT_BACKUP to "このアプリのバックアップ形式ではありません",
            BackupError.CORRUPT to "ファイルが壊れているか、途中で切れています",
            BackupError.TOO_LARGE to "ファイルが大きすぎるため読み込めません",
            BackupError.UNSUPPORTED_VERSION to "新しいバージョンのアプリで作成されたファイルです。アプリを更新してください。",
        )
        // setContent は 1 テストで 1 回しか呼べないので、状態を差し替えて種類ごとに確認する。
        var state by mutableStateOf(BackupUiState(phase = BackupPhase.Failed(BackupError.NOT_BACKUP, duringCreate = false)))
        rule.setContent {
            HeadacheDiaryTheme { BackupContent(state, {}, {}, {}, {}, {}, {}) }
        }
        cases.forEach { (error, text) ->
            state = BackupUiState(phase = BackupPhase.Failed(error, duringCreate = false))
            rule.waitForIdle()
            rule.onNodeWithText(text, substring = true).assertIsDisplayed()
            rule.onNodeWithText("記録や設定は変更されていません。").assertIsDisplayed()
        }
    }

    @Test
    fun FR606_作成の失敗では変更なしの文を出さない() {
        show(BackupUiState(phase = BackupPhase.Failed(BackupError.IO, duringCreate = true)))
        rule.onNodeWithText("作成できませんでした").assertIsDisplayed()
        rule.onNodeWithText("記録や設定は変更されていません。").assertDoesNotExist()
    }

    @Test
    fun FR607_処理中は進捗を出し_作成と読み込みのボタンを無効にする() {
        show(BackupUiState(phase = BackupPhase.Working(BackupOperation.RESTORING)))
        rule.onNodeWithTag(TAG_BACKUP_PROGRESS).assertIsDisplayed()
        rule.onNodeWithText("記録を読み込んでいます…").assertIsDisplayed()
        rule.onNodeWithTag(TAG_BACKUP_CREATE).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_BACKUP_RESTORE).assertIsNotEnabled()
    }

    @Test
    fun FR600_待機中はボタンが有効で進捗は出ない() {
        show(BackupUiState())
        rule.onNodeWithTag(TAG_BACKUP_CREATE).assertIsEnabled()
        rule.onNodeWithTag(TAG_BACKUP_PROGRESS).assertDoesNotExist()
    }
}
