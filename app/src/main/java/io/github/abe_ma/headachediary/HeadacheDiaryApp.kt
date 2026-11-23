package io.github.abe_ma.headachediary

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.abe_ma.headachediary.data.notify.NotificationChannels
import io.github.abe_ma.headachediary.data.notify.NotifyBootstrap
import io.github.abe_ma.headachediary.data.work.PressureRefreshScheduler

@HiltAndroidApp
class HeadacheDiaryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // FR-101: 日次の気圧取得を予約 (冪等。既存の予約は維持)。
        PressureRefreshScheduler.ensureDaily(this)
        // T-08: 通知の再予約 (再起動・バックアップ復元後。気圧低下通知と未評価の効果リマインダー)。
        NotifyBootstrap.start(this)
        // 通知チャンネルを起動直後から作成 (システム設定の一覧に出す)。
        NotificationChannels.ensure(this)
    }
}
