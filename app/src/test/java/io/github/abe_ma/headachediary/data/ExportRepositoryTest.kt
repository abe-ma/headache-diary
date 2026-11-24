package io.github.abe_ma.headachediary.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.export.FileExportRepository
import io.github.abe_ma.headachediary.data.export.formatDelta
import io.github.abe_ma.headachediary.data.export.PdfOutput
import io.github.abe_ma.headachediary.data.fake.FakePressureRepository
import io.github.abe_ma.headachediary.data.repo.DataStoreSettingsRepository
import io.github.abe_ma.headachediary.domain.ReminderScheduler
import io.github.abe_ma.headachediary.data.repo.RoomAttackRepository
import io.github.abe_ma.headachediary.data.repo.RoomMedicationRepository
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Intake
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** JVM テスト用: PdfDocument (native 依存で JVM では動かない) の代わりに、ページを Canvas に描くだけ。 */
class CanvasPdfOutput : PdfOutput {
    override fun startPage(width: Int, height: Int, number: Int) = android.graphics.Canvas()
    override fun finishPage() = Unit
    override fun writeTo(out: java.io.OutputStream) { out.write("%PDF-test".toByteArray()) }
    override fun close() = Unit
}

@RunWith(RobolectricTestRunner::class)
// レイアウト/描画コードを JVM で走らせるため LEGACY グラフィックス (Canvas/Paint の shadow) を使う。
// 実際の PdfDocument への書き出しと見た目は実機未確認。
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34])
class ExportRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var attacks: RoomAttackRepository
    private lateinit var meds: RoomMedicationRepository
    private lateinit var pressure: FakePressureRepository
    private lateinit var export: FileExportRepository
    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = Instant.parse("2026-11-23T03:00:00Z")
    private val t0 = Instant.parse("2026-11-20T03:05:00Z") // JST 12:05

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = AppDatabase.inMemory(ctx)
        pressure = FakePressureRepository()
        val settings = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { File(tmp.root, "s.preferences_pb") },
        )
        attacks = RoomAttackRepository(db, pressure, settings, object : ReminderScheduler {
            override fun scheduleEffectReminder(attackId: Long, delayMinutes: Int) = Unit
            override fun cancelEffectReminder(attackId: Long) = Unit
        }, { zone }, { now })
        meds = RoomMedicationRepository(db)
        export = FileExportRepository(ctx, attacks, pressure, { zone }, { now }, pdfOutput = { CanvasPdfOutput() })
        File(ctx.cacheDir, "exports").deleteRecursively()
        // FileProvider は authority ごとにパス設定を static にキャッシュする。テストごとに cacheDir が変わるので消す。
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
    }

    @After fun tearDown() = db.close()

    private suspend fun save(at: Instant, sev: Severity = Severity.MODERATE, hpa: Double? = null, vararg i: Triple<String, Int, Effect>) {
        val intakes = i.map { (name, n, e) -> Intake(0, meds.observeAll().first().firstOrNull { it.name == name } ?: meds.addCustom(name), n, e) }
        attacks.save(Attack(0, at, sev, hpa, intakes))
    }

    private fun bytes(uri: android.net.Uri): ByteArray {
        // FileProvider の Uri → cache/exports/<name>
        val f = File(ctx.cacheDir, "exports/" + uri.lastPathSegment)
        return f.readBytes()
    }

    private val all = Instant.EPOCH..Instant.parse("2027-01-01T00:00:00Z")

    @Test
    fun FR_400_csv_has_utf8_bom_crlf_header_and_oldest_first_rows() = runBlocking {
        save(t0.plusSeconds(86400), Severity.SEVERE, 1004.26, Triple("ロキソニン", 2, Effect.EFFECTIVE), Triple("カロナール", 1, Effect.UNRATED))
        save(t0, Severity.MILD, null)
        val uri = export.exportCsv(all)
        val b = bytes(uri)
        assertEquals(listOf(0xEF, 0xBB, 0xBF), b.take(3).map { it.toInt() and 0xFF })
        val text = String(b, Charsets.UTF_8).removePrefix("\uFEFF")
        val lines = text.split("\r\n")
        assertEquals("発作日時,痛みの強さ,服薬,記録時の気圧(hPa),前日との気圧差(hPa)", lines[0])
        assertEquals("2026-11-20 12:05,軽い,,,", lines[1]) // 古い順・服薬なし・気圧欠損は空
        assertEquals("2026-11-21 12:05,強い,ロキソニン×2錠 (効いた) / カロナール×1錠 (未評価),1004.3,", lines[2])
        assertEquals("# 気圧データ: Weather data by Open-Meteo.com (https://open-meteo.com/)", lines[3]) // FR-403
        assertEquals("", lines[4])
        assertFalse(text.replace("\r\n", "").contains("\n"))
        assertTrue(uri.toString().startsWith("content://${ctx.packageName}.fileprovider/exports/"))
    }

    @Test
    fun FR_400_csv_escapes_commas_quotes_newlines_rfc4180() = runBlocking {
        save(t0, Severity.MILD, null, Triple("A,\"B\"\nC", 1, Effect.NONE))
        val text = String(bytes(export.exportCsv(all)), Charsets.UTF_8)
        assertTrue(text.contains("\"A,\"\"B\"\"\nC×1錠 (効かなかった)\""))
    }

    @Test
    fun FR_400_csv_neutralizes_spreadsheet_formulas_in_medication_names() = runBlocking {
        listOf("=1+1", "+cmd", "-2", "@SUM(A1)").forEach { save(t0, Severity.MILD, null, Triple(it, 1, Effect.UNRATED)) }
        val text = String(bytes(export.exportCsv(all)), Charsets.UTF_8)
        listOf("'=1+1×1錠", "'+cmd×1錠", "'-2×1錠", "'@SUM(A1)×1錠").forEach { assertTrue(it, text.contains(it)) }
        assertFalse(text.contains(",=1+1"))
    }

    @Test
    fun FR_400_guardFormula_also_covers_tab_and_cr() {
        assertEquals("'\tx", io.github.abe_ma.headachediary.data.export.CsvExport.guardFormula("\tx"))
        assertEquals("'\rx", io.github.abe_ma.headachediary.data.export.CsvExport.guardFormula("\rx"))
        assertEquals("abc", io.github.abe_ma.headachediary.data.export.CsvExport.guardFormula("abc"))
    }

    @Test
    fun FR_400_csv_empty_range_returns_header_only() = runBlocking {
        save(t0, Severity.MILD, null)
        val text = String(bytes(export.exportCsv(t0.plusSeconds(86400)..t0.plusSeconds(2 * 86400))), Charsets.UTF_8)
        assertEquals("\uFEFF発作日時,痛みの強さ,服薬,記録時の気圧(hPa),前日との気圧差(hPa)\r\n", text)
    }

    @Test
    fun FR_400_csv_range_is_inclusive_and_filters() = runBlocking {
        save(t0); save(t0.plusSeconds(3600)); save(t0.plusSeconds(7200))
        val text = String(bytes(export.exportCsv(t0.plusSeconds(3600)..t0.plusSeconds(7200))), Charsets.UTF_8)
        assertEquals(3, text.trim().split("\r\n").size) // header + 2
    }

    @Test
    fun FR_402_file_name_uses_local_dates_and_old_exports_are_deleted() = runBlocking {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stale = File(dir, "old.csv").apply { writeText("x"); setLastModified(now.minusSeconds(25 * 3600).toEpochMilli()) }
        val fresh = File(dir, "fresh.csv").apply { writeText("x"); setLastModified(now.minusSeconds(3600).toEpochMilli()) }
        save(t0)
        val uri = export.exportCsv(Instant.parse("2026-11-19T15:00:00Z")..Instant.parse("2026-11-22T14:59:59Z"))
        assertEquals("headache-diary_20261120-20261122.csv", uri.lastPathSegment)
        assertFalse(stale.exists()); assertTrue(fresh.exists())
    }

    @Test
    fun FR_401_pdf_single_page_with_pressure_chart_and_footer() = runBlocking {
        pressure.points.value = (0 until 72).map { PressurePoint(t0.minusSeconds(36 * 3600).plusSeconds(it * 3600L), 1010.0 + it % 7) }
        // 気圧の地域は未設定のため series は空にならないよう Fake を使用
        save(t0, Severity.SEVERE, 1011.0, Triple("ロキソニン", 1, Effect.SLIGHT))
        val uri = export.exportPdf(all)
        val f = File(ctx.cacheDir, "exports/" + uri.lastPathSegment)
        assertTrue(f.exists() && f.length() > 0)
        assertTrue(uri.lastPathSegment!!.endsWith(".pdf"))
        assertEquals(1, export.lastPdfPageCount)
    }

    @Test
    fun FR_401_pdf_paginates_long_lists() = runBlocking {
        repeat(120) { save(t0.plusSeconds(it * 3600L), Severity.MILD, 1000.0, Triple("ロキソニン", 1, Effect.EFFECTIVE), Triple("カロナール", 2, Effect.NONE)) }
        export.exportPdf(all)
        assertTrue("pages=${export.lastPdfPageCount}", export.lastPdfPageCount >= 3)
    }

    @Test
    fun FR_401_pdf_with_no_attacks_and_no_pressure_still_generates() = runBlocking {
        val uri = export.exportPdf(all)
        assertTrue(File(ctx.cacheDir, "exports/" + uri.lastPathSegment).length() > 0)
        assertEquals(1, export.lastPdfPageCount)
    }

    @Test
    fun FR_401_pdf_handles_many_months_and_very_long_medication_names() = runBlocking {
        repeat(30) { save(Instant.parse("2024-01-15T03:00:00Z").plusSeconds(it * 31L * 86400), Severity.MODERATE, null) }
        save(t0, Severity.MILD, null, Triple("あ".repeat(300), 1, Effect.UNRATED))
        export.exportPdf(all)
        assertTrue(export.lastPdfPageCount >= 1)
    }

    @Test
    fun FR_209_kampo_is_shown_with_packet_unit() = runBlocking {
        save(t0, Severity.MILD, null, Triple("五苓散", 1, Effect.EFFECTIVE))
        val text = String(bytes(export.exportCsv(all)), Charsets.UTF_8)
        assertTrue(text.contains("五苓散×1包 (効いた)"))
    }

    @Test
    fun FR_403_csv_has_no_attribution_line_when_all_pressure_missing() = runBlocking {
        save(t0, Severity.MILD, null)
        val text = String(bytes(export.exportCsv(all)), Charsets.UTF_8)
        assertFalse(text.contains("Open-Meteo"))
    }

    @Test
    fun FR_402_failed_write_leaves_no_final_or_tmp_file_and_stale_files_are_swept_by_helper() = runBlocking {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stale = File(dir, "x.csv.tmp").apply { writeText("x"); setLastModified(now.minusSeconds(30 * 3600).toEpochMilli()) }
        io.github.abe_ma.headachediary.data.export.ExportFiles.deleteOld(ctx, now)
        assertFalse(stale.exists())
        val failing = FileExportRepository(ctx, attacks, pressure, { zone }, { now }, pdfOutput = {
            object : PdfOutput by CanvasPdfOutput() { override fun writeTo(out: java.io.OutputStream) { throw java.io.IOException("disk full") } }
        })
        save(t0)
        val r = runCatching { failing.exportPdf(all) }
        assertTrue(r.isFailure)
        assertEquals(emptyList<String>(), dir.list()!!.toList()) // 最終名も .tmp も残らない
    }

    private suspend fun saveWithDelta(at: Instant, hpa: Double?, delta: Double?, med: String = "ロキソニン") {
        val m = meds.observeAll().first().first { it.name == med }
        attacks.save(Attack(0, at, Severity.MILD, hpa, listOf(Intake(0, m, 1, Effect.UNRATED)), pressureDeltaHpa = delta))
    }

    @Test
    fun FR_404_csv_delta_column_is_signed_one_decimal_blank_when_missing_and_never_formula_guarded() = runBlocking {
        saveWithDelta(t0, 1008.0, 1.2)
        saveWithDelta(t0.plusSeconds(3600), 1000.0, -3.44)
        saveWithDelta(t0.plusSeconds(7200), 1000.0, 0.0)
        saveWithDelta(t0.plusSeconds(10800), 1000.0, -0.04) // 丸めると 0.0 → 符号なし
        saveWithDelta(t0.plusSeconds(14400), 1000.0, null)
        val rows = String(bytes(export.exportCsv(all)), Charsets.UTF_8).removePrefix("\uFEFF").split("\r\n").drop(1)
        assertTrue(rows[0].endsWith(",1008.0,+1.2"))
        assertTrue(rows[1].endsWith(",1000.0,-3.4"))
        assertTrue(rows[2].endsWith(",1000.0,0.0"))
        assertTrue(rows[3].endsWith(",1000.0,0.0"))
        assertTrue(rows[4].endsWith(",1000.0,")) // 欠損は空欄
        assertFalse(rows.any { it.contains("'+1.2") || it.contains("'-3.4") }) // 数値列に ' を付けない
    }

    @Test
    fun FR_404_csv_delta_is_blank_but_pressure_attribution_rule_is_unchanged() = runBlocking {
        saveWithDelta(t0, null, null)
        val text = String(bytes(export.exportCsv(all)), Charsets.UTF_8)
        assertFalse(text.contains("Open-Meteo")) // 気圧が全て欠損 → 帰属行なし
        assertTrue(text.contains("軽い,ロキソニン×1錠 (未評価),,"))
    }

    @Test
    fun FR_404_format_delta_rounding_and_signs() {
        fun f(v: Double?, m: String = "") = formatDelta(v, m)
        assertEquals("+0.1", f(0.06)); assertEquals("-0.1", f(-0.06)); assertEquals("0.0", f(0.049)); assertEquals("-", f(null, "-"))
        assertEquals("+10.0", f(10.0)); assertEquals("-12.3", f(-12.34))
    }

    @Test
    fun FR_404_pdf_with_delta_column_generates_and_keeps_pagination() = runBlocking {
        repeat(60) { saveWithDelta(t0.plusSeconds(it * 3600L), 1000.0, if (it % 3 == 0) null else (it - 30) / 10.0) }
        export.exportPdf(all)
        assertTrue("pages=${export.lastPdfPageCount}", export.lastPdfPageCount >= 2)
    }
}
