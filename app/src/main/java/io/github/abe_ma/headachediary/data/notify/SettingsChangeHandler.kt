package io.github.abe_ma.headachediary.data.notify

import android.util.Log
import io.github.abe_ma.headachediary.domain.Settings
import kotlinx.coroutines.CancellationException

/**
 * 設定の変更に応じた副作用 (気圧の即時取得、気圧低下通知の再予約、効果リマインドの復元/取消)。
 * NotifyAwareSettingsRepository のフックとして DataModule から束ねる。
 *
 * 各処理は独立に try/catch する: 設定は保存済みなので、DB や WorkManager の例外でアプリを落とさず、
 * 1 つが失敗しても残りは実行する (CancellationException だけは再送出)。起動時の NotifyBootstrap が後で補正する。
 */
class SettingsChangeHandler(
    private val enqueuePressureNow: () -> Unit,
    private val rescheduleDrop: (Settings) -> Unit,
    private val effectSync: EffectReminderSync,
    private val hasActiveEffectWork: suspend (attackId: Long) -> Boolean,
) {
    suspend operator fun invoke(old: Settings, new: Settings) {
        // 地域を登録/変更したら直ちに 1 回取得 (初回 31 日分)。次の日次まで待たない。
        if (new.prefectureCode != null && new.prefectureCode != old.prefectureCode) {
            guarded("enqueuePressureNow") { enqueuePressureNow() }
        }
        // 気圧低下通知のオン/オフ・時刻の変更を予約に反映。
        if (old.dropNotifyEnabled != new.dropNotifyEnabled || old.dropNotifyTime != new.dropNotifyTime) {
            guarded("rescheduleDrop") { rescheduleDrop(new) }
        }
        // FR-505: 効果リマインドのオン → 直近の未評価の発作に予約 (直前に保存した発作を含む)。オフ → 予約済みを取消。
        if (!old.effectReminderEnabled && new.effectReminderEnabled) {
            guarded("restoreEffectReminders") { effectSync.restore(new, hasActiveEffectWork) }
        } else if (old.effectReminderEnabled && !new.effectReminderEnabled) {
            guarded("cancelEffectReminders") { effectSync.cancelPending() }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "settings side effect failed: $what", e)
        }
    }

    private companion object { const val TAG = "SettingsChange" }
}
