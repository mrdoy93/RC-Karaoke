package com.example.offlinekaraoke

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.TextView

/** Draws only the stroke layer used behind a normal lyric TextView. */
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : TextView(context, attrs, defStyleAttr) {

    private val outlineWidth = 4f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outlineWidth
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeMiter = 10f
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
    }
}
