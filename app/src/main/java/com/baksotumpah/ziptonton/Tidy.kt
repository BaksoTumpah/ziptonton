package com.baksotumpah.ziptonton

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Merapikan ZIP lama di folder ZipTonton: ZIP yang belum ada subtitle sama sekali dipindah ke
 * ZipTonton/<judul>/ (sama seperti ZIP baru hasil "Rapikan jadi ZIP"). Progres tontonan dan pengaturan
 * ikut dipindahkan ke kunci baru supaya tidak hilang.
 */
object Tidy {
    class Move(val film: Film, val from: File, val folder: String)

    fun folderOf(title: String): String =
        Organizer.safeName(title.replace(Regex("""\s+S\d{1,2}$"""), "")).ifEmpty { "Film" }

    /**
     * ZIP yang langsung ada di [root], semua/sebagian partnya "Belum ada subtitle" (CC dicoret),
     * dan tidak punya subtitle di luar ZIP (file lepas atau ZIP subtitle) yang akan tertinggal.
     */
    fun plan(context: Context, root: File): List<Move> {
        val films = Library.scan(context, Uri.fromFile(root))
        return films
            .filter { !it.key.contains('/') }
            .filter { f -> f.subs.none { it.zipUri != f.uri } }
            .filter { f -> Subtitles.states(f.videos, f.subs, f.uri).any { it == SubState.NONE } }
            .mapNotNull { f -> f.uri.path?.let { Move(f, File(it), folderOf(f.title)) } }
    }

    /** Pindahkan. Mengembalikan (jumlah berhasil, daftar masalah). */
    fun apply(root: File, moves: List<Move>, store: Store): Pair<Int, List<String>> {
        var moved = 0
        val problems = ArrayList<String>()
        for (m in moves) {
            try {
                val dir = File(root, m.folder)
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IllegalStateException("tidak bisa membuat folder")
                val target = File(dir, m.from.name)
                if (target.exists()) throw IllegalStateException("sudah ada di folder tujuan")
                if (!m.from.renameTo(target)) throw IllegalStateException("gagal memindahkan")
                store.renameFilm(m.film.key, m.folder + "/" + m.from.name)
                moved++
            } catch (e: Exception) {
                problems += m.from.name + ": " + (e.message ?: "gagal")
            }
        }
        return moved to problems
    }
}
