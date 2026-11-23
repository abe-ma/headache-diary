package io.github.abe_ma.headachediary.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.abe_ma.headachediary.data.catalog.StaticPrefectureRepository
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.db.AttackEntity
import io.github.abe_ma.headachediary.data.remote.OpenMeteoApi
import io.github.abe_ma.headachediary.data.repo.DataStoreSettingsRepository
import io.github.abe_ma.headachediary.data.repo.RoomPressureRepository
import io.github.abe_ma.headachediary.data.work.PressureRefreshJob
import io.github.abe_ma.headachediary.data.work.PressureRefreshScheduler
import io.github.abe_ma.headachediary.domain.FailureReason
import io.github.abe_ma.headachediary.domain.RefreshResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var settings: DataStoreSettingsRepository
    private lateinit var repo: RoomPressureRepository
    private val now = Instant.parse("2026-11-23T03:00:00Z")
    // 2026-11-23T00:00:00Z = 1795392000 (秒)
    private val base = Instant.parse("2026-11-23T00:00:00Z").epochSecond

    private fun store(name: String) = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { File(tmp.root, name) }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        settings = DataStoreSettingsRepository(store("settings.preferences_pb"))
        repo = RoomPressureRepository(
            db.pressureDao(), OpenMeteoApi.create(server.url("/").toString()), settings,
            StaticPrefectureRepository(), store("meta.preferences_pb"), { now },
        )
    }

    @After
    fun tearDown() { db.close(); server.shutdown() }

    private fun body(vararg v: Double?, start: Long = base): String =
        """{"latitude":35.7,"hourly":{"time":[${v.indices.joinToString(",") { (start + it * 3600).toString() }}],""" +
            """"pressure_msl":[${v.joinToString(",") { it?.toString() ?: "null" }}]}}"""

    private fun ok(json: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json)

    @Test
    fun FR_101_refresh_stores_hourly_values_skipping_missing_and_sets_last_fetched() = runBlocking {
        settings.update { it.copy(prefectureCode = "13") }
        server.enqueue(ok(body(1010.0, null, 1008.5)))
        assertEquals(RefreshResult.Success, repo.refresh())
        val series = repo.observeSeries(Instant.ofEpochSecond(base)..Instant.ofEpochSecond(base + 7200)).first()
        assertEquals(listOf(1010.0, 1008.5), series.map { it.hpa }) // 欠損 (null) は保存しない
        assertEquals(Instant.ofEpochSecond(base), series[0].time)
        assertEquals(now, repo.observeLastFetchedAt().first())
    }

    @Test
    fun FR_102_data_persists_offline_after_failed_refresh() = runBlocking {
        settings.update { it.copy(prefectureCode = "13") }
        server.enqueue(ok(body(1010.0)))
        repo.refresh()
        server.shutdown() // 接続不可 (ConnectException)
        assertEquals(RefreshResult.Failure(FailureReason.NETWORK), repo.refresh())
        assertEquals(1, repo.observeSeries(Instant.ofEpochSecond(base)..Instant.ofEpochSecond(base)).first().size)
    }

    @Test
    fun FR_105_http_error_returns_api_error_without_throwing_and_keeps_last_fetched() = runBlocking {
        settings.update { it.copy(prefectureCode = "13") }
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(RefreshResult.Failure(FailureReason.API_ERROR), repo.refresh())
        assertNull(repo.observeLastFetchedAt().first())
    }

    @Test
    fun FR_105_malformed_or_mismatched_response_is_api_error() = runBlocking {
        settings.update { it.copy(prefectureCode = "13") }
        server.enqueue(ok("{not json"))
        assertEquals(RefreshResult.Failure(FailureReason.API_ERROR), repo.refresh())
        server.enqueue(ok("""{"hourly":{"time":[1,2],"pressure_msl":[1000.0]}}"""))
        assertEquals(RefreshResult.Failure(FailureReason.API_ERROR), repo.refresh())
        server.enqueue(ok("""{"latitude":1}""")) // hourly なし
        assertEquals(RefreshResult.Failure(FailureReason.API_ERROR), repo.refresh())
    }

    @Test
    fun FR_100_refresh_without_prefecture_fails_without_network_call() = runBlocking {
        assertEquals(RefreshResult.Failure(FailureReason.NO_PREFECTURE), repo.refresh())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun NFR_100_request_has_only_rounded_coordinates_and_period_params_no_health_data() = runBlocking {
        settings.update { it.copy(prefectureCode = "13") } // 新宿区 35.6895, 139.6917
        // 健康データが DB にあっても送られないこと
        db.attackDao().insertAttack(AttackEntity(startedAt = base * 1000, severity = "SEVERE", pressureHpa = 1000.0))
        server.enqueue(ok(body(1000.0)))
        repo.refresh()
        val req = server.takeRequest()
        val url = req.requestUrl!!
        assertEquals("/v1/forecast", url.encodedPath)
        assertEquals(
            setOf("latitude", "longitude", "hourly", "past_days", "forecast_days", "timezone", "timeformat"),
            url.queryParameterNames,
        )
        assertEquals("35.7", url.queryParameter("latitude"))
        assertEquals("139.7", url.queryParameter("longitude"))
        assertEquals("pressure_msl", url.queryParameter("hourly"))
        assertEquals("Asia/Tokyo", url.queryParameter("timezone"))
        assertEquals("unixtime", url.queryParameter("timeformat"))
        assertEquals("3", url.queryParameter("forecast_days"))
        assertNull(req.getHeader("Authorization")); assertNull(req.getHeader("Cookie")) // NFR-102 キー不使用
        assertFalse(req.requestUrl.toString().contains("SEVERE"))
        assertEquals("", req.body.readUtf8()) // GET、ボディなし
    }

    @Test
    fun FR_101_past_days_31_on_first_fetch_for_region_then_7_and_31_again_after_region_change() = runBlocking {
        fun nextPast(): String? { server.enqueue(ok(body(1000.0))); return null }
        settings.update { it.copy(prefectureCode = "13") }
        nextPast(); repo.refresh()
        nextPast(); repo.refresh()
        settings.update { it.copy(prefectureCode = "27") }
        nextPast(); repo.refresh()
        assertEquals(listOf("31", "7", "31"), (1..3).map { server.takeRequest().requestUrl!!.queryParameter("past_days") })
    }

    @Test
    fun FR_103_series_follows_registered_region_and_old_region_data_is_kept() = runBlocking {
        val range = Instant.ofEpochSecond(base)..Instant.ofEpochSecond(base + 3600)
        settings.update { it.copy(prefectureCode = "13") }
        server.enqueue(ok(body(1010.0))); repo.refresh()
        settings.update { it.copy(prefectureCode = "27") }
        assertTrue(repo.observeSeries(range).first().isEmpty())
        server.enqueue(ok(body(1005.0))); repo.refresh()
        assertEquals(listOf(1005.0), repo.observeSeries(range).first().map { it.hpa })
        settings.update { it.copy(prefectureCode = "13") } // 過去地域のデータは残っている
        assertEquals(listOf(1010.0), repo.observeSeries(range).first().map { it.hpa })
    }

    @Test
    fun FR_104_pressureAt_nearest_within_one_hour_else_null() = runBlocking {
        settings.update { it.copy(prefectureCode = "13") }
        server.enqueue(ok(body(1010.0, 1006.0)))
        repo.refresh()
        val t = Instant.ofEpochSecond(base)
        assertEquals(1010.0, repo.pressureAt(t.plusSeconds(1200))!!, 0.0)
        assertEquals(1006.0, repo.pressureAt(t.plusSeconds(2400))!!, 0.0)
        assertEquals(1006.0, repo.pressureAt(t.plusSeconds(3600 + 3600))!!, 0.0) // ちょうど 1h
        assertNull(repo.pressureAt(t.plusSeconds(3600 + 3601)))
        assertNull(repo.pressureAt(t.minusSeconds(3601)))
    }

    @Test
    fun FR_104_pressureAt_null_when_no_prefecture() = runBlocking {
        assertNull(repo.pressureAt(Instant.ofEpochSecond(base)))
    }

    @Test
    fun FR_104_job_backfills_missing_pressure_of_recent_attacks_only() = runBlocking {
        settings.update { it.copy(prefectureCode = "13") }
        val dao = db.attackDao()
        val recent = dao.insertAttack(AttackEntity(startedAt = (base + 2400) * 1000, severity = "MILD", pressureHpa = null))
        val has = dao.insertAttack(AttackEntity(startedAt = (base + 1800) * 1000, severity = "MILD", pressureHpa = 999.0))
        val old = dao.insertAttack(AttackEntity(startedAt = (base - 40 * 86400) * 1000, severity = "MILD", pressureHpa = null))
        val nodata = dao.insertAttack(AttackEntity(startedAt = (base + 20 * 3600) * 1000, severity = "MILD", pressureHpa = null))
        server.enqueue(ok(body(1010.0, 1006.0)))
        val out = PressureRefreshJob(repo, db) { now }.run()
        assertEquals(PressureRefreshJob.Outcome.DONE, out)
        suspend fun hpa(id: Long) = dao.observeAttack(id).first()!!.attack.pressureHpa
        assertEquals(1006.0, hpa(recent)!!, 0.0) // 40分後 → +1h の点が最寄り
        assertEquals(999.0, hpa(has)!!, 0.0) // 既存値は上書きしない
        assertNull(hpa(old)); assertNull(hpa(nodata))
    }

    @Test
    fun FR_105_job_retries_on_failure_and_is_done_without_prefecture() = runBlocking {
        assertEquals(PressureRefreshJob.Outcome.DONE, PressureRefreshJob(repo, db).run())
        settings.update { it.copy(prefectureCode = "13") }
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(PressureRefreshJob.Outcome.RETRY, PressureRefreshJob(repo, db).run())
    }

    @Test
    fun FR_101_daily_work_is_unique_periodic_with_network_constraint_and_backoff() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        PressureRefreshScheduler.ensureDaily(ctx)
        PressureRefreshScheduler.ensureDaily(ctx) // 冪等
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(PressureRefreshScheduler.DAILY_NAME).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertNotNull(info.periodicityInfo)
        assertEquals(24 * 3600 * 1000L, info.periodicityInfo!!.repeatIntervalMillis)
    }
}
