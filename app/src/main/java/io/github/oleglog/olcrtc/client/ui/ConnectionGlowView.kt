package io.github.oleglog.olcrtc.client.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

internal class ConnectionGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentColor = context.obtainStyledAttributes(
        attrs,
        intArrayOf(androidx.appcompat.R.attr.colorPrimary),
        defStyleAttr,
        0,
    ).run { getColor(0, 0).also { recycle() } }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) * 0.31f
        paint.color = accentColor
        paint.maskFilter = BlurMaskFilter(minOf(width, height) * 0.15f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }
}
