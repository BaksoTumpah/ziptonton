package com.baksotumpah.ziptonton

import android.net.Uri

/**
 * Satu file subtitle. Sumbernya salah satu dari:
 * - entri di dalam ZIP (zipUri + zipItem): ZIP film itu sendiri atau ZIP subtitle terpisah
 * - file lepas di folder (uri)
 * global = berlaku untuk semua part film (dipakai kalau namanya mengikuti nama ZIP, bukan nama part)
 */
data class SubFile(
    val name: String,
    val mime: String,
    val label: String,
    val language: String?,
    val zipUri: Uri? = null,
    val zipItem: ZipItem? = null,
    val uri: Uri? = null,
    val global: Boolean = false
)

enum class SubState { FILE, HARD, NONE }

object Subtitles {
    /** Nama file memuat [Indonesian] / [Indo] = subtitle sudah tertulis di dalam video (hardsub). */
    private val HARD_TAG = Regex("""\[(?:indonesian|indonesia|indo)\]""", RegexOption.IGNORE_CASE)

    fun hasHardTag(name: String) = HARD_TAG.containsMatchIn(name)

    fun stateOf(video: ZipItem, subs: List<SubFile>, videoCount: Int, zip: Uri): SubState = when {
        forVideo(video, subs, videoCount, zip).isNotEmpty() -> SubState.FILE
        HARD_TAG.containsMatchIn(video.name) -> SubState.HARD
        else -> SubState.NONE
    }

    fun states(videos: List<ZipItem>, subs: List<SubFile>, zip: Uri): List<SubState> =
        videos.map { stateOf(it, subs, videos.size, zip) }

    const val MAX_SIZE = 5L * 1024 * 1024   // subtitle normal cuma puluhan-ratusan KB

    private val MIME = mapOf(
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa"
    )

    private val LANGS = HashMap<String, Pair<String, String>>().apply {
        fun l(label: String, code: String, vararg keys: String) { keys.forEach { put(it, label to code) } }
        l("Indonesia", "id", "id", "ind", "indo", "indonesia", "indonesian", "bahasa")
        l("English", "en", "en", "eng", "english")
        l("Melayu", "ms", "ms", "may", "malay", "melayu")
        l("Jepang", "ja", "ja", "jpn", "jap", "japanese")
        l("Korea", "ko", "ko", "kor", "korean")
        l("Mandarin", "zh", "zh", "chi", "chs", "cht", "chinese")
        l("Spanyol", "es", "es", "spa", "spanish")
        l("Prancis", "fr", "fr", "fre", "fra", "french")
        l("Jerman", "de", "de", "ger", "deu", "german")
        l("Arab", "ar", "ar", "ara", "arabic")
        l("Thai", "th", "th", "tha", "thai")
        l("Hindi", "hi", "hi", "hin", "hindi")
    }

    /** Kata di akhir nama yang bukan bagian judul. */
    private val MODS = setOf("forced", "sdh", "cc", "hi", "sub", "subs", "subtitle", "subtitles", "srt", "teks")

    fun mimeOf(name: String): String? = MIME[name.substringAfterLast('.', "").lowercase()]

    fun isSubName(name: String) = mimeOf(name) != null

    fun isSubEntry(item: ZipItem): Boolean {
        if (item.name.endsWith("/") || item.encrypted || item.size > MAX_SIZE) return false
        if (item.name.startsWith("__MACOSX/") || item.displayName.startsWith("._")) return false
        return isSubName(item.name)
    }

    private fun nameNoExt(name: String): String {
        val n = name.substringAfterLast('/')
        return if (n.contains('.')) n.substringBeforeLast('.') else n
    }

    private fun tokens(s: String) = s.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }

    /** Huruf kecil, hanya huruf/angka, dipisah satu spasi. */
    fun norm(s: String) = tokens(s).joinToString(" ") { it.lowercase() }

    /** Nama dasar untuk pencocokan: tanpa ekstensi dan tanpa kode bahasa / kata "sub" di ujung. */
    fun baseOf(name: String): String {
        val t = tokens(nameNoExt(name)).map { it.lowercase() }.toMutableList()
        var cut = 0
        while (t.size > 1 && cut < 3 && (t.last() in LANGS || t.last() in MODS)) { t.removeAt(t.size - 1); cut++ }
        return t.joinToString(" ")
    }

    fun videoBase(name: String) = norm(nameNoExt(name))

    fun langOf(name: String): Pair<String, String>? {
        val t = tokens(nameNoExt(name)).map { it.lowercase() }
        for (tok in t.takeLast(3).reversed()) LANGS[tok]?.let { return it }
        return null
    }

    fun labelOf(name: String): String = langOf(name)?.first ?: nameNoExt(name).take(40)

    fun make(name: String, zipUri: Uri? = null, zipItem: ZipItem? = null, uri: Uri? = null, global: Boolean = false): SubFile? {
        val mime = mimeOf(name) ?: return null
        val shown = name.substringAfterLast('/')
        return SubFile(shown, mime, labelOf(shown), langOf(shown)?.second, zipUri, zipItem, uri, global)
    }

    // Angka >= 1000 (tahun, 1080p) diabaikan agar tidak salah cocok antar film berbeda
    private fun numbers(s: String) = Regex("\\d+").findAll(s).map { it.value.toLongOrNull() ?: 0L }.filter { it < 1000 }.toList()

    /** Apakah subtitle ber-nama dasar [b] cocok dengan salah satu video (nama sama, atau deretan angka sama: "Part 1" = "ep01"). */
    fun matchesAny(videos: List<ZipItem>, b: String): Boolean = videos.any { matches(videoBase(it.name), b) }

    private fun matches(videoBase: String, subBase: String): Boolean {
        if (videoBase == subBase) return true
        val vn = numbers(videoBase)
        return vn.isNotEmpty() && vn == numbers(subBase)
    }

    /** Nama ZIP subtitle ("Film sub indo.zip") cocok dengan judul ZIP film ("Film"). */
    fun zipPairs(zipName: String, filmTitle: String): Boolean {
        val t = tokens(nameNoExt(zipName)).map { it.lowercase() }.toMutableList()
        while (t.size > 1 && (t.last() in LANGS || t.last() in MODS)) t.removeAt(t.size - 1)
        val z = t.joinToString(" ")
        val f = norm(filmTitle)
        return z.isNotEmpty() && (z == f || z.startsWith("$f "))
    }

    /**
     * Pilih subtitle untuk satu video. Yang cocok nama/angka diprioritaskan;
     * kalau tidak ada, pakai subtitle "global". Kalau ZIP-nya hanya berisi satu video,
     * subtitle lain di ZIP yang sama ([ownZip]) ikut dipakai.
     */
    fun forVideo(video: ZipItem, subs: List<SubFile>, videoCount: Int, ownZip: Uri): List<SubFile> {
        if (subs.isEmpty()) return emptyList()
        val vb = videoBase(video.name)
        var picked = subs.filter { !it.global && matches(vb, baseOf(it.name)) }
        if (picked.isEmpty() && videoCount == 1) picked = subs.filter { !it.global && it.zipUri == ownZip }
        if (picked.isEmpty()) picked = subs.filter { it.global }
        return uniqueLabels(picked)
    }

    private fun uniqueLabels(list: List<SubFile>): List<SubFile> {
        val seen = HashMap<String, Int>()
        return list.map {
            val n = (seen[it.label] ?: 0) + 1
            seen[it.label] = n
            if (n == 1) it else it.copy(label = "${it.label} $n")
        }
    }
}
