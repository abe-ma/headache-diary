package io.github.abe_ma.headachediary.ui.records

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Intake
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.ui.theme.HeadacheDiaryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // SDK 36 の Robolectric は Java 21 を要求するため (既存テストと同じ)
class RecordListScreenTest {
    @get:Rule val rule = createComposeRule()

    private val med = Medication(1, "ロキソニンS", null, isPreset = true, isHidden = false)
    private val now = Instant.parse("2026-11-23T00:00:00Z")
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun show(state: RecordListUiState, onAdd: () -> Unit = {}, onOpen: (Long) -> Unit = {}) =
        rule.setContent {
            HeadacheDiaryTheme { RecordListContent(state, onAdd, onOpen, zone, now) }
        }

    @Test
    fun FR200_記録がないと案内を表示しFABで新規作成できる() {
        var added = 0
        show(RecordListUiState.Content(emptyList()), onAdd = { added++ })
        rule.onNodeWithTag(TAG_RECORD_EMPTY).assertIsDisplayed()
        rule.onNodeWithTag(TAG_ADD_ATTACK).performClick()
        assertEquals(1, added)
    }

    @Test
    fun FR200_行に日時_強さ_服薬の要約が出てタップで開ける() {
        val attack = Attack(
            7, Instant.parse("2026-11-20T03:05:00Z"), Severity.SEVERE, 1004.5,
            listOf(Intake(1, med, 2, Effect.UNRATED)),
        )
        var opened = -1L
        show(RecordListUiState.Content(listOf(attack)), onOpen = { opened = it })
        rule.onNodeWithText("11月20日(", substring = true).assertIsDisplayed()
        rule.onNodeWithText("強い").assertIsDisplayed()
        rule.onNodeWithText("ロキソニンS 2錠").assertIsDisplayed()
        rule.onNodeWithText("効果 未評価あり").assertIsDisplayed()
        rule.onNodeWithTag(recordItemTag(7)).performClick()
        assertEquals(7L, opened)
    }

    private fun attackWith(pressure: Double?, delta: Double?) =
        Attack(9, Instant.parse("2026-11-20T03:05:00Z"), Severity.MILD, pressure, emptyList(), pressureDeltaHpa = delta)

    @Test
    fun FR404_気圧と前日差を並べて表示する() {
        show(RecordListUiState.Content(listOf(attackWith(1013.2, -3.4))))
        rule.onNodeWithTag(recordPressureTag(9), useUnmergedTree = true).assertTextEquals("気圧 1013.2 hPa (前日差 -3.4)")
    }

    @Test
    fun FR404_前日差が増加なら符号プラスで表示する() {
        show(RecordListUiState.Content(listOf(attackWith(1013.2, 1.2))))
        rule.onNodeWithTag(recordPressureTag(9), useUnmergedTree = true).assertTextEquals("気圧 1013.2 hPa (前日差 +1.2)")
    }

    @Test
    fun FR404_前日差が無ければ括弧部分を省略する() {
        show(RecordListUiState.Content(listOf(attackWith(1013.2, null))))
        rule.onNodeWithTag(recordPressureTag(9), useUnmergedTree = true).assertTextEquals("気圧 1013.2 hPa")
    }

    @Test
    fun FR404_気圧が無ければ気圧の行を出さない() {
        show(RecordListUiState.Content(listOf(attackWith(null, null))))
        rule.onNodeWithTag(recordPressureTag(9), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun FR404_読み上げには前日より低い高い同じを言葉で含める() {
        show(RecordListUiState.Content(listOf(attackWith(1013.2, -3.4))))
        rule.onNodeWithTag(recordItemTag(9)).assertContentDescriptionContains("前日より 3.4 hPa 低い", substring = true)
    }

    @Test
    fun FR404_読み上げ_高い() {
        show(RecordListUiState.Content(listOf(attackWith(1013.2, 1.2))))
        rule.onNodeWithTag(recordItemTag(9)).assertContentDescriptionContains("前日より 1.2 hPa 高い", substring = true)
    }

    @Test
    fun FR404_読み上げ_同じ() {
        show(RecordListUiState.Content(listOf(attackWith(1013.2, 0.0))))
        rule.onNodeWithTag(recordItemTag(9)).assertContentDescriptionContains("前日と同じ気圧", substring = true)
    }

    @Test
    fun FR404_気圧が無いとき差だけがあっても表示も読み上げもしない() {
        show(RecordListUiState.Content(listOf(attackWith(null, -3.4))))
        rule.onNodeWithTag(recordPressureTag(9), useUnmergedTree = true).assertDoesNotExist()
        val d = rule.onNodeWithTag(recordItemTag(9)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        org.junit.Assert.assertFalse(d.contains("前日"))
    }
}
