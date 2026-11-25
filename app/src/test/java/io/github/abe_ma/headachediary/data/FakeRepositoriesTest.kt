package io.github.abe_ma.headachediary.data

import app.cash.turbine.test
import io.github.abe_ma.headachediary.data.catalog.PREFECTURES
import io.github.abe_ma.headachediary.data.catalog.PRESET_MEDICATIONS
import io.github.abe_ma.headachediary.data.fake.FakeRepositories
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Intake
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.MonthCount
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.Severity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class FakeRepositoriesTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val t0 = Instant.parse("2026-11-20T03:00:00Z")

    private fun attack(at: Instant = t0, vararg meds: Medication, id: Long = 0) = Attack(
        id = id, startedAt = at, severity = Severity.MODERATE, pressureHpa = null,
        intakes = meds.map { Intake(0, it, 1, Effect.UNRATED) },
    )

    @Test
    fun FR_206_candidates_ordered_by_usage_then_preset_order() = runTest {
        val r = FakeRepositories()
        val all = r.medications.observeAll().first()
        val loxo = all.first { it.name == "ロキソニン" }
        val kalonal = all.first { it.name == "カロナール" }
        val maxalt = all.first { it.name == "マクサルト" }
        // 初期状態: 使用なし → プリセット定義順
        assertEquals(PRESET_MEDICATIONS.map { it.name }, r.medications.observeCandidates().first().map { it.name })
        r.attacks.save(attack(t0, maxalt))
        r.attacks.save(attack(t0.plusSeconds(60), maxalt, loxo))
        r.attacks.save(attack(t0.plusSeconds(120), loxo, maxalt))
        r.attacks.save(attack(t0.plusSeconds(180), kalonal))
        val names = r.medications.observeCandidates().first().map { it.name }
        // マクサルト3回 > ロキソニン2回 > カロナール1回 > 以降は定義順
        assertEquals(listOf("マクサルト", "ロキソニン", "カロナール"), names.take(3))
        assertEquals("タイレノール", names[3])
    }

    @Test
    fun FR_206_custom_medication_appears_in_candidates_and_same_name_returns_existing() = runTest {
        val r = FakeRepositories()
        val a = r.medications.addCustom("  市販薬X ")
        val b = r.medications.addCustom("市販薬X")
        assertEquals(a, b)
        assertEquals("市販薬X", a.name)
        assertNull(a.ingredient)
        assertTrue(!a.isPreset)
        assertTrue(r.medications.observeCandidates().first().contains(a))
        // 既存プリセット名は既存を返す
        assertEquals("ロキソニン", r.medications.addCustom("ロキソニン").name)
        assertEquals(PRESET_MEDICATIONS.size + 1, r.medications.observeAll().first().size)
    }

    @Test
    fun FR_207_hidden_excluded_from_candidates_but_kept_in_all_and_in_past_records() = runTest {
        val r = FakeRepositories()
        val loxo = r.medications.observeAll().first().first { it.name == "ロキソニン" }
        val id = r.attacks.save(attack(t0, loxo))
        r.medications.setHidden(loxo.id, true)
        assertTrue(r.medications.observeCandidates().first().none { it.id == loxo.id })
        val inAll = r.medications.observeAll().first().first { it.id == loxo.id }
        assertTrue(inAll.isHidden)
        // 過去記録の薬は表示できる (記録は薬を保持)
        assertEquals("ロキソニン", r.attacks.observeAttack(id).first()!!.intakes.single().medication.name)
        // 再表示
        r.medications.setHidden(loxo.id, false)
        assertTrue(r.medications.observeCandidates().first().any { it.id == loxo.id })
    }

    @Test
    fun FR_207_readding_hidden_custom_medication_unhides_it() = runTest {
        val r = FakeRepositories()
        val a = r.medications.addCustom("X")
        r.medications.setHidden(a.id, true)
        val again = r.medications.addCustom("X")
        assertEquals(a.id, again.id)
        assertTrue(r.medications.observeCandidates().first().any { it.id == a.id })
    }

    @Test
    fun FR_200_save_assigns_ids_lists_newest_first_and_delete() = runTest {
        val r = FakeRepositories()
        val older = r.attacks.save(attack(t0))
        val newer = r.attacks.save(attack(t0.plusSeconds(3600)))
        assertEquals(listOf(newer, older), r.attacks.observeAttacks().first().map { it.id })
        // 編集 (同じ id) は置換
        r.attacks.save(r.attacks.observeAttack(older).first()!!.copy(severity = Severity.SEVERE))
        assertEquals(Severity.SEVERE, r.attacks.observeAttack(older).first()!!.severity)
        assertEquals(2, r.attacks.observeAttacks().first().size)
        r.attacks.delete(older)
        assertNull(r.attacks.observeAttack(older).first())
        assertEquals(listOf(newer), r.attacks.observeAttacks().first().map { it.id })
    }

    @Test
    fun FR_200_observeAttacks_filters_by_range() = runTest {
        val r = FakeRepositories()
        r.attacks.save(attack(t0))
        val inRange = r.attacks.save(attack(t0.plusSeconds(86400)))
        val got = r.attacks.observeAttacks(t0.plusSeconds(3600)..t0.plusSeconds(2 * 86400)).first()
        assertEquals(listOf(inRange), got.map { it.id })
    }

    @Test
    fun FR_203_204_intake_ids_assigned_and_effect_can_be_updated_later() = runTest {
        val r = FakeRepositories()
        val med = r.medications.observeAll().first().first()
        val id = r.attacks.save(attack(t0, med, med))
        val saved = r.attacks.observeAttack(id).first()!!
        assertEquals(2, saved.intakes.map { it.id }.distinct().size)
        assertTrue(saved.intakes.all { it.id != 0L && it.effect == Effect.UNRATED })
        r.attacks.save(saved.copy(intakes = saved.intakes.map { it.copy(effect = Effect.EFFECTIVE) }))
        assertTrue(r.attacks.observeAttack(id).first()!!.intakes.all { it.effect == Effect.EFFECTIVE })
    }

    @Test
    fun FR_104_save_links_nearest_pressure_within_one_hour_else_null() = runTest {
        val pressure = io.github.abe_ma.headachediary.data.fake.FakePressureRepository(
            listOf(PressurePoint(t0, 1008.0), PressurePoint(t0.plusSeconds(3600), 1006.0)),
        )
        val r = FakeRepositories(pressure = pressure)
        val near = r.attacks.save(attack(t0.plusSeconds(2400))) // 40分後 → +60分の点が最寄り
        assertEquals(1006.0, r.attacks.observeAttack(near).first()!!.pressureHpa!!, 0.0)
        val far = r.attacks.save(attack(t0.plusSeconds(3 * 3600)))
        assertNull(r.attacks.observeAttack(far).first()!!.pressureHpa) // 欠損でも保存できる
    }

    @Test
    fun FR_103_observeSeries_filters_range() = runTest {
        val p = io.github.abe_ma.headachediary.data.fake.FakePressureRepository(
            listOf(PressurePoint(t0, 1.0), PressurePoint(t0.plusSeconds(3600), 2.0), PressurePoint(t0.plusSeconds(7200), 3.0)),
        )
        assertEquals(listOf(2.0, 3.0), p.observeSeries(t0.plusSeconds(3600)..t0.plusSeconds(7200)).first().map { it.hpa })
    }

    @Test
    fun FR_300_monthly_counts_oldest_first_with_zero_months() = runTest {
        val now = Instant.parse("2026-11-23T00:00:00Z")
        val attacks = io.github.abe_ma.headachediary.data.fake.FakeAttackRepository(zone = zone, now = { now })
        attacks.save(attack(Instant.parse("2026-11-01T00:00:00Z")))
        attacks.save(attack(Instant.parse("2026-11-10T00:00:00Z")))
        attacks.save(attack(Instant.parse("2026-09-30T15:30:00Z"))) // JST では 10/1
        attacks.save(attack(Instant.parse("2026-08-01T00:00:00Z"))) // 範囲外 (3か月)
        attacks.observeMonthlyCounts(3).test {
            assertEquals(
                listOf(
                    MonthCount(YearMonth.of(2026, 9), 0),
                    MonthCount(YearMonth.of(2026, 10), 1),
                    MonthCount(YearMonth.of(2026, 11), 2),
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR_100_prefectures_47_unique_codes_with_valid_coordinates() {
        assertEquals(47, PREFECTURES.size)
        assertEquals((1..47).map { "%02d".format(it) }, PREFECTURES.map { it.code })
        assertEquals(47, PREFECTURES.map { it.name }.toSet().size)
        // 日本国内の概ねの範囲
        assertTrue(PREFECTURES.all { it.lat in 24.0..46.0 && it.lon in 122.0..146.0 })
        assertEquals("北海道", PREFECTURES.first().name)
        assertEquals("沖縄県", PREFECTURES.last().name)
        assertEquals(PREFECTURES, FakeRepositories().prefectures.all())
    }

    @Test
    fun FR_203_presets_are_defined_in_order_with_stable_ids_and_no_prophylactics() {
        val meds = io.github.abe_ma.headachediary.data.catalog.presetMedications()
        assertEquals((1L..meds.size).toList(), meds.map { it.id })
        assertTrue(meds.all { it.isPreset && !it.isHidden && it.ingredient != null })
        val names = meds.map { it.name }
        assertEquals(names.size, names.toSet().size)
        listOf("ロメリジン", "デパケン", "インデラル", "エムガルティ").forEach { assertTrue(it !in names) }
    }

    @Test
    fun FR_500_settings_defaults_and_update() = runTest {
        val r = FakeRepositories()
        r.settings.observe().test {
            val s = awaitItem()
            assertNull(s.prefectureCode)
            assertEquals(120, s.effectReminderDelayMinutes)
            assertEquals(6.0, s.dropThresholdHpa, 0.0)
            r.settings.update { it.copy(prefectureCode = "13") }
            assertEquals("13", awaitItem().prefectureCode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun FR_105_refresh_returns_result_without_throwing() = runTest {
        val p = io.github.abe_ma.headachediary.data.fake.FakePressureRepository()
        assertEquals(io.github.abe_ma.headachediary.domain.RefreshResult.Success, p.refresh())
        p.nextRefreshResult = io.github.abe_ma.headachediary.domain.RefreshResult.Failure(
            io.github.abe_ma.headachediary.domain.FailureReason.NETWORK,
        )
        assertTrue(p.refresh() is io.github.abe_ma.headachediary.domain.RefreshResult.Failure)
        assertNotNull(p.observeLastFetchedAt())
    }

    @Test
    fun FR_203_kampo_presets_appended_after_existing_ids_unchanged() {
        val meds = io.github.abe_ma.headachediary.data.catalog.presetMedications()
        assertEquals(22, meds.size)
        assertEquals(1L, meds.first { it.name == "カロナール" }.id)
        assertEquals(15L, meds.first { it.name == "プリンペラン" }.id)
        assertEquals(
            listOf("五苓散", "呉茱萸湯", "葛根湯", "川芎茶調散", "桂枝人参湯", "釣藤散", "半夏白朮天麻湯"),
            meds.drop(15).map { it.name },
        )
        assertEquals((16L..22L).toList(), meds.drop(15).map { it.id })
        assertTrue(meds.drop(15).all { it.ingredient == "漢方" })
    }

    @Test
    fun FR_104_fake_recomputes_pressure_when_time_changes_like_room() = runTest {
        val pressure = io.github.abe_ma.headachediary.data.fake.FakePressureRepository(listOf(PressurePoint(t0, 1008.0)))
        val r = FakeRepositories(pressure = pressure)
        val id = r.attacks.save(attack(t0))
        val saved = r.attacks.observeAttack(id).first()!!
        assertEquals(1008.0, saved.pressureHpa!!, 0.0)
        r.attacks.save(saved.copy(startedAt = t0.plusSeconds(10 * 3600)))
        assertNull(r.attacks.observeAttack(id).first()!!.pressureHpa)
    }

    @Test
    fun FR_207_fake_addCustom_unhides_hidden_medication() = runTest {
        val r = FakeRepositories()
        val loxo = r.medications.observeAll().first().first { it.name == "ロキソニン" }
        r.medications.setHidden(loxo.id, true)
        assertTrue(!r.medications.addCustom("ロキソニン").isHidden)
        assertTrue(r.medications.observeCandidates().first().any { it.id == loxo.id })
    }

    @Test
    fun FR_404_fake_resolves_delta_as_p_t_minus_p_24h_before_and_null_if_either_missing() = runTest {
        val day = java.time.Duration.ofHours(24)
        val pressure = io.github.abe_ma.headachediary.data.fake.FakePressureRepository(
            listOf(PressurePoint(t0, 1008.0), PressurePoint(t0.minus(day), 1012.5), PressurePoint(t0.plusSeconds(3 * 3600), 1000.0)),
        )
        val r = FakeRepositories(pressure = pressure)
        val a = r.attacks.save(attack(t0))
        assertEquals(-4.5, r.attacks.observeAttack(a).first()!!.pressureDeltaHpa!!, 1e-9) // マイナス=低下
        // 24 時間前が ±1h 以内で取れる (30分ずれ) → 最寄りを使う
        val b = r.attacks.save(attack(t0.plusSeconds(1800)))
        assertEquals(1008.0 - 1012.5, r.attacks.observeAttack(b).first()!!.pressureDeltaHpa!!, 1e-9)
        // 24 時間前のデータなし → null (気圧は取れていても)
        val c = r.attacks.save(attack(t0.plusSeconds(3 * 3600)))
        assertEquals(1000.0, r.attacks.observeAttack(c).first()!!.pressureHpa!!, 0.0)
        assertNull(r.attacks.observeAttack(c).first()!!.pressureDeltaHpa)
        // 発作時の気圧が無い → null
        val d = r.attacks.save(attack(t0.plusSeconds(40 * 3600)))
        assertNull(r.attacks.observeAttack(d).first()!!.pressureDeltaHpa)
    }

    @Test
    fun FR_404_fake_keeps_delta_on_edit_and_recomputes_when_time_changes() = runTest {
        val day = java.time.Duration.ofHours(24)
        val pressure = io.github.abe_ma.headachediary.data.fake.FakePressureRepository(
            listOf(PressurePoint(t0, 1008.0), PressurePoint(t0.minus(day), 1010.0)),
        )
        val r = FakeRepositories(pressure = pressure)
        val id = r.attacks.save(attack(t0))
        val saved = r.attacks.observeAttack(id).first()!!
        pressure.points.value = emptyList() // 後でデータが変わっても保存値を維持 (書き出し時に再計算しない)
        r.attacks.save(saved.copy(severity = Severity.MILD))
        assertEquals(-2.0, r.attacks.observeAttack(id).first()!!.pressureDeltaHpa!!, 1e-9)
        r.attacks.save(saved.copy(startedAt = t0.plusSeconds(10 * 3600))) // 日時変更 → 再解決 (データなし → null)
        assertNull(r.attacks.observeAttack(id).first()!!.pressureDeltaHpa)
    }

    @Test
    fun FR_404_fake_does_not_save_a_mixed_region_delta_when_editing_after_region_change() = runTest {
        val day = java.time.Duration.ofHours(24)
        val pressure = io.github.abe_ma.headachediary.data.fake.FakePressureRepository(
            listOf(PressurePoint(t0, 1000.0), PressurePoint(t0.minus(day), 1011.0)),
        )
        val r = FakeRepositories(pressure = pressure)
        val id = r.attacks.save(Attack(0, t0, Severity.MILD, 1008.0, emptyList())) // 旧地域で保存済みの気圧 1008.0
        assertNull(r.attacks.observeAttack(id).first()!!.pressureDeltaHpa)
        r.attacks.save(r.attacks.observeAttack(id).first()!!.copy(severity = Severity.SEVERE))
        assertNull(r.attacks.observeAttack(id).first()!!.pressureDeltaHpa)
    }

    @Test
    fun FR_608_fake_addCustom_truncates_names_like_room() = runTest {
        val r = FakeRepositories()
        val m = r.medications.addCustom("あ".repeat(150))
        assertEquals(100, m.name.length)
        assertEquals(m, r.medications.addCustom("あ".repeat(101)))
    }
}
