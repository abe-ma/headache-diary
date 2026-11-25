package io.github.abe_ma.headachediary.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.abe_ma.headachediary.data.backup.BackupFormat
import io.github.abe_ma.headachediary.data.backup.RoomBackupRepository
import io.github.abe_ma.headachediary.data.catalog.StaticPrefectureRepository
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.db.AttackEntity
import io.github.abe_ma.headachediary.data.db.IntakeEntity
import io.github.abe_ma.headachediary.data.repo.DataStoreSettingsRepository
import io.github.abe_ma.headachediary.data.repo.NotifyAwareSettingsRepository
import io.github.abe_ma.headachediary.domain.BackupException
import io.github.abe_ma.headachediary.domain.BackupInspection
import io.github.abe_ma.headachediary.domain.InvalidReason
import io.github.abe_ma.headachediary.domain.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private inner class Env(name: String) {
        val db: AppDatabase = AppDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        val base = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { File(tmp.root, "$name.preferences_pb") },
        )
        val regionChanges = mutableListOf<String?>()
        val settings = NotifyAwareSettingsRepository(base) { o, n -> if (o.prefectureCode != n.prefectureCode) regionChanges += n.prefectureCode }
        val repo = RoomBackupRepository(db, settings, StaticPrefectureRepository(), "1.2.3", { Instant.parse("2026-11-25T01:02:03Z") })
        suspend fun med(name: String) = db.medicationDao().getAll().first { it.name == name }
        suspend fun attack(at: Long, sev: String, hpa: Double?, delta: Double?, vararg intakes: Triple<String, Int, String>): Long {
            val id = db.attackDao().insertAttack(AttackEntity(0, at, sev, hpa, delta))
            intakes.forEach { (n, t, e) -> db.attackDao().insertIntake(IntakeEntity(0, id, med(n).id, t, e)) }
            return id
        }
        suspend fun addCustom(name: String) = io.github.abe_ma.headachediary.data.repo.RoomMedicationRepository(db).addCustom(name)
        suspend fun snapshot(): List<String> = db.attackDao().getAllWithIntakes().sortedBy { it.attack.startedAt }.map { a ->
            "${a.attack.startedAt}|${a.attack.severity}|${a.attack.pressureHpa}|${a.attack.pressureDeltaHpa}|" +
                a.intakes.sortedBy { it.intake.id }.joinToString(",") { "${it.medication.name}x${it.intake.tablets}:${it.intake.effect}" }
        }
        suspend fun counts() = Triple(db.attackDao().getAllWithIntakes().size, db.medicationDao().getAll().size, base.observe().first())
        fun close() = db.close()
    }

    private val envs = mutableListOf<Env>()
    private fun env(name: String) = Env(name).also { envs += it }
    @After fun tearDown() = envs.forEach { it.close() }

    private suspend fun Env.written(): ByteArray = ByteArrayOutputStream().also { repo.write(it) }.toByteArray()
    private fun bytes(b: ByteArray): InputStream = ByteArrayInputStream(b)
    private fun text(s: String): InputStream = ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))

    private suspend fun source(): Env {
        val a = env("src")
        a.addCustom("自由薬")
        a.attack(1_700_000_000_000, "SEVERE", 1008.5, -3.4, Triple("ロキソニン", 2, "EFFECTIVE"), Triple("自由薬", 1, "UNRATED"))
        a.attack(1_700_100_000_000, "MILD", null, null)
        a.attack(1_700_200_000_000, "MODERATE", 1000.0, 1.2, Triple("五苓散", 1, "SLIGHT"))
        a.db.medicationDao().setHidden(a.med("ロキソニン").id, true)
        a.db.medicationDao().setHidden(a.med("自由薬").id, true)
        a.settings.update { it.copy(prefectureCode = "13", effectReminderEnabled = true, effectReminderDelayMinutes = 45, dropNotifyEnabled = true, dropThresholdHpa = 4.5, dropNotifyTime = LocalTime.of(7, 30)) }
        return a
    }

    // ---- FR-601/602: 作成と往復 ----
    @Test
    fun FR_601_roundtrip_write_then_restore_into_empty_db_reproduces_records_medications_and_settings() = runBlocking {
        val a = source(); val b = env("dst")
        val r = b.repo.restore(bytes(a.written()))
        assertEquals(a.snapshot(), b.snapshot()) // 日時・強さ・気圧・気圧差・服薬 (薬名/数量/効果) が同一
        assertEquals(3, r.addedAttacks); assertEquals(0, r.skippedDuplicates); assertEquals(1, r.addedMedications)
        assertTrue(r.settingsApplied); assertTrue(r.notificationsTurnedOff)
        val meds = b.db.medicationDao().getAll().associateBy { it.name }
        assertFalse(meds.getValue("自由薬").isPreset); assertTrue(meds.getValue("自由薬").isHidden) // 自由入力の薬と非表示
        assertTrue(meds.getValue("ロキソニン").isHidden); assertFalse(meds.getValue("カロナール").isHidden)
        val s = b.base.observe().first()
        assertEquals("13", s.prefectureCode); assertEquals(45, s.effectReminderDelayMinutes); assertEquals(4.5, s.dropThresholdHpa, 0.0)
        assertEquals(LocalTime.of(7, 30), s.dropNotifyTime)
        assertFalse(s.effectReminderEnabled); assertFalse(s.dropNotifyEnabled) // FR-605: 通知は常にオフ
    }

    @Test
    fun FR_602_file_has_header_fields_and_no_pressure_series_or_fetch_time() = runBlocking {
        val a = source()
        val o = JSONObject(String(a.written(), Charsets.UTF_8))
        assertEquals(BackupFormat.NAME, o.getString("format")); assertEquals(1, o.getInt("formatVersion"))
        assertEquals("1.2.3", o.getString("appVersion")); assertEquals(Instant.parse("2026-11-25T01:02:03Z").toEpochMilli(), o.getLong("exportedAt"))
        assertEquals(setOf("format", "formatVersion", "exportedAt", "appVersion", "attacks", "customMedications", "medicationVisibility", "settings"), o.keys().asSequence().toSet())
        assertEquals(3, o.getJSONArray("attacks").length())
        // 薬は端末ごとの id ではなく presetId / customName で参照
        val first = o.getJSONArray("attacks").getJSONObject(0).getJSONArray("intakes")
        assertTrue(first.getJSONObject(0).getJSONObject("medication").has("presetId"))
        assertEquals("自由薬", first.getJSONObject(1).getJSONObject("medication").getString("customName"))
    }

    @Test
    fun FR_601_write_to_empty_db_is_a_valid_backup_with_zero_attacks() = runBlocking {
        val e = env("empty")
        val v = e.repo.inspect(bytes(e.written())) as BackupInspection.Valid
        assertEquals(0, v.attackCount); assertNull(v.periodStart); assertNull(v.periodEnd); assertTrue(v.includesSettings)
    }

    // ---- FR-603: inspect (DB 不変・概要) ----
    @Test
    fun FR_603_inspect_reports_counts_period_new_and_duplicates_without_changing_anything() = runBlocking {
        val a = source(); val b = env("dst")
        b.attack(1_700_000_000_000, "SEVERE", null, null, Triple("ロキソニン", 2, "NONE")) // 自由薬を含まないので別の発作 (服薬集合が違う)
        b.attack(1_700_100_000_000, "MILD", null, null) // a の 2 件目と同一 → 重複
        val before = b.counts(); val snap = b.snapshot()
        val v = b.repo.inspect(bytes(a.written())) as BackupInspection.Valid
        assertEquals(3, v.attackCount); assertEquals(1, v.duplicateCount); assertEquals(2, v.newAttackCount)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), v.periodStart); assertEquals(Instant.ofEpochMilli(1_700_200_000_000), v.periodEnd)
        assertEquals(1, v.customMedicationCount); assertTrue(v.includesSettings)
        assertEquals("1.2.3", v.appVersion); assertEquals(Instant.parse("2026-11-25T01:02:03Z"), v.exportedAt)
        assertEquals(before, b.counts()); assertEquals(snap, b.snapshot()) // 何も変わらない
    }

    // ---- FR-604: 統合 ----
    @Test
    fun FR_604_restoring_twice_does_not_add_records_again() = runBlocking {
        val a = source(); val b = env("dst"); val file = a.written()
        assertEquals(3, b.repo.restore(bytes(file)).addedAttacks)
        val snap = b.snapshot(); val medCount = b.db.medicationDao().getAll().size
        val second = b.repo.restore(bytes(file))
        assertEquals(0, second.addedAttacks); assertEquals(3, second.skippedDuplicates); assertEquals(0, second.addedMedications)
        assertEquals(snap, b.snapshot()); assertEquals(medCount, b.db.medicationDao().getAll().size)
    }

    @Test
    fun FR_604_partial_overlap_adds_only_new_and_keeps_existing_even_if_effect_differs() = runBlocking {
        val a = source(); val b = env("dst")
        b.attack(1_700_200_000_000, "MODERATE", 995.0, null, Triple("五苓散", 1, "NONE")) // a の 3 件目と同キー (効果・気圧は別)
        val r = b.repo.restore(bytes(a.written()))
        assertEquals(2, r.addedAttacks); assertEquals(1, r.skippedDuplicates)
        val kept = b.snapshot().first { it.startsWith("1700200000000") }
        assertTrue(kept.contains("995.0") && kept.contains("NONE")) // 既存を残す
    }

    @Test
    fun FR_604_duplicates_inside_the_same_file_are_collapsed_and_intake_order_does_not_matter() = runBlocking {
        val b = env("dst")
        val a1 = """{"startedAt":1000,"severity":"MILD","intakes":[{"medication":{"presetId":6},"tablets":1,"effect":"UNRATED"},{"medication":{"presetId":1},"tablets":2,"effect":"UNRATED"}]}"""
        val a2 = """{"startedAt":1000,"severity":"MILD","intakes":[{"medication":{"presetId":1},"tablets":2,"effect":"EFFECTIVE"},{"medication":{"presetId":6},"tablets":1,"effect":"UNRATED"}]}"""
        val r = b.repo.restore(text(backup("$a1,$a2")))
        assertEquals(1, r.addedAttacks); assertEquals(1, r.skippedDuplicates)
    }

    // ---- FR-605: 設定 ----
    @Test
    fun FR_605_region_change_triggers_hook_once_and_same_region_does_not() = runBlocking {
        val a = source(); val b = env("dst"); val file = a.written()
        b.repo.restore(bytes(file)); assertEquals(listOf<String?>("13"), b.regionChanges)
        b.repo.restore(bytes(file)); assertEquals(listOf<String?>("13"), b.regionChanges) // 同じ地域では呼ばれない
    }

    @Test
    fun FR_605_file_without_settings_leaves_current_settings_and_reports_not_applied() = runBlocking {
        val b = env("dst")
        b.settings.update { it.copy(prefectureCode = "27", effectReminderEnabled = true) }
        val r = b.repo.restore(text(backup("")))
        assertFalse(r.settingsApplied); assertFalse(r.notificationsTurnedOff)
        val s = b.base.observe().first(); assertEquals("27", s.prefectureCode); assertTrue(s.effectReminderEnabled)
        val v = b.repo.inspect(text(backup(""))) as BackupInspection.Valid
        assertFalse(v.includesSettings)
    }

    @Test
    fun FR_605_pressure_values_are_restored_as_is_without_recalculation_and_out_of_range_settings_fall_back() = runBlocking {
        val b = env("dst")
        val a = """{"startedAt":5000,"severity":"MILD","pressureHpa":1234.5,"pressureDeltaHpa":-9.9}"""
        b.repo.restore(text(backup(a, settings = """{"prefectureCode":"99","effectReminderDelayMinutes":-5,"dropThresholdHpa":-1.0,"dropNotifyTime":"25:99"}""")))
        assertEquals(listOf("5000|MILD|1234.5|-9.9|"), b.snapshot())
        val s = b.base.observe().first()
        assertNull(s.prefectureCode); assertEquals(120, s.effectReminderDelayMinutes); assertEquals(6.0, s.dropThresholdHpa, 0.0); assertEquals(LocalTime.of(20, 0), s.dropNotifyTime)
    }

    // ---- FR-606: 不正・非対応 ----
    private suspend fun assertInvalid(input: () -> InputStream, reason: InvalidReason) {
        val b = env("inv" + System.nanoTime())
        try { assertInvalidOn(b, input, reason) } finally { b.close(); envs.remove(b) } // 数が多いのでその場で閉じる (テストJVMのヒープ節約)
    }

    private suspend fun assertInvalidOn(b: Env, input: () -> InputStream, reason: InvalidReason) {
        b.attack(1, "MILD", null, null)
        val before = b.counts(); val snap = b.snapshot()
        assertEquals(BackupInspection.Invalid(reason), b.repo.inspect(input()))
        val e = runCatching { b.repo.restore(input()) }.exceptionOrNull()
        assertTrue("expected BackupException($reason) but $e", e is BackupException && e.reason == reason)
        assertEquals(before, b.counts()); assertEquals(snap, b.snapshot()) // DB・設定は不変
    }

    @Test
    fun FR_606_files_that_are_not_backups_are_rejected_without_changes() = runBlocking {
        assertInvalid({ text("hello world") }, InvalidReason.NOT_BACKUP)
        assertInvalid({ bytes(ByteArray(2048) { (it * 31).toByte() }) }, InvalidReason.NOT_BACKUP)
        assertInvalid({ text("") }, InvalidReason.NOT_BACKUP)
        assertInvalid({ text("[1,2,3]") }, InvalidReason.NOT_BACKUP)
        assertInvalid({ text("""{"a":1}""") }, InvalidReason.NOT_BACKUP)
        assertInvalid({ text("""{"format":"other-app","formatVersion":1}""") }, InvalidReason.NOT_BACKUP)
    }

    @Test
    fun FR_606_corrupt_or_truncated_files_are_rejected_without_changes() = runBlocking {
        val a = source(); val full = a.written()
        assertInvalid({ bytes(full.copyOf(full.size / 2)) }, InvalidReason.CORRUPT)
        assertInvalid({ text("""{"format":"headache-diary-backup","formatVersion":1,"attacks":[{"startedAt":"x"}]}""") }, InvalidReason.CORRUPT)
        assertInvalid({ text("""{"format":"headache-diary-backup"}""") }, InvalidReason.CORRUPT) // バージョンなし
        assertInvalid({ text(backup("""{"startedAt":1,"severity":"HUGE"}""")) }, InvalidReason.CORRUPT)
        assertInvalid({ text(backup("""{"startedAt":1,"severity":"MILD","intakes":[{"medication":{"presetId":1},"tablets":0,"effect":"UNRATED"}]}""")) }, InvalidReason.CORRUPT)
        assertInvalid({ text(backup("""{"startedAt":1,"severity":"MILD","intakes":[{"medication":{"presetId":1},"tablets":1,"effect":"???"}]}""")) }, InvalidReason.CORRUPT)
    }

    @Test
    fun FR_606_newer_format_version_is_rejected_as_unsupported_even_if_structure_changed() = runBlocking {
        assertInvalid({ text("""{"format":"headache-diary-backup","formatVersion":2,"attacks":{"new":"layout"}}""") }, InvalidReason.UNSUPPORTED_VERSION)
    }

    @Test
    fun FR_606_file_over_20MB_is_rejected_as_too_large_without_reading_everything_into_memory() = runBlocking {
        val limit = BackupFormat.MAX_BYTES
        var produced = 0L
        val huge = object : InputStream() {
            override fun read(): Int = if (produced++ < limit + 1L) ' '.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (produced >= limit + 1L) return -1
                val n = minOf(len.toLong(), limit + 1L - produced).toInt()
                java.util.Arrays.fill(b, off, off + n, ' '.code.toByte()); produced += n; return n
            }
        }
        val b = env("big")
        assertEquals(BackupInspection.Invalid(InvalidReason.TOO_LARGE), b.repo.inspect(huge))
        assertTrue("読みすぎ: $produced", produced <= limit + 64 * 1024L + 1)
    }

    @Test
    fun FR_606_unknown_fields_are_ignored() = runBlocking {
        val b = env("dst")
        val a = """{"startedAt":7,"severity":"SEVERE","futureField":{"x":[1,2]},"intakes":[{"medication":{"presetId":1,"extra":1},"tablets":1,"effect":"NONE","note":"n"}]}"""
        val r = b.repo.restore(text(backup(a, root = ""","unknownRoot":[1,{"a":null}]""")))
        assertEquals(1, r.addedAttacks)
    }

    @Test
    fun FR_606_unresolvable_medication_references_are_added_as_custom_medications() = runBlocking {
        val b = env("dst")
        fun intake(ref: String) = """{"startedAt":${ref.hashCode()},"severity":"MILD","intakes":[{"medication":$ref,"tablets":1,"effect":"UNRATED"}]}"""
        val atks = listOf(
            intake("""{"presetId":9999,"name":"将来のプリセット"}"""), // 解決不能 + 表示名 → 自由入力
            intake("""{"presetId":9998}"""), // 解決不能・名前なし → 不明な薬
            intake("""{"customName":"ロキソニン"}"""), // 既存プリセットと同名 → 既存を再利用
            intake("""{"presetId":6}"""), // 既存プリセット
            intake("""{"customName":"新しい自由薬"}"""),
        ).joinToString(",")
        val before = b.db.medicationDao().getAll().size
        val r = b.repo.restore(text(backup(atks)))
        assertEquals(5, r.addedAttacks); assertEquals(3, r.addedMedications)
        val names = b.db.medicationDao().getAll().map { it.name }
        assertTrue(names.containsAll(listOf("将来のプリセット", "不明な薬", "新しい自由薬")))
        assertEquals(before + 3, names.size)
        assertEquals(2, b.snapshot().count { it.contains("ロキソニン") }) // presetId:6 と customName:ロキソニン は同じ薬
    }

    // ---- 性能 (数千〜1 万件) ----
    @Test
    fun FR_607_ten_thousand_attacks_restore_and_inspect_in_reasonable_time_and_roundtrip() = runBlocking {
        val sb = StringBuilder()
        for (i in 0 until 10_000) {
            if (i > 0) sb.append(',')
            sb.append("""{"startedAt":${1_600_000_000_000 + i * 3_600_000L},"severity":"MODERATE","pressureHpa":1000.0,"intakes":[{"medication":{"presetId":${1 + i % 15}},"tablets":${1 + i % 3},"effect":"UNRATED"},{"medication":{"customName":"薬${i % 50}"},"tablets":1,"effect":"NONE"}]}""")
        }
        val file = backup(sb.toString())
        val b = env("dst")
        var t = System.nanoTime()
        val v = b.repo.inspect(text(file)) as BackupInspection.Valid
        val inspectMs = (System.nanoTime() - t) / 1_000_000
        assertEquals(10_000, v.newAttackCount); assertEquals(50, v.customMedicationCount)
        t = System.nanoTime()
        val r = b.repo.restore(text(file))
        val restoreMs = (System.nanoTime() - t) / 1_000_000
        assertEquals(10_000, r.addedAttacks)
        assertEquals(10_000, b.db.attackDao().getAllWithIntakes().size)
        t = System.nanoTime()
        val out = b.written()
        val writeMs = (System.nanoTime() - t) / 1_000_000
        println("backup perf (10k attacks): inspect=${inspectMs}ms restore=${restoreMs}ms write=${writeMs}ms size=${out.size / 1024}KB")
        assertTrue("restore too slow: ${restoreMs}ms", restoreMs < 60_000)
        assertTrue("inspect too slow: ${inspectMs}ms", inspectMs < 60_000)
        assertTrue("write too slow: ${writeMs}ms", writeMs < 60_000)
        val again = b.repo.restore(bytes(out))
        assertEquals(0, again.addedAttacks); assertEquals(10_000, again.skippedDuplicates) // 往復しても同一
    }

    /** 最小の有効なバックアップ JSON (attacks は JSON 配列の中身)。 */
    private fun backup(attacks: String, root: String = "", settings: String? = null): String =
        """{"format":"headache-diary-backup","formatVersion":1,"exportedAt":1,"appVersion":"t","attacks":[$attacks]$root""" +
            (settings?.let { ""","settings":$it""" } ?: "") + "}"
}
