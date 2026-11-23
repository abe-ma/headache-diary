package io.github.abe_ma.headachediary.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.abe_ma.headachediary.data.db.PressureDao
import io.github.abe_ma.headachediary.data.db.PressureHourEntity
import io.github.abe_ma.headachediary.data.remote.OpenMeteoApi
import io.github.abe_ma.headachediary.domain.FailureReason
import io.github.abe_ma.headachediary.domain.PrefectureRepository
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.PressureRepository
import io.github.abe_ma.headachediary.domain.RefreshResult
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 気圧 (海面更正気圧) の取得・保存 (FR-101〜105)。登録地域の 1 時間値を pressure_hour に保存する。
 * 地域を変えても過去地域のデータは削除しない。
 */
class RoomPressureRepository(
    private val dao: PressureDao,
    private val api: OpenMeteoApi,
    private val settings: SettingsRepository,
    private val prefectures: PrefectureRepository,
    private val meta: DataStore<Preferences>,
    private val now: () -> Instant = Instant::now,
) : PressureRepository {

    private object K {
        val lastFetchedAt = longPreferencesKey("lastFetchedAtMs")
        val lastFetchedPrefecture = stringPreferencesKey("lastFetchedPrefecture")
    }

    private fun prefectureFlow(): Flow<String?> = settings.observe().map { it.prefectureCode }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSeries(range: ClosedRange<Instant>): Flow<List<PressurePoint>> =
        prefectureFlow().flatMapLatest { code ->
            if (code == null) flowOf(emptyList())
            else dao.observeSeries(code, range.start.toEpochMilli(), range.endInclusive.toEpochMilli())
                .map { rows -> rows.map { PressurePoint(Instant.ofEpochMilli(it.time), it.hpa) } }
        }

    override suspend fun pressureAt(time: Instant): Double? {
        val code = settings.observe().first().prefectureCode ?: return null
        val t = time.toEpochMilli()
        val w = Duration.ofHours(1).toMillis()
        return dao.getRange(code, t - w, t + w).minByOrNull { abs(it.time - t) }?.hpa
    }

    override suspend fun refresh(): RefreshResult {
        val code = settings.observe().first().prefectureCode ?: return RefreshResult.Failure(FailureReason.NO_PREFECTURE)
        val pref = prefectures.all().firstOrNull { it.code == code }
            ?: return RefreshResult.Failure(FailureReason.NO_PREFECTURE)
        // 地域設定直後 (この地域で未取得) は 31 日、通常は 7 日。
        val first = meta.data.first()[K.lastFetchedPrefecture] != code
        val pastDays = if (first) FIRST_PAST_DAYS else DAILY_PAST_DAYS
        return try {
            val res = api.forecast(round1(pref.lat), round1(pref.lon), pastDays = pastDays)
            val h = res.hourly
            if (h == null || h.time.size != h.pressureMsl.size) {
                return RefreshResult.Failure(FailureReason.API_ERROR)
            }
            val rows = h.time.indices.mapNotNull { i ->
                h.pressureMsl[i]?.let { PressureHourEntity(code, h.time[i] * 1000, it) } // 欠損値は保存しない
            }
            dao.upsertAll(rows)
            meta.edit {
                it[K.lastFetchedAt] = now().toEpochMilli()
                it[K.lastFetchedPrefecture] = code
            }
            RefreshResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            RefreshResult.Failure(FailureReason.API_ERROR)
        } catch (e: SerializationException) {
            RefreshResult.Failure(FailureReason.API_ERROR)
        } catch (e: IOException) {
            RefreshResult.Failure(FailureReason.NETWORK)
        } catch (e: IllegalArgumentException) { // 想定外の応答形式
            RefreshResult.Failure(FailureReason.API_ERROR)
        }
    }

    override fun observeLastFetchedAt(): Flow<Instant?> =
        meta.data.map { p -> p[K.lastFetchedAt]?.let(Instant::ofEpochMilli) }

    companion object {
        const val FIRST_PAST_DAYS = 31
        const val DAILY_PAST_DAYS = 7

        /** NFR-100: 座標は小数1桁に丸めて送る。 */
        fun round1(v: Double): Double = (v * 10).roundToLong() / 10.0
    }
}
