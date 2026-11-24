package io.github.abe_ma.headachediary.data.notify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.PressureRepository
import io.github.abe_ma.headachediary.domain.ReminderScheduler
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** 気圧低下通知を最後に出した日 (1 日 1 回、FR-501)。 */
class NotifyStateStore(private val store: DataStore<Preferences>) {
    private val key = longPreferencesKey("lastDropNotifiedEpochDay")
    suspend fun lastDropNotified(): LocalDate? = store.data.first()[key]?.let(LocalDate::ofEpochDay)
    suspend fun setDropNotified(day: LocalDate) { store.edit { it[key] = day.toEpochDay() } }
}

/** 効果入力リマインドの発火時処理 (FR-500, 503)。 */
class EffectReminderJob(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
) {
    /** 通知を出したら true。 */
    suspend fun run(attackId: Long): Boolean {
        if (!settings.observe().first().effectReminderEnabled) return false // FR-503: オフなら出さない
        val a = db.attackDao().observeAttack(attackId).first() ?: return false // 削除済み
        if (a.intakes.none { it.intake.effect == Effect.UNRATED.name }) return false // 入力済み → 通知しない
        if (!notifier.canNotify()) return false
        notifier.showEffectReminder(attackId)
        return true
    }
}

/** 気圧低下通知の発火時処理 (FR-501, 502, 504)。 */
class DropNotifyJob(
    private val settings: SettingsRepository,
    private val pressure: PressureRepository,
    private val state: NotifyStateStore,
    private val notifier: Notifier,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = Instant::now,
) {
    enum class Result { NOTIFIED, DISABLED, NO_PREFECTURE, STALE_FORECAST, TOO_LATE, ALREADY_TODAY, NO_DATA, BELOW_THRESHOLD, NOT_ALLOWED }

    /** @param intendedAt 本来の発火予定時刻 (大幅に遅れた実行では「明日」が誤りになるため出さない) */
    suspend fun run(intendedAt: Instant): Result {
        val s = settings.observe().first()
        if (!s.dropNotifyEnabled) return Result.DISABLED
        if (s.prefectureCode == null) return Result.NO_PREFECTURE
        val z = zone()
        val t = now()
        if (Duration.between(intendedAt, t) > MAX_LATE) return Result.TOO_LATE
        // FR-504: 最終取得 (登録地域) が 36 時間以上前、または未取得なら出さない。
        val last = pressure.observeLastFetchedAt().first()
        if (last == null || Duration.between(last, t) >= STALE) return Result.STALE_FORECAST
        val today = intendedAt.atZone(z).toLocalDate()
        if (state.lastDropNotified() == today) return Result.ALREADY_TODAY // 1 日 1 回
        val tomorrow = today.plusDays(1)
        val from = tomorrow.atStartOfDay(z).toInstant().minus(Duration.ofHours(24))
        val to = tomorrow.plusDays(1).atStartOfDay(z).toInstant()
        val drop = PressureDropDetector.maxDrop(pressure.observeSeries(from..to).first(), tomorrow, z)
            ?: return Result.NO_DATA
        if (!PressureDropDetector.isDrop(drop, s.dropThresholdHpa)) return Result.BELOW_THRESHOLD
        if (!notifier.canNotify()) return Result.NOT_ALLOWED
        notifier.showPressureDrop(drop.roundToInt())
        state.setDropNotified(today)
        return Result.NOTIFIED
    }

    companion object {
        val STALE: Duration = Duration.ofHours(36)
        val MAX_LATE: Duration = Duration.ofHours(3)
    }
}

/**
 * 効果リマインドの復元・取消 (直近 12 時間の未評価の発作が対象)。
 * 起動時の再予約 (NotifyBootstrapJob) と、設定で効果リマインドを切り替えたとき (FR-505) の両方で使う。
 */
class EffectReminderSync(
    private val db: AppDatabase,
    private val reminders: ReminderScheduler,
    private val now: () -> Instant = Instant::now,
) {
    private suspend fun recentUnrated() = db.attackDao().getUnratedSince(now().minus(WINDOW).toEpochMilli())

    /** 予約済みでない (hasWork が false の) 未評価の発作に、設定の遅延で予約する。 */
    suspend fun restore(s: io.github.abe_ma.headachediary.domain.Settings, hasWork: suspend (attackId: Long) -> Boolean) {
        if (!s.effectReminderEnabled) return
        recentUnrated().forEach { a -> if (!hasWork(a.id)) reminders.scheduleEffectReminder(a.id, s.effectReminderDelayMinutes) }
    }

    /** オフにしたとき: 予約済みの取消 (表示済みの通知も消える)。 */
    suspend fun cancelPending() {
        recentUnrated().forEach { reminders.cancelEffectReminder(it.id) }
    }

    companion object { val WINDOW: Duration = Duration.ofHours(12) }
}

/** 起動時の再予約 (再起動・バックアップ復元後。WorkManager の DB は復元されない)。 */
class NotifyBootstrapJob(
    db: AppDatabase,
    private val settings: SettingsRepository,
    reminders: ReminderScheduler,
    private val hasEffectWork: suspend (attackId: Long) -> Boolean,
    private val scheduleDrop: (io.github.abe_ma.headachediary.domain.Settings) -> Unit,
    now: () -> Instant = Instant::now,
) {
    private val sync = EffectReminderSync(db, reminders, now)

    suspend fun run() {
        val s = settings.observe().first()
        scheduleDrop(s)
        // リマインダーの記録 (実行済みを含む) が無いものだけ予約し直す (通知の再送を避ける)。
        sync.restore(s, hasEffectWork)
    }
}
