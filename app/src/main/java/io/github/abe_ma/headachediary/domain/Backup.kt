package io.github.abe_ma.headachediary.domain

import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/**
 * バックアップと復元 (FR-600〜607、設計 7 章)。java.io の Stream のみに依存し、Uri / ContentResolver は ui が扱う。
 */
interface BackupRepository {
    /** バックアップ JSON を書き出す (FR-601, 602)。close は呼び出し側。 */
    suspend fun write(out: OutputStream)

    /** 検証と統合のプレビュー (FR-603)。DB は変更しない。不正なファイルは [BackupInspection.Invalid]。 */
    suspend fun inspect(input: InputStream): BackupInspection

    /**
     * 検証し、1 トランザクションで既存の記録に統合する (FR-604〜606)。失敗時は何も変更しない。
     * 不正・非対応のファイルは [BackupException] を投げる (inspect で確認済みでも、restore 時に再検証する)。
     */
    suspend fun restore(input: InputStream): RestoreResult
}

sealed interface BackupInspection {
    data class Valid(
        val attackCount: Int,
        val newAttackCount: Int,
        val duplicateCount: Int,
        val periodStart: Instant?, // 発作が 0 件なら null
        val periodEnd: Instant?,
        val customMedicationCount: Int,
        val includesSettings: Boolean,
        val exportedAt: Instant,
        val appVersion: String,
    ) : BackupInspection

    data class Invalid(val reason: InvalidReason) : BackupInspection
}

/** FR-606。 */
enum class InvalidReason {
    /** バックアップ形式ではないファイル */
    NOT_BACKUP,
    /** 壊れた/切り詰められたファイル */
    CORRUPT,
    /** サイズが大きすぎる (20MB 超) */
    TOO_LARGE,
    /** 新しい形式バージョン (アプリの更新が必要) */
    UNSUPPORTED_VERSION,
}

/** [BackupRepository.restore] が不正なファイルで投げる。 */
class BackupException(val reason: InvalidReason) : Exception("invalid backup: $reason")

/** FR-607。通知のオン/オフは常にオフで復元するので、設定を反映したら [notificationsTurnedOff] は true。 */
data class RestoreResult(
    val addedAttacks: Int,
    val skippedDuplicates: Int,
    val addedMedications: Int,
    val settingsApplied: Boolean,
    val notificationsTurnedOff: Boolean,
)
