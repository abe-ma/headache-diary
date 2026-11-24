package io.github.abe_ma.headachediary.data.export

import android.content.Context
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.PressureFormat
import io.github.abe_ma.headachediary.domain.MedicationUnit
import io.github.abe_ma.headachediary.domain.Severity
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 書き出し用の文言 (res/values/strings_export.xml から)。 */
class ExportLabels(private val ctx: Context) {
    fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)
    fun severity(v: Severity) = ctx.getString(
        when (v) {
            Severity.MILD -> R.string.export_severity_mild
            Severity.MODERATE -> R.string.export_severity_moderate
            Severity.SEVERE -> R.string.export_severity_severe
        },
    )
    fun unit(v: MedicationUnit) = ctx.getString(if (v == MedicationUnit.PACKET) R.string.export_unit_packet else R.string.export_unit_tablet)
    fun effect(v: Effect) = ctx.getString(
        when (v) {
            Effect.UNRATED -> R.string.export_effect_unrated
            Effect.EFFECTIVE -> R.string.export_effect_effective
            Effect.SLIGHT -> R.string.export_effect_slight
            Effect.NONE -> R.string.export_effect_none
        },
    )
}

internal val DATETIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

internal fun formatHpa(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""

/**
 * 前日との気圧差 (FR-404): 符号付き小数 1 桁 (+1.2 / -3.4 / 0.0)。四捨五入して 0.0 になる値は符号なし。欠損は [missing]。
 * 数式対策の ' 前置は文字列列 (薬名) のみで、この数値列には付けない。
 */
internal fun formatDelta(v: Double?, missing: String): String = if (v == null) missing else PressureFormat.delta(v)

/** 「薬名×2錠 (効果)」(FR-209: 単位つき)。薬名はそのまま (数式対策は CSV 側)。 */
internal fun intakeText(name: String, count: Int, unit: String, effect: String) = "$name×$count$unit ($effect)"

object CsvExport {
    const val BOM = "\uFEFF"
    private val FORMULA_PREFIX = setOf('=', '+', '-', '@', '\t', '\r')

    /** 表計算の数式実行対策: 先頭が = + - @ なら先頭に ' を付ける。 */
    internal fun guardFormula(s: String): String = if (s.isNotEmpty() && s[0] in FORMULA_PREFIX) "'$s" else s

    /** RFC 4180: カンマ・引用符・改行を含むなら引用符で囲み、" は "" にする。 */
    internal fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    /** 古い順に並んだ発作から CSV 文字列 (BOM 付き、CRLF、末尾も CRLF) を作る。 */
    fun build(attacksOldestFirst: List<Attack>, zone: ZoneId, labels: ExportLabels): String {
        val sb = StringBuilder(BOM)
        fun row(vararg f: String) { sb.append(f.joinToString(",") { escape(it) }).append("\r\n") }
        row(
            labels.s(R.string.export_csv_datetime), labels.s(R.string.export_csv_severity),
            labels.s(R.string.export_csv_medication), labels.s(R.string.export_csv_pressure),
            labels.s(R.string.export_csv_pressure_delta),
        )
        for (a in attacksOldestFirst) {
            row(
                DATETIME_FMT.format(a.startedAt.atZone(zone)),
                labels.severity(a.severity),
                a.intakes.joinToString(" / ") {
                    intakeText(guardFormula(it.medication.name), it.tablets, labels.unit(it.medication.unit), labels.effect(it.effect))
                },
                formatHpa(a.pressureHpa),
                formatDelta(a.pressureDeltaHpa, missing = ""),
            )
        }
        // FR-403: 気圧が 1 件でもあれば帰属行 (CC BY 4.0)。全て欠損なら付けない。
        if (attacksOldestFirst.any { it.pressureHpa != null }) sb.append(labels.s(R.string.export_csv_attribution)).append("\r\n")
        return sb.toString()
    }
}
