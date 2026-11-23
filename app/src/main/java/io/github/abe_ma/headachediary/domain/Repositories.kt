package io.github.abe_ma.headachediary.domain

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface AttackRepository {
    /** 新しい順。range が null なら全件。 */
    fun observeAttacks(range: ClosedRange<Instant>? = null): Flow<List<Attack>>
    fun observeAttack(id: Long): Flow<Attack?>

    /** 保存時に pressureHpa を PressureRepository で解決。未評価の服薬があれば効果リマインダーを予約/更新 (FR-500)。 */
    suspend fun save(attack: Attack): Long

    /** 関連リマインダーも取消。 */
    suspend fun delete(id: Long)

    /** FR-300 */
    fun observeMonthlyCounts(months: Int): Flow<List<MonthCount>>
}

interface MedicationRepository {
    /** 非表示を除く。使用回数の多い順 → プリセット順 (FR-206, 207)。 */
    fun observeCandidates(): Flow<List<Medication>>

    /** 薬の管理画面用。非表示も含む。 */
    fun observeAll(): Flow<List<Medication>>

    /** FR-207。過去記録の表示には影響しない。 */
    suspend fun setHidden(id: Long, hidden: Boolean)

    /** 同名は既存を返す。 */
    suspend fun addCustom(name: String): Medication
}

interface PressureRepository {
    /** 登録地域。FR-103 */
    fun observeSeries(range: ClosedRange<Instant>): Flow<List<PressurePoint>>

    /** 最寄りの1時間値 (±1時間以内)。なければ null。 */
    suspend fun pressureAt(time: Instant): Double?

    /** FR-101。失敗でも例外を投げない。 */
    suspend fun refresh(): RefreshResult

    /** FR-105 */
    fun observeLastFetchedAt(): Flow<Instant?>
}

interface SettingsRepository {
    fun observe(): Flow<Settings>

    /** 変更に応じて通知ジョブを再設定する。 */
    suspend fun update(transform: (Settings) -> Settings)
}

interface ExportRepository {
    /** FR-400。FileProvider の content Uri。 */
    suspend fun exportCsv(range: ClosedRange<Instant>): Uri

    /** FR-401。android.graphics.pdf.PdfDocument。 */
    suspend fun exportPdf(range: ClosedRange<Instant>): Uri
}

interface PrefectureRepository {
    fun all(): List<Prefecture>
}
