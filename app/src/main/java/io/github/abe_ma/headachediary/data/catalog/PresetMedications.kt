package io.github.abe_ma.headachediary.data.catalog

import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.MedicationUnit

/**
 * 薬プリセット (頓服薬のみ。01-requirements.md の一覧、FR-203)。
 * 配列の順序が「プリセット順」(FR-206 の同数時の順)。id は 1 始まりの定義順で固定し、
 * Room 投入時も同じ id を使う (後から追加する場合は末尾にのみ追加すること)。
 * 照合結果は docs/medication-verification.md。
 */
data class PresetMedication(val name: String, val ingredient: String, val unit: MedicationUnit = MedicationUnit.TABLET)

val PRESET_MEDICATIONS: List<PresetMedication> = listOf(
    PresetMedication("カロナール", "アセトアミノフェン"),
    PresetMedication("タイレノール", "アセトアミノフェン"),
    PresetMedication("イブ", "イブプロフェン (シリーズにより成分が異なる)"),
    PresetMedication("EVE", "イブプロフェン (シリーズにより成分が異なる)"),
    PresetMedication("バファリン", "シリーズにより成分が異なる"),
    PresetMedication("ロキソニン", "ロキソプロフェンナトリウム水和物"),
    PresetMedication("ナイキサン", "ナプロキセン"),
    PresetMedication("マクサルト", "リザトリプタン安息香酸塩"),
    PresetMedication("イミグラン", "スマトリプタンコハク酸塩"),
    PresetMedication("ゾーミッグ", "ゾルミトリプタン"),
    PresetMedication("レルパックス", "エレトリプタン臭化水素酸塩"),
    PresetMedication("アマージ", "ナラトリプタン塩酸塩"),
    PresetMedication("レイボー", "ラスミジタンコハク酸塩"),
    PresetMedication("ナウゼリン", "ドンペリドン"),
    PresetMedication("プリンペラン", "メトクロプラミド"),
    // T-03b: 漢方 (包単位。錠数欄は「1回分」扱い)。既存 id を変えないため必ず末尾に追加する。
    PresetMedication("五苓散", "漢方", MedicationUnit.PACKET),
    PresetMedication("呉茱萸湯", "漢方", MedicationUnit.PACKET),
    PresetMedication("葛根湯", "漢方", MedicationUnit.PACKET),
    PresetMedication("川芎茶調散", "漢方", MedicationUnit.PACKET),
    PresetMedication("桂枝人参湯", "漢方", MedicationUnit.PACKET),
    PresetMedication("釣藤散", "漢方", MedicationUnit.PACKET),
    PresetMedication("半夏白朮天麻湯", "漢方", MedicationUnit.PACKET),
)

/** プリセットを Medication に変換 (id = 定義順 + 1)。 */
fun presetMedications(): List<Medication> = PRESET_MEDICATIONS.mapIndexed { i, p ->
    Medication(id = (i + 1).toLong(), name = p.name, ingredient = p.ingredient, isPreset = true, isHidden = false, unit = p.unit)
}
