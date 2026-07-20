package io.github.oleglog.olcrtc.client.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) * 0.5f
        paint.shader = RadialGradient(
            width / 2f,
            height / 2f,
            radius,
            intArrayOf(
                (0xCC shl 24) or (accentColor and 0xFFFFFF),
                (0x78 shl 24) or (accentColor and 0xFFFFFF),
                (0x20 shl 24) or (accentColor and 0xFFFFFF),
                accentColor and 0xFFFFFF,
            ),
            floatArrayOf(0f, 0.42f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }
}
