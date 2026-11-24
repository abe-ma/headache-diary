package io.github.abe_ma.headachediary.domain

import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth

/** 痛みの強さ。FR-202 */
enum class Severity { MILD, MODERATE, SEVERE }

/** 薬の効果 (体感)。FR-204 */
enum class Effect { UNRATED, EFFECTIVE, SLIGHT, NONE }

/** 発作1件。FR-200, 201 */
data class Attack(
    val id: Long, // 0 = 新規
    val startedAt: Instant,
    val severity: Severity,
    val pressureHpa: Double?, // FR-104: 保存時の紐づけ。欠損可
    val intakes: List<Intake>,
    // FR-404: 発作時 − 24時間前の気圧差 (hPa、マイナス=低下)。欠損可。UI は null を渡せば Repository が解決する。
    // 既存の位置引数の呼び出しを壊さないよう末尾に置く (デフォルト値つき)。
    val pressureDeltaHpa: Double? = null,
)

/** 服薬1件。FR-203, 204, 205 */
data class Intake(
    val id: Long,
    val medication: Medication,
    val tablets: Int, // 1以上
    val effect: Effect,
)

/** 服薬の数え方の単位。FR-205, 209。 */
enum class MedicationUnit { TABLET, PACKET } // 錠 / 包

data class Medication(
    val id: Long,
    val name: String, // 表示名 (商品名)
    val ingredient: String?, // 参考表示 (成分)。自由入力は null
    val isPreset: Boolean,
    val isHidden: Boolean, // FR-207
    val unit: MedicationUnit = MedicationUnit.TABLET, // FR-209。自由入力は TABLET
)

/** 気圧の1時間値。 */
data class PressurePoint(val time: Instant, val hpa: Double)

/** 月別発作回数。FR-300 */
data class MonthCount(val yearMonth: YearMonth, val count: Int)

data class Settings(
    val prefectureCode: String?, // JIS X 0401 の2桁 "01".."47"。未設定可
    val effectReminderEnabled: Boolean = false, // FR-505: 既定オフ (権限要求のため)
    val effectReminderDelayMinutes: Int = 120, // FR-500
    val dropNotifyEnabled: Boolean = false, // FR-501 (権限要求のため既定オフ)
    val dropThresholdHpa: Double = 6.0,
    val dropNotifyTime: LocalTime = LocalTime.of(20, 0),
)

/** 都道府県 (県庁所在地の代表座標)。FR-100 */
data class Prefecture(val code: String, val name: String, val lat: Double, val lon: Double)

/**
 * 気圧取得の結果。FR-101, 105。`refresh()` は例外を投げずにこれを返す。
 * (設計書に型の定義がなかったため実装時に追加した)
 */
sealed interface RefreshResult {
    data object Success : RefreshResult
    data class Failure(val reason: FailureReason) : RefreshResult
}

enum class FailureReason {
    /** 地域が未設定 */
    NO_PREFECTURE,
    /** 通信不可 (オフライン・タイムアウト等) */
    NETWORK,
    /** API が非 2xx を返した、または応答を解釈できなかった */
    API_ERROR,
}
