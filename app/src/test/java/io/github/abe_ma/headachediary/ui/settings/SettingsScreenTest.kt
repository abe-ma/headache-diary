package io.github.abe_ma.headachediary.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abe_ma.headachediary.data.catalog.StaticPrefectureRepository
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.Settings
import io.github.abe_ma.headachediary.ui.theme.HeadacheDiaryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // SDK 36 の Robolectric は Java 21 を要求するため (既存テストと同じ)
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val prefectures = StaticPrefectureRepository().all()

    private fun show(
        settings: Settings = Settings(prefectureCode = null),
        actions: SettingsActions = SettingsActions(),
        fetched: Instant? = null,
        denied: Boolean = false,
        missing: Boolean = false,
    ) = rule.setContent {
        HeadacheDiaryTheme {
            SettingsContent(
                SettingsUiState(isLoading = false, settings = settings, prefectures = prefectures, lastFetchedAt = fetched),
                actions, permissionDenied = denied, permissionMissing = missing,
            )
        }
    }

    @Test
    fun FR100_地域が未設定なら案内を出し_選ぶと保存が呼ばれる() {
        var selected: String? = "unset"
        show(actions = SettingsActions(onSelectPrefecture = { selected = it }))
        rule.onNodeWithText("地域を設定すると気圧が表示されます", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_REGION_ROW).performClick()
        rule.onNodeWithTag(regionItemTag("01")).performClick() // 先頭の北海道 (長いリストの途中は遅延生成のため)
        assertEquals("01", selected)
    }

    @Test
    fun FR100_設定済みの地域名を表示し_未設定に戻せる() {
        var selected: String? = "unset"
        show(Settings(prefectureCode = "27"), SettingsActions(onSelectPrefecture = { selected = it }))
        rule.onNodeWithText("大阪府").assertIsDisplayed()
        rule.onNodeWithTag(TAG_REGION_ROW).performClick()
        rule.onNodeWithTag(TAG_REGION_CLEAR).performClick()
        assertEquals(null, selected)
    }

    @Test
    fun FR105_最終取得時刻を表示する() {
        show(fetched = Instant.parse("2026-11-23T11:05:00Z"))
        rule.onNodeWithTag(TAG_LAST_FETCHED).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("最終取得: ", substring = true).assertIsDisplayed()
    }

    @Test
    fun FR105_未取得ならその旨を表示する() {
        show()
        rule.onNodeWithText("気圧はまだ取得していません").assertIsDisplayed()
    }

    @Test
    fun FR505_通知は既定オフで詳細設定は出ない() {
        show(Settings(prefectureCode = null)) // 既定: 両方オフ
        rule.onNodeWithTag(delayChipTag(120)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_THRESHOLD_VALUE).assertDoesNotExist()
    }

    @Test
    fun FR503_スイッチ操作がコールバックされる() {
        val toggles = mutableListOf<String>()
        show(actions = SettingsActions(
            onEffectReminderToggle = { toggles += "effect:$it" },
            onDropNotifyToggle = { toggles += "drop:$it" },
        ))
        rule.onNodeWithTag(TAG_EFFECT_SWITCH).performClick()
        rule.onNodeWithTag(TAG_DROP_SWITCH).performScrollTo().performClick()
        assertEquals(listOf("effect:true", "drop:true"), toggles)
    }

    @Test
    fun FR500_効果リマインドの遅延を選べる() {
        var minutes = 0
        show(Settings(null, effectReminderEnabled = true, effectReminderDelayMinutes = 120), SettingsActions(onEffectDelayChange = { minutes = it }))
        rule.onNodeWithTag(delayChipTag(120)).assertIsSelected()
        rule.onNodeWithTag(delayChipTag(60)).performScrollTo().performClick()
        assertEquals(60, minutes)
    }

    @Test
    fun FR501_しきい値と通知時刻を表示し_増減できる() {
        val steps = mutableListOf<Int>()
        show(
            Settings(null, dropNotifyEnabled = true, dropThresholdHpa = 6.0, dropNotifyTime = LocalTime.of(20, 0)),
            SettingsActions(onThresholdStep = { steps += it }),
        )
        rule.onNodeWithTag(TAG_THRESHOLD_VALUE).performScrollTo().assertTextEquals("6 hPa")
        rule.onNodeWithTag(TAG_THRESHOLD_PLUS).performScrollTo().performClick()
        rule.onNodeWithTag(TAG_THRESHOLD_MINUS).performClick()
        assertEquals(listOf(1, -1), steps)
        rule.onNodeWithTag(TAG_DROP_TIME).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("20:00").assertIsDisplayed()
    }

    @Test
    fun FR501_しきい値の下限では減らせない() {
        show(Settings(null, dropNotifyEnabled = true, dropThresholdHpa = MIN_DROP_THRESHOLD_HPA))
        rule.onNodeWithTag(TAG_THRESHOLD_MINUS).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun FR503_権限が拒否されたら理由を案内する() {
        show(denied = true)
        rule.onNodeWithTag(TAG_PERMISSION_DENIED).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun FR503_有効なのに権限がないときは端末の通知設定への導線を出す() {
        var opened = 0
        show(
            Settings(null, effectReminderEnabled = true), SettingsActions(onOpenNotificationSettings = { opened++ }), missing = true,
        )
        rule.onNodeWithTag(TAG_OPEN_NOTIFICATION_SETTINGS).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun FR207_書き出し_薬の管理_このアプリについてへの導線がある() {
        val opened = mutableListOf<String>()
        show(actions = SettingsActions(
            onOpenMedications = { opened += "meds" }, onOpenExport = { opened += "export" }, onOpenAbout = { opened += "about" },
        ))
        rule.onNodeWithTag(TAG_MEDICATIONS_ROW).performScrollTo().performClick()
        rule.onNodeWithTag(TAG_EXPORT_ROW).performScrollTo().performClick()
        rule.onNodeWithTag(TAG_ABOUT_ROW).performScrollTo().performClick()
        assertEquals(listOf("meds", "export", "about"), opened)
    }

    @Test
    fun FR207_薬の管理は非表示の薬も一覧に出し_スイッチで切り替えられる() {
        val a = Medication(1, "ロキソニンS", "ロキソプロフェン", isPreset = true, isHidden = false)
        val b = Medication(2, "イブA", null, isPreset = true, isHidden = true)
        val changes = mutableListOf<Pair<Long, Boolean>>()
        rule.setContent {
            HeadacheDiaryTheme {
                MedicationManageContent(listOf(a, b), onBack = {}, onVisibleChange = { id, v -> changes += id to v })
            }
        }
        rule.onNodeWithText("イブA").assertIsDisplayed()
        rule.onNodeWithText("非表示").assertIsDisplayed()
        rule.onNodeWithTag(medicationSwitchTag(1)).performClick()
        rule.onNodeWithTag(medicationSwitchTag(2)).performClick()
        assertEquals(listOf(1L to false, 2L to true), changes)
    }

    @Test
    fun FR106_このアプリについてにOpenMeteoの帰属と外部送信の説明を出す() {
        rule.setContent { HeadacheDiaryTheme { AboutScreen(onBack = {}, versionName = "1.2.3") } }
        rule.onNodeWithText("Weather data by Open-Meteo.com").assertIsDisplayed()
        rule.onNodeWithText("外部には送信しません", substring = true).assertIsDisplayed()
        rule.onNodeWithText("バージョン 1.2.3").performScrollTo().assertIsDisplayed()
    }
}
