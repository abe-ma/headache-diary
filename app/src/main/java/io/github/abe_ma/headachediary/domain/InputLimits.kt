package io.github.abe_ma.headachediary.domain

/**
 * 入力側の上限 (FR-608a)。UI の入力制限と、バックアップの復元上限 (BackupLimits) が同じ値を使い、
 * アプリで作れる記録は必ずバックアップから復元できるようにする。ui は data に依存できないので domain に置く。
 */
object InputLimits {
    /** 1 つの発作に記録できる服薬の件数。 */
    const val MAX_INTAKES_PER_ATTACK = 20

    /** 薬名 (自由入力を含む) の最大文字数。 */
    const val MAX_MEDICATION_NAME_LENGTH = 100
}
