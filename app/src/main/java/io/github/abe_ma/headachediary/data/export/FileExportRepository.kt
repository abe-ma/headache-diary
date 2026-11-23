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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** cache/exports/ の管理。 */
object ExportFiles {
    fun dir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    /** 24 時間を超えた古い出力 (と取り残された .tmp) を削除する。起動後の日次ジョブからも呼ぶ。 */
    fun deleteOld(dir: File, now: Instant) {
        val limit = now.minusSeconds(24 * 3600L).toEpochMilli()
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < limit) it.delete() }
    }

    fun deleteOld(context: Context, now: Instant = Instant.now()) = deleteOld(dir(context), now)
}

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
        writeAtomically(file) { it.writeText(text, Charsets.UTF_8) } // BOM は text 先頭の U+FEFF が UTF-8 で EF BB BF になる
        uriFor(file)
    }

    override suspend fun exportPdf(range: ClosedRange<Instant>): Uri = withContext(io) {
        val list = loadAttacks(range)
        val series = pressure.observeSeries(range).first()
        val file = newFile("pdf", range, list)
        writeAtomically(file) { tmp ->
            tmp.outputStream().buffered().use { out ->
                lastPdfPageCount = PdfReport(ExportLabels(context), zone()).write(out, list, series, range, now(), pdfOutput())
            }
        }
        uriFor(file)
    }

    /** 古い順。 */
    private suspend fun loadAttacks(range: ClosedRange<Instant>): List<Attack> =
        attacks.observeAttacks(range).first().sortedBy { it.startedAt }

    private fun exportsDir(): File = ExportFiles.dir(context)

    private fun newFile(ext: String, range: ClosedRange<Instant>, list: List<Attack>): File {
        val dir = exportsDir()
        ExportFiles.deleteOld(dir, now())
        val (from, to) = PdfReport.displayPeriod(range, list.firstOrNull()?.startedAt, now(), zone())
        val f = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
        return File(dir, "headache-diary_${f.format(from)}-${f.format(to)}.$ext")
    }

    /** 一時ファイルに書いて完成後に rename する (失敗で壊れたファイルを最終名で残さない)。 */
    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            write(tmp)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
