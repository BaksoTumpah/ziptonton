package com.baksotumpah.ziptonton

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat

/**
 * Deteksi episode yang terlewat dari nomor di nama file (S01_E01, S01E01, E01, Ep01).
 * Yang dihitung "terlewat" adalah nomor di antara episode terkecil dan terbesar yang ada di tiap musim.
 */
object Episodes {
    private val SE = Regex("""[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})""")
    private val EP = Regex("""(?:^|[ ._-])(?:[Ee][Pp]?|[Ee]pisode)[ ._-]?(\d{1,3})(?=[ ._-]|$)""")
    private const val MAX_SHOWN = 5

    /** (musim, episode); musim 0 = nama file tidak menyebut musim. */
    fun parse(name: String): Pair<Int, Int>? {
        val base = name.substringAfterLast('/').substringBeforeLast('.')
        SE.find(base)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        EP.find(base)?.let { return 0 to it.groupValues[1].toInt() }
        return null
    }

    class Gap(val season: Int, val missing: List<Int>)

    fun gaps(names: List<String>): List<Gap> {
        val bySeason = sortedMapOf<Int, MutableSet<Int>>()
        for (n in names) {
            val p = parse(n) ?: continue
            bySeason.getOrPut(p.first) { sortedSetOf() }.add(p.second)
        }
        val out = ArrayList<Gap>()
        for ((season, eps) in bySeason) {
            val lo = eps.first()
            val hi = eps.last()
            val missing = (lo..hi).filter { it !in eps }
            if (missing.isNotEmpty()) out += Gap(season, missing)
        }
        return out
    }

    private fun list(ctx: Context, nums: List<Int>): String {
        val shown = nums.take(MAX_SHOWN).map { it.toString() }
        val extra = nums.size - shown.size
        val and = ctx.getString(R.string.eps_and)
        val base = if (shown.size == 1) shown[0] else shown.dropLast(1).joinToString(", ") + " $and " + shown.last()
        return if (extra > 0) shown.joinToString(", ") + " " + ctx.getString(R.string.eps_more, extra) else base
    }

    /** Contoh: "⚠ Eps 4 dan 9 belum terdownload". null kalau tidak ada yang terlewat. */
    fun describe(ctx: Context, names: List<String>): CharSequence? {
        val g = gaps(names)
        if (g.isEmpty()) return null
        val what = if (g.size == 1) list(ctx, g[0].missing)
        else g.joinToString(" · ") { "S" + Organizer.pad(it.season) + ": " + g2(ctx, it) }
        val text = ctx.getString(R.string.eps_missing, what)
        val sp = SpannableString(text)
        sp.setSpan(ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.eps_warn)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    // di daftar beberapa musim, nomor dipisah koma saja
    private fun g2(ctx: Context, g: Gap): String {
        val shown = g.missing.take(MAX_SHOWN).joinToString(", ")
        val extra = g.missing.size - MAX_SHOWN
        return if (extra > 0) shown + " " + ctx.getString(R.string.eps_more, extra) else shown
    }
}
