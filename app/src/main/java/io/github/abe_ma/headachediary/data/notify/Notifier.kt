package io.github.abe_ma.headachediary.data.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.abe_ma.headachediary.R

/** 通知の表示口。ジョブのロジックをテストできるよう抽象化する。 */
interface Notifier {
    /** 通知が許可されているか (権限・チャンネル無効を含む)。拒否でも記録機能は使える (FR-503)。 */
    fun canNotify(): Boolean
    fun showEffectReminder(attackId: Long)
    fun showPressureDrop(dropHpa: Int)
    fun cancelEffectReminder(attackId: Long)
}

object NotificationChannels {
    const val EFFECT = "effect_input"
    const val DROP = "pressure_drop"
    private const val DROP_NOTIFICATION_ID = 2
    private const val EFFECT_ID_BASE = 1_000_000

    internal fun effectNotificationId(attackId: Long) = EFFECT_ID_BASE + (attackId % 1_000_000_000L).toInt()
    internal fun dropNotificationId() = DROP_NOTIFICATION_ID

    /** チャンネルは 2 つ (効果入力 / 気圧低下)。冪等。 */
    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(EFFECT, context.getString(R.string.notify_channel_effect_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notify_channel_effect_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(DROP, context.getString(R.string.notify_channel_drop_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notify_channel_drop_desc) },
        )
    }
}

class AndroidNotifier(private val context: Context) : Notifier {
    override fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("MissingPermission") // canNotify() (areNotificationsEnabled) で確認してから呼ぶ前提
    override fun showEffectReminder(attackId: Long) {
        if (!canNotify()) return
        NotificationChannels.ensure(context)
        // 通知タップで attack/{id} を開く (UI の deep link: headachediary://attack/{id})。
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("headachediary://attack/$attackId"))
            .setClassName(context.packageName, "${context.packageName}.ui.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, NotificationChannels.effectNotificationId(attackId), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, NotificationChannels.EFFECT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_effect_title))
            .setContentText(context.getString(R.string.notify_effect_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NotificationChannels.effectNotificationId(attackId), n)
    }

    @SuppressLint("MissingPermission")
    override fun showPressureDrop(dropHpa: Int) {
        if (!canNotify()) return
        NotificationChannels.ensure(context)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = intent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, NotificationChannels.DROP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_drop_title))
            .setContentText(context.getString(R.string.notify_drop_text, dropHpa))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NotificationChannels.dropNotificationId(), n)
    }

    override fun cancelEffectReminder(attackId: Long) {
        NotificationManagerCompat.from(context).cancel(NotificationChannels.effectNotificationId(attackId))
    }
}
