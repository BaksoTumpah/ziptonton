package com.baksotumpah.ziptonton

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Calendar
import java.util.zip.CRC32

/**
 * Penulis ZIP tanpa kompresi (STORED) ke FileChannel yang bisa di-seek.
 * Data disalin apa adanya sambil menghitung CRC32, jadi film tetap bisa di-seek di pemutar.
 * Mendukung ZIP64 (ukuran/posisi >= 4 GB). [z64Limit] hanya diubah untuk pengujian.
 */
class ZipWriter(private val ch: FileChannel, private val z64Limit: Long = 0xFFFFFFFFL) {

    class Entry(val name: String, val size: Long, val crc: Long, val offset: Long)

    private val entries = ArrayList<Entry>()
    private var pos = 0L
    private val dosTime: Int
    private val dosDate: Int

    init {
        val c = Calendar.getInstance()
        dosTime = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        dosDate = ((c.get(Calendar.YEAR) - 1980).coerceAtLeast(0) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
    }

    private fun ByteBuffer.u16(v: Int): ByteBuffer = putShort(v.toShort())
    private fun ByteBuffer.u32(v: Long): ByteBuffer = putInt(v.toInt())
    private fun ByteBuffer.u64(v: Long): ByteBuffer = putLong(v)

    private fun newBuf(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun writeAt(buf: ByteBuffer, at: Long) {
        buf.flip()
        var p = at
        while (buf.hasRemaining()) p += ch.write(buf, p)
    }

    private fun append(buf: ByteBuffer) {
        buf.flip()
        while (buf.hasRemaining()) pos += ch.write(buf, pos)
    }

    /**
     * Tambah satu file. Mengembalikan null kalau dibatalkan.
     * Melempar IOException kalau jumlah byte yang terbaca tidak sama dengan [size].
     */
    fun add(name: String, size: Long, input: InputStream, onBytes: (Long) -> Unit, cancelled: () -> Boolean): Entry? {
        val nameB = name.toByteArray(Charsets.UTF_8)
        val big = size >= z64Limit
        val extraLen = if (big) 20 else 0
        val offset = pos

        val h = newBuf(30 + nameB.size + extraLen)
        h.u32(0x04034b50L)
        h.u16(if (big) 45 else 20)
        h.u16(0x0800)                 // nama berkode UTF-8
        h.u16(0)                      // STORED
        h.u16(dosTime); h.u16(dosDate)
        h.u32(0)                      // CRC diisi setelah data selesai
        h.u32(if (big) 0xFFFFFFFFL else size)
        h.u32(if (big) 0xFFFFFFFFL else size)
        h.u16(nameB.size); h.u16(extraLen)
        h.put(nameB)
        if (big) { h.u16(1); h.u16(16); h.u64(size); h.u64(size) }
        append(h)

        val crc = CRC32()
        val arr = ByteArray(256 * 1024)
        var copied = 0L
        while (true) {
            if (cancelled()) return null
            val n = input.read(arr)
            if (n < 0) break
            crc.update(arr, 0, n)
            val bb = ByteBuffer.wrap(arr, 0, n)
            while (bb.hasRemaining()) pos += ch.write(bb, pos)
            copied += n
            onBytes(n.toLong())
        }
        if (copied != size) throw IOException("Ukuran file tidak sesuai ($copied dari $size byte)")

        val c = newBuf(4)
        c.u32(crc.value)
        writeAt(c, offset + 14)       // posisi CRC di header lokal
        val e = Entry(name, size, crc.value, offset)
        entries += e
        return e
    }

    /** Tulis central directory dan penutup. Panggil sekali setelah semua file ditambahkan. */
    fun finish() {
        val cdStart = pos
        for (e in entries) {
            val nameB = e.name.toByteArray(Charsets.UTF_8)
            val bigSize = e.size >= z64Limit
            val bigOff = e.offset >= z64Limit
            val fields = (if (bigSize) 16 else 0) + (if (bigOff) 8 else 0)
            val extraLen = if (fields > 0) 4 + fields else 0
            val ver = if (fields > 0) 45 else 20
            val b = newBuf(46 + nameB.size + extraLen)
            b.u32(0x02014b50L)
            b.u16(ver); b.u16(ver)
            b.u16(0x0800); b.u16(0)
            b.u16(dosTime); b.u16(dosDate)
            b.u32(e.crc)
            b.u32(if (bigSize) 0xFFFFFFFFL else e.size)
            b.u32(if (bigSize) 0xFFFFFFFFL else e.size)
            b.u16(nameB.size); b.u16(extraLen); b.u16(0)
            b.u16(0); b.u16(0); b.u32(0)
            b.u32(if (bigOff) 0xFFFFFFFFL else e.offset)
            b.put(nameB)
            if (fields > 0) {
                b.u16(1); b.u16(fields)
                if (bigSize) { b.u64(e.size); b.u64(e.size) }
                if (bigOff) b.u64(e.offset)
            }
            append(b)
        }
        val cdSize = pos - cdStart
        val n = entries.size
        val z64 = cdStart >= z64Limit || cdSize >= z64Limit || n >= 0xFFFF
        if (z64) {
            val z64Off = pos
            val r = newBuf(56)
            r.u32(0x06064b50L); r.u64(44); r.u16(45); r.u16(45)
            r.u32(0); r.u32(0); r.u64(n.toLong()); r.u64(n.toLong()); r.u64(cdSize); r.u64(cdStart)
            append(r)
            val l = newBuf(20)
            l.u32(0x07064b50L); l.u32(0); l.u64(z64Off); l.u32(1)
            append(l)
        }
        val t = newBuf(22)
        t.u32(0x06054b50L); t.u16(0); t.u16(0)
        t.u16(if (z64) 0xFFFF else n); t.u16(if (z64) 0xFFFF else n)
        t.u32(if (z64) 0xFFFFFFFFL else cdSize)
        t.u32(if (z64) 0xFFFFFFFFL else cdStart)
        t.u16(0)
        append(t)
    }
}
