package io.github.abe_ma.headachediary.data.notify

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.domain.PressureRepository
import io.github.abe_ma.headachediary.domain.ReminderScheduler
import io.github.abe_ma.headachediary.domain.Settings
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime
import java.util.concurrent.TimeUnit

private val Context.notifyMetaDataStore: DataStore<Preferences> by preferencesDataStore(name = "notify_meta")

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotifyWorkEntryPoint {
    fun settingsRepository(): SettingsRepository
    fun pressureRepository(): PressureRepository
    fun reminderScheduler(): ReminderScheduler
    fun database(): AppDatabase
}

private fun ep(c: Context) = EntryPointAccessors.fromApplication(c, NotifyWorkEntryPoint::class.java)

/** 効果リマインド (FR-500)。`effect-<attackId>` の OneTimeWork。 */
class WorkManagerReminderScheduler(
    private val context: Context,
    private val notifier: Notifier = AndroidNotifier(context),
) : ReminderScheduler {
    override fun scheduleEffectReminder(attackId: Long, delayMinutes: Int) {
        val req = OneTimeWorkRequestBuilder<EffectReminderWorker>()
            .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
            .setInputData(Data.Builder().putLong(KEY_ATTACK_ID, attackId).build())
            .addTag(tag(attackId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(tag(attackId), ExistingWorkPolicy.REPLACE, req)
    }

    override fun cancelEffectReminder(attackId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(tag(attackId))
        notifier.cancelEffectReminder(attackId) // 表示済みの通知も取り消す
    }

    companion object {
        const val KEY_ATTACK_ID = "attackId"
        fun tag(attackId: Long) = "effect-$attackId"
    }
}

class EffectReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(WorkManagerReminderScheduler.KEY_ATTACK_ID, 0L)
        if (id == 0L) return Result.success()
        val e = ep(applicationContext)
        EffectReminderJob(e.database(), e.settingsRepository(), AndroidNotifier(applicationContext)).run(id)
        return Result.success()
    }
}

/** 気圧低下通知のスケジュール (設定時刻の OneTimeWork。発火後に翌日分を再予約するチェーン)。 */
object DropNotifyScheduler {
    const val NAME = "drop-notify"
    const val KEY_INTENDED = "intendedAtMs"

    /** now より後で最も近い、設定時刻 (ローカル)。 */
    fun nextFireAt(now: Instant, time: LocalTime, zone: ZoneId): Instant {
        val today = now.atZone(zone).toLocalDate().atTime(time).atZone(zone)
        val fire = if (today.toInstant() > now) today else today.plusDays(1)
        return fire.toInstant()
    }

    /** 設定に応じて予約 (オフなら取消)。REPLACE なので何度呼んでもよい。 */
    fun reschedule(
        context: Context, s: Settings, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val wm = WorkManager.getInstance(context)
        if (!s.dropNotifyEnabled) { wm.cancelUniqueWork(NAME); return }
        val at = nextFireAt(now, s.dropNotifyTime, zone)
        val req = OneTimeWorkRequestBuilder<PressureDropWorker>()
            .setInitialDelay(Duration.between(now, at).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong(KEY_INTENDED, at.toEpochMilli()).build())
            .build()
        wm.enqueueUniqueWork(NAME, policy, req)
    }

    /**
     * 発火処理のあとに必ず翌日分を予約する (処理が例外で失敗してもチェーンを止めない)。
     * キャンセルは握らず再送出し、reschedule は走らせない (キャンセル = 設定変更等で別の予約が既にある)。
     */
    internal suspend fun runThenReschedule(job: suspend () -> Unit, reschedule: suspend () -> Unit) {
        try {
            job()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            reschedule()
            throw e
        }
        reschedule()
    }
}

class PressureDropWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val intended = Instant.ofEpochMilli(inputData.getLong(DropNotifyScheduler.KEY_INTENDED, System.currentTimeMillis()))
        val e = ep(applicationContext)
        val settings = e.settingsRepository()
        return try {
            DropNotifyScheduler.runThenReschedule(
                job = {
                    DropNotifyJob(
                        settings, e.pressureRepository(), NotifyStateStore(applicationContext.notifyMetaDataStore),
                        AndroidNotifier(applicationContext),
                    ).run(intended)
                },
                // 翌日分。自分自身 (実行中) を REPLACE で置き換えないよう APPEND_OR_REPLACE。
                reschedule = {
                    DropNotifyScheduler.reschedule(
                        applicationContext, settings.observe().first(), Instant.now().plusSeconds(60),
                        policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                    )
                },
            )
            Result.success()
        } catch (e: Exception) {
            Result.success() // 失敗扱いにしてバックオフで二重実行しない。翌日分は予約済み。
        }
    }
}

/** 起動時の再予約 (App.onCreate から)。 */
object NotifyBootstrap {
    private const val NAME = "notify-bootstrap"
    fun start(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<NotifyBootstrapWorker>().build())
    }
}

class NotifyBootstrapWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val e = ep(applicationContext)
        val wm = WorkManager.getInstance(applicationContext)
        NotifyBootstrapJob(
            e.database(), e.settingsRepository(), e.reminderScheduler(),
            hasEffectWork = { id -> withContext(Dispatchers.IO) { wm.getWorkInfosForUniqueWork(WorkManagerReminderScheduler.tag(id)).get().isNotEmpty() } },
            scheduleDrop = { DropNotifyScheduler.reschedule(applicationContext, it) },
        ).run()
        return Result.success()
    }
}
