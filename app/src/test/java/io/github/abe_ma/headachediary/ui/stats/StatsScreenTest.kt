package io.github.abe_ma.headachediary.ui.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.MonthCount
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.ui.theme.HeadacheDiaryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34]) // SDK 36 の Robolectric は Java 21 を要求するため (既存テストと同じ)
class StatsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val now = Instant.parse("2026-11-23T03:00:00Z")
    private val counts = listOf(
        MonthCount(YearMonth.of(2026, 9), 2), MonthCount(YearMonth.of(2026, 10), 0), MonthCount(YearMonth.of(2026, 11), 3),
    )
    private val points = (0..48).map { PressurePoint(now.minus(Duration.ofHours(24)).plus(Duration.ofHours(it.toLong())), 1000.0 + it % 7) }
    private val base = StatsUiState(
        isLoading = false, monthlyCounts = counts, windowStart = now.minus(Duration.ofDays(7)),
        windowEnd = now.plus(Duration.ofDays(3)), now = now, hasPrefecture = true,
    )

    private fun show(
        state: StatsUiState,
        onSpan: (MonthSpan) -> Unit = {}, onPeriod: (PressurePeriod) -> Unit = {}, onSettings: () -> Unit = {},
    ) = rule.setContent {
        HeadacheDiaryTheme { StatsContent(state, onSpan, onPeriod, onSettings, ZoneId.of("Asia/Tokyo")) }
    }

    @Test
    fun FR100_地域未設定なら案内を出し設定へ誘導する() {
        var opened = 0
        show(base.copy(hasPrefecture = false), onSettings = { opened++ })
        rule.onNodeWithTag(TAG_REGION_GUIDANCE).assertIsDisplayed()
        rule.onNodeWithText("地域を設定すると気圧が表示されます。").assertIsDisplayed()
        rule.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun FR100_地域設定済みなら案内は出ない() {
        show(base)
        rule.onNodeWithTag(TAG_REGION_GUIDANCE).assertDoesNotExist()
    }

    @Test
    fun FR300_月別グラフを表示し_月数を切り替えられる() {
        var span: MonthSpan? = null
        show(base, onSpan = { span = it })
        rule.onNodeWithTag(TAG_MONTHLY_CHART).assertIsDisplayed()
        rule.onNodeWithTag(monthSpanTag(MonthSpan.SIX)).assertIsSelected()
        rule.onNodeWithTag(monthSpanTag(MonthSpan.TWELVE)).performClick()
        assertEquals(MonthSpan.TWELVE, span)
    }

    @Test
    fun FR301_気圧グラフの期間を切り替えられる() {
        var p: PressurePeriod? = null
        show(base.copy(pressure = points), onPeriod = { p = it })
        rule.onNodeWithTag(periodTag(PressurePeriod.WEEK)).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(periodTag(PressurePeriod.MONTH)).performClick()
        assertEquals(PressurePeriod.MONTH, p)
    }

    @Test
    fun FR103_気圧と発作を重ねて表示し_件数と凡例を出す() {
        val attacks = listOf(Attack(1, now.minus(Duration.ofHours(10)), Severity.MILD, null, emptyList()))
        show(base.copy(pressure = points, attacks = attacks))
        rule.onNodeWithTag(TAG_PRESSURE_CHART).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(TAG_ATTACK_COUNT).performScrollTo()
        rule.onNodeWithText("この期間の発作: 1件").assertIsDisplayed()
        rule.onNodeWithText("気圧(予報)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("発作").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun FR103_気圧データが無いときはグラフの代わりに案内を出す() {
        show(base.copy(pressure = emptyList()))
        rule.onNodeWithTag(TAG_PRESSURE_CHART).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PRESSURE_EMPTY).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun FR105_最終取得時刻を表示する() {
        show(base.copy(lastFetchedAt = now))
        rule.onNodeWithTag(TAG_STATS_LAST_FETCHED).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("気圧の最終取得: ", substring = true).assertIsDisplayed()
    }

    @Test
    fun FR106_気圧グラフの近くにOpenMeteoの出典リンクを表示する() {
        show(base.copy(pressure = points))
        rule.onNodeWithTag(TAG_OPEN_METEO).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Weather data by Open-Meteo.com").assertIsDisplayed()
    }
}
