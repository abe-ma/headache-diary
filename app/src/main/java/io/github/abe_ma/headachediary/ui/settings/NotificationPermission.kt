package io.github.abe_ma.headachediary.ui.settings

import android.Manifest
import android.annotation.SuppressLint
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

/**
 * 通知権限の名前 (POST_NOTIFICATIONS)。
 * API 33 で追加された定数だが、実体は文字列定数としてコンパイル時にインライン化されるため、API 33 未満でも参照して安全。
 * API 33 未満で要求すると実行時権限がないので即座に「拒否」が返り、設定画面の案内 (端末の通知設定を開く) に誘導される。
 */
@SuppressLint("InlinedApi")
fun notificationPermissionName(): String = Manifest.permission.POST_NOTIFICATIONS
