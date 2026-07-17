package io.github.oleglog.olcrtc.client.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

internal class ParticleDriftView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Particle(
        var x: Float,
        var y: Float,
        val radius: Float,
        val speedX: Float,
        val speedY: Float,
        val alpha: Int,
    )

    private val accentColor: Int
    private val particles = ArrayList<Particle>(MAX_PARTICLES)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var lastFrameMillis = 0L
    private var active = false
    private var running = false
    private var lastWidth = 0
    private var lastHeight = 0
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameMillis == 0L) 16L else (now - lastFrameMillis).coerceAtMost(64L)
            lastFrameMillis = now
            step(dt / 1000f)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        val a = context.obtainStyledAttributes(
            attrs, intArrayOf(android.R.attr.colorPrimary), defStyleAttr, 0,
        )
        accentColor = a.getColor(0, 0).also { a.recycle() }
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        updateRunning()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        updateRunning()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lastWidth = w
        lastHeight = h
        seedParticles()
        updateRunning()
    }

    private fun updateRunning() {
        val shouldRun = active && isShown && animationsEnabled() && width > 0
        if (shouldRun == running) return
        running = shouldRun
        if (running) {
            lastFrameMillis = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            invalidate()
        }
    }

    private fun animationsEnabled(): Boolean {
        val scale = android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        return scale != 0f
    }

    private fun seedParticles() {
        if (particles.isNotEmpty()) return
        val w = lastWidth.coerceAtLeast(1)
        val h = lastHeight.coerceAtLeast(1)
        repeat(MAX_PARTICLES) {
            particles.add(
                Particle(
                    x = (Math.random() * w).toFloat(),
                    y = (Math.random() * h).toFloat(),
                    radius = (1.5f + Math.random() * 2.5f).toFloat(),
                    speedX = ((Math.random() - 0.5) * 18f).toFloat(),
                    speedY = ((Math.random() - 0.5) * 18f).toFloat(),
                    alpha = (38 + (Math.random() * 48)).toInt().coerceIn(38, 86),
                ),
            )
        }
    }

    private fun step(dt: Float) {
        val w = lastWidth.toFloat()
        val h = lastHeight.toFloat()
        particles.forEach { p ->
            p.x += p.speedX * dt
            p.y += p.speedY * dt
            if (p.x < -p.radius) p.x = w + p.radius
            if (p.x > w + p.radius) p.x = -p.radius
            if (p.y < -p.radius) p.y = h + p.radius
            if (p.y > h + p.radius) p.y = -p.radius
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        particles.forEach { p ->
            paint.color = (p.alpha shl 24) or (accentColor and 0xFFFFFF)
            canvas.drawCircle(p.x, p.y, p.radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        running = false
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_PARTICLES = 18
    }
}
