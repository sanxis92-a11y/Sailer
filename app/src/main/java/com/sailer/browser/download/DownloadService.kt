package com.sailer.browser.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sailer.browser.R
import com.sailer.browser.settings.Disguise
import com.sailer.browser.vault.VaultActivity

/**
 * Mantiene vivas las descargas aunque cierres el navegador. Muestra una
 * notificación discreta (sin nombres de archivo, oculta en la pantalla de bloqueo).
 */
class DownloadService : Service(), DownloadCenter.Listener {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET }
        )
        DownloadCenter.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_PAUSE_ALL) DownloadCenter.pauseAll()
        if (DownloadCenter.activeCount == 0) stop()
        return START_NOT_STICKY
    }

    override fun onDownloadsChanged() {
        if (DownloadCenter.activeCount == 0) {
            stop()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        DownloadCenter.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15 limita el tiempo de este tipo de servicio; si no se para a tiempo, cierra la app. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadCenter.pauseAll()
        stop()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type) }
            .onFailure { stopSelf() }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_download)
        .setContentTitle(Disguise.label(this))
        .setContentText(getString(R.string.downloads_running, DownloadCenter.activeCount))
        .apply {
            val running = DownloadCenter.tasks.filter { it.status == DownloadCenter.Status.RUNNING }
            val progress = running.map { it.progress }.filter { it >= 0 }
            if (progress.isEmpty()) setProgress(0, 0, true) else setProgress(100, progress.average().toInt(), false)
        }
        .addAction(
            0, getString(R.string.downloads_pause_all),
            PendingIntent.getService(
                this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setOngoing(true)
        .setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, VaultActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .build()

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE_ALL = "com.sailer.browser.PAUSE_ALL"

        fun start(context: Context) {
            // Android 12+ no deja arrancarlo con la app en segundo plano: antes eso cerraba la app.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
