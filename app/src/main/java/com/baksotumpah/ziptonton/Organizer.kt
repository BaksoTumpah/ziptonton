package com.baksotumpah.ziptonton

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Satu file video di folder sumber. */
class SrcFile(val uri: Uri, val name: String, val size: Long)

/** Satu episode yang akan masuk ZIP. */
class PlanItem(val src: SrcFile, val entryName: String, val season: Int?, val episode: Int, val quality: String?)

/** Satu film/serial (satu musim) = satu ZIP. */
class Group(val title: String, val season: Int?, val items: List<PlanItem>) {
    var selected = true
    val zipName: String get() = Organizer.safeName(if (season != null) "$title S${Organizer.pad(season)}" else title) + ".zip"
    val totalSize: Long get() = items.sumOf { it.src.size }

    /** Nomor episode yang tidak ada di antara yang terkecil dan terbesar (mis. E11 hilang). */
    val missing: List<Int>
        get() {
            val eps = items.map { it.episode }.toSet()
            val lo = eps.minOrNull() ?: return emptyList()
            val hi = eps.maxOrNull() ?: return emptyList()
            return (lo..hi).filter { it !in eps }
        }
    val hasDuplicates: Boolean get() = items.map { it.episode }.toSet().size != items.size
}

class Plan(val groups: List<Group>, val skipped: List<SrcFile>)

class RunResult(val zipsMade: Int, val originalsDeleted: Int, val failures: List<String>, val cancelled: Boolean)

object Organizer {

    private val SE = Regex("""[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})""")
    private val EP = Regex("""(?:^|[ ._-])(?:[Ee][Pp]?|[Ee]pisode)[ ._-]?(\d{1,3})(?=[ ._-]|$)""")
    private val QUALITY = Regex("""\d{3,4}[pP]|4[kK]""")
    private val SPLIT = Regex("""[_.\s]+""")

    fun pad(n: Int) = n.toString().padStart(2, '0')

    fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|]"""), "").trim()

    private class Parsed(val title: String, val season: Int?, val episode: Int, val quality: String?, val tag: String?)

    private fun parse(fileName: String): Parsed? {
        val base = fileName.substringBeforeLast('.')
        val season: Int?
        val ep: Int
        val prefix: String
        val suffix: String
        val m = SE.find(base)
        if (m != null) {
            season = m.groupValues[1].toInt()
            ep = m.groupValues[2].toInt()
            prefix = base.substring(0, m.range.first)
            suffix = base.substring(m.range.last + 1)
        } else {
            val m2 = EP.find(base) ?: return null
            season = null
            ep = m2.groupValues[1].toInt()
            prefix = base.substring(0, m2.range.first)
            suffix = base.substring(m2.range.last + 1)
        }
        val tokens = prefix.split(SPLIT).filter { it.isNotEmpty() }.toMutableList()
        var quality: String? = null
        var tag: String? = null
        while (tokens.isNotEmpty()) {
            val t = tokens.last()
            if (QUALITY.matches(t)) { if (quality == null) quality = t.uppercase(); tokens.removeAt(tokens.size - 1) }
            else if (t.startsWith("[") || t.startsWith("(") || t.endsWith("]") || t.endsWith(")") || t == "-") {
                if (tag == null && t.startsWith("[") && t.endsWith("]")) tag = t   // mis. [Indonesian] = subtitle tertulis di video
                tokens.removeAt(tokens.size - 1)
            }
            else break
        }
        if (tokens.isEmpty()) return null
        if (quality == null) quality = QUALITY.find(suffix)?.value?.uppercase()
        return Parsed(tokens.joinToString(" "), season, ep, quality, tag)
    }

    /** Kelompokkan file video per judul + musim berdasarkan nama file. File yang polanya tidak dikenali dilewati. */
    fun plan(files: List<SrcFile>): Plan {
        val byKey = LinkedHashMap<String, MutableList<Pair<SrcFile, Parsed>>>()
        val skipped = ArrayList<SrcFile>()
        for (f in files) {
            val p = parse(f.name)
            if (p == null) { skipped += f; continue }
            byKey.getOrPut(p.title.lowercase() + "|" + (p.season ?: -1)) { ArrayList() } += f to p
        }
        val groups = byKey.values.map { list ->
            val title = list.first().second.title
            val season = list.first().second.season
            val used = HashMap<String, Int>()
            val items = list.sortedWith(compareBy({ it.second.episode }, { it.first.name })).map { (f, p) ->
                val ext = f.name.substringAfterLast('.', "mp4").lowercase()
                var name = (if (season != null) "$title S${pad(season)}E${pad(p.episode)}" else "$title E${pad(p.episode)}") +
                    (if (p.quality != null) " ${p.quality}" else "") +
                    (if (p.tag != null) " ${p.tag}" else "")
                val n = (used[name] ?: 0) + 1
                used[name] = n
                if (n > 1) name += " ($n)"
                PlanItem(f, safeName("$name.$ext"), season, p.episode, p.quality)
            }
            Group(title, season, items)
        }.sortedWith { a, b -> ZipIndex.naturalCompare(a.zipName, b.zipName) }
        return Plan(groups, skipped)
    }

    /** Video yang langsung ada di folder (tidak masuk subfolder). */
    fun listVideos(context: Context, tree: Uri): List<SrcFile> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val out = ArrayList<SrcFile>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (name.substringAfterLast('.', "").lowercase() !in ZipIndex.VIDEO_EXT) continue
                out += SrcFile(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(3))
            }
        }
        return out
    }

    private fun existingNames(context: Context, tree: Uri): MutableSet<String> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val names = HashSet<String>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { names += it.lowercase() }
        }
        return names
    }

    interface Listener {
        fun onGroup(index: Int, total: Int, group: Group)
        fun onProgress(file: String, fileDone: Long, fileSize: Long, doneAll: Long, totalAll: Long)
        fun onStatus(text: String)
        fun cancelled(): Boolean
    }

    /**
     * Buat satu ZIP per grup. File asli baru dihapus setelah ZIP selesai, diberi nama final,
     * dan diperiksa ulang (jumlah file, ukuran, dan CRC32 dibaca ulang dari ZIP).
     * Kalau ada langkah yang gagal atau dibatalkan, file asli tidak disentuh.
     */
    fun run(context: Context, tree: Uri, groups: List<Group>, deleteOriginals: Boolean, l: Listener): RunResult {
        val cr = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val names = existingNames(context, tree)
        val failures = ArrayList<String>()
        var made = 0
        var deleted = 0
        val total = groups.sumOf { it.totalSize }
        var done = 0L

        for ((gi, g) in groups.withIndex()) {
            if (l.cancelled()) return RunResult(made, deleted, failures, true)
            l.onGroup(gi + 1, groups.size, g)
            val groupStart = done

            var zipName = g.zipName
            var k = 2
            while (zipName.lowercase() in names) { zipName = g.zipName.removeSuffix(".zip") + " ($k).zip"; k++ }

            var part: Uri? = null
            try {
                part = DocumentsContract.createDocument(cr, parent, "application/octet-stream", "$zipName.part")
                    ?: throw IllegalStateException("Tidak bisa membuat file ZIP di folder")
                val written = ArrayList<ZipWriter.Entry>()
                cr.openFileDescriptor(part, "rw").use { pfd ->
                    if (pfd == null) throw IllegalStateException("Tidak bisa membuka file ZIP untuk ditulis")
                    FileOutputStream(pfd.fileDescriptor).channel.use { ch ->
                        val w = ZipWriter(ch)
                        for (pi in g.items) {
                            val input = cr.openInputStream(pi.src.uri) ?: throw IllegalStateException("Tidak bisa membaca ${pi.src.name}")
                            var fileDone = 0L
                            val e = input.use { ins ->
                                w.add(pi.entryName, pi.src.size, ins, { n ->
                                    fileDone += n; done += n
                                    l.onProgress(pi.src.name, fileDone, pi.src.size, done, total)
                                }, { l.cancelled() })
                            }
                            if (e == null) throw CancelledException()   // dibatalkan
                            written += e
                        }
                        w.finish()
                        ch.force(true)
                    }
                }

                l.onStatus("verify")
                if (!verify(context, part, written, l)) throw IllegalStateException("Pemeriksaan ZIP gagal")

                DocumentsContract.renameDocument(cr, part, zipName)
                    ?: throw IllegalStateException("Tidak bisa memberi nama akhir pada ZIP")
                part = null
                names += zipName.lowercase()
                made++

                if (deleteOriginals) {
                    for (pi in g.items) {
                        val ok = try { DocumentsContract.deleteDocument(cr, pi.src.uri) } catch (_: Exception) { false }
                        if (ok) deleted++ else failures += "Gagal menghapus ${pi.src.name}"
                    }
                }
            } catch (_: CancelledException) {
                part?.let { try { DocumentsContract.deleteDocument(cr, it) } catch (_: Exception) {} }
                return RunResult(made, deleted, failures, true)
            } catch (e: Exception) {
                part?.let { try { DocumentsContract.deleteDocument(cr, it) } catch (_: Exception) {} }
                failures += "${g.zipName}: ${e.message ?: e.javaClass.simpleName} (file asli tidak diubah)"
                done = groupStart + g.totalSize
            }
        }
        return RunResult(made, deleted, failures, false)
    }

    private class CancelledException : Exception()

    /** Baca ulang ZIP yang baru ditulis: daftar isi, ukuran, dan CRC32 setiap file harus sama. */
    private fun verify(context: Context, zip: Uri, expected: List<ZipWriter.Entry>, l: Listener): Boolean {
        context.contentResolver.openFileDescriptor(zip, "r").use { pfd ->
            if (pfd == null) return false
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val items = ZipIndex.list(ch)
                if (items.size != expected.size) return false
                val byName = items.associateBy { it.name }
                val buf = ByteBuffer.allocate(256 * 1024)
                for (e in expected) {
                    if (l.cancelled()) return false
                    val z = byName[e.name] ?: return false
                    if (z.method != 0 || z.size != e.size || z.compressedSize != e.size) return false
                    val lh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                    ZipIndex.readFully(ch, lh, z.localHeaderOffset)
                    if (lh.getInt(0) != ZipIndex.SIG_LOC) return false
                    val nl = lh.getShort(26).toInt() and 0xffff
                    val el = lh.getShort(28).toInt() and 0xffff
                    var p = z.localHeaderOffset + 30 + nl + el
                    var left = e.size
                    val crc = CRC32()
                    while (left > 0) {
                        buf.clear()
                        buf.limit(minOf(buf.capacity().toLong(), left).toInt())
                        ZipIndex.readFully(ch, buf, p)
                        crc.update(buf.array(), 0, buf.limit())
                        p += buf.limit()
                        left -= buf.limit()
                    }
                    if (crc.value != e.crc) return false
                }
                return true
            }
        }
    }
}
