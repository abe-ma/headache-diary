package io.github.abe_ma.headachediary.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 通知を出せる状態か (FR-505)。
 * API 33+ は POST_NOTIFICATIONS の付与状況、全 API で端末側の通知オン/オフ (アプリの通知を無効にしていないか) も見る。
 * API 33 未満は実行時権限がないので、端末側の通知設定だけで判定する。
 */
fun notificationsGranted(context: Context): Boolean {
    val permissionOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return permissionOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
}
