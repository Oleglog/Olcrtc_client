package io.github.oleglog.olcrtc.client.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import io.github.oleglog.olcrtc.client.routing.RoutingSettings
import kotlin.random.Random

internal class ParticleDriftView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Particle(
        var x: Float,
        var y: Float,
        val size: Float,
        val length: Float,
        val speedX: Float,
        val speedY: Float,
        val alpha: Int,
    )

    private val accentColor: Int
    private val particles = ArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var settings = RoutingSettings.BackgroundEffects()
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
        val attributes = context.obtainStyledAttributes(
            attrs, intArrayOf(android.R.attr.colorPrimary), defStyleAttr, 0,
        )
        accentColor = attributes.getColor(0, 0).also { attributes.recycle() }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun configure(value: RoutingSettings.BackgroundEffects) {
        if (settings == value) return
        settings = value
        particles.clear()
        seedParticles()
        invalidate()
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
        particles.clear()
        seedParticles()
        updateRunning()
    }

    private fun updateRunning() {
        val shouldRun = active && isShown && animationsEnabled() && width > 0 && height > 0
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

    private fun animationsEnabled(): Boolean = android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) != 0f

    private fun seedParticles() {
        if (particles.isNotEmpty() || lastWidth <= 0 || lastHeight <= 0) return
        val speedScale = when (settings.intensity) {
            RoutingSettings.BackgroundEffects.Intensity.LOW -> 0.8f
            RoutingSettings.BackgroundEffects.Intensity.MEDIUM -> 1f
            RoutingSettings.BackgroundEffects.Intensity.HIGH -> 1.25f
        }
        repeat(particleCount(settings.intensity)) {
            particles += when (settings.style) {
                RoutingSettings.BackgroundEffects.Style.SNOW -> Particle(
                    x = Random.nextFloat() * lastWidth,
                    y = Random.nextFloat() * lastHeight,
                    size = dp(1.4f + Random.nextFloat() * 2.4f),
                    length = 0f,
                    speedX = (-12f + Random.nextFloat() * 24f) * speedScale,
                    speedY = (34f + Random.nextFloat() * 54f) * speedScale,
                    alpha = Random.nextInt(72, 145),
                )
                RoutingSettings.BackgroundEffects.Style.RAIN -> Particle(
                    x = Random.nextFloat() * lastWidth,
                    y = Random.nextFloat() * lastHeight,
                    size = dp(0.7f + Random.nextFloat() * 0.5f),
                    length = dp(6f + Random.nextFloat() * 5f),
                    speedX = -14f - Random.nextFloat() * 14f,
                    speedY = 150f + Random.nextFloat() * 80f,
                    alpha = Random.nextInt(40, 86),
                )
                RoutingSettings.BackgroundEffects.Style.GLOW -> Particle(
                    x = Random.nextFloat() * lastWidth,
                    y = Random.nextFloat() * lastHeight,
                    size = dp(1.8f + Random.nextFloat() * 2f),
                    length = 0f,
                    speedX = (-7f + Random.nextFloat() * 14f) * speedScale,
                    speedY = (-5f + Random.nextFloat() * 10f) * speedScale,
                    alpha = Random.nextInt(58, 112),
                )
            }
        }
    }

    private fun step(dt: Float) {
        val width = lastWidth.toFloat()
        val height = lastHeight.toFloat()
        particles.forEach { particle ->
            particle.x += particle.speedX * dt
            particle.y += particle.speedY * dt
            when (settings.style) {
                RoutingSettings.BackgroundEffects.Style.SNOW -> {
                    if (particle.x < -particle.size) particle.x = width + particle.size
                    if (particle.x > width + particle.size) particle.x = -particle.size
                    if (particle.y > height + particle.size) {
                        particle.y = -particle.size
                        particle.x = Random.nextFloat() * width
                    }
                }
                RoutingSettings.BackgroundEffects.Style.RAIN -> {
                    if (particle.y > height + particle.length || particle.x < -particle.length) {
                        particle.y = -particle.length
                        particle.x = Random.nextFloat() * (width + particle.length)
                    }
                }
                RoutingSettings.BackgroundEffects.Style.GLOW -> {
                    if (particle.x < -particle.size) particle.x = width + particle.size
                    if (particle.x > width + particle.size) particle.x = -particle.size
                    if (particle.y < -particle.size) particle.y = height + particle.size
                    if (particle.y > height + particle.size) particle.y = -particle.size
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        particles.forEach { particle ->
            paint.color = (particle.alpha shl 24) or (accentColor and 0xFFFFFF)
            when (settings.style) {
                RoutingSettings.BackgroundEffects.Style.SNOW -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(particle.x, particle.y, particle.size, paint)
                }
                RoutingSettings.BackgroundEffects.Style.RAIN -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = particle.size
                    canvas.drawLine(
                        particle.x,
                        particle.y,
                        particle.x - particle.speedX / particle.speedY * particle.length,
                        particle.y - particle.length,
                        paint,
                    )
                }
                RoutingSettings.BackgroundEffects.Style.GLOW -> {
                    paint.style = Paint.Style.FILL
                    paint.alpha = particle.alpha / 3
                    canvas.drawCircle(particle.x, particle.y, particle.size * 2.4f, paint)
                    paint.alpha = particle.alpha
                    canvas.drawCircle(particle.x, particle.y, particle.size, paint)
                    paint.alpha = 255
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        running = false
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

internal fun particleCount(intensity: RoutingSettings.BackgroundEffects.Intensity): Int = when (intensity) {
    RoutingSettings.BackgroundEffects.Intensity.LOW -> 28
    RoutingSettings.BackgroundEffects.Intensity.MEDIUM -> 48
    RoutingSettings.BackgroundEffects.Intensity.HIGH -> 72
}
