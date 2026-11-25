package com.baksotumpah.ziptonton

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Bar progres yang dibagi per part film: satu ruas = satu part,
 * terisi sesuai menit yang sudah ditonton.
 */
class SegmentBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.segment_track) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.lamp) }
    private val r = RectF()
    private var parts = floatArrayOf()
    var gap = 3f * dp

    fun setParts(fractions: List<Float>) {
        parts = fractions.toFloatArray()
        contentDescription = "${fractions.count { it >= 1f }} dari ${fractions.size} part selesai"
        invalidate()
    }

    fun setTrackColor(c: Int) { track.color = c; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((4 * dp).toInt(), heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        val n = parts.size
        if (n == 0) return
        val h = height.toFloat()
        val rad = h / 2
        val w = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val x = i * (w + gap)
            r.set(x, 0f, x + w, h)
            canvas.drawRoundRect(r, rad, rad, track)
            val f = parts[i].coerceIn(0f, 1f)
            if (f > 0f) {
                r.set(x, 0f, x + maxOf(w * f, h), h)
                canvas.drawRoundRect(r, rad, rad, fill)
            }
        }
    }
}
