package com.baksotumpah.ziptonton

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Satu file di dalam ZIP. Hanya metadata; isi tidak pernah diekstrak ke penyimpanan. */
data class ZipItem(
    val index: Int,
    val name: String,
    val method: Int,          // 0 = STORED (tanpa kompresi), 8 = DEFLATE
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
    val flags: Int
) {
    val displayName: String get() = name.substringAfterLast('/')
    val encrypted: Boolean get() = (flags and 1) != 0
}

/**
 * Pembaca daftar isi ZIP (central directory) langsung dari FileChannel.
 * Mendukung ZIP64 (file > 4 GB). Hanya membaca, tidak pernah menulis.
 */
object ZipIndex {
    private const val SIG_EOCD = 0x06054b50
    private const val SIG_Z64_LOCATOR = 0x07064b50
    private const val SIG_Z64_EOCD = 0x06064b50
    private const val SIG_CEN = 0x02014b50
    const val SIG_LOC = 0x04034b50

    val VIDEO_EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "m2ts", "avi", "flv", "mpg", "mpeg"
    )

    fun readFully(ch: FileChannel, buf: ByteBuffer, position: Long) {
        var pos = position
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n < 0) throw EOFException("File ZIP terpotong")
            pos += n
        }
        buf.flip()
    }

    private fun u16(b: ByteBuffer, i: Int) = b.getShort(i).toInt() and 0xffff
    private fun u32(b: ByteBuffer, i: Int) = b.getInt(i).toLong() and 0xffffffffL

    fun list(ch: FileChannel): List<ZipItem> {
        val fileSize = ch.size()
        if (fileSize < 22) throw IOException("Bukan file ZIP")
        val tailLen = minOf(fileSize, 65_557L).toInt()
        val tail = ByteBuffer.allocate(tailLen).order(ByteOrder.LITTLE_ENDIAN)
        readFully(ch, tail, fileSize - tailLen)

        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail.getInt(i) == SIG_EOCD) { eocd = i; break }
        }
        if (eocd < 0) throw IOException("Bukan file ZIP atau ZIP rusak")

        var total = u16(tail, eocd + 10).toLong()
        var cdSize = u32(tail, eocd + 12)
        var cdOffset = u32(tail, eocd + 16)

        if (eocd >= 20 && tail.getInt(eocd - 20) == SIG_Z64_LOCATOR) {
            val z64Offset = tail.getLong(eocd - 20 + 8)
            val z = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            readFully(ch, z, z64Offset)
            if (z.getInt(0) == SIG_Z64_EOCD) {
                total = z.getLong(32)
                cdSize = z.getLong(40)
                cdOffset = z.getLong(48)
            }
        }
        if (cdSize > Int.MAX_VALUE || cdOffset + cdSize > fileSize) {
            throw IOException("Daftar isi ZIP tidak valid")
        }

        val cd = ByteBuffer.allocate(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        readFully(ch, cd, cdOffset)

        val out = ArrayList<ZipItem>(minOf(total, 10_000L).toInt())
        var p = 0
        var idx = 0
        while (p + 46 <= cdSize && cd.getInt(p) == SIG_CEN) {
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            var comp = u32(cd, p + 20)
            var size = u32(cd, p + 24)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            var lho = u32(cd, p + 42)

            val nameBytes = ByteArray(nameLen)
            for (k in 0 until nameLen) nameBytes[k] = cd.get(p + 46 + k)
            val name = String(nameBytes, Charsets.UTF_8)

            var e = p + 46 + nameLen
            val eEnd = e + extraLen
            while (e + 4 <= eEnd) {
                val id = u16(cd, e)
                val len = u16(cd, e + 2)
                if (id == 0x0001) {
                    var q = e + 4
                    if (size == 0xffffffffL) { size = cd.getLong(q); q += 8 }
                    if (comp == 0xffffffffL) { comp = cd.getLong(q); q += 8 }
                    if (lho == 0xffffffffL) { lho = cd.getLong(q) }
                }
                e += 4 + len
            }

            out += ZipItem(idx++, name, method, comp, size, lho, flags)
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    fun isVideo(item: ZipItem): Boolean {
        if (item.name.endsWith("/")) return false
        if (item.name.startsWith("__MACOSX/") || item.displayName.startsWith("._")) return false
        return item.name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
    }

    /** Urutan alami: "Part 2" sebelum "Part 10". */
    val naturalOrder = Comparator<ZipItem> { a, b -> naturalCompare(a.name, b.name) }

    fun naturalCompare(x: String, y: String): Int {
        val a = x.lowercase(); val b = y.lowercase()
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei; j = ej
            } else {
                if (ca != cb) return ca - cb
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
