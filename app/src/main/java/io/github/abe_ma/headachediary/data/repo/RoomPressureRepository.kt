package io.github.abe_ma.headachediary.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
import kotlinx.coroutines.flow.combine
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

    /** 最終取得時刻は地域ごとに持つ (FR-504: 新地域の予報が無いのに旧地域の時刻で「新しい」と誤判定しない)。 */
    private fun lastFetchedKey(code: String) = longPreferencesKey("lastFetchedAtMs_$code")

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

    /**
     * 契約: 例外を投げない (CancellationException を除く)。
     * 読み出し・通信・保存の各段階の失敗を RefreshResult.Failure に変換する。
     * 保存系 (SQLite/DataStore) の失敗は専用の理由が無いため API_ERROR とする (設計の FailureReason を増やさない)。
     */
    override suspend fun refresh(): RefreshResult {
        // 1) 準備: 設定と前回取得時刻の読み出し
        val code: String
        val pref: io.github.abe_ma.headachediary.domain.Prefecture
        val pastDays: Int
        try {
            code = settings.observe().first().prefectureCode ?: return RefreshResult.Failure(FailureReason.NO_PREFECTURE)
            pref = prefectures.all().firstOrNull { it.code == code }
                ?: return RefreshResult.Failure(FailureReason.NO_PREFECTURE)
            val last = meta.data.first()[lastFetchedKey(code)]?.let(Instant::ofEpochMilli)
            pastDays = pastDaysFor(last, now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RefreshResult.Failure(FailureReason.API_ERROR)
        }
        // 2) 通信
        val rows = try {
            val h = api.forecast(round1(pref.lat), round1(pref.lon), pastDays = pastDays).hourly
            if (h == null || h.time.size != h.pressureMsl.size) return RefreshResult.Failure(FailureReason.API_ERROR)
            h.time.indices.mapNotNull { i ->
                h.pressureMsl[i]?.let { PressureHourEntity(code, h.time[i] * 1000, it) } // 欠損値は保存しない
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            return RefreshResult.Failure(FailureReason.API_ERROR)
        } catch (e: SerializationException) {
            return RefreshResult.Failure(FailureReason.API_ERROR)
        } catch (e: IOException) {
            return RefreshResult.Failure(FailureReason.NETWORK)
        } catch (e: IllegalArgumentException) { // 想定外の応答形式
            return RefreshResult.Failure(FailureReason.API_ERROR)
        } catch (e: Exception) {
            return RefreshResult.Failure(FailureReason.API_ERROR)
        }
        // 3) 保存 (ディスクフル等の SQLiteException / DataStore の IOException もここで Failure にする)
        return try {
            dao.upsertAll(rows)
            meta.edit { it[lastFetchedKey(code)] = now().toEpochMilli() }
            RefreshResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RefreshResult.Failure(FailureReason.API_ERROR)
        }
    }

    /** 現在の登録地域の最終取得時刻。地域を変えると、その地域で未取得なら null。 */
    override fun observeLastFetchedAt(): Flow<Instant?> =
        combine(prefectureFlow(), meta.data) { code, p ->
            code?.let { p[lastFetchedKey(it)] }?.let(Instant::ofEpochMilli)
        }.distinctUntilChanged()

    companion object {
        const val FIRST_PAST_DAYS = 31
        const val DAILY_PAST_DAYS = 7
        const val MAX_PAST_DAYS = 92 // Open-Meteo の上限

        /**
         * 取得する過去日数。この地域で未取得なら 31。以降は通常 7 日、取得が途切れていたら
         * 「前回からの経過日数 + 1」(上限 92) に広げ、失敗が続いた期間の穴を埋める (FR-102)。
         */
        fun pastDaysFor(lastFetched: Instant?, now: Instant): Int {
            if (lastFetched == null) return FIRST_PAST_DAYS
            val gapDays = Duration.between(lastFetched, now).toDays().toInt() + 1
            return gapDays.coerceIn(DAILY_PAST_DAYS, MAX_PAST_DAYS)
        }

        /** NFR-100: 座標は小数1桁に丸めて送る。 */
        fun round1(v: Double): Double = (v * 10).roundToLong() / 10.0
    }
}
