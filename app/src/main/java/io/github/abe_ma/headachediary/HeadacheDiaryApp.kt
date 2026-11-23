package io.github.abe_ma.headachediary

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.abe_ma.headachediary.data.work.PressureRefreshScheduler

@HiltAndroidApp
class HeadacheDiaryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // FR-101: 日次の気圧取得を予約 (冪等。既存の予約は維持)。
        PressureRefreshScheduler.ensureDaily(this)
    }
}
