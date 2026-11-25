package io.github.abe_ma.headachediary.data.backup

import kotlinx.serialization.Serializable

/**
 * バックアップファイル (FR-602、設計 7 章) の JSON モデル。形式バージョン 1。
 * 未知の項目は無視する (Json の ignoreUnknownKeys)。時刻は epoch ミリ秒。
 * 薬は端末ごとの id を持ち出さず、プリセットは presetId、自由入力は customName で参照する。
 */
object BackupFormat {
    const val NAME = "headache-diary-backup"
    const val VERSION = 1
    const val MAX_BYTES = 20 * 1024 * 1024 // 読み込みの上限 (FR-606)
}

/** 悪意・誤りのあるファイル対策の上限 (超過・範囲外は CORRUPT で中止、DB 不変)。docs/data-layer.md に同じ値を記載。 */
object BackupLimits {
    const val MAX_ATTACKS = 100_000
    const val MAX_INTAKES_PER_ATTACK = 20
    const val MAX_CUSTOM_MEDICATIONS = 1_000
    const val MAX_MEDICATION_NAME_LENGTH = 100

    /** 発作の開始日時: 2000-01-01T00:00Z 〜 (現在 + 1 日)。 */
    const val MIN_STARTED_AT_MS = 946_684_800_000L
    const val MAX_STARTED_AT_MARGIN_DAYS = 1L

    const val MIN_PRESSURE_HPA = 300.0
    const val MAX_PRESSURE_HPA = 1200.0
    const val MAX_ABS_PRESSURE_DELTA_HPA = 150.0
    const val MIN_TABLETS = 1
    const val MAX_TABLETS = 99
}

/** 形式名とバージョンだけを先に読む (残りは読み飛ばし)。 */
@Serializable
internal class BackupHeader(val format: String? = null, val formatVersion: Int? = null)

@Serializable
internal class BackupFile(
    val format: String? = null,
    val formatVersion: Int? = null,
    val exportedAt: Long = 0,
    val appVersion: String = "",
    val attacks: List<BackupAttack> = emptyList(),
    val customMedications: List<BackupCustomMedication> = emptyList(),
    val medicationVisibility: List<BackupVisibility> = emptyList(),
    val settings: BackupSettings? = null,
)

@Serializable
internal class BackupAttack(
    val startedAt: Long,
    val severity: String,
    val pressureHpa: Double? = null,
    val pressureDeltaHpa: Double? = null,
    val intakes: List<BackupIntake> = emptyList(),
)

@Serializable
internal class BackupIntake(val medication: BackupMedicationRef, val tablets: Int, val effect: String)

/**
 * 薬の参照。プリセットは presetId (端末をまたいで固定の定義順 id)、自由入力は customName。
 * [name] は presetId を解決できない場合 (将来のプリセットなど) に自由入力として復元するための表示名 (書き出し時の名前)。
 */
@Serializable
internal class BackupMedicationRef(val presetId: Long? = null, val customName: String? = null, val name: String? = null)

@Serializable
internal class BackupCustomMedication(val name: String, val unit: String? = null)

@Serializable
internal class BackupVisibility(val presetId: Long? = null, val customName: String? = null, val hidden: Boolean = false)

/** 設定。通知のオン/オフは書き出すが、復元では無視して常にオフにする (FR-605)。 */
@Serializable
internal class BackupSettings(
    val prefectureCode: String? = null,
    val effectReminderEnabled: Boolean = false,
    val effectReminderDelayMinutes: Int = 120,
    val dropNotifyEnabled: Boolean = false,
    val dropThresholdHpa: Double = 6.0,
    val dropNotifyTime: String? = null, // "HH:mm"
)
