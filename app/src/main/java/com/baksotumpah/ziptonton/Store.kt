package com.baksotumpah.ziptonton

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Progress(val positionMs: Long, val durationMs: Long, val finished: Boolean) {
    val fraction: Float
        get() = if (finished) 1f else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val started: Boolean get() = finished || positionMs > 0
}

/**
 * Menyimpan folder pilihan, menit terakhir tiap part, dan tontonan terakhir.
 * Progres dikunci memakai NAMA file ZIP di dalam folder (bukan alamat file),
 * jadi tetap aman walau folder dipilih ulang.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ziptonton", Context.MODE_PRIVATE)

    var folderUri: String?
        get() = prefs.getString("folder", null)
        set(v) { prefs.edit().putString("folder", v).apply() }

    private fun key(film: String, entry: String) = "p|$film|$entry"

    fun progress(film: String, entry: String): Progress? {
        val s = prefs.getString(key(film, entry), null) ?: return null
        val o = JSONObject(s)
        return Progress(o.optLong("pos"), o.optLong("dur"), o.optBoolean("done"))
    }

    fun saveProgress(film: String, entry: String, posMs: Long, durMs: Long) {
        // Sisa kurang dari 15 detik dianggap selesai.
        val done = durMs > 0 && posMs >= durMs - 15_000
        val o = JSONObject().put("pos", if (done) 0 else posMs).put("dur", durMs).put("done", done)
        prefs.edit()
            .putString(key(film, entry), o.toString())
            .putString("last_film", film)
            .putString("last_entry", entry)
            .apply()
    }

    fun markFinished(film: String, entry: String, durMs: Long) {
        val o = JSONObject().put("pos", 0).put("dur", durMs).put("done", true)
        prefs.edit().putString(key(film, entry), o.toString()).apply()
    }

    fun resetFilm(film: String) {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("p|$film|") }.forEach { e.remove(it) }
        if (prefs.getString("last_film", null) == film) e.remove("last_film").remove("last_entry")
        e.apply()
    }

    // ---- Urutan part manual (diatur user lewat tarik-geser di halaman film) ----

    private fun orderKey(film: String) = "o|$film"

    fun saveOrder(film: String, names: List<String>) {
        prefs.edit().putString(orderKey(film), JSONArray(names).toString()).apply()
    }

    fun clearOrder(film: String) {
        prefs.edit().remove(orderKey(film)).apply()
    }

    fun hasOrder(film: String) = prefs.contains(orderKey(film))

    /** Terapkan urutan manual; part yang belum tercatat ditaruh di belakang sesuai urutan alami. */
    fun applyOrder(film: String, items: List<ZipItem>): List<ZipItem> {
        val s = prefs.getString(orderKey(film), null) ?: return items
        val saved = try {
            JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (_: Exception) { return items }
        val byName = items.associateBy { it.name }
        val head = saved.mapNotNull { byName[it] }
        val rest = items.filter { it.name !in saved }
        return head + rest
    }

    fun markUnwatched(film: String, entry: String) {
        prefs.edit().remove(key(film, entry)).apply()
    }

    // ---- Kecepatan putar per film ----

    fun speed(film: String): Float = prefs.getString("s|$film", null)?.toFloatOrNull() ?: 1f

    fun setSpeed(film: String, v: Float) {
        prefs.edit().putString("s|$film", v.toString()).apply()
    }

    // ---- Penanda intro per film (berlaku untuk semua part film itu), -1 = belum diatur ----

    fun introStart(film: String): Long = introPair(film).first
    fun introEnd(film: String): Long = introPair(film).second

    private fun introPair(film: String): Pair<Long, Long> {
        val p = prefs.getString("i|$film", null)?.split(",") ?: return -1L to -1L
        return (p.getOrNull(0)?.toLongOrNull() ?: -1L) to (p.getOrNull(1)?.toLongOrNull() ?: -1L)
    }

    fun setIntroStart(film: String, ms: Long) {
        prefs.edit().putString("i|$film", "$ms,${introEnd(film)}").apply()
    }

    fun setIntroEnd(film: String, ms: Long) {
        prefs.edit().putString("i|$film", "${introStart(film)},$ms").apply()
    }

    fun clearIntro(film: String) {
        prefs.edit().remove("i|$film").apply()
    }

    // ---- Sembunyikan part yang sudah ditonton (per film) ----

    fun hideWatched(film: String) = prefs.getString("h|$film", null) == "1"

    fun setHideWatched(film: String, v: Boolean) {
        prefs.edit().apply { if (v) putString("h|$film", "1") else remove("h|$film") }.apply()
    }

    // ---- Cadangan & pemulihan ----

    private val backupPrefixes = listOf("p|", "o|", "s|", "i|", "h|")
    private val backupKeys = setOf("last_film", "last_entry")
    private fun backupable(k: String) = k in backupKeys || backupPrefixes.any { k.startsWith(it) }

    /** Semua progres, urutan part, kecepatan, penanda intro. Folder tidak ikut (izin folder tidak bisa dipindah). */
    fun exportBackup(): String {
        val data = JSONObject()
        prefs.all.forEach { (k, v) -> if (v is String && backupable(k)) data.put(k, v) }
        return JSONObject().put("app", "ziptonton").put("version", 1)
            .put("time", System.currentTimeMillis()).put("data", data).toString(2)
    }

    /** Mengembalikan jumlah entri yang dipulihkan, atau -1 kalau file bukan cadangan ZipTonton. */
    fun importBackup(json: String): Int {
        val o = try { JSONObject(json) } catch (_: Exception) { return -1 }
        if (o.optString("app") != "ziptonton") return -1
        val d = o.optJSONObject("data") ?: return -1
        val e = prefs.edit()
        var n = 0
        for (k in d.keys()) {
            if (!backupable(k)) continue
            val v = d.opt(k) as? String ?: continue
            e.putString(k, v); n++
        }
        e.apply()
        return n
    }

    /** Pasangan (kunci film, nama part) yang terakhir ditonton. */
    fun lastWatched(): Pair<String, String>? {
        val f = prefs.getString("last_film", null) ?: return null
        val e = prefs.getString("last_entry", null) ?: return null
        return f to e
    }

    /** Part terakhir yang ditonton di film tertentu (untuk tombol "Lanjutkan" di halaman film). */
    fun lastPartOf(film: String, entries: List<String>): String? {
        lastWatched()?.let { if (it.first == film && it.second in entries) return it.second }
        return entries.firstOrNull { val p = progress(film, it); p != null && !p.finished && p.positionMs > 0 }
            ?: entries.firstOrNull { progress(film, it)?.finished != true }
    }
}

object Fmt {
    private val id = Locale("in", "ID")

    fun time(ms: Long): String {
        val t = ms / 1000
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Ukuran dengan koma desimal ala Indonesia: 4,20 GB */
    fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format(id, "%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(id, "%.0f MB", b / (1L shl 20).toDouble())
        else -> String.format(id, "%d KB", b / 1024)
    }

    fun remaining(posMs: Long, durMs: Long): String {
        val min = ((durMs - posMs).coerceAtLeast(0) + 59_999) / 60_000
        return if (min >= 60) "Sisa ${min / 60} jam ${min % 60} menit" else "Sisa $min menit"
    }

    /** "Film Contoh - Part 2.mp4" -> "Film Contoh - Part 2" */
    fun stripExt(name: String) = name.substringAfterLast('/').substringBeforeLast('.')
}
