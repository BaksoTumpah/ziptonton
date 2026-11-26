package com.baksotumpah.ziptonton

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat

/**
 * Menjalankan "Rapikan jadi ZIP" sebagai layanan latar belakang (notifikasi + progres),
 * jadi proses tetap jalan walau layar mati atau aplikasi ditutup dari layar depan.
 */
class OrganizeService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.baksotumpah.ziptonton.ORGANIZE_CANCEL"
        private const val CHANNEL = "organize"
        private const val NOTIF_ID = 4107
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            OrganizeState.cancel.set(true)
            return START_NOT_STICKY
        }
        ensureChannel()
        startForegroundNow(buildNotif(getString(R.string.org_title), getString(R.string.org_notif_start), 0, true))
        val job = OrganizeState.pending
        if (job == null || OrganizeState.running) {
            if (!OrganizeState.running) stopSelf()
            return START_NOT_STICKY
        }
        begin(job)
        return START_NOT_STICKY
    }

    private fun begin(job: OrganizeState.Job) {
        OrganizeState.apply {
            pending = null; running = true; result = null
            cancel.set(false); status = ""; fileLine = ""; progress = 0
        }
        OrganizeState.changed()
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ziptonton:organize").apply { acquire(6 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}

        Thread {
            val res = try {
                Organizer.run(applicationContext, job.tree, job.groups, job.deleteOriginals, listener)
            } catch (e: Exception) {
                RunResult(0, 0, listOf(e.message ?: e.javaClass.simpleName), false)
            }
            // izin tulis hanya dibutuhkan selama proses; izin baca perpustakaan tidak disentuh
            try { contentResolver.releasePersistableUriPermission(job.tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
            OrganizeState.result = res
            OrganizeState.running = false
            stopForeground(STOP_FOREGROUND_DETACH)
            val title = getString(if (res.cancelled) R.string.org_notif_cancelled else R.string.org_notif_done)
            val text = getString(R.string.org_done, res.zipsMade, res.originalsDeleted) +
                if (res.failures.isEmpty()) "" else " " + getString(R.string.org_notif_problems, res.failures.size)
            postNotif(buildNotif(title, text, 0, false))
            OrganizeState.changed()
            stopSelf()
        }.start()
    }

    private val listener = object : Organizer.Listener {
        private var lastUi = 0L
        private var lastNotif = 0L

        override fun onGroup(index: Int, total: Int, group: Group) {
            OrganizeState.status = getString(R.string.org_running, index, total, group.zipName)
            OrganizeState.changed()
            postNotif(buildNotif(getString(R.string.org_title), OrganizeState.status, OrganizeState.progress, true))
        }

        override fun onProgress(file: String, fileDone: Long, fileSize: Long, doneAll: Long, totalAll: Long) {
            val now = SystemClock.uptimeMillis()
            val pct = if (fileSize > 0) (fileDone * 100 / fileSize).toInt() else 0
            OrganizeState.fileLine = getString(R.string.org_file, file, pct)
            OrganizeState.progress = if (totalAll > 0) (doneAll * 1000 / totalAll).toInt() else 0
            if (now - lastUi >= 250) { lastUi = now; OrganizeState.changed() }
            if (now - lastNotif >= 1000) {
                lastNotif = now
                postNotif(buildNotif(getString(R.string.org_title), OrganizeState.status, OrganizeState.progress, true))
            }
        }

        override fun onStatus(text: String) {
            OrganizeState.fileLine = getString(R.string.org_verifying)
            OrganizeState.changed()
        }

        override fun cancelled() = OrganizeState.cancel.get()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.org_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun startForegroundNow(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    private fun postNotif(n: Notification) {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n) } catch (_: Exception) {}
    }

    private fun buildNotif(title: String, text: String, progress: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, OrganizeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
        if (ongoing) {
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, OrganizeService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.setProgress(1000, progress, false).addAction(0, getString(R.string.org_cancel), cancel)
        } else b.setAutoCancel(true)
        return b.build()
    }

    override fun onDestroy() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }
}
