package io.github.abe_ma.headachediary.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.PressurePoint
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** PDF の出力先。実機は PdfDocument、JVM テストでは Canvas を返すだけの実装に差し替える。 */
interface PdfOutput {
    fun startPage(width: Int, height: Int, number: Int): Canvas
    fun finishPage()
    fun writeTo(out: OutputStream)
    fun close()
}

class PdfDocumentOutput : PdfOutput {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    override fun startPage(width: Int, height: Int, number: Int): Canvas {
        val p = doc.startPage(PdfDocument.PageInfo.Builder(width, height, number).create())
        page = p
        return p.canvas
    }
    override fun finishPage() { page?.let { doc.finishPage(it) }; page = null }
    override fun writeTo(out: OutputStream) = doc.writeTo(out)
    override fun close() = doc.close()
}

/** PDF レポート (FR-401)。A4 縦、複数ページ。診断・助言・傾向の解釈文は入れない (NFR-104)。 */
class PdfReport(
    private val labels: ExportLabels,
    private val zone: ZoneId,
) {
    private class Block(val height: Float, val draw: (Canvas, Float) -> Unit)

    private val pageW = 595
    private val pageH = 842
    private val margin = 36f
    private val footerH = 40f
    private val contentW = pageW - margin * 2
    private val bodyBottom = pageH - footerH - 8f

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private val body = paint(10f)
    private val small = paint(8f, color = Color.DKGRAY)
    private val h2 = paint(13f, bold = true)
    private val grid = paint(8f, color = Color.GRAY).apply { strokeWidth = 0.5f; style = Paint.Style.STROKE }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3F51B5.toInt(); style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B5E20.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD32F2F.toInt(); style = Paint.Style.FILL; strokeWidth = 0.8f }

    /** ページ数を返す。 */
    fun write(
        out: OutputStream,
        attacksOldestFirst: List<Attack>,
        pressure: List<PressurePoint>,
        range: ClosedRange<Instant>,
        now: Instant,
        output: PdfOutput = PdfDocumentOutput(),
    ): Int {
        val blocks = ArrayList<Block>()
        val tableHeader = tableHeaderBlock()
        blocks += headerBlock(attacksOldestFirst, range, now)
        blocks += sectionTitle(labels.s(R.string.export_pdf_section_monthly))
        blocks += monthlyBlocks(attacksOldestFirst, range, now)
        blocks += sectionTitle(labels.s(R.string.export_pdf_section_pressure))
        blocks += pressureBlocks(attacksOldestFirst, pressure, range)
        blocks += sectionTitle(labels.s(R.string.export_pdf_section_list))
        val listStart = blocks.size
        if (attacksOldestFirst.isEmpty()) {
            blocks += textBlock(labels.s(R.string.export_pdf_no_attacks), body)
        } else {
            blocks += tableHeader
            attacksOldestFirst.forEach { blocks += rowBlock(it) }
        }

        // ページ割り: ブロック単位。表の途中で改ページしたら見出し行を再掲する。
        val pages = ArrayList<MutableList<Block>>()
        var cur = ArrayList<Block>().also { pages += it }
        var y = margin
        blocks.forEachIndexed { i, b ->
            if (y + b.height > bodyBottom && cur.isNotEmpty()) {
                cur = ArrayList(); pages += cur; y = margin
                if (i > listStart && attacksOldestFirst.isNotEmpty()) { cur += tableHeader; y += tableHeader.height }
            }
            cur += b; y += b.height
        }

        try {
            pages.forEachIndexed { idx, list ->
                val canvas = output.startPage(pageW, pageH, idx + 1)
                var top = margin
                for (b in list) { b.draw(canvas, top); top += b.height }
                drawFooter(canvas, idx + 1, pages.size)
                output.finishPage()
            }
            output.writeTo(out)
        } finally {
            output.close()
        }
        return pages.size
    }

    private fun drawFooter(c: Canvas, n: Int, total: Int) {
        val y = pageH - footerH + 14f
        c.drawLine(margin, y - 12f, pageW - margin, y - 12f, grid)
        c.drawText(labels.s(R.string.export_pdf_footer), margin, y, small)
        val num = labels.s(R.string.export_pdf_page, n, total)
        c.drawText(num, pageW - margin - small.measureText(num), y, small)
    }

    private fun textBlock(text: String, p: Paint) = lines(text, p, contentW).let { ls ->
        Block(ls.size * (p.textSize + 4f) + 4f) { c, top -> ls.forEachIndexed { i, l -> c.drawText(l, margin, top + p.textSize + i * (p.textSize + 4f), p) } }
    }

    private fun sectionTitle(t: String) = Block(h2.textSize + 14f) { c, top -> c.drawText(t, margin, top + h2.textSize + 6f, h2) }

    private fun headerBlock(attacks: List<Attack>, range: ClosedRange<Instant>, now: Instant): Block {
        val (from, to) = displayPeriod(range, attacks.firstOrNull()?.startedAt, now, zone)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
        val title = paint(20f, bold = true)
        val period = labels.s(R.string.export_pdf_period, fmt.format(from), fmt.format(to))
        val created = labels.s(R.string.export_pdf_created, fmt.format(now.atZone(zone)))
        return Block(70f) { c, top ->
            c.drawText(labels.s(R.string.export_pdf_title), margin, top + 22f, title)
            c.drawText(period, margin, top + 44f, body)
            c.drawText(created, margin, top + 60f, body)
        }
    }

    // ---- 月別棒グラフ ----
    private fun monthlyBlocks(attacks: List<Attack>, range: ClosedRange<Instant>, now: Instant): List<Block> {
        if (attacks.isEmpty()) return listOf(textBlock(labels.s(R.string.export_pdf_no_attacks), body))
        val counts = attacks.groupingBy { YearMonth.from(it.startedAt.atZone(zone)) }.eachCount()
        val first = counts.keys.min()
        val last = maxOf(counts.keys.max(), YearMonth.from(minOf(range.endInclusive, now).atZone(zone)))
        var months = generateSequence(first) { if (it < last) it.plusMonths(1) else null }.toList()
        var truncated = false
        if (months.size > MAX_MONTHS) { months = months.takeLast(MAX_MONTHS); truncated = true }
        val maxCount = months.maxOf { counts[it] ?: 0 }.coerceAtLeast(1)
        val h = 150f
        val chart = Block(h) { c, top ->
            val left = margin + 24f
            val w = contentW - 24f
            val plotTop = top + 14f
            val plotBottom = top + h - 24f
            c.drawLine(left, plotBottom, left + w, plotBottom, grid)
            c.drawLine(left, plotTop, left, plotBottom, grid)
            c.drawText("0", margin + 8f, plotBottom, small)
            c.drawText(maxCount.toString(), margin + 8f, plotTop + 6f, small)
            val slot = w / months.size
            val barW = (slot * 0.6f).coerceAtMost(28f)
            val labelEvery = ((months.size + 11) / 12).coerceAtLeast(1)
            months.forEachIndexed { i, m ->
                val n = counts[m] ?: 0
                val x = left + slot * i + (slot - barW) / 2
                val bh = (plotBottom - plotTop - 12f) * n / maxCount
                if (n > 0) c.drawRect(x, plotBottom - bh, x + barW, plotBottom, barPaint)
                val num = n.toString()
                c.drawText(num, x + barW / 2 - small.measureText(num) / 2, plotBottom - bh - 3f, small)
                if (i % labelEvery == 0) {
                    val lbl = "${m.year % 100}/${m.monthValue}"
                    c.drawText(lbl, x + barW / 2 - small.measureText(lbl) / 2, plotBottom + 11f, small)
                }
            }
        }
        val out = mutableListOf(chart)
        if (truncated) out += textBlock(labels.s(R.string.export_pdf_months_truncated, MAX_MONTHS), small)
        return out
    }

    // ---- 気圧 + 発作マーカー ----
    private fun pressureBlocks(attacks: List<Attack>, series: List<PressurePoint>, range: ClosedRange<Instant>): List<Block> {
        val pts = series.filter { it.time in range }.sortedBy { it.time }
        if (pts.size < 2) return listOf(textBlock(labels.s(R.string.export_pdf_no_pressure), body))
        val t0 = pts.first().time.toEpochMilli()
        val t1 = pts.last().time.toEpochMilli()
        val lo = pts.minOf { it.hpa } - 1
        val hi = pts.maxOf { it.hpa } + 1
        val h = 170f
        val fmt = DateTimeFormatter.ofPattern("M/d", Locale.ROOT)
        val chart = Block(h) { c, top ->
            val left = margin + 34f
            val w = contentW - 34f
            val plotTop = top + 16f
            val plotBottom = top + h - 22f
            fun x(t: Long) = left + w * (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1)
            fun y(v: Double) = plotBottom - (plotBottom - plotTop) * ((v - lo) / (hi - lo)).toFloat()
            c.drawText(labels.s(R.string.export_pdf_pressure_unit), margin, top + 9f, small)
            c.drawLine(left, plotBottom, left + w, plotBottom, grid)
            c.drawLine(left, plotTop, left, plotBottom, grid)
            c.drawText(String.format(Locale.ROOT, "%.0f", hi), margin, plotTop + 6f, small)
            c.drawText(String.format(Locale.ROOT, "%.0f", lo), margin, plotBottom, small)
            val path = Path()
            pts.forEachIndexed { i, p ->
                val px = x(p.time.toEpochMilli()); val py = y(p.hpa)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, linePaint)
            // 発作マーカー: 縦線 + 上向き三角 (色に依存せず形で判別)
            attacks.filter { it.startedAt.toEpochMilli() in t0..t1 }.forEach { a ->
                val ax = x(a.startedAt.toEpochMilli())
                c.drawLine(ax, plotTop, ax, plotBottom, markPaint)
                val tri = Path().apply { moveTo(ax, plotTop - 7f); lineTo(ax - 4f, plotTop); lineTo(ax + 4f, plotTop); close() }
                c.drawPath(tri, markPaint)
            }
            c.drawText(fmt.format(pts.first().time.atZone(zone)), left, plotBottom + 11f, small)
            val endLbl = fmt.format(pts.last().time.atZone(zone))
            c.drawText(endLbl, left + w - small.measureText(endLbl), plotBottom + 11f, small)
            // 凡例
            val lx = left + w - 60f
            val tri = Path().apply { moveTo(lx, top + 2f); lineTo(lx - 4f, top + 9f); lineTo(lx + 4f, top + 9f); close() }
            c.drawPath(tri, markPaint)
            c.drawText(labels.s(R.string.export_pdf_legend_attack), lx + 8f, top + 9f, small)
        }
        // FR-106: 気圧グラフの近くに帰属表示
        return listOf(chart, textBlock(labels.s(R.string.export_pdf_pressure_attribution), small))
    }

    // ---- 発作一覧 ----
    private val colDate = margin
    private val colSev = margin + 92f
    private val colMed = margin + 150f
    private val colHpa = margin + contentW - 52f
    private val medW = colHpa - colMed - 6f

    private fun tableHeaderBlock() = Block(18f) { c, top ->
        val p = paint(9f, bold = true)
        c.drawText(labels.s(R.string.export_pdf_col_datetime), colDate, top + 12f, p)
        c.drawText(labels.s(R.string.export_pdf_col_severity), colSev, top + 12f, p)
        c.drawText(labels.s(R.string.export_pdf_col_medication), colMed, top + 12f, p)
        c.drawText(labels.s(R.string.export_pdf_col_pressure), colHpa, top + 12f, p)
        c.drawLine(margin, top + 16f, margin + contentW, top + 16f, grid)
    }

    private fun rowBlock(a: Attack): Block {
        val medLines = a.intakes.flatMap { lines(intakeText(it.medication.name, it.tablets, labels.effect(it.effect)), body, medW) }
        val n = maxOf(1, medLines.size)
        val lh = body.textSize + 3f
        return Block(n * lh + 6f) { c, top ->
            val base = top + body.textSize
            c.drawText(DATETIME_FMT.format(a.startedAt.atZone(zone)), colDate, base, body)
            c.drawText(labels.severity(a.severity), colSev, base, body)
            medLines.forEachIndexed { i, l -> c.drawText(l, colMed, base + i * lh, body) }
            c.drawText(formatHpa(a.pressureHpa), colHpa, base, body)
            c.drawLine(margin, top + n * lh + 3f, margin + contentW, top + n * lh + 3f, grid)
        }
    }

    /** 幅に収まるよう文字単位で折り返す (日本語を想定)。 */
    private fun lines(text: String, p: Paint, width: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            var n = p.breakText(rest, true, width, null)
            if (n <= 0) n = 1 // 計測不能でも前進する
            n = n.coerceAtMost(rest.length)
            out += rest.substring(0, n)
            rest = rest.substring(n)
        }
        return out
    }

    companion object {
        const val MAX_MONTHS = 24

        /** 表示用の期間 (開始がエポック付近なら最初の発作日、終了は現在を上限)。 */
        internal fun displayPeriod(range: ClosedRange<Instant>, firstAttack: Instant?, now: Instant, zone: ZoneId): Pair<LocalDate, LocalDate> {
            val start = if (range.start <= Instant.EPOCH) (firstAttack ?: minOf(range.endInclusive, now)) else range.start
            val end = minOf(range.endInclusive, now).let { if (it < start) start else it }
            return start.atZone(zone).toLocalDate() to end.atZone(zone).toLocalDate()
        }
    }
}
