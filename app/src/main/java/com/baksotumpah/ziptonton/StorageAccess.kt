package com.baksotumpah.ziptonton

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Folder "ZipTonton" di penyimpanan utama perangkat: dipakai kalau sudah ada, dibuat kalau belum.
 * Android 11+ butuh izin "Akses semua file"; Android 10 ke bawah memakai izin penyimpanan biasa.
 */
object StorageAccess {
    const val FOLDER_NAME = "ZipTonton"

    fun dir(): File = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Folder ZipTonton: sudah ada -> dipakai; belum ada -> dibuat. null kalau gagal. */
    fun ensureDir(): File? {
        val d = dir()
        if (d.isDirectory) return d
        d.mkdirs()
        return if (d.isDirectory) d else null
    }

    /** Layar pengaturan "Akses semua file" untuk aplikasi ini (Android 11+). null di Android 10 ke bawah. */
    fun settingsIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 30)
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
        else null
}
