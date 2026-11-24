package io.github.abe_ma.headachediary.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.db.AttackEntity
import io.github.abe_ma.headachediary.data.db.IntakeEntity
import io.github.abe_ma.headachediary.data.fake.FakePressureRepository
import io.github.abe_ma.headachediary.data.notify.DropNotifyJob
import io.github.abe_ma.headachediary.data.notify.DropNotifyScheduler.runThenReschedule
import io.github.abe_ma.headachediary.data.notify.DropNotifyJob.Result
import io.github.abe_ma.headachediary.data.notify.DropNotifyScheduler
import io.github.abe_ma.headachediary.data.notify.EffectReminderJob
import io.github.abe_ma.headachediary.data.notify.EffectReminderSync
import io.github.abe_ma.headachediary.data.notify.NotifyBootstrapJob
import io.github.abe_ma.headachediary.data.notify.NotifyStateStore
import io.github.abe_ma.headachediary.data.notify.Notifier
import io.github.abe_ma.headachediary.data.notify.PressureDropDetector
import io.github.abe_ma.headachediary.data.notify.WorkManagerReminderScheduler
import io.github.abe_ma.headachediary.data.repo.DataStoreSettingsRepository
import io.github.abe_ma.headachediary.data.repo.NotifyAwareSettingsRepository
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.ReminderScheduler
import io.github.abe_ma.headachediary.domain.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeNotifier(var allowed: Boolean = true) : Notifier {
        val effect = mutableListOf<Long>(); val drops = mutableListOf<Int>(); val cancelled = mutableListOf<Long>()
        override fun canNotify() = allowed
        override fun showEffectReminder(attackId: Long) { effect += attackId }
        override fun showPressureDrop(dropHpa: Int) { drops += dropHpa }
        override fun cancelEffectReminder(attackId: Long) { cancelled += attackId }
    }

    private val zone = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 11, 23)
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant()

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: DataStoreSettingsRepository
    private lateinit var pressure: FakePressureRepository
    private lateinit var state: NotifyStateStore
    private lateinit var notifier: FakeNotifier
    private fun store(n: String) = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { File(tmp.root, n) }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = AppDatabase.inMemory(ctx)
        settings = DataStoreSettingsRepository(store("s.preferences_pb"))
        pressure = FakePressureRepository(lastFetchedAt = at(today, 18))
        state = NotifyStateStore(store("n.preferences_pb"))
        notifier = FakeNotifier()
    }

    @After fun tearDown() = db.close()

    /** 時刻 -> hPa の 1 時間値を [from, to] で作る。f(hourIndex)。 */
    private fun series(from: Instant, hours: Int, f: (Int) -> Double) = (0 until hours).map { PressurePoint(from.plusSeconds(it * 3600L), f(it)) }

    // ---- 判定ロジック (FR-501) ----
    @Test
    fun FR_501_drop_is_max_over_24h_window_before_each_hour_of_target_day() {
        val tomorrow = today.plusDays(1)
        // 今日 20:00 に 1010、明日 12:00 に 1002 → 24h 以内の最大 (1010) との差 8
        val s = series(at(today, 0), 72) { 1000.0 } .map {
            when (it.time) { at(today, 20) -> it.copy(hpa = 1010.0); at(tomorrow, 12) -> it.copy(hpa = 1002.0); else -> it }
        }
        assertEquals(10.0, PressureDropDetector.maxDrop(s, tomorrow, zone)!!, 1e-9) // 今日20:00(1010) → 明日 前後の 1000 まで 10
        val s2 = series(at(today, 0), 72) { 1000.0 }.map { if (it.time == at(today, 20)) it.copy(hpa = 1010.0) else it }
        // 1010 が窓 (24h) から外れる明日 20:00 以降は差が出ない。窓内の最小は 明日 19:00 以前の 1000 → 差 10
        assertEquals(10.0, PressureDropDetector.maxDrop(s2, tomorrow, zone)!!, 1e-9)
    }

    @Test
    fun FR_501_peak_older_than_24h_does_not_count() {
        val tomorrow = today.plusDays(1)
        // ピークは今日 00:00 (明日 00:00 の 24h 前ちょうどは窓内、1 時間前の今日 23:00 より前は明日 0:00〜 で外れていく)
        val s = series(at(today, 0), 72) { 1000.0 }.map { if (it.time == at(today, 0)) it.copy(hpa = 1020.0) else it }
        // 明日 00:00 の窓は [今日 00:00, 明日 00:00) に 1020 を含む → 20
        assertEquals(20.0, PressureDropDetector.maxDrop(s, tomorrow, zone)!!, 1e-9)
        // ピークが今日 00:00 より前 (23:00 前日) なら明日の窓に入らない
        val s2 = series(at(today.minusDays(1), 23), 80) { 1000.0 }.map { if (it.time == at(today.minusDays(1), 23)) it.copy(hpa = 1020.0) else it }
        assertEquals(0.0, PressureDropDetector.maxDrop(s2, tomorrow, zone)!!, 1e-9)
    }

    @Test
    fun FR_501_threshold_boundary_is_inclusive_and_missing_data_is_not_a_drop() {
        assertTrue(PressureDropDetector.isDrop(6.0, 6.0))
        assertTrue(PressureDropDetector.isDrop(6.0000000001, 6.0))
        assertFalse(PressureDropDetector.isDrop(5.99, 6.0))
        assertFalse(PressureDropDetector.isDrop(null, 6.0))
        // 対象日にデータなし / 比較対象の過去がない
        assertNull(PressureDropDetector.maxDrop(emptyList(), today.plusDays(1), zone))
        assertNull(PressureDropDetector.maxDrop(series(at(today.plusDays(1), 3), 1) { 1000.0 }, today.plusDays(1), zone))
        assertNull(PressureDropDetector.maxDrop(series(at(today, 0), 24) { 1000.0 }, today.plusDays(1), zone)) // 明日の点なし
    }

    private suspend fun enableDrop(threshold: Double = 6.0) =
        settings.update { it.copy(prefectureCode = "13", dropNotifyEnabled = true, dropThresholdHpa = threshold) }

    /** 今日 0:00〜明後日 0:00。明日の 12:00 から 1010→1003 (差 7)。 */
    private fun forecastWithDrop(drop: Double) {
        pressure.points.value = series(at(today, 0), 48) { h -> if (h < 36) 1010.0 else 1010.0 - drop }
    }

    private fun job(now: Instant) = DropNotifyJob(settings, pressure, state, notifier, { zone }, { now })

    @Test
    fun FR_501_502_notifies_with_rounded_fact_when_drop_meets_threshold() = runBlocking {
        enableDrop(); forecastWithDrop(7.0)
        assertEquals(Result.NOTIFIED, job(at(today, 20)).run(at(today, 20)))
        assertEquals(listOf(7), notifier.drops)
    }

    @Test
    fun FR_501_no_notification_below_threshold_or_when_disabled_or_no_prefecture() = runBlocking {
        enableDrop(); forecastWithDrop(5.9)
        assertEquals(Result.BELOW_THRESHOLD, job(at(today, 20)).run(at(today, 20)))
        settings.update { it.copy(dropNotifyEnabled = false) }
        forecastWithDrop(9.0)
        assertEquals(Result.DISABLED, job(at(today, 20)).run(at(today, 20)))
        settings.update { it.copy(dropNotifyEnabled = true, prefectureCode = null) }
        assertEquals(Result.NO_PREFECTURE, job(at(today, 20)).run(at(today, 20)))
        assertTrue(notifier.drops.isEmpty())
    }

    @Test
    fun FR_501_at_most_once_per_day() = runBlocking {
        enableDrop(); forecastWithDrop(8.0)
        assertEquals(Result.NOTIFIED, job(at(today, 20)).run(at(today, 20)))
        assertEquals(Result.ALREADY_TODAY, job(at(today, 20, 30)).run(at(today, 20)))
        assertEquals(1, notifier.drops.size)
    }

    @Test
    fun FR_504_no_notification_when_last_fetch_36h_or_older_or_never() = runBlocking {
        enableDrop(); forecastWithDrop(8.0)
        pressure.lastFetched.value = at(today, 20).minusSeconds(36 * 3600L) // ちょうど 36h → 出さない
        assertEquals(Result.STALE_FORECAST, job(at(today, 20)).run(at(today, 20)))
        pressure.lastFetched.value = null
        assertEquals(Result.STALE_FORECAST, job(at(today, 20)).run(at(today, 20)))
        pressure.lastFetched.value = at(today, 20).minusSeconds(36 * 3600L - 60) // 35h59m → 出す
        assertEquals(Result.NOTIFIED, job(at(today, 20)).run(at(today, 20)))
    }

    @Test
    fun FR_501_no_notification_if_forecast_missing_or_run_is_far_too_late_or_not_allowed() = runBlocking {
        enableDrop()
        pressure.points.value = emptyList()
        assertEquals(Result.NO_DATA, job(at(today, 20)).run(at(today, 20)))
        forecastWithDrop(8.0)
        assertEquals(Result.TOO_LATE, job(at(today, 23, 30)).run(at(today, 20))) // 3.5h 遅れ: 「明日」が誤りになる
        notifier.allowed = false // FR-503: 権限なし
        assertEquals(Result.NOT_ALLOWED, job(at(today, 20)).run(at(today, 20)))
        assertTrue(notifier.drops.isEmpty())
        notifier.allowed = true
        assertEquals(Result.NOTIFIED, job(at(today, 20)).run(at(today, 20))) // 拒否時は「出した日」にしない
    }

    @Test
    fun FR_501_next_fire_time_today_if_ahead_else_tomorrow() {
        val t = LocalTime.of(20, 0)
        assertEquals(at(today, 20), DropNotifyScheduler.nextFireAt(at(today, 9), t, zone))
        assertEquals(at(today.plusDays(1), 20), DropNotifyScheduler.nextFireAt(at(today, 20), t, zone)) // ちょうどなら翌日
        assertEquals(at(today.plusDays(1), 20), DropNotifyScheduler.nextFireAt(at(today, 21), t, zone))
    }

    @Test
    fun FR_503_drop_work_scheduled_when_enabled_and_cancelled_when_disabled() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val wm = WorkManager.getInstance(ctx)
        DropNotifyScheduler.reschedule(ctx, Settings(null, dropNotifyEnabled = true), at(today, 9), zone)
        DropNotifyScheduler.reschedule(ctx, Settings(null, dropNotifyEnabled = true), at(today, 9), zone) // REPLACE で1件
        val live = wm.getWorkInfosForUniqueWork(DropNotifyScheduler.NAME).get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, live.size)
        DropNotifyScheduler.reschedule(ctx, Settings(null, dropNotifyEnabled = false), at(today, 9), zone)
        assertTrue(wm.getWorkInfosForUniqueWork(DropNotifyScheduler.NAME).get().none { it.state == WorkInfo.State.ENQUEUED })
    }

    // ---- 効果リマインド (FR-500, 503) ----
    private fun insertAttack(id: Long, startedAt: Instant, vararg effects: String) = runBlocking {
        db.attackDao().insertAttack(AttackEntity(id, startedAt.toEpochMilli(), "MILD", null))
        effects.forEach { db.attackDao().insertIntake(IntakeEntity(0, id, 1, 1, it)) }
    }

    @Test
    fun FR_500_effect_reminder_fires_only_if_still_unrated_and_enabled_and_attack_exists() = runBlocking {
        settings.update { it.copy(effectReminderEnabled = true) }
        val j = EffectReminderJob(db, settings, notifier)
        insertAttack(1, at(today, 10), "UNRATED")
        insertAttack(2, at(today, 10), "EFFECTIVE")
        insertAttack(3, at(today, 10), "EFFECTIVE", "UNRATED") // 一部未評価
        assertTrue(j.run(1)); assertFalse(j.run(2)); assertTrue(j.run(3)); assertFalse(j.run(99)) // 削除済み
        assertEquals(listOf(1L, 3L), notifier.effect)
        settings.update { it.copy(effectReminderEnabled = false) }
        assertFalse(j.run(1))
        settings.update { it.copy(effectReminderEnabled = true) }
        notifier.allowed = false
        assertFalse(j.run(1)) // FR-503: 権限なしでも例外にならない
    }

    @Test
    fun FR_500_work_manager_scheduler_enqueues_unique_per_attack_and_cancel_also_clears_notification() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val wm = WorkManager.getInstance(ctx)
        val s = WorkManagerReminderScheduler(ctx, notifier)
        s.scheduleEffectReminder(5, 120)
        s.scheduleEffectReminder(5, 60) // 置換 (更新)
        s.scheduleEffectReminder(6, 120)
        fun live(id: Long) = wm.getWorkInfosForUniqueWork("effect-$id").get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, live(5).size); assertEquals(1, live(6).size)
        assertTrue(live(5)[0].tags.contains("effect-5"))
        s.cancelEffectReminder(5)
        assertTrue(live(5).isEmpty()); assertEquals(1, live(6).size)
        assertEquals(listOf(5L), notifier.cancelled)
    }

    // ---- 起動時の再予約 ----
    private class RecordingReminders : ReminderScheduler {
        val scheduled = mutableListOf<Pair<Long, Int>>()
        override fun scheduleEffectReminder(attackId: Long, delayMinutes: Int) { scheduled += attackId to delayMinutes }
        override fun cancelEffectReminder(attackId: Long) = Unit
    }

    @Test
    fun FR_500_bootstrap_reschedules_drop_and_only_recent_unrated_attacks_without_existing_work() = runBlocking {
        val now = at(today, 12)
        insertAttack(1, now.minusSeconds(3600), "UNRATED") // 復元対象
        insertAttack(2, now.minusSeconds(3600), "UNRATED") // 既に記録あり
        insertAttack(3, now.minusSeconds(3600), "EFFECTIVE")
        insertAttack(4, now.minusSeconds(13 * 3600L), "UNRATED") // 古い
        settings.update { it.copy(effectReminderEnabled = true, effectReminderDelayMinutes = 90, dropNotifyEnabled = true) }
        val r = RecordingReminders(); var dropScheduled: Settings? = null
        NotifyBootstrapJob(db, settings, r, { it == 2L }, { dropScheduled = it }, { now }).run()
        assertEquals(listOf(1L to 90), r.scheduled)
        assertTrue(dropScheduled!!.dropNotifyEnabled)
    }

    @Test
    fun FR_503_bootstrap_does_not_restore_effect_reminders_when_disabled() = runBlocking {
        insertAttack(1, at(today, 11), "UNRATED")
        settings.update { it.copy(effectReminderEnabled = false) }
        val r = RecordingReminders()
        NotifyBootstrapJob(db, settings, r, { false }, { }, { at(today, 12) }).run()
        assertTrue(r.scheduled.isEmpty())
    }

    // ---- 設定変更フック ----
    @Test
    fun FR_100_settings_update_triggers_callback_only_on_change() = runBlocking {
        val calls = mutableListOf<Pair<String?, String?>>()
        val repo = NotifyAwareSettingsRepository(settings) { o, n -> calls += o.prefectureCode to n.prefectureCode }
        repo.update { it.copy(prefectureCode = "13") }
        repo.update { it.copy(prefectureCode = "13") } // 変化なし
        repo.update { it.copy(prefectureCode = "27") }
        assertEquals(listOf<Pair<String?, String?>>(null to "13", "13" to "27"), calls)
    }

    @Test
    fun FR_501_next_day_is_rescheduled_even_if_job_throws_but_not_on_cancellation() = runBlocking {
        var rescheduled = 0
        val r = runCatching { runThenReschedule(job = { throw IllegalStateException("datastore") }, reschedule = { rescheduled++ }) }
        assertTrue(r.exceptionOrNull() is IllegalStateException)
        assertEquals(1, rescheduled)
        runThenReschedule(job = { }, reschedule = { rescheduled++ })
        assertEquals(2, rescheduled)
        val c = runCatching { runThenReschedule(job = { throw kotlinx.coroutines.CancellationException("x") }, reschedule = { rescheduled++ }) }
        assertTrue(c.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(2, rescheduled) // キャンセル時は予約しない
    }

    @Test
    fun FR_505_notification_settings_default_to_off() = runBlocking {
        val d = settings.observe().first()
        assertFalse(d.effectReminderEnabled); assertFalse(d.dropNotifyEnabled)
    }

    @Test
    fun FR_505_enabling_effect_reminder_schedules_recent_unrated_attacks_not_already_active() = runBlocking {
        val now = at(today, 12)
        insertAttack(1, now.minusSeconds(600), "UNRATED") // 直前に保存した発作 ("受け取る" の直後)
        insertAttack(2, now.minusSeconds(3600), "UNRATED") // 既に有効な予約あり
        insertAttack(3, now.minusSeconds(3600), "EFFECTIVE")
        insertAttack(4, now.minusSeconds(13 * 3600L), "UNRATED") // 12h より古い
        val r = RecordingReminders()
        val repo = NotifyAwareSettingsRepository(settings) { old, new ->
            if (!old.effectReminderEnabled && new.effectReminderEnabled) EffectReminderSync(db, r) { now }.restore(new) { it == 2L }
        }
        repo.update { it.copy(effectReminderDelayMinutes = 30) } // 有効化ではない → 何もしない
        assertTrue(r.scheduled.isEmpty())
        repo.update { it.copy(effectReminderEnabled = true) }
        assertEquals(listOf(1L to 30), r.scheduled)
    }

    @Test
    fun FR_505_disabling_effect_reminder_cancels_pending_ones_for_recent_unrated_attacks() = runBlocking {
        val now = at(today, 12)
        insertAttack(1, now.minusSeconds(600), "UNRATED")
        insertAttack(2, now.minusSeconds(600), "EFFECTIVE")
        val cancelled = mutableListOf<Long>()
        val r = object : ReminderScheduler {
            override fun scheduleEffectReminder(attackId: Long, delayMinutes: Int) = Unit
            override fun cancelEffectReminder(attackId: Long) { cancelled += attackId }
        }
        EffectReminderSync(db, r) { now }.cancelPending()
        assertEquals(listOf(1L), cancelled)
    }

    @Test
    fun FR_505_restore_does_nothing_when_effect_reminder_is_off() = runBlocking {
        insertAttack(1, at(today, 11), "UNRATED")
        val r = RecordingReminders()
        EffectReminderSync(db, r) { at(today, 12) }.restore(Settings(null, effectReminderEnabled = false)) { false }
        assertTrue(r.scheduled.isEmpty())
    }

    @Test
    fun FR_105_pressure_work_retries_up_to_the_cap_then_gives_up_until_next_daily_run() {
        val cap = io.github.abe_ma.headachediary.data.work.PressureRefreshJob.MAX_RETRIES
        fun retry(n: Int) = io.github.abe_ma.headachediary.data.work.PressureRefreshJob.shouldRetry(n)
        assertTrue((0 until cap).all { retry(it) }) // 1〜cap 回目の失敗は再試行
        assertFalse(retry(cap)); assertFalse(retry(cap + 5)) // 上限超えは打ち切り
    }

    // ---- 設定変更フック (配線を実際に通す) ----
    private class HookProbe {
        var pressureNow = 0
        val dropReschedules = mutableListOf<Settings>()
        val reminders = RecordingReminders()
        val cancelled = mutableListOf<Long>()
        val scheduler = object : ReminderScheduler {
            override fun scheduleEffectReminder(attackId: Long, delayMinutes: Int) = reminders.scheduleEffectReminder(attackId, delayMinutes)
            override fun cancelEffectReminder(attackId: Long) { cancelled += attackId }
        }
    }

    private fun hookedRepo(
        probe: HookProbe,
        now: Instant,
        enqueuePressureNow: () -> Unit = { probe.pressureNow++ },
        hasActive: suspend (Long) -> Boolean = { false },
    ): NotifyAwareSettingsRepository {
        val handler = io.github.abe_ma.headachediary.data.notify.SettingsChangeHandler(
            enqueuePressureNow = enqueuePressureNow,
            rescheduleDrop = { probe.dropReschedules += it },
            effectSync = EffectReminderSync(db, probe.scheduler) { now },
            hasActiveEffectWork = hasActive,
        )
        return NotifyAwareSettingsRepository(settings) { o, n -> handler(o, n) }
    }

    @Test
    fun FR_505_settings_update_runs_all_side_effects_through_the_real_wiring() = runBlocking {
        val now = at(today, 12)
        insertAttack(1, now.minusSeconds(600), "UNRATED")
        val p = HookProbe(); val repo = hookedRepo(p, now)
        repo.update { it.copy(prefectureCode = "13") }
        repo.update { it.copy(prefectureCode = "13") } // 変化なし → 何も起きない
        assertEquals(1, p.pressureNow)
        repo.update { it.copy(dropNotifyEnabled = true) }
        assertEquals(1, p.dropReschedules.size); assertTrue(p.dropReschedules[0].dropNotifyEnabled)
        repo.update { it.copy(effectReminderEnabled = true, effectReminderDelayMinutes = 45) }
        assertEquals(listOf(1L to 45), p.reminders.scheduled)
        repo.update { it.copy(effectReminderEnabled = false) }
        assertEquals(listOf(1L), p.cancelled)
        assertEquals("13", settings.observe().first().prefectureCode)
    }

    @Test
    fun FR_505_side_effect_failures_do_not_break_update_and_do_not_skip_other_effects() = runBlocking {
        val now = at(today, 12)
        insertAttack(1, now.minusSeconds(600), "UNRATED")
        val p = HookProbe()
        val repo = hookedRepo(p, now, enqueuePressureNow = { throw IllegalStateException("WorkManager not initialized") })
        repo.update { it.copy(prefectureCode = "13", effectReminderEnabled = true) } // 例外にならない
        assertEquals("13", settings.observe().first().prefectureCode) // 設定は保存済み
        assertEquals(listOf(1L to 120), p.reminders.scheduled) // 先に失敗した処理の後も実行される
    }

    @Test
    fun FR_505_storage_exception_in_hook_does_not_crash_the_update() = runBlocking {
        val now = at(today, 12)
        insertAttack(1, now.minusSeconds(600), "UNRATED")
        val throwing = object : ReminderScheduler {
            override fun scheduleEffectReminder(attackId: Long, delayMinutes: Int) { throw android.database.sqlite.SQLiteException("disk full") }
            override fun cancelEffectReminder(attackId: Long) = Unit
        }
        val handler = io.github.abe_ma.headachediary.data.notify.SettingsChangeHandler({ }, { }, EffectReminderSync(db, throwing) { now }, { false })
        val repo = NotifyAwareSettingsRepository(settings) { o, n -> handler(o, n) }
        repo.update { it.copy(effectReminderEnabled = true) } // SQLiteException は握られる
        assertTrue(settings.observe().first().effectReminderEnabled) // 設定は保存済み
    }

    @Test
    fun FR_505_cancellation_inside_a_side_effect_is_rethrown() = runBlocking {
        val p = HookProbe()
        val h = io.github.abe_ma.headachediary.data.notify.SettingsChangeHandler(
            enqueuePressureNow = { throw kotlinx.coroutines.CancellationException("cancelled") },
            rescheduleDrop = { },
            effectSync = EffectReminderSync(db, p.scheduler),
            hasActiveEffectWork = { false },
        )
        val r = runCatching { h(Settings(null), Settings("13")) }
        assertTrue(r.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
