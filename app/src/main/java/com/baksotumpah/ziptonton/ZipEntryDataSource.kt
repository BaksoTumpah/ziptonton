package com.baksotumpah.ziptonton

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Sumber data ExoPlayer yang membaca satu video LANGSUNG dari dalam ZIP.
 * - File ZIP dibuka mode "r" (read-only) -> file asli tidak mungkin berubah/rusak.
 * - Byte video dikirim apa adanya ke decoder -> kualitas 100% sama.
 * - ZIP "Store"/tanpa kompresi: lompat menit (seek) instan.
 * - ZIP terkompresi (Deflate): tetap bisa, tapi seek lebih lambat karena harus dibuka berurutan.
 */
@UnstableApi
class ZipEntryDataSource(
    private val context: Context,
    private val zipUri: Uri,
    private val item: ZipItem
) : BaseDataSource(false) {

    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null
    private var inflated: InputStream? = null
    private var filePos = 0L
    private var remaining = 0L
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        if (item.encrypted) throw IOException("ZIP berpassword tidak didukung")

        val fd = context.contentResolver.openFileDescriptor(zipUri, "r")
            ?: throw IOException("Tidak bisa membuka file ZIP")
        pfd = fd
        val ch = FileInputStream(fd.fileDescriptor).channel
        channel = ch

        val lh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        ZipIndex.readFully(ch, lh, item.localHeaderOffset)
        if (lh.getInt(0) != ZipIndex.SIG_LOC) throw IOException("Header ZIP rusak")
        val nameLen = lh.getShort(26).toInt() and 0xffff
        val extraLen = lh.getShort(28).toInt() and 0xffff
        val dataStart = item.localHeaderOffset + 30 + nameLen + extraLen

        if (dataSpec.position > item.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        remaining = item.size - dataSpec.position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            remaining = minOf(remaining, dataSpec.length)
        }

        when (item.method) {
            0 -> filePos = dataStart + dataSpec.position
            8 -> {
                val raw = ChannelRangeStream(ch, dataStart, item.compressedSize)
                val s = InflaterInputStream(BufferedInputStream(raw, 1 shl 16), Inflater(true), 1 shl 16)
                skipFully(s, dataSpec.position)
                inflated = s
            }
            else -> throw IOException("Metode kompresi ZIP (${item.method}) tidak didukung")
        }

        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(1 shl 16)
        while (left > 0) {
            val r = s.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (r < 0) throw EOFException()
            left -= r
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), remaining).toInt()
        val n: Int = if (item.method == 0) {
            val r = channel!!.read(ByteBuffer.wrap(buffer, offset, toRead), filePos)
            if (r > 0) filePos += r
            r
        } else {
            inflated!!.read(buffer, offset, toRead)
        }
        if (n < 0) throw EOFException("Video di dalam ZIP terpotong")
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { inflated?.close() } catch (_: IOException) {}
        try { channel?.close() } catch (_: IOException) {}
        try { pfd?.close() } catch (_: IOException) {}
        inflated = null; channel = null; pfd = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** InputStream untuk potongan byte tertentu dari file (tanpa menggeser posisi global). */
    private class ChannelRangeStream(
        private val ch: FileChannel,
        private var pos: Long,
        length: Long
    ) : InputStream() {
        private var left = length
        private var dummySent = false

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left <= 0) {
                // Inflater mode "nowrap" kadang butuh 1 byte dummy di akhir data.
                if (!dummySent) { dummySent = true; b[off] = 0; return 1 }
                return -1
            }
            val n = ch.read(ByteBuffer.wrap(b, off, minOf(len.toLong(), left).toInt()), pos)
            if (n < 0) return -1
            pos += n; left -= n
            return n
        }
    }
}
