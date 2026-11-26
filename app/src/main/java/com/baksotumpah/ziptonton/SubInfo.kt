package com.baksotumpah.ziptonton

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat

/** Teks keterangan "CC": hijau = ada file subtitle, biru = sub Indonesia di dalam video, oranye dicoret = belum ada subtitle. */
object SubInfo {

    fun forPart(ctx: Context, st: SubState): CharSequence = build(ctx, st, ctx.getString(when (st) {
        SubState.FILE -> R.string.sub_info_file
        SubState.HARD -> R.string.sub_info_hard
        SubState.NONE -> R.string.sub_info_none
    }))

    fun forFilm(ctx: Context, states: List<SubState>): CharSequence {
        val n = states.size
        val none = states.count { it == SubState.NONE }
        val file = states.count { it == SubState.FILE }
        val hard = states.count { it == SubState.HARD }
        return when {
            n == 0 || none == n -> build(ctx, SubState.NONE, ctx.getString(R.string.sub_info_none))
            none > 0 -> build(ctx, SubState.NONE, ctx.getString(R.string.sub_film_some_none, none, n))
            hard == n -> build(ctx, SubState.HARD, ctx.getString(R.string.sub_info_hard))
            file == n -> build(ctx, SubState.FILE, ctx.getString(R.string.sub_info_file))
            else -> build(ctx, SubState.FILE, ctx.getString(R.string.sub_film_mixed))
        }
    }

    private fun build(ctx: Context, st: SubState, label: String): CharSequence {
        val color = ContextCompat.getColor(ctx, when (st) {
            SubState.FILE -> R.color.sub_file
            SubState.HARD -> R.color.sub_hard
            SubState.NONE -> R.color.sub_none
        })
        val sb = SpannableStringBuilder("CC  $label")
        sb.setSpan(ForegroundColorSpan(color), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (st == SubState.NONE) sb.setSpan(StrikethroughSpan(), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }
}
