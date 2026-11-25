package io.github.abe_ma.headachediary.data.fake

import io.github.abe_ma.headachediary.domain.BackupException
import io.github.abe_ma.headachediary.domain.BackupInspection
import io.github.abe_ma.headachediary.domain.BackupRepository
import io.github.abe_ma.headachediary.domain.InvalidReason
import io.github.abe_ma.headachediary.domain.RestoreResult
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/**
 * UI 開発・UI テスト用のインメモリ実装 (T-21a)。write は固定の JSON、inspect/restore は設定した結果を返す。
 * 既定は「3 件中 2 件が新規・1 件が重複」の有効なファイル。入力ストリームは中身を見ずに読み捨てる。
 */
class FakeBackupRepository : BackupRepository {
    /** inspect が返す結果。テストで差し替える (Invalid にすれば不正ファイルの表示を確認できる)。 */
    var nextInspection: BackupInspection = BackupInspection.Valid(
        attackCount = 3, newAttackCount = 2, duplicateCount = 1,
        periodStart = Instant.parse("2026-10-01T00:00:00Z"), periodEnd = Instant.parse("2026-11-20T00:00:00Z"),
        customMedicationCount = 1, includesSettings = true,
        exportedAt = Instant.parse("2026-11-25T00:00:00Z"), appVersion = "0.1.0",
    )

    /** restore が返す結果。`nextRestoreFailure` が非 null ならそちらの理由で [BackupException] を投げる。 */
    var nextRestoreResult: RestoreResult = RestoreResult(
        addedAttacks = 2, skippedDuplicates = 1, addedMedications = 1, settingsApplied = true, notificationsTurnedOff = true,
    )
    var nextRestoreFailure: InvalidReason? = null

    var writeCount = 0; private set
    var inspectCount = 0; private set
    var restoreCount = 0; private set

    override suspend fun write(out: OutputStream) {
        writeCount++
        out.write(FIXED_JSON.toByteArray(Charsets.UTF_8))
    }

    override suspend fun inspect(input: InputStream): BackupInspection {
        inspectCount++
        input.readBytes()
        return nextInspection
    }

    override suspend fun restore(input: InputStream): RestoreResult {
        restoreCount++
        input.readBytes()
        nextRestoreFailure?.let { throw BackupException(it) }
        return nextRestoreResult
    }

    companion object {
        const val FIXED_JSON = """{"format":"headache-diary-backup","formatVersion":1,"exportedAt":1795651200000,"appVersion":"0.1.0","attacks":[],"customMedications":[],"medicationVisibility":[],"settings":null}"""
    }
}
