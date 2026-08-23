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
// A custom View subclass — this is how you get a UI behavior Android's built-in
// views don't offer. `: AppCompatTextView(...)` means it starts out as a completely
// normal TextView (still usable in activity_main.xml like any other), and only
// overrides how it actually paints its pixels.
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    // onDraw() is where any View paints itself onto the screen's Canvas — the
    // low-level drawing surface — using a Paint object to describe color/style/etc.
    // Every View already carries a `paint` used to draw its text; changing it to
    // Paint.Style.STROKE here (instead of the default filled style) is the one
    // change that makes the glyphs render as hollow outlines. super.onDraw(canvas)
    // then does the actual text-drawing work as normal, just using this modified paint.
    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = textSize * 0.05f
        super.onDraw(canvas)
    }
}
