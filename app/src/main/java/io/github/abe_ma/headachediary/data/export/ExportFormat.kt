package io.github.abe_ma.headachediary.data.export

import android.content.Context
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.Effect
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

/** 「薬名×錠数 (効果)」。薬名はそのまま (数式対策は CSV 側)。 */
internal fun intakeText(name: String, tablets: Int, effect: String) = "$name×$tablets ($effect)"

object CsvExport {
    const val BOM = "﻿"
    private val FORMULA_PREFIX = setOf('=', '+', '-', '@')

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
        )
        for (a in attacksOldestFirst) {
            row(
                DATETIME_FMT.format(a.startedAt.atZone(zone)),
                labels.severity(a.severity),
                a.intakes.joinToString(" / ") { intakeText(guardFormula(it.medication.name), it.tablets, labels.effect(it.effect)) },
                formatHpa(a.pressureHpa),
            )
        }
        return sb.toString()
    }
}
