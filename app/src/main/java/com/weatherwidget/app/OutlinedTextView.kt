package com.weatherwidget.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A hollow, glow-only TextView: the glyph itself is never filled, just
 * stroked in the view's own textColor — combined with the XML shadowLayer
 * (shadowColor/shadowRadius, set on tempText in activity_main.xml), the glow
 * sits on both sides of that stroke line, inside and outside it. No solid
 * color anywhere inside the number, by request.
 */
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = textSize * 0.05f
        super.onDraw(canvas)
    }
}
