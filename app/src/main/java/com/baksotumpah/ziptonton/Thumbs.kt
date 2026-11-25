package com.baksotumpah.ziptonton

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Thumbnail disimpan di penyimpanan PRIVAT aplikasi (files/thumbs),
 * tidak terlihat oleh galeri maupun aplikasi lain.
 *  - scene : cuplikan layar terakhir saat menonton part tertentu
 *  - frame : gambar dari dalam video (dibuat otomatis untuk ZIP tanpa kompresi)
 */
object Thumbs {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "thumbs").apply { mkdirs() }
    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun sceneFile(ctx: Context, film: String, entry: String) = File(dir(ctx), "s_" + hash("$film|$entry") + ".jpg")
    fun frameFile(ctx: Context, film: String, entry: String) = File(dir(ctx), "f_" + hash("$film|$entry") + ".jpg")

    fun saveScene(ctx: Context, film: String, entry: String, bmp: Bitmap) {
        val f = sceneFile(ctx, film, entry)
        io.execute {
            try {
                val tmp = File(f.path + ".tmp")
                FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                tmp.renameTo(f)
                mem.remove(f.path)
            } catch (_: Exception) {}
        }
    }

    /** Gambar terbaik untuk satu part: scene terakhir, lalu frame dari video. */
    private fun bestForPart(ctx: Context, film: Film, item: ZipItem): File? {
        sceneFile(ctx, film.key, item.name).takeIf { it.exists() }?.let { return it }
        val fr = frameFile(ctx, film.key, item.name)
        if (fr.exists()) return fr
        return if (generateFrame(ctx, film.uri, item, fr)) fr else null
    }

    /** Gambar untuk film: scene part terakhir yang ditonton, lalu part pertama. */
    private fun bestForFilm(ctx: Context, film: Film, lastEntry: String?): File? {
        if (lastEntry != null) {
            sceneFile(ctx, film.key, lastEntry).takeIf { it.exists() }?.let { return it }
        }
        film.videos.forEach { v -> sceneFile(ctx, film.key, v.name).takeIf { it.exists() }?.let { return it } }
        val first = film.videos.firstOrNull() ?: return null
        return bestForPart(ctx, film, first)
    }

    fun loadFilm(view: ImageView, film: Film, lastEntry: String?) =
        load(view, "film|${film.key}|$lastEntry") { bestForFilm(view.context.applicationContext, film, lastEntry) }

    fun loadPart(view: ImageView, film: Film, item: ZipItem) =
        load(view, "part|${film.key}|${item.name}") { bestForPart(view.context.applicationContext, film, item) }

    private fun load(view: ImageView, tag: String, find: () -> File?) {
        view.tag = tag
        view.setImageDrawable(null)
        io.execute {
            val f = try { find() } catch (_: Exception) { null } ?: return@execute
            val k = f.path + "|" + f.lastModified()
            val bmp = mem.get(k) ?: BitmapFactory.decodeFile(f.path)?.also { mem.put(k, it) } ?: return@execute
            main.post { if (view.tag == tag) view.setImageBitmap(bmp) }
        }
    }

    /**
     * Ambil satu frame dari video di dalam ZIP tanpa ekstrak.
     * Hanya untuk ZIP tanpa kompresi (Store), karena butuh lompat-lompat posisi baca.
     */
    private fun generateFrame(ctx: Context, zipUri: Uri, item: ZipItem, out: File): Boolean {
        if (item.method != 0 || item.encrypted) return false
        val src = try { ZipMediaSource.open(ctx, zipUri, item) } catch (_: Exception) { return false }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(src)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = (minOf(dur / 10, 90_000L)) * 1000
            val raw = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return false
            val w = 480
            val h = (raw.height * w / raw.width.coerceAtLeast(1)).coerceAtLeast(1)
            val bmp = Bitmap.createScaledBitmap(raw, w, h, true)
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            return true
        } catch (_: Exception) {
            out.delete()
            return false
        } finally {
            try { r.release() } catch (_: Exception) {}
            src.close()
        }
    }
}

/** MediaDataSource yang membaca langsung satu video (tanpa kompresi) di dalam ZIP. */
class ZipMediaSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val ch: FileChannel,
    private val start: Long,
    private val length: Long
) : MediaDataSource() {

    companion object {
        fun open(ctx: Context, zipUri: Uri, item: ZipItem): ZipMediaSource {
            val pfd = ctx.contentResolver.openFileDescriptor(zipUri, "r") ?: throw java.io.IOException()
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val lh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            ZipIndex.readFully(ch, lh, item.localHeaderOffset)
            val start = item.localHeaderOffset + 30 + (lh.getShort(26).toInt() and 0xffff) + (lh.getShort(28).toInt() and 0xffff)
            return ZipMediaSource(pfd, ch, start, item.size)
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        val n = minOf(size.toLong(), length - position).toInt()
        return ch.read(ByteBuffer.wrap(buffer, offset, n), start + position)
    }

    override fun getSize(): Long = length

    override fun close() {
        try { ch.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}
