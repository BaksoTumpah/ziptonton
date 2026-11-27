package com.baksotumpah.ziptonton

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream

/** Satu file ZIP di folder film. key = jalur relatif di folder (dipakai untuk progres & thumbnail). */
data class Film(
    val key: String,
    val uri: Uri,
    val title: String,
    val size: Long,
    val modified: Long,
    val videos: List<ZipItem>,
    val subs: List<SubFile> = emptyList()
) {
    val entryNames: List<String> get() = videos.map { it.name }
}

/**
 * Membaca HANYA folder yang dipilih user (beserta subfolder sampai 2 tingkat).
 * Tidak ada akses ke folder lain, tidak ada izin penyimpanan umum.
 */
/** Isi sebuah ZIP: video (urut alami) dan file subtitle di dalamnya. */
class Indexed(val videos: List<ZipItem>, val subs: List<SubFile>)

object Library {
    private val indexCache = HashMap<String, Indexed>()

    /** Subtitle tiap film hasil pemindaian terakhir (termasuk file lepas & ZIP subtitle terpisah). */
    private val subsByKey = HashMap<String, List<SubFile>>()
    fun subsFor(filmKey: String): List<SubFile>? = subsByKey[filmKey]

    fun scan(context: Context, treeUri: Uri): List<Film> {
        val out = ArrayList<Film>()
        if (treeUri.scheme == "file") {
            // Folder ZipTonton di penyimpanan perangkat (dibaca langsung, butuh izin akses file)
            val root = File(treeUri.path ?: throw java.io.IOException("Folder tidak valid"))
            if (!root.isDirectory) throw java.io.IOException("Folder tidak ditemukan")
            walkFiles(context, root, "", 0, out, Store(context))
        } else {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            walk(context, treeUri, rootId, "", 0, out, Store(context))
        }
        subsByKey.clear()
        out.forEach { subsByKey[it.key] = it.subs }
        return out.sortedWith { a, b -> ZipIndex.naturalCompare(a.title, b.title) }
    }

    private fun walkFiles(context: Context, dir: File, prefix: String, depth: Int, out: MutableList<Film>, store: Store) {
        val children = dir.listFiles() ?: if (depth == 0) throw java.io.IOException("Tidak bisa membaca folder") else return
        val subdirs = ArrayList<File>()
        val folderFilms = ArrayList<Film>()
        val looseSubs = ArrayList<SubFile>()
        val subZips = ArrayList<Pair<String, List<SubFile>>>()
        for (f in children.sortedBy { it.name.lowercase() }) {
            val name = f.name
            if (f.isDirectory) {
                if (depth < 2 && !name.startsWith(".")) subdirs += f
            } else if (name.lowercase().endsWith(".zip")) {
                val uri = Uri.fromFile(f)
                val size = f.length()
                val mod = f.lastModified()
                val ix = try { index(context, uri, size, mod) } catch (_: Exception) { Indexed(emptyList(), emptyList()) }
                if (ix.videos.isNotEmpty()) {
                    folderFilms += Film(prefix + name, uri, name.substringBeforeLast('.'), size, mod,
                        store.applyOrder(prefix + name, ix.videos), ix.subs)
                } else if (ix.subs.isNotEmpty()) {
                    subZips += name to ix.subs
                }
            } else if (Subtitles.isSubName(name) && f.length() <= Subtitles.MAX_SIZE) {
                Subtitles.make(name, uri = Uri.fromFile(f))?.let { looseSubs += it }
            }
        }
        out += attachExternal(folderFilms, looseSubs, subZips)
        for (d in subdirs) walkFiles(context, d, "$prefix${d.name}/", depth + 1, out, store)
    }

    private fun walk(context: Context, tree: Uri, docId: String, prefix: String, depth: Int, out: MutableList<Film>, store: Store) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val subdirs = ArrayList<Pair<String, String>>()
        val folderFilms = ArrayList<Film>()
        val looseSubs = ArrayList<SubFile>()
        val subZips = ArrayList<Pair<String, List<SubFile>>>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val mod = if (c.isNull(4)) 0L else c.getLong(4)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 2 && !name.startsWith(".")) subdirs += id to name
                } else if (name.lowercase().endsWith(".zip")) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val ix = try { index(context, uri, size, mod) } catch (_: Exception) { Indexed(emptyList(), emptyList()) }
                    if (ix.videos.isNotEmpty()) {
                        folderFilms += Film(prefix + name, uri, name.substringBeforeLast('.'), size, mod,
                            store.applyOrder(prefix + name, ix.videos), ix.subs)
                    } else if (ix.subs.isNotEmpty()) {
                        subZips += name to ix.subs      // ZIP berisi subtitle saja
                    }
                } else if (Subtitles.isSubName(name) && size <= Subtitles.MAX_SIZE) {
                    Subtitles.make(name, uri = DocumentsContract.buildDocumentUriUsingTree(tree, id))?.let { looseSubs += it }
                }
            }
        }
        out += attachExternal(folderFilms, looseSubs, subZips)
        for ((id, name) in subdirs) walk(context, tree, id, "$prefix$name/", depth + 1, out, store)
    }

    /**
     * Hubungkan subtitle di luar ZIP film (file lepas atau ZIP subtitle terpisah di folder yang sama):
     * - nama mengikuti nama ZIP film  -> berlaku untuk semua part
     * - nama cocok dengan nama/nomor part -> hanya untuk part itu
     * - sisanya, kalau di folder hanya ada satu film -> berlaku untuk semua part film itu
     */
    private fun attachExternal(films: List<Film>, loose: List<SubFile>, subZips: List<Pair<String, List<SubFile>>>): List<Film> {
        if (films.isEmpty() || (loose.isEmpty() && subZips.isEmpty())) return films
        val extra = HashMap<String, MutableList<SubFile>>()
        fun add(f: Film, s: SubFile) { extra.getOrPut(f.key) { ArrayList() }.add(s) }
        val single = films.size == 1

        fun place(s: SubFile, fallback: Film?) {
            val b = Subtitles.baseOf(s.name)
            var used = false
            for (f in films) {
                if (b == Subtitles.norm(f.title)) { add(f, s.copy(global = true)); used = true }
                else if (Subtitles.matchesAny(f.videos, b)) { add(f, s); used = true }
            }
            if (!used && fallback != null) add(fallback, s.copy(global = true))
        }

        for (s in loose) place(s, if (single) films[0] else null)
        for ((zname, entries) in subZips) {
            val target = films.firstOrNull { Subtitles.zipPairs(zname, it.title) } ?: if (single) films[0] else null
            for (s in entries) place(s, target)
        }
        return films.map { f -> extra[f.key]?.let { f.copy(subs = f.subs + it) } ?: f }
    }

    private fun index(context: Context, uri: Uri, size: Long, mod: Long): Indexed {
        val cacheKey = "$uri|$size|$mod"
        indexCache[cacheKey]?.let { return it }
        val v = loadIndexed(context, uri)
        indexCache[cacheKey] = v
        return v
    }

    /** Baca isi ZIP: daftar video dan subtitle (hanya membaca, tanpa ekstrak). */
    fun loadIndexed(context: Context, uri: Uri): Indexed {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("Tidak bisa membuka file")
        return pfd.use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                val all = ZipIndex.list(ch)
                val videos = all.filter { ZipIndex.isVideo(it) }.sortedWith(ZipIndex.naturalOrder)
                val subs = all.filter { Subtitles.isSubEntry(it) }.sortedWith(ZipIndex.naturalOrder)
                    .mapNotNull { Subtitles.make(it.name, zipUri = uri, zipItem = it) }
                Indexed(videos, subs)
            }
        }
    }

    /** Baca daftar video di ZIP (hanya membaca, tanpa ekstrak). */
    fun loadVideos(context: Context, uri: Uri): List<ZipItem> = loadIndexed(context, uri).videos

    fun folderName(treeUri: Uri): String {
        if (treeUri.scheme == "file") return File(treeUri.path ?: "").name.ifEmpty { "Penyimpanan" }
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        return id.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Penyimpanan" }
    }
}
