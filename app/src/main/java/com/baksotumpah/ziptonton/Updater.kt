package com.baksotumpah.ziptonton

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URL

/**
 * Pembaruan lewat GitHub Releases (mirip Magisk).
 * Tag rilis berbentuk "build-N" dan N sama dengan versionCode APK-nya
 * (diatur di workflow: GITHUB_RUN_NUMBER).
 */
object Updater {
    private const val REPO = "BaksoTumpah/ziptonton"

    data class Release(val code: Long, val name: String, val apkUrl: String, val size: Long, val notes: String)

    @Volatile private var checkedThisLaunch = false
    private var pendingApk: File? = null

    fun currentCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    /** Jalankan di thread latar. null = tidak ada info (offline / repo privat / belum ada rilis). */
    private fun fetchLatest(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "ZipTonton")
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val code = Regex("(\\d+)$").find(o.optString("tag_name"))?.value?.toLongOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(code, o.optString("name").ifEmpty { "build $code" },
                        a.getString("browser_download_url"), a.optLong("size"), o.optString("body").trim())
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Cek sekali per peluncuran aplikasi; munculkan popup kalau ada versi lebih baru. */
    fun checkOnLaunch(activity: AppCompatActivity) {
        if (checkedThisLaunch) return
        checkedThisLaunch = true
        Thread {
            val rel = try { fetchLatest() } catch (_: Exception) { null }
            val now = currentCode(activity)
            if (rel != null && rel.code > now) {
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) showPrompt(activity, rel, now)
                }
            }
        }.start()
    }

    /** Dari menu Pengaturan: selalu memberi tahu hasilnya. */
    fun checkNow(activity: AppCompatActivity) {
        Thread {
            val rel = try { fetchLatest() } catch (_: Exception) { null }
            val now = currentCode(activity)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    rel == null -> Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                    rel.code > now -> showPrompt(activity, rel, now)
                    else -> Toast.makeText(activity, R.string.update_latest, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showPrompt(activity: AppCompatActivity, rel: Release, now: Long) {
        var msg = activity.getString(R.string.update_msg, rel.code, now, Fmt.size(rel.size))
        if (rel.notes.isNotBlank()) msg += "\n\n" + activity.getString(R.string.update_notes) + "\n" + rel.notes.take(800)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setMessage(msg)
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_now) { _, _ -> download(activity, rel) }
            .show()
    }

    private fun download(activity: AppCompatActivity, rel: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(activity).apply { text = activity.getString(R.string.update_downloading, 0) }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(label); addView(bar)
        }
        val cancelled = AtomicBoolean(false)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled.set(true) }
            .show()

        Thread {
            var file: File? = null
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val part = File(dir, "update.apk.part")
                val c = URL(rel.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", "ZipTonton")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
                var read = 0L; var lastPct = -1
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (!cancelled.get()) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val pct = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (pct != lastPct) {
                                lastPct = pct
                                activity.runOnUiThread {
                                    bar.progress = pct
                                    label.text = activity.getString(R.string.update_downloading, pct)
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                if (!cancelled.get()) {
                    val apk = File(dir, "update.apk")
                    if (part.renameTo(apk)) file = apk
                } else part.delete()
            } catch (_: Exception) { file = null }

            val result = file
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                dialog.dismiss()
                when {
                    result != null -> install(activity, result)
                    !cancelled.get() -> Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun install(activity: AppCompatActivity, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            // Android minta izin "pasang aplikasi tidak dikenal" untuk ZipTonton (sekali saja)
            pendingApk = apk
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_perm_title)
                .setMessage(R.string.update_perm_msg)
                .setNegativeButton(R.string.cancel) { _, _ -> pendingApk = null }
                .setPositiveButton(R.string.update_perm_open) { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }
                .show()
            return
        }
        launchInstaller(activity, apk)
    }

    /** Dipanggil dari onResume: lanjutkan pemasangan setelah user memberi izin di Pengaturan. */
    fun resumePending(activity: AppCompatActivity) {
        val apk = pendingApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
        pendingApk = null
        if (apk.exists()) launchInstaller(activity, apk)
    }

    private fun launchInstaller(activity: AppCompatActivity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(i) }
        catch (_: Exception) { Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show() }
    }
}
