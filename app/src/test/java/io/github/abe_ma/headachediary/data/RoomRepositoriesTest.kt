package io.github.abe_ma.headachediary.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.github.abe_ma.headachediary.data.catalog.PRESET_MEDICATIONS
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.db.PresetSeedCallback
import io.github.abe_ma.headachediary.data.fake.FakePressureRepository
import io.github.abe_ma.headachediary.data.repo.DataStoreSettingsRepository
import io.github.abe_ma.headachediary.data.repo.RoomAttackRepository
import io.github.abe_ma.headachediary.data.repo.RoomMedicationRepository
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Intake
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.MonthCount
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.ReminderScheduler
import io.github.abe_ma.headachediary.domain.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomRepositoriesTest {
    @get:Rule val tmp = TemporaryFolder()

    private class RecordingScheduler : ReminderScheduler {
        val scheduled = mutableListOf<Pair<Long, Int>>()
        val cancelled = mutableListOf<Long>()
        override fun scheduleEffectReminder(attackId: Long, delayMinutes: Int) { scheduled += attackId to delayMinutes }
        override fun cancelEffectReminder(attackId: Long) { cancelled += attackId }
    }

    private lateinit var db: AppDatabase
    private lateinit var pressure: FakePressureRepository
    private lateinit var settings: DataStoreSettingsRepository
    private lateinit var reminders: RecordingScheduler
    private lateinit var attacks: RoomAttackRepository
    private lateinit var meds: RoomMedicationRepository
    private val zone = ZoneId.of("Asia/Tokyo")
    private val t0 = Instant.parse("2026-11-20T03:00:00Z")
    private val now = Instant.parse("2026-11-23T00:00:00Z")

    @Before
    fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        pressure = FakePressureRepository()
        settings = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { File(tmp.root, "settings.preferences_pb") },
        )
        reminders = RecordingScheduler()
        attacks = RoomAttackRepository(db, pressure, settings, reminders, { zone }, { now })
        meds = RoomMedicationRepository(db)
    }

    @After fun tearDown() = db.close()

    private suspend fun med(name: String): Medication = meds.observeAll().first().first { it.name == name }

    private fun attack(at: Instant = t0, vararg m: Medication, effect: Effect = Effect.UNRATED) = Attack(
        0, at, Severity.MODERATE, null, m.map { Intake(0, it, 2, effect) },
    )

    @Test
    fun FR_203_presets_seeded_with_fixed_ids_in_definition_order() = runBlocking {
        val all = meds.observeAll().first()
        assertEquals(PRESET_MEDICATIONS.map { it.name }, all.map { it.name })
        assertEquals((1L..PRESET_MEDICATIONS.size).toList(), all.map { it.id })
        assertEquals(22, all.size)
        assertEquals(16L, all.first { it.name == "五苓散" }.id)
        // FR-209: 漢方 (id 16-22) は包、それ以外は錠
        assertTrue(all.filter { it.id in 16..22 }.all { it.unit == io.github.abe_ma.headachediary.domain.MedicationUnit.PACKET })
        assertTrue(all.filter { it.id !in 16..22 }.all { it.unit == io.github.abe_ma.headachediary.domain.MedicationUnit.TABLET })
        assertTrue(all.all { it.isPreset && !it.isHidden })
    }

    @Test
    fun FR_206_candidates_ordered_by_usage_then_definition_order() = runBlocking {
        val maxalt = med("マクサルト"); val loxo = med("ロキソニン"); val kalo = med("カロナール")
        attacks.save(attack(t0, maxalt))
        attacks.save(attack(t0.plusSeconds(60), maxalt, loxo))
        attacks.save(attack(t0.plusSeconds(120), loxo))
        attacks.save(attack(t0.plusSeconds(180), maxalt))
        attacks.save(attack(t0.plusSeconds(240), kalo))
        val names = meds.observeCandidates().first().map { it.name }
        assertEquals(listOf("マクサルト", "ロキソニン", "カロナール", "タイレノール"), names.take(4))
    }

    @Test
    fun FR_206_custom_medication_added_once_and_listed() = runBlocking {
        val a = meds.addCustom(" 市販薬X ")
        assertEquals(a, meds.addCustom("市販薬X"))
        assertTrue(a.id >= 1000 && !a.isPreset && a.ingredient == null)
        assertTrue(meds.observeCandidates().first().contains(a))
        assertEquals(PRESET_MEDICATIONS.size + 1, meds.observeAll().first().size)
    }

    @Test
    fun FR_207_hidden_excluded_from_candidates_kept_in_all_and_in_records() = runBlocking {
        val loxo = med("ロキソニン")
        val id = attacks.save(attack(t0, loxo))
        meds.setHidden(loxo.id, true)
        assertTrue(meds.observeCandidates().first().none { it.id == loxo.id })
        assertTrue(meds.observeAll().first().first { it.id == loxo.id }.isHidden)
        assertEquals("ロキソニン", attacks.observeAttack(id).first()!!.intakes.single().medication.name)
        meds.setHidden(loxo.id, false)
        assertTrue(meds.observeCandidates().first().any { it.id == loxo.id })
    }

    @Test
    fun FR_207_hidden_flag_survives_reopen_seeding() = runBlocking {
        val loxo = med("ロキソニン")
        meds.setHidden(loxo.id, true)
        PresetSeedCallback.onOpen(db.openHelper.writableDatabase)
        assertTrue(meds.observeAll().first().first { it.id == loxo.id }.isHidden)
        assertEquals(PRESET_MEDICATIONS.size, meds.observeAll().first().size)
    }

    @Test
    fun FR_200_crud_newest_first_and_cascade_delete() = runBlocking {
        val loxo = med("ロキソニン")
        val older = attacks.save(attack(t0, loxo))
        val newer = attacks.save(attack(t0.plusSeconds(3600)))
        assertEquals(listOf(newer, older), attacks.observeAttacks().first().map { it.id })
        attacks.save(attacks.observeAttack(older).first()!!.copy(severity = Severity.SEVERE))
        assertEquals(Severity.SEVERE, attacks.observeAttack(older).first()!!.severity)
        assertEquals(2, attacks.observeAttacks().first().size)
        attacks.delete(older)
        assertNull(attacks.observeAttack(older).first())
        assertEquals(0, db.query("SELECT * FROM intake", null).use { it.count })
    }

    @Test
    fun FR_200_range_filter_is_inclusive() = runBlocking {
        attacks.save(attack(t0))
        val b = attacks.save(attack(t0.plusSeconds(86400)))
        assertEquals(listOf(b), attacks.observeAttacks(t0.plusSeconds(1)..t0.plusSeconds(86400)).first().map { it.id })
    }

    @Test
    fun FR_203_204_205_intakes_edit_remove_and_effect_update() = runBlocking {
        val id = attacks.save(attack(t0, med("ロキソニン"), med("カロナール")))
        val saved = attacks.observeAttack(id).first()!!
        assertEquals(listOf(2, 2), saved.intakes.map { it.tablets })
        attacks.save(saved.copy(intakes = listOf(saved.intakes[0].copy(effect = Effect.EFFECTIVE, tablets = 3))))
        val after = attacks.observeAttack(id).first()!!
        assertEquals(1, after.intakes.size)
        assertEquals(Effect.EFFECTIVE, after.intakes[0].effect)
        assertEquals(3, after.intakes[0].tablets)
        assertEquals(saved.intakes[0].id, after.intakes[0].id)
    }

    @Test
    fun FR_205_tablets_below_one_rejected() = runBlocking {
        val bad = attack(t0, med("ロキソニン")).let { it.copy(intakes = listOf(it.intakes[0].copy(tablets = 0))) }
        assertTrue(runCatching { attacks.save(bad) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, attacks.observeAttacks().first().size)
    }

    @Test
    fun FR_104_save_links_pressure_and_missing_is_null() = runBlocking {
        pressure.points.value = listOf(PressurePoint(t0, 1008.0))
        val ok = attacks.save(attack(t0.plusSeconds(1200)))
        assertEquals(1008.0, attacks.observeAttack(ok).first()!!.pressureHpa!!, 0.0)
        val none = attacks.save(attack(t0.plusSeconds(5 * 3600)))
        assertNull(attacks.observeAttack(none).first()!!.pressureHpa)
    }

    @Test
    fun FR_104_editing_time_recomputes_pressure_but_other_edits_keep_it() = runBlocking {
        pressure.points.value = listOf(PressurePoint(t0, 1008.0))
        val id = attacks.save(attack(t0))
        val saved = attacks.observeAttack(id).first()!!
        pressure.points.value = emptyList()
        attacks.save(saved.copy(severity = Severity.MILD))
        assertEquals(1008.0, attacks.observeAttack(id).first()!!.pressureHpa!!, 0.0)
        attacks.save(saved.copy(startedAt = t0.plusSeconds(86400)))
        assertNull(attacks.observeAttack(id).first()!!.pressureHpa)
    }

    @Test
    fun FR_500_reminder_scheduled_when_unrated_cancelled_when_rated_or_deleted() = runBlocking {
        settings.update { it.copy(effectReminderEnabled = true) }
        val id = attacks.save(attack(t0, med("ロキソニン")))
        assertEquals(listOf(id to 120), reminders.scheduled)
        val saved = attacks.observeAttack(id).first()!!
        attacks.save(saved.copy(intakes = saved.intakes.map { it.copy(effect = Effect.EFFECTIVE) }))
        assertEquals(listOf(id), reminders.cancelled)
        attacks.delete(id)
        assertEquals(id, reminders.cancelled.last())
    }

    @Test
    fun FR_500_reminder_respects_settings_delay_and_disabled() = runBlocking {
        settings.update { it.copy(effectReminderEnabled = true, effectReminderDelayMinutes = 45) }
        val id = attacks.save(attack(t0, med("ロキソニン")))
        assertEquals(listOf(id to 45), reminders.scheduled)
        settings.update { it.copy(effectReminderEnabled = false) }
        attacks.save(attacks.observeAttack(id).first()!!)
        assertEquals(1, reminders.scheduled.size)
        assertEquals(listOf(id), reminders.cancelled)
    }

    @Test
    fun FR_300_monthly_counts_in_local_timezone_with_zero_months() = runBlocking {
        attacks.save(attack(Instant.parse("2026-11-01T00:00:00Z")))
        attacks.save(attack(Instant.parse("2026-11-10T00:00:00Z")))
        attacks.save(attack(Instant.parse("2026-09-30T15:30:00Z"))) // JST 10/1
        attacks.save(attack(Instant.parse("2026-08-31T14:59:00Z"))) // JST 8/31 → 範囲外
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
    fun FR_100_settings_defaults_roundtrip_and_clear_prefecture() = runBlocking {
        val d = settings.observe().first()
        assertNull(d.prefectureCode); assertEquals(120, d.effectReminderDelayMinutes); assertFalse(d.effectReminderEnabled)
        assertEquals(false, d.dropNotifyEnabled); assertEquals(6.0, d.dropThresholdHpa, 0.0)
        assertEquals(LocalTime.of(20, 0), d.dropNotifyTime)
        settings.update { it.copy(prefectureCode = "13", dropNotifyEnabled = true, dropThresholdHpa = 4.5, dropNotifyTime = LocalTime.of(7, 30)) }
        val s = settings.observe().first()
        assertEquals("13", s.prefectureCode); assertTrue(s.dropNotifyEnabled)
        assertEquals(4.5, s.dropThresholdHpa, 0.0); assertEquals(LocalTime.of(7, 30), s.dropNotifyTime)
        settings.update { it.copy(prefectureCode = null) }
        assertNull(settings.observe().first().prefectureCode)
    }

    @Test
    fun FR_203_preset_rename_is_synced_on_open_keeping_id_hidden_and_records() = runBlocking {
        val naproxen = med("ナイキサン")
        assertEquals(7L, naproxen.id)
        assertEquals("ナプロキセン", naproxen.ingredient)
        // 旧バージョンの DB を模擬: 旧名に戻し、非表示 + 記録あり + 自由入力あり
        val custom = meds.addCustom("自由薬")
        val id = attacks.save(attack(t0, naproxen))
        meds.setHidden(naproxen.id, true)
        val w = db.openHelper.writableDatabase
        w.execSQL("UPDATE medication SET name = 'ナプロキセン（ナイキサン）', ingredient = '旧成分' WHERE id = 7") // 旧表記 (T-03b 追記2) の端末を模擬
        w.execSQL("UPDATE medication SET ingredient = '改変' WHERE id = ?", arrayOf<Any?>(custom.id))
        PresetSeedCallback.onOpen(w)
        val after = meds.observeAll().first()
        val p = after.first { it.id == 7L }
        assertEquals("ナイキサン", p.name)
        assertEquals("ナプロキセン", p.ingredient)
        assertTrue(p.isHidden) // 非表示は維持
        assertEquals(PRESET_MEDICATIONS.size + 1, after.size)
        assertEquals("改変", after.first { it.id == custom.id }.ingredient) // 自由入力は対象外
        val rec = attacks.observeAttack(id).first()!!.intakes.single().medication
        assertEquals(7L, rec.id) // 記録の medicationId は不変
        assertEquals("ナイキサン", rec.name)
    }

    @Test
    fun FR_203_preset_rename_skipped_if_custom_has_same_name_without_crash() = runBlocking {
        val w = db.openHelper.writableDatabase
        w.execSQL("UPDATE medication SET name = 'ナプロキセン（ナイキサン）' WHERE id = 7")
        meds.addCustom("ナイキサン") // 新名を自由入力が先に占有
        PresetSeedCallback.onOpen(w) // 例外にならない
        assertEquals("ナプロキセン（ナイキサン）", meds.observeAll().first().first { it.id == 7L }.name)
    }

    @Test
    fun FR_207_addCustom_of_hidden_medication_unhides_it() = runBlocking {
        val loxo = med("ロキソニン")
        meds.setHidden(loxo.id, true)
        val again = meds.addCustom("ロキソニン")
        assertEquals(loxo.id, again.id)
        assertFalse(again.isHidden)
        assertTrue(meds.observeCandidates().first().any { it.id == loxo.id })
    }

    @Test
    fun FR_203_preset_sync_does_not_rewrite_unchanged_rows() = runBlocking {
        val w = db.openHelper.writableDatabase
        w.execSQL("CREATE TEMP TABLE upd_log (n INTEGER)")
        w.execSQL("CREATE TEMP TRIGGER t AFTER UPDATE ON main.medication BEGIN INSERT INTO upd_log VALUES (1); END")
        PresetSeedCallback.onOpen(w)
        val n = w.query("SELECT COUNT(*) FROM upd_log").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, n)
    }

    @Test
    fun FR_209_custom_medication_is_tablet() = runBlocking {
        assertEquals(io.github.abe_ma.headachediary.domain.MedicationUnit.TABLET, meds.addCustom("自由薬").unit)
    }

    private val day = java.time.Duration.ofHours(24)

    @Test
    fun FR_404_save_resolves_delta_as_p_t_minus_p_24h_before_within_one_hour() = runBlocking {
        pressure.points.value = listOf(PressurePoint(t0, 1008.0), PressurePoint(t0.minus(day).plusSeconds(1800), 1012.0))
        val id = attacks.save(attack(t0))
        val a = attacks.observeAttack(id).first()!!
        assertEquals(1008.0, a.pressureHpa!!, 0.0)
        assertEquals(-4.0, a.pressureDeltaHpa!!, 1e-9) // 24h前 ±1h 以内の最寄り (30分ずれ) を使う
        // 境界: ちょうど 1 時間ずれは取れる、1 秒超えると取れない
        pressure.points.value = listOf(PressurePoint(t0, 1008.0), PressurePoint(t0.minus(day).minusSeconds(3600), 1010.0))
        val b = attacks.save(attack(t0))
        assertEquals(1008.0 - 1010.0, attacks.observeAttack(b).first()!!.pressureDeltaHpa!!, 1e-9)
        pressure.points.value = listOf(PressurePoint(t0, 1008.0), PressurePoint(t0.minus(day).minusSeconds(3602), 1010.0))
        val c = attacks.save(attack(t0))
        assertNull(attacks.observeAttack(c).first()!!.pressureDeltaHpa)
    }

    @Test
    fun FR_404_delta_is_null_when_either_pressure_is_missing() = runBlocking {
        pressure.points.value = listOf(PressurePoint(t0.minus(day), 1010.0)) // 発作時の気圧なし
        assertNull(attacks.observeAttack(attacks.save(attack(t0))).first()!!.pressureDeltaHpa)
        pressure.points.value = listOf(PressurePoint(t0, 1008.0)) // 24h前なし
        assertNull(attacks.observeAttack(attacks.save(attack(t0))).first()!!.pressureDeltaHpa)
    }

    @Test
    fun FR_404_edit_keeps_stored_delta_and_time_change_recomputes() = runBlocking {
        pressure.points.value = listOf(PressurePoint(t0, 1008.0), PressurePoint(t0.minus(day), 1010.0))
        val id = attacks.save(attack(t0))
        val saved = attacks.observeAttack(id).first()!!
        pressure.points.value = emptyList()
        attacks.save(saved.copy(severity = Severity.MILD))
        assertEquals(-2.0, attacks.observeAttack(id).first()!!.pressureDeltaHpa!!, 1e-9) // 書き出し時などに再計算しない
        attacks.save(saved.copy(startedAt = t0.plusSeconds(86400)))
        assertNull(attacks.observeAttack(id).first()!!.pressureDeltaHpa)
    }

    @Test
    fun FR_404_backfill_fills_delta_when_day_before_data_arrives_later_without_overwriting() = runBlocking {
        val dao = db.attackDao()
        val base = Instant.parse("2026-11-22T00:00:00Z")
        // A: 気圧あり・差なし (後から 24h 前が取れた)、B: 気圧も差もなし、C: 両方あり (上書きしない)、D: 31日より古い
        val a = dao.insertAttack(io.github.abe_ma.headachediary.data.db.AttackEntity(startedAt = base.toEpochMilli(), severity = "MILD", pressureHpa = 1000.0))
        val b = dao.insertAttack(io.github.abe_ma.headachediary.data.db.AttackEntity(startedAt = base.plusSeconds(3600).toEpochMilli(), severity = "MILD", pressureHpa = null))
        val c = dao.insertAttack(io.github.abe_ma.headachediary.data.db.AttackEntity(startedAt = base.toEpochMilli(), severity = "MILD", pressureHpa = 999.0, pressureDeltaHpa = 5.0))
        val d = dao.insertAttack(io.github.abe_ma.headachediary.data.db.AttackEntity(startedAt = base.minus(java.time.Duration.ofDays(40)).toEpochMilli(), severity = "MILD", pressureHpa = null))
        pressure.points.value = listOf(
            PressurePoint(base.minus(day), 1011.0), PressurePoint(base, 1000.0), PressurePoint(base.plusSeconds(3600), 1002.0),
            PressurePoint(base.plusSeconds(3600).minus(day), 1005.0),
        )
        pressure.lastFetched.value = now
        val job = io.github.abe_ma.headachediary.data.work.PressureRefreshJob(pressure, db) { Instant.parse("2026-11-23T00:00:00Z") }
        job.run()
        suspend fun get(id: Long) = dao.observeAttack(id).first()!!.attack
        assertEquals(1000.0, get(a).pressureHpa!!, 0.0) // 既存の気圧は維持
        assertEquals(1000.0 - 1011.0, get(a).pressureDeltaHpa!!, 1e-9)
        assertEquals(1002.0, get(b).pressureHpa!!, 0.0)
        assertEquals(1002.0 - 1005.0, get(b).pressureDeltaHpa!!, 1e-9)
        assertEquals(999.0, get(c).pressureHpa!!, 0.0); assertEquals(5.0, get(c).pressureDeltaHpa!!, 0.0) // 上書きしない
        assertNull(get(d).pressureHpa); assertNull(get(d).pressureDeltaHpa) // 31 日より前は対象外
    }

    private fun rawAttack(startedAt: Instant, hpa: Double?, delta: Double? = null) = io.github.abe_ma.headachediary.data.db.AttackEntity(
        startedAt = startedAt.toEpochMilli(), severity = "MILD", pressureHpa = hpa, pressureDeltaHpa = delta,
    )

    @Test
    fun FR_404_backfill_does_not_fill_delta_when_stored_pressure_differs_from_current_region() = runBlocking {
        val dao = db.attackDao()
        val base = Instant.parse("2026-11-22T00:00:00Z")
        // 旧地域で保存した 1008.0。現在地域の p(t) は 1000.0 → 0.1 hPa を超えて不一致 → 差は埋めない
        val id = dao.insertAttack(rawAttack(base, 1008.0))
        pressure.points.value = listOf(PressurePoint(base, 1000.0), PressurePoint(base.minus(day), 1011.0))
        pressure.lastFetched.value = now
        io.github.abe_ma.headachediary.data.work.PressureRefreshJob(pressure, db) { Instant.parse("2026-11-23T00:00:00Z") }.run()
        val a = dao.observeAttack(id).first()!!.attack
        assertEquals(1008.0, a.pressureHpa!!, 0.0) // 保存済みの気圧は変えない
        assertNull(a.pressureDeltaHpa)
    }

    @Test
    fun FR_404_backfill_tolerance_is_0_1_hpa_inclusive() = runBlocking {
        val dao = db.attackDao()
        val base = Instant.parse("2026-11-22T00:00:00Z")
        val within = dao.insertAttack(rawAttack(base, 1000.1)) // 差 0.1 → 一致とみなす
        val beyond = dao.insertAttack(rawAttack(base, 1000.2)) // 差 0.2 → 不一致
        pressure.points.value = listOf(PressurePoint(base, 1000.0), PressurePoint(base.minus(day), 1011.0))
        pressure.lastFetched.value = now
        io.github.abe_ma.headachediary.data.work.PressureRefreshJob(pressure, db) { Instant.parse("2026-11-23T00:00:00Z") }.run()
        assertEquals(1000.1 - 1011.0, dao.observeAttack(within).first()!!.attack.pressureDeltaHpa!!, 1e-9) // 保存済み値と 24h 前の差
        assertNull(dao.observeAttack(beyond).first()!!.attack.pressureDeltaHpa)
    }

    @Test
    fun FR_404_backfill_right_after_migration_fills_only_attacks_that_match_current_data() = runBlocking {
        // v2→v3 直後: 既存の全発作は pressureDeltaHpa=null。地域変更なし (一致) は埋まり、データなし/不一致は null のまま。
        val dao = db.attackDao()
        val base = Instant.parse("2026-11-22T00:00:00Z")
        val same = dao.insertAttack(rawAttack(base, 1000.0))
        val noData = dao.insertAttack(rawAttack(base.minus(java.time.Duration.ofDays(3)), 1005.0)) // 現在データに無い時刻
        pressure.points.value = listOf(PressurePoint(base, 1000.0), PressurePoint(base.minus(day), 1004.0))
        pressure.lastFetched.value = now
        io.github.abe_ma.headachediary.data.work.PressureRefreshJob(pressure, db) { Instant.parse("2026-11-23T00:00:00Z") }.run()
        assertEquals(-4.0, dao.observeAttack(same).first()!!.attack.pressureDeltaHpa!!, 1e-9)
        assertNull(dao.observeAttack(noData).first()!!.attack.pressureDeltaHpa)
    }

    @Test
    fun FR_404_editing_an_old_attack_after_region_change_does_not_save_a_mixed_region_delta() = runBlocking {
        // 旧地域で保存した 1008.0 (差なし)。地域変更後の現在地域は p(t)=1000.0、p(t−24h)=1011.0。
        val id = db.attackDao().insertAttack(
            io.github.abe_ma.headachediary.data.db.AttackEntity(startedAt = t0.toEpochMilli(), severity = "MILD", pressureHpa = 1008.0),
        )
        pressure.points.value = listOf(PressurePoint(t0, 1000.0), PressurePoint(t0.minus(day), 1011.0))
        val saved = attacks.observeAttack(id).first()!!
        attacks.save(saved.copy(severity = Severity.SEVERE)) // 編集して保存 (日時は同じ)
        val after = attacks.observeAttack(id).first()!!
        assertEquals(1008.0, after.pressureHpa!!, 0.0) // 気圧は保存済みのまま
        assertNull(after.pressureDeltaHpa) // 混在した差 (1008−1011) を保存しない
    }

    @Test
    fun FR_404_editing_fills_delta_when_stored_pressure_matches_current_region_within_tolerance() = runBlocking {
        val id = db.attackDao().insertAttack(
            io.github.abe_ma.headachediary.data.db.AttackEntity(startedAt = t0.toEpochMilli(), severity = "MILD", pressureHpa = 1000.1),
        )
        pressure.points.value = listOf(PressurePoint(t0, 1000.0), PressurePoint(t0.minus(day), 1011.0))
        attacks.save(attacks.observeAttack(id).first()!!.copy(severity = Severity.SEVERE))
        assertEquals(1000.1 - 1011.0, attacks.observeAttack(id).first()!!.pressureDeltaHpa!!, 1e-9)
    }

    @Test
    fun FR_404_changing_the_time_re_resolves_both_values_in_the_current_region() = runBlocking {
        val id = db.attackDao().insertAttack(
            io.github.abe_ma.headachediary.data.db.AttackEntity(startedAt = t0.toEpochMilli(), severity = "MILD", pressureHpa = 1008.0),
        )
        val t1 = t0.plusSeconds(86400)
        pressure.points.value = listOf(PressurePoint(t1, 995.0), PressurePoint(t1.minus(day), 1000.0))
        attacks.save(attacks.observeAttack(id).first()!!.copy(startedAt = t1))
        val a = attacks.observeAttack(id).first()!!
        assertEquals(995.0, a.pressureHpa!!, 0.0) // 旧地域の値は捨てる
        assertEquals(-5.0, a.pressureDeltaHpa!!, 1e-9)
    }

    @Test
    fun FR_404_new_attack_with_explicit_pressure_that_mismatches_current_region_gets_no_delta() = runBlocking {
        pressure.points.value = listOf(PressurePoint(t0, 1000.0), PressurePoint(t0.minus(day), 1011.0))
        val id = attacks.save(Attack(0, t0, Severity.MILD, 1008.0, emptyList()))
        assertNull(attacks.observeAttack(id).first()!!.pressureDeltaHpa)
    }
}
