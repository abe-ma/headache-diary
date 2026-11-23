package io.github.abe_ma.headachediary.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Open-Meteo (気圧のみ)。NFR-100: 送るのは丸めた緯度経度と期間のみ。ヘッダ/クエリに識別子・健康データを足さないこと。
 * NFR-101: この API は PressureRepository の裏側に隔離する (UI/他層から直接使わない)。
 */
interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "pressure_msl",
        @Query("past_days") pastDays: Int,
        @Query("forecast_days") forecastDays: Int = 3,
        @Query("timezone") timezone: String = "Asia/Tokyo",
        @Query("timeformat") timeFormat: String = "unixtime",
    ): ForecastResponse

    companion object {
        const val BASE_URL = "https://api.open-meteo.com/"

        val json = Json { ignoreUnknownKeys = true }

        fun create(baseUrl: String = BASE_URL, client: OkHttpClient = defaultClient()): OpenMeteoApi =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(OpenMeteoApi::class.java)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
data class ForecastResponse(val hourly: Hourly? = null)

@Serializable
data class Hourly(
    /** unixtime (秒)。 */
    val time: List<Long>,
    /** 欠損は null。 */
    @SerialName("pressure_msl") val pressureMsl: List<Double?>,
)
