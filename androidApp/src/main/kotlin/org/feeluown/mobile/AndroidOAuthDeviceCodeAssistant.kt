package org.feeluown.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class AndroidOAuthDeviceCodeAssistant(
    private val context: Context,
) : OAuthDeviceCodeAssistant {
    private val appContext = context.applicationContext

    override fun copyUserCode(userCode: String) {
        copyToClipboard(appContext, userCode)
        Toast.makeText(appContext, "验证码已复制：$userCode", Toast.LENGTH_SHORT).show()
    }

    override fun showUserCodeNotification(userCode: String) {
        ensureChannel()
        val copyIntent = Intent(appContext, OAuthUserCodeCopyReceiver::class.java).apply {
            action = OAuthUserCodeCopyReceiver.ACTION_COPY_USER_CODE
            putExtra(OAuthUserCodeCopyReceiver.EXTRA_USER_CODE, userCode)
        }
        val copyPendingIntent = PendingIntent.getBroadcast(
            appContext,
            REQUEST_COPY,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_COPY_OAUTH_USER_CODE
            putExtra(EXTRA_OAUTH_USER_CODE, userCode)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_OPEN,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("YouTube Music 授权验证码")
            .setContentText(userCode)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("请在浏览器中输入验证码：$userCode\n点击通知或「复制」按钮可复制验证码"),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_save,
                "复制验证码",
                copyPendingIntent,
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        }
    }

    override fun clearUserCodeNotification() {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "OAuth 授权验证码",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "YouTube Music TV OAuth 设备验证码"
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "fuo_oauth_user_code"
        const val NOTIFICATION_ID = 1003
        const val ACTION_COPY_OAUTH_USER_CODE = "org.feeluown.mobile.action.COPY_OAUTH_USER_CODE"
        const val EXTRA_OAUTH_USER_CODE = "oauth_user_code"
        private const val REQUEST_COPY = 2001
        private const val REQUEST_OPEN = 2002

        fun copyToClipboard(context: Context, userCode: String) {
            val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("YouTube Music OAuth", userCode))
        }
    }
}

class OAuthUserCodeCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_COPY_USER_CODE) return
        val userCode = intent.getStringExtra(EXTRA_USER_CODE)?.takeIf { it.isNotBlank() } ?: return
        AndroidOAuthDeviceCodeAssistant.copyToClipboard(context.applicationContext, userCode)
        Toast.makeText(context.applicationContext, "验证码已复制：$userCode", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_COPY_USER_CODE = "org.feeluown.mobile.action.OAUTH_COPY_USER_CODE"
        const val EXTRA_USER_CODE = "user_code"
    }
}
