package io.github.abe_ma.headachediary.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.repo.PressureDelta
import io.github.abe_ma.headachediary.domain.FailureReason
import io.github.abe_ma.headachediary.domain.PressureRepository
import io.github.abe_ma.headachediary.domain.RefreshResult
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** ジョブ本体 (Worker から切り離してテストする)。 */
class PressureRefreshJob(
    private val pressure: PressureRepository,
    private val db: AppDatabase,
    private val now: () -> Instant = Instant::now,
) {
    enum class Outcome { DONE, RETRY }

    /**
     * 取得 → 成功したら気圧が null の直近発作 (31 日以内) を補完 (FR-104)。
     * 地域未設定は何もせず DONE。通信/API 失敗は RETRY (WorkManager の指数バックオフ。FR-105)。
     */
    suspend fun run(): Outcome = when (val r = pressure.refresh()) {
        RefreshResult.Success -> { backfill(); Outcome.DONE }
        is RefreshResult.Failure ->
            if (r.reason == FailureReason.NO_PREFECTURE) Outcome.DONE else Outcome.RETRY
    }

    private suspend fun backfill() {
        val dao = db.attackDao()
        val since = now().minus(Duration.ofDays(BACKFILL_DAYS)).toEpochMilli()
        // 気圧が欠損、または前日との気圧差 (FR-404) が欠損している直近の発作を、取れるようになった分だけ埋める。
        dao.getMissingPressureSince(since).forEach { a ->
            val t = Instant.ofEpochMilli(a.startedAt)
            val current = pressure.pressureAt(t) // 現在の登録地域の p(t)
            val hpa = a.pressureHpa ?: current
            // 差を埋めるのは、保存済みの気圧が現在地域の p(t) と 0.1 hPa 以内で一致するときだけ (編集保存と共通のガード)。
            val delta = if (a.pressureDeltaHpa == null) PressureDelta.resolveIfSameRegion(pressure, t, hpa, current) else null
            if (hpa != a.pressureHpa || delta != null) dao.fillPressure(a.id, hpa, delta)
        }
    }

    companion object {
        const val BACKFILL_DAYS = 31L

        /** Result.retry() の上限 (指数バックオフ 15分〜)。超えたら次の日次実行に任せる。 */
        const val MAX_RETRIES = 4

        /** 失敗後に再試行するか。runAttemptCount は 0 始まりの実行回数。上限に達したら成功扱いで終え、翌日の定期実行に任せる。 */
        fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_RETRIES
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PressureWorkEntryPoint {
    fun pressureRepository(): PressureRepository
    fun database(): AppDatabase
}

/**
 * 日次の気圧取得ワーカー。HiltWorkerFactory を使わず EntryPoint で依存を取る
 * (Application / Manifest の WorkManager 初期化設定を変えないため)。
 */
class PressureRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        io.github.abe_ma.headachediary.data.export.ExportFiles.deleteOld(applicationContext) // 古い書き出しファイルの掃除
        val ep = EntryPointAccessors.fromApplication(applicationContext, PressureWorkEntryPoint::class.java)
        return when (PressureRefreshJob(ep.pressureRepository(), ep.database()).run()) {
            PressureRefreshJob.Outcome.DONE -> Result.success()
            // 失敗が続く場合は上限で打ち切る (日次の定期実行が翌日また試す)。
            PressureRefreshJob.Outcome.RETRY -> if (PressureRefreshJob.shouldRetry(runAttemptCount)) Result.retry() else Result.success()
        }
    }
}

object PressureRefreshScheduler {
    const val DAILY_NAME = "pressure-daily"
    const val NOW_NAME = "pressure-now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** アプリ起動時に呼ぶ (冪等。既存の予約は維持)。FR-101 */
    fun ensureDaily(context: Context) {
        val req = PeriodicWorkRequestBuilder<PressureRefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** 地域変更直後などに 1 回すぐ取得する (ネットワーク接続時)。 */
    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<PressureRefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.REPLACE, req)
    }
}
