package io.github.abe_ma.headachediary.data.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.AttackRepository
import io.github.abe_ma.headachediary.domain.ExportRepository
import io.github.abe_ma.headachediary.domain.PressureRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CSV / PDF の書き出し (FR-400, 401, 402)。出力先は cache/exports/ (FileProvider の content Uri)。
 * 端末外へは送らない。共有は UI 側が OS の共有シートで行う。
 */
class FileExportRepository(
    private val context: Context,
    private val attacks: AttackRepository,
    private val pressure: PressureRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pdfOutput: () -> PdfOutput = { PdfDocumentOutput() },
) : ExportRepository {

    /** 最終のページ数 (テスト・診断用)。 */
    @Volatile var lastPdfPageCount: Int = 0
        private set

    override suspend fun exportCsv(range: ClosedRange<Instant>): Uri = withContext(io) {
        val list = loadAttacks(range)
        val text = CsvExport.build(list, zone(), ExportLabels(context))
        val file = newFile("csv", range, list)
        file.writeText(text, Charsets.UTF_8) // BOM は text 先頭の U+FEFF が UTF-8 で EF BB BF になる
        uriFor(file)
    }

    override suspend fun exportPdf(range: ClosedRange<Instant>): Uri = withContext(io) {
        val list = loadAttacks(range)
        val series = pressure.observeSeries(range).first()
        val file = newFile("pdf", range, list)
        file.outputStream().buffered().use { out ->
            lastPdfPageCount = PdfReport(ExportLabels(context), zone()).write(out, list, series, range, now(), pdfOutput())
        }
        uriFor(file)
    }

    /** 古い順。 */
    private suspend fun loadAttacks(range: ClosedRange<Instant>): List<Attack> =
        attacks.observeAttacks(range).first().sortedBy { it.startedAt }

    private fun exportsDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun newFile(ext: String, range: ClosedRange<Instant>, list: List<Attack>): File {
        val dir = exportsDir()
        deleteOld(dir)
        val (from, to) = PdfReport.displayPeriod(range, list.firstOrNull()?.startedAt, now(), zone())
        val f = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
        return File(dir, "headache-diary_${f.format(from)}-${f.format(to)}.$ext")
    }

    /** 24 時間を超えた古い出力を削除する。 */
    private fun deleteOld(dir: File) {
        val limit = now().minus(Duration.ofHours(24)).toEpochMilli()
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < limit) it.delete() }
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
