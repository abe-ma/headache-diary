package io.github.abe_ma.headachediary.domain

/**
 * 設定値の入力範囲。UI の入力 (設定画面) と、バックアップ復元時の正規化で同じ値を使う。
 * ui/settings/SettingsViewModel の同名の定数と一致していること (BackupRepositoryTest で検証)。
 */
object SettingsLimits {
    /** 効果入力リマインドの遅延 (分) の選択肢 (FR-500)。 */
    val EFFECT_DELAY_OPTIONS_MINUTES: List<Int> = listOf(30, 60, 120, 180, 360)
    const val DEFAULT_EFFECT_DELAY_MINUTES = 120

    /** 気圧低下のしきい値 (hPa、FR-501)。 */
    const val MIN_DROP_THRESHOLD_HPA = 1.0
    const val MAX_DROP_THRESHOLD_HPA = 20.0
    const val DEFAULT_DROP_THRESHOLD_HPA = 6.0
}
