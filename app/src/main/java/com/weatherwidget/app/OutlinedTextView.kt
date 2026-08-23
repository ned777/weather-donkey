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
 * the current textSize, so it stays proportionally right whatever size
 * MainActivity.applyTempTextSize() picks.
 */
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private val outlineColor = ContextCompat.getColor(context, R.color.retro_white)

    override fun onDraw(canvas: Canvas) {
        val fillColor = currentTextColor

        // setTextColor(), not paint.color = ... — TextView's own onDraw() reapplies
        // currentTextColor to the paint every time it runs, which silently overwrote a
        // direct paint.color mutation here and made the "outline" pass draw in the fill
        // color too (i.e., no visible outline at all, just a solid glyph).
        setTextColor(outlineColor)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = textSize * 0.045f
        super.onDraw(canvas)

        setTextColor(fillColor)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        super.onDraw(canvas)
    }
}
