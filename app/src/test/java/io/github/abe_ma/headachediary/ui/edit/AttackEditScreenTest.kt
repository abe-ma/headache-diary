package io.github.abe_ma.headachediary.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.ui.theme.HeadacheDiaryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // SDK 36 の Robolectric は Java 21 を要求するため (既存テストと同じ)
class AttackEditScreenTest {
    @get:Rule val rule = createComposeRule()

    private val med = Medication(1, "ロキソニンS", "ロキソプロフェン", isPreset = true, isHidden = false)

    /** VM を介さず、状態を持つ最小のホストで画面の振る舞いを確認する。 */
    private fun host(
        initial: AttackEditUiState,
        onSave: () -> Unit = {},
        onDelete: () -> Unit = {},
    ) = rule.setContent {
        var s by remember { mutableStateOf(initial) }
        HeadacheDiaryTheme {
            AttackEditContent(
                state = s,
                onBack = {},
                onDateTimeChange = { s = s.copy(startedAt = it) },
                onSeverityChange = { s = s.copy(severity = it) },
                onAddMedication = {},
                onRemoveIntake = { k -> s = s.copy(intakes = s.intakes.filterNot { it.key == k }) },
                onTabletsChange = { k, d ->
                    s = s.copy(intakes = s.intakes.map {
                        if (it.key == k) it.copy(tablets = (it.tablets + d).coerceIn(MIN_TABLETS, MAX_TABLETS)) else it
                    })
                },
                onEffectChange = { k, e -> s = s.copy(intakes = s.intakes.map { if (it.key == k) it.copy(effect = e) else it }) },
                onSave = onSave,
                onDelete = onDelete,
            )
        }
    }

    private val base = AttackEditUiState(isLoading = false, startedAt = Instant.parse("2026-11-20T03:00:00Z"))
    private val withIntake = base.copy(intakes = listOf(IntakeDraft(1, 0, med, 1, Effect.UNRATED)))

    @Test
    fun FR202_痛みの強さを3択から選べる() {
        host(base)
        rule.onNodeWithTag(severityTag(Severity.MODERATE)).assertIsSelected()
        rule.onNodeWithTag(severityTag(Severity.SEVERE)).performClick()
        rule.onNodeWithTag(severityTag(Severity.SEVERE)).assertIsSelected()
    }

    @Test
    fun FR205_錠数は1未満にできず_増減できる() {
        host(withIntake)
        rule.onNodeWithTag(tabletsMinusTag(1)).assertIsNotEnabled()
        rule.onNodeWithTag(tabletsPlusTag(1)).performScrollTo().performClick()
        rule.onNodeWithTag(tabletsValueTag(1)).assertTextEquals("2錠")
        rule.onNodeWithTag(tabletsMinusTag(1)).assertIsEnabled()
    }

    @Test
    fun FR204_効果を選べる() {
        host(withIntake)
        rule.onNodeWithTag(effectTag(1, Effect.UNRATED)).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(effectTag(1, Effect.SLIGHT)).performScrollTo().performClick()
        rule.onNodeWithTag(effectTag(1, Effect.SLIGHT)).assertIsSelected()
    }

    @Test
    fun FR203_服薬を削除できる() {
        host(withIntake)
        rule.onNodeWithText("ロキソニンS").assertIsDisplayed()
        rule.onNodeWithTag(removeIntakeTag(1)).performScrollTo().performClick()
        rule.onNodeWithText("服薬の記録はありません").assertIsDisplayed()
    }

    @Test
    fun FR201_保存ボタンで保存が呼ばれる() {
        var saved = 0
        host(base, onSave = { saved++ })
        rule.onNodeWithTag(TAG_SAVE).performClick()
        assertEquals(1, saved)
    }

    @Test
    fun FR200_新規では削除ボタンが出ない() {
        host(base)
        rule.onNodeWithTag(TAG_DELETE).assertDoesNotExist()

    }

    @Test
    fun FR200_既存の記録は削除前に確認ダイアログが出る() {
        var deleted = 0
        host(base.copy(isNew = false), onDelete = { deleted++ })
        rule.onNodeWithTag(TAG_DELETE).performScrollTo().performClick()
        assertEquals(0, deleted)
        rule.onNodeWithText("この記録を削除しますか?").assertIsDisplayed()
        rule.onNodeWithTag(TAG_DELETE_CONFIRM).performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun FR203_薬選択は候補をタップで選べ_検索に一致しない名前は追加できる() {
        val picked = mutableListOf<Medication>()
        val customs = mutableListOf<String>()
        rule.setContent {
            HeadacheDiaryTheme {
                MedicationPickerContent(
                    state = MedicationPickerUiState("新", listOf(med), customName = "新薬X"),
                    onQueryChange = {},
                    onSelect = { picked += it },
                    onAddCustom = { customs += it },
                )
            }
        }
        rule.onNodeWithTag(pickerItemTag(1)).performClick()
        rule.onNodeWithTag(TAG_PICKER_CUSTOM).performClick()
        assertEquals(listOf(med), picked)
        assertEquals(listOf("新薬X"), customs)
    }

    @Test
    fun FR201_未保存の変更があると戻る前に確認が出て_破棄で戻れる() {
        var backed = 0
        rule.setContent {
            HeadacheDiaryTheme {
                AttackEditContent(
                    state = base.copy(isDirty = true), onBack = { backed++ }, onDateTimeChange = {}, onSeverityChange = {},
                    onAddMedication = {}, onRemoveIntake = {}, onTabletsChange = { _, _ -> }, onEffectChange = { _, _ -> },
                    onSave = {}, onDelete = {},
                )
            }
        }
        rule.onNodeWithTag(TAG_BACK).performClick()
        assertEquals(0, backed)
        rule.onNodeWithText("保存しないで戻りますか?").assertIsDisplayed()
        rule.onNodeWithTag(TAG_DISCARD_CONFIRM).performClick()
        assertEquals(1, backed)
    }

    @Test
    fun FR201_変更がなければ確認なしで戻れる() {
        var backed = 0
        rule.setContent {
            HeadacheDiaryTheme {
                AttackEditContent(
                    state = base, onBack = { backed++ }, onDateTimeChange = {}, onSeverityChange = {},
                    onAddMedication = {}, onRemoveIntake = {}, onTabletsChange = { _, _ -> }, onEffectChange = { _, _ -> },
                    onSave = {}, onDelete = {},
                )
            }
        }
        rule.onNodeWithTag(TAG_BACK).performClick()
        assertEquals(1, backed)
    }
}
