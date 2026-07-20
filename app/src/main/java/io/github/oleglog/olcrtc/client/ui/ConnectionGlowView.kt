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
        val radius = minOf(width, height) * 0.46f
        paint.shader = RadialGradient(
            width / 2f,
            height / 2f,
            radius,
            intArrayOf(
                (0xB8 shl 24) or (accentColor and 0xFFFFFF),
                (0x88 shl 24) or (accentColor and 0xFFFFFF),
                (0x50 shl 24) or (accentColor and 0xFFFFFF),
                (0x18 shl 24) or (accentColor and 0xFFFFFF),
                accentColor and 0xFFFFFF,
                accentColor and 0xFFFFFF,
            ),
            floatArrayOf(0f, 0.30f, 0.56f, 0.74f, 0.90f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }
}
