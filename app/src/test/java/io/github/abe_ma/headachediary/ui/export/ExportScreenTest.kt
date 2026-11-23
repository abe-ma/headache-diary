package io.github.abe_ma.headachediary.ui.export

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abe_ma.headachediary.ui.theme.HeadacheDiaryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // SDK 36 の Robolectric は Java 21 を要求するため (既存テストと同じ)
class ExportScreenTest {
    @get:Rule val rule = createComposeRule()

    private val base = ExportUiState(
        customStart = LocalDate.of(2026, 11, 1), customEnd = LocalDate.of(2026, 11, 23),
        rangeStart = LocalDate.of(2026, 11, 1), rangeEnd = LocalDate.of(2026, 11, 23), attackCount = 3,
    )

    private class Calls {
        val presets = mutableListOf<ExportPreset>(); val kinds = mutableListOf<ExportKind>()
        var exported = 0; var sharedAgain = 0
    }

    private fun show(state: ExportUiState, calls: Calls = Calls()) = rule.setContent {
        HeadacheDiaryTheme {
            ExportContent(
                state, onBack = {}, onPreset = { calls.presets += it }, onKind = { calls.kinds += it },
                onCustomStart = {}, onCustomEnd = {}, onExport = { calls.exported++ }, onShareAgain = { calls.sharedAgain++ },
                today = LocalDate.of(2026, 11, 23),
            )
        }
    }

    @Test
    fun FR400_期間と件数を表示し_プリセットと形式を選べる() {
        val calls = Calls()
        show(base, calls)
        rule.onNodeWithTag(presetTag(ExportPreset.THIS_MONTH)).assertIsSelected()
        rule.onNodeWithText("対象: 2026年11月1日 〜 2026年11月23日").assertIsDisplayed()
        rule.onNodeWithTag(TAG_EXPORT_COUNT).assertIsDisplayed()
        rule.onNodeWithText("この期間の発作: 3件").assertIsDisplayed()
        rule.onNodeWithTag(presetTag(ExportPreset.LAST_YEAR)).performClick()
        rule.onNodeWithTag(kindTag(ExportKind.CSV)).performClick()
        assertEquals(listOf(ExportPreset.LAST_YEAR), calls.presets)
        assertEquals(listOf(ExportKind.CSV), calls.kinds)
    }

    @Test
    fun FR400_期間指定では開始日と終了日のボタンを出す() {
        show(base.copy(preset = ExportPreset.CUSTOM))
        rule.onNodeWithTag(TAG_EXPORT_START).assertIsDisplayed()
        rule.onNodeWithTag(TAG_EXPORT_END).assertIsDisplayed()
    }

    @Test
    fun FR400_開始が終了より後なら案内して出力ボタンを無効にする() {
        show(base.copy(preset = ExportPreset.CUSTOM, rangeStart = LocalDate.of(2026, 11, 20), rangeEnd = LocalDate.of(2026, 11, 10)))
        rule.onNodeWithTag(TAG_EXPORT_RANGE_INVALID).assertIsDisplayed()
        rule.onNodeWithTag(TAG_EXPORT_RUN).assertIsNotEnabled()
    }

    @Test
    fun FR402_発作0件でもその旨を表示しつつ出力できる() {
        val calls = Calls()
        show(base.copy(attackCount = 0), calls)
        rule.onNodeWithText("この期間の発作の記録はありません", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_EXPORT_RUN).assertIsEnabled().performClick()
        assertEquals(1, calls.exported)
    }

    @Test
    fun FR402_生成中は進捗を出し出力ボタンを無効にする() {
        show(base.copy(status = ExportStatus.Generating))
        rule.onNodeWithTag(TAG_EXPORT_PROGRESS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_EXPORT_RUN).assertIsNotEnabled()
    }

    @Test
    fun FR402_失敗したらメッセージを出しやり直せる() {
        show(base.copy(status = ExportStatus.Failed))
        rule.onNodeWithTag(TAG_EXPORT_FAILED).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_EXPORT_RUN).assertIsEnabled()
    }

    @Test
    fun FR402_生成後はもう一度共有できる() {
        val calls = Calls()
        show(base.copy(status = ExportStatus.Done(Uri.parse("content://x/a.pdf"), ExportKind.PDF)), calls)
        rule.onNodeWithTag(TAG_EXPORT_DONE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_EXPORT_SHARE_AGAIN).performScrollTo().performClick()
        assertEquals(1, calls.sharedAgain)
    }

    @Test
    fun FR403_書き出し画面にOpenMeteoの帰属表示とプライバシーの注記を出す() {
        show(base)
        rule.onNodeWithTag(TAG_EXPORT_ATTRIBUTION).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Weather data by Open-Meteo.com", substring = true).assertIsDisplayed()
        rule.onNodeWithText("共有先はあなたが選びます", substring = true).performScrollTo().assertIsDisplayed()
    }
}
