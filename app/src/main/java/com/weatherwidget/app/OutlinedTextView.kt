package com.weatherwidget.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat

/**
 * A TextView that draws itself twice — once stroked (the outline), once
 * filled on top — the standard technique for "outlined" display text, since
 * Android has no built-in outline-text attribute. Outline width scales with
 * the current textSize, so it stays proportionally right even as autosize
 * (see activity_main.xml's tempText) shrinks or grows the font.
 */
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private val outlineColor = ContextCompat.getColor(context, R.color.retro_white)

    override fun onDraw(canvas: Canvas) {
        val fillColor = paint.color

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = textSize * 0.06f
        paint.color = outlineColor
        super.onDraw(canvas)

        paint.style = Paint.Style.FILL
        paint.color = fillColor
        super.onDraw(canvas)
    }
}
