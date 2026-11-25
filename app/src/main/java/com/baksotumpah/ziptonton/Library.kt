package com.baksotumpah.ziptonton

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileInputStream

/** Satu file ZIP di folder film. key = jalur relatif di folder (dipakai untuk progres & thumbnail). */
data class Film(
    val key: String,
    val uri: Uri,
    val title: String,
    val size: Long,
    val modified: Long,
    val videos: List<ZipItem>
) {
    val entryNames: List<String> get() = videos.map { it.name }
}

/**
 * Membaca HANYA folder yang dipilih user (beserta subfolder sampai 2 tingkat).
 * Tidak ada akses ke folder lain, tidak ada izin penyimpanan umum.
 */
object Library {
    private val indexCache = HashMap<String, List<ZipItem>>()

    fun scan(context: Context, treeUri: Uri): List<Film> {
        val out = ArrayList<Film>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        walk(context, treeUri, rootId, "", 0, out, Store(context))
        return out.sortedWith { a, b -> ZipIndex.naturalCompare(a.title, b.title) }
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
                    val videos = try { index(context, uri, size, mod) } catch (_: Exception) { emptyList() }
                    if (videos.isNotEmpty()) {
                        out += Film(prefix + name, uri, name.substringBeforeLast('.'), size, mod, store.applyOrder(prefix + name, videos))
                    }
                }
            }
        }
        for ((id, name) in subdirs) walk(context, tree, id, "$prefix$name/", depth + 1, out, store)
    }

    private fun index(context: Context, uri: Uri, size: Long, mod: Long): List<ZipItem> {
        val cacheKey = "$uri|$size|$mod"
        indexCache[cacheKey]?.let { return it }
        val v = loadVideos(context, uri)
        indexCache[cacheKey] = v
        return v
    }

    /** Baca daftar video di ZIP (hanya membaca, tanpa ekstrak). */
    fun loadVideos(context: Context, uri: Uri): List<ZipItem> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("Tidak bisa membuka file")
        return pfd.use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                ZipIndex.list(ch).filter { ZipIndex.isVideo(it) }.sortedWith(ZipIndex.naturalOrder)
            }
        }
    }

    fun folderName(treeUri: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        return id.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Penyimpanan" }
    }
}
