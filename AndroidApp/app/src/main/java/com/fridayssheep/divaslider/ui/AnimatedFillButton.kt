package com.fridayssheep.divaslider.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import kotlin.math.hypot
import kotlin.math.max

internal class AnimatedFillButton(context: Context) : Button(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private var radiusPx = 0f
    private var fillX = 0f
    private var fillY = 0f
    private var fillProgress = 0f
    private var fillAnimator: ValueAnimator? = null
    var fillColor: Int = Color.argb(150, 94, 234, 212)

    init {
        setWillNotDraw(false)
    }

    fun setBaseBackground(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) {
        radiusPx = radius.toFloat()
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    fun animateToBackground(
        color: Int,
        radius: Int,
        strokeColor: Int,
        strokeWidth: Int,
        textColor: Int,
        nextFillColor: Int,
        updateTextColorAfterFill: Boolean
    ) {
        fillAnimator?.cancel()
        if (!updateTextColorAfterFill) {
            setTextColor(textColor)
        }
        radiusPx = radius.toFloat()
        fillX = width * 0.5f
        fillY = height * 0.5f
        fillColor = color
        fillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fillProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setBaseBackground(color, radius, strokeColor, strokeWidth)
                    if (updateTextColorAfterFill) {
                        setTextColor(textColor)
                    }
                    fillColor = nextFillColor
                    fillProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            fillX = event.x.coerceIn(0f, width.toFloat())
            fillY = event.y.coerceIn(0f, height.toFloat())
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && isEnabled) {
            startFill()
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (fillProgress > 0f && width > 0 && height > 0) {
            val maxRadius = max(
                max(hypot(fillX, fillY), hypot(width - fillX, fillY)),
                max(hypot(fillX, height - fillY), hypot(width - fillX, height - fillY))
            )
            val save = canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radiusPx, radiusPx, Path.Direction.CW)
            canvas.clipPath(clipPath)
            fillPaint.color = fillColor
            canvas.drawCircle(fillX, fillY, maxRadius * fillProgress, fillPaint)
            canvas.restoreToCount(save)
        }
        super.onDraw(canvas)
    }

    private fun startFill() {
        fillAnimator?.cancel()
        fillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fillProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fillProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }
}
