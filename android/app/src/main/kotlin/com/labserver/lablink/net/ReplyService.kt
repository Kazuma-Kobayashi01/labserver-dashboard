package com.labserver.lablink.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.labserver.lablink.R

/**
 * Keeps the process (and its network) alive while a reply is streaming. Without it Android freezes or kills the app soon
 * after it leaves the screen, and a bot that is still thinking loses its answer.
 */
class ReplyService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.reply_channel), NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.reply_running))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, note)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LabLink:reply")
                .apply { acquire(MAX_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "reply"
        private const val ID = 1
        private const val MAX_MS = 30 * 60 * 1000L

        private const val APPROVALS = "approvals"
        private const val APPROVAL_ID = 2

        /** Tells the user a bot waits for an answer (the app is not on screen); a tap opens that conversation. */
        fun notifyApproval(context: Context, botId: String, convId: String, botName: String, what: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(APPROVALS, context.getString(R.string.approval_channel), NotificationManager.IMPORTANCE_HIGH))
            val open = Intent(context, com.labserver.lablink.MainActivity::class.java)
                .putExtra(EXTRA_BOT, botId).putExtra(EXTRA_CONV, convId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val tap = android.app.PendingIntent.getActivity(context, convId.hashCode(), open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val note = Notification.Builder(context, APPROVALS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.approval_notify_title, botName))
                .setContentText(what)
                .setStyle(Notification.BigTextStyle().bigText(what))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(APPROVAL_ID, note) }
        }

        fun clearApproval(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(APPROVAL_ID)
        }

        const val EXTRA_BOT = "lablink.bot"
        const val EXTRA_CONV = "lablink.conv"

        fun set(context: Context, running: Boolean) {
            val i = Intent(context, ReplyService::class.java)
            runCatching { if (running) context.startForegroundService(i) else context.stopService(i) }
        }
    }
}
