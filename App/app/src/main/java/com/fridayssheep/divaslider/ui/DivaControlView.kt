package com.fridayssheep.divaslider.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.fridayssheep.divaslider.input.BUTTON_COIN
import com.fridayssheep.divaslider.input.BUTTON_CIRCLE
import com.fridayssheep.divaslider.input.BUTTON_CROSS
import com.fridayssheep.divaslider.input.BUTTON_SQUARE
import com.fridayssheep.divaslider.input.BUTTON_START
import com.fridayssheep.divaslider.input.BUTTON_TRIANGLE
import com.fridayssheep.divaslider.input.DivaInputState
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal class DivaControlView(context: Context, private val input: DivaInputState) : View(context) {
    private data class PointerState(var x: Float, var y: Float)
    private data class PadButton(
        val label: String,
        val bit: Int,
        val baseColor: Int,
        val pulseCoin: Boolean = false,
        val rect: RectF = RectF()
    )

    private val pointers = LinkedHashMap<Int, PointerState>()
    private val pressure = ByteArray(32)
    private val sliderLEDs = IntArray(96)
    private val buttonLEDs = IntArray(10)
    private val buttons = listOf(
        PadButton("TRI", BUTTON_TRIANGLE, Color.rgb(134, 246, 207)),
        PadButton("SQR", BUTTON_SQUARE, Color.rgb(224, 102, 255)),
        PadButton("X", BUTTON_CROSS, Color.rgb(80, 139, 255)),
        PadButton("O", BUTTON_CIRCLE, Color.rgb(255, 115, 129)),
        PadButton("START", BUTTON_START, Color.rgb(238, 244, 255)),
        PadButton("COIN", 0, Color.rgb(241, 198, 75), pulseCoin = true)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sliderRect = RectF()
    private val buttonRect = RectF()
    private val railRect = RectF()
    private val handleRect = RectF()
    private val lockRect = RectF()
    private var backgroundShader: LinearGradient? = null
    private var controlsExpanded = false
    private var expandProgress = 0f
    private var panelHeightRatio = 0f
    private var expandAnimator: android.animation.ValueAnimator? = null
    private var panelLocked = false
    private var handlePointerId = -1
    private var lockPointerId = -1
    private var handleDownX = 0f
    private var handleDownY = 0f
    private var lockDownX = 0f
    private var lockDownY = 0f
    private var handleDragStartRatio = 0f
    private var handleDownTime = 0L
    private var handleDragging = false
    private var lockDownTime = 0L
    private var lockLongPressHandled = false
    private var handleTapX = 0f
    private var handleTapY = 0f
    private var handleTapProgress = 0f
    private var lockTapX = 0f
    private var lockTapY = 0f
    private var lockTapProgress = 0f
    private var handleTapAnimator: ValueAnimator? = null
    private var lockTapAnimator: ValueAnimator? = null
    private val iconPath = android.graphics.Path()
    private val clipPath = Path()
    private val progressTicker = object : Runnable {
        override fun run() {
            updateLongPressActions(SystemClock.uptimeMillis())
            if (handlePointerId != -1 || lockPointerId != -1) {
                invalidate()
                postDelayed(this, 16L)
            }
        }
    }

    init {
        setBackgroundColor(Palette.ink)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        isLongClickable = false
        isHapticFeedbackEnabled = false
        setOnLongClickListener { true }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        backgroundShader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Palette.ink, Color.rgb(13, 35, 42), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        layoutRects()
        drawBackground(canvas)
        drawSlider(canvas)
        drawHandle(canvas)
        drawButtons(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                parent?.requestDisallowInterceptTouchEvent(true)
                val x = event.getX(index)
                val y = event.getY(index)
                val pointerId = event.getPointerId(index)
                if (lockRect.contains(x, y)) {
                    lockPointerId = pointerId
                    lockDownX = x
                    lockDownY = y
                    lockDownTime = event.eventTime
                    lockLongPressHandled = false
                    removeCallbacks(progressTicker)
                    post(progressTicker)
                    return true
                }
                if (!panelLocked && handleRect.contains(x, y)) {
                    handlePointerId = pointerId
                    handleDownX = x
                    handleDownY = y
                    handleDragStartRatio = panelHeightRatio
                    handleDownTime = event.eventTime
                    handleDragging = false
                    removeCallbacks(progressTicker)
                    post(progressTicker)
                    return true
                }
                pointers[pointerId] = PointerState(x, y)
                pulseCoinIfNeeded(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val longPressTimeout = ViewConfiguration.getLongPressTimeout()
                if (lockPointerId != -1) {
                    val lockIndex = event.findPointerIndex(lockPointerId)
                    if (lockIndex >= 0) {
                        val x = event.getX(lockIndex)
                        val y = event.getY(lockIndex)
                        if (!lockLongPressHandled &&
                            lockRect.contains(x, y) &&
                            event.eventTime - lockDownTime >= longPressTimeout) {
                            panelLocked = !panelLocked
                            if (panelLocked) {
                                controlsExpanded = true
                                expandAnimator?.cancel()
                                expandProgress = 1f
                                pointers.clear()
                                input.update(0, ByteArray(32))
                            }
                            lockLongPressHandled = true
                            invalidate()
                            return true
                        }
                    }
                }

                if (handlePointerId != -1 && !panelLocked) {
                    val handleIndex = event.findPointerIndex(handlePointerId)
                    if (handleIndex >= 0) {
                        val y = event.getY(handleIndex)
                        if (!handleDragging &&
                            event.eventTime - handleDownTime >= longPressTimeout) {
                            beginHandleResize()
                        }
                        if (handleDragging) {
                            val heightDelta = handleDownY - y
                            panelHeightRatio = (handleDragStartRatio + heightDelta / max(1f, height.toFloat()))
                                .coerceIn(minPanelRatio(), maxPanelRatio())
                            invalidate()
                            return true
                        }
                    }
                }

                for (i in 0 until event.pointerCount) {
                    pointers[event.getPointerId(i)]?.let {
                        it.x = event.getX(i)
                        it.y = event.getY(i)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                if (pointerId == lockPointerId) {
                    if (!lockLongPressHandled && lockRect.contains(event.getX(index), event.getY(index))) {
                        startPanelControlTap(lockRect, event.getX(index), event.getY(index), false)
                    }
                    lockPointerId = -1
                    lockLongPressHandled = false
                    removeCallbacks(progressTicker)
                    return true
                }
                if (pointerId == handlePointerId) {
                    val wasDragging = handleDragging
                    handlePointerId = -1
                    handleDragging = false
                    removeCallbacks(progressTicker)
                    if (!wasDragging && !panelLocked && handleRect.contains(event.getX(index), event.getY(index))) {
                        startPanelControlTap(handleRect, event.getX(index), event.getY(index), true)
                        toggleControls()
                    }
                    return true
                }
                pointers.remove(pointerId)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointers.clear()
                    handlePointerId = -1
                    lockPointerId = -1
                    handleDragging = false
                    lockLongPressHandled = false
                    removeCallbacks(progressTicker)
                }
            }
        }
        rebuildInput()
        invalidate()
        return true
    }

    fun updateLEDs(slider: IntArray, buttons: IntArray) {
        sliderLEDs.fill(0)
        buttonLEDs.fill(0)
        slider.copyInto(sliderLEDs, endIndex = min(slider.size, sliderLEDs.size))
        buttons.copyInto(buttonLEDs, endIndex = min(buttons.size, buttonLEDs.size))
        invalidate()
    }

    private fun toggleControls() {
        if (panelLocked) return
        controlsExpanded = !controlsExpanded
        pointers.clear()
        input.update(0, ByteArray(32))
        expandAnimator?.cancel()
        expandAnimator = android.animation.ValueAnimator.ofFloat(expandProgress, if (controlsExpanded) 1f else 0f).apply {
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                expandProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun beginHandleResize() {
        if (handleDragging || panelLocked) return
        handleDragging = true
        controlsExpanded = true
        expandAnimator?.cancel()
        expandProgress = 1f
        pointers.clear()
        input.update(0, ByteArray(32))
    }

    private fun startPanelControlTap(rect: RectF, x: Float, y: Float, handle: Boolean) {
        if (rect.isEmpty) return
        val animator = if (handle) {
            handleTapAnimator
        } else {
            lockTapAnimator
        }
        animator?.cancel()
        if (handle) {
            handleTapX = x
            handleTapY = y
        } else {
            lockTapX = x
            lockTapY = y
        }
        val nextAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (handle) {
                    handleTapProgress = it.animatedValue as Float
                } else {
                    lockTapProgress = it.animatedValue as Float
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (handle) {
                        handleTapProgress = 0f
                    } else {
                        lockTapProgress = 0f
                    }
                    invalidate()
                }
            })
            start()
        }
        if (handle) {
            handleTapAnimator = nextAnimator
        } else {
            lockTapAnimator = nextAnimator
        }
    }

    private fun updateLongPressActions(now: Long) {
        val longPressTimeout = ViewConfiguration.getLongPressTimeout()
        if (lockPointerId != -1 &&
            !lockLongPressHandled &&
            now - lockDownTime >= longPressTimeout) {
            panelLocked = !panelLocked
            if (panelLocked) {
                controlsExpanded = true
                expandAnimator?.cancel()
                expandProgress = 1f
                pointers.clear()
                input.update(0, ByteArray(32))
            }
            lockLongPressHandled = true
        }

        if (handlePointerId != -1 &&
            !handleDragging &&
            !panelLocked &&
            now - handleDownTime >= longPressTimeout) {
            beginHandleResize()
        }
    }

    private fun rebuildInput() {
        layoutRects()
        pressure.fill(0)
        var buttonMask = 0

        for (pointer in pointers.values) {
            if (expandProgress > 0f && buttonRect.contains(pointer.x, pointer.y)) {
                for (button in buttons) {
                    if (!button.pulseCoin && button.rect.contains(pointer.x, pointer.y)) {
                        buttonMask = buttonMask or button.bit
                    }
                }
                continue
            }

            if (handleRect.contains(pointer.x, pointer.y) || lockRect.contains(pointer.x, pointer.y)) {
                continue
            }

            if (sliderRect.contains(pointer.x, pointer.y)) {
                val cell = min(31, max(0, ((pointer.x - sliderRect.left) / sliderRect.width() * 32).toInt()))
                pressure[cell] = 0x80.toByte()
                continue
            }
        }

        input.update(buttonMask, pressure)
    }

    private fun pulseCoinIfNeeded(x: Float, y: Float) {
        layoutRects()
        for (button in buttons) {
            if (button.pulseCoin && button.rect.contains(x, y)) {
                input.pulseCoin()
                break
            }
        }
    }

    private fun layoutRects() {
        val w = width.toFloat()
        val h = height.toFloat()
        val outer = dp(18)
        sliderRect.set(0f, 0f, w, h)
        railRect.set(sliderRect)

        val collapsedHeight = dp(34)
        val defaultExpandedHeight = min(dp(150), h * 0.34f)
        if (panelHeightRatio == 0f && h > 0f) {
            panelHeightRatio = (defaultExpandedHeight / h).coerceIn(minPanelRatio(), maxPanelRatio())
        }
        val expandedHeight = (h * panelHeightRatio).coerceIn(defaultExpandedHeight, h * maxPanelRatio())
        val panelHeight = collapsedHeight + (expandedHeight - collapsedHeight) * expandProgress
        buttonRect.set(outer, h - panelHeight - dp(10), w - outer, h - dp(10))
        handleRect.set(w * 0.5f - dp(70), buttonRect.top - dp(4), w * 0.5f + dp(70), buttonRect.top + dp(26))
        lockRect.set(buttonRect.left + dp(10), buttonRect.top - dp(4), buttonRect.left + dp(66), buttonRect.top + dp(26))

        val gap = dp(18)
        if (expandProgress == 0f) {
            for (button in buttons) button.rect.setEmpty()
            return
        }

        val sideReserve = min(dp(110), buttonRect.width() * 0.13f)
        val mainAreaWidth = buttonRect.width() - sideReserve - gap
        val diameter = min((mainAreaWidth - gap * 3f) / 4f, buttonRect.height() * 0.72f)
        val top = buttonRect.centerY() - diameter / 2f + dp(8)
        val startLeft = buttonRect.left + (mainAreaWidth - diameter * 4f - gap * 3f) / 2f
        for (i in 0 until 4) {
            val left = startLeft + i * (diameter + gap)
            buttons[i].rect.set(left, top, left + diameter, top + diameter)
        }

        val smallDiameter = min(diameter * 0.48f, sideReserve - dp(20))
        val smallLeft = buttonRect.right - smallDiameter - dp(10)
        val startTop = buttonRect.top + dp(24)
        buttons[4].rect.set(smallLeft, startTop, smallLeft + smallDiameter, startTop + smallDiameter)
        val coinTop = buttonRect.bottom - smallDiameter - dp(10)
        buttons[5].rect.set(smallLeft, coinTop, smallLeft + smallDiameter, coinTop + smallDiameter)
    }

    private fun minPanelRatio(): Float {
        if (height <= 0) return 0.2f
        return (min(dp(150), height * 0.34f) / height).coerceAtLeast(0.18f)
    }

    private fun maxPanelRatio(): Float = 0.70f

    private fun drawBackground(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = backgroundShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(120, 210, 232, 244)
        canvas.drawRect(0f, height * 0.52f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.argb(80, 24, 34, 48)
        canvas.drawRect(0f, height * 0.56f, width.toFloat(), height.toFloat(), paint)

        paint.color = Color.argb(55, 220, 252, 248)
        paint.strokeWidth = 1f
        val step = max(28f, width / 30f)
        var x = -height * 0.25f
        while (x < width) {
            canvas.drawLine(x, 0f, x + height * 0.35f, height.toFloat(), paint)
            x += step
        }
    }

    private fun drawSlider(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(17, 24, 39)
        canvas.drawRect(sliderRect, paint)

        val cellWidth = sliderRect.width() / 32f
        for (i in 0 until 32) {
            val left = sliderRect.left + i * cellWidth
            val rect = RectF(left, sliderRect.top, left + cellWidth, sliderRect.bottom)
            val ledColor = sliderLedColor(i)
            val led = max(Color.red(ledColor), max(Color.green(ledColor), Color.blue(ledColor)))
            val baseColor = if (i < 4 || i >= 28) Color.rgb(30, 41, 59) else Color.rgb(17, 24, 39)
            paint.color = when {
                led > 0 -> {
                    val alpha = (led * 0.55f).toInt()
                    Color.rgb(
                        blend(Color.red(baseColor), Color.red(ledColor), alpha),
                        blend(Color.green(baseColor), Color.green(ledColor), alpha),
                        blend(Color.blue(baseColor), Color.blue(ledColor), alpha)
                    )
                }
                else -> baseColor
            }
            canvas.drawRect(rect, paint)
            if (pressure[i].toInt() != 0) {
                paint.color = Color.argb(135, 255, 238, 170)
                canvas.drawRect(rect, paint)
            }
            paint.color = Color.rgb(38, 48, 67)
            canvas.drawRect(left, sliderRect.top, left + 1f, sliderRect.bottom, paint)
        }

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 42f * resources.displayMetrics.scaledDensity
        paint.color = Color.argb(180, 226, 232, 240)
        canvas.drawText("L", sliderRect.left + cellWidth * 2f, sliderRect.centerY(), paint)
        canvas.drawText("R", sliderRect.right - cellWidth * 2f, sliderRect.centerY(), paint)
    }

    private fun drawHandle(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((150 + 75 * expandProgress).toInt().coerceIn(0, 255), 12, 18, 30)
        canvas.drawRoundRect(buttonRect, dp(16), dp(16), paint)

        paint.color = if (panelLocked) {
            Color.argb(235, 245, 158, 11)
        } else {
            Color.argb(220, 220, 252, 248)
        }
        canvas.drawRoundRect(lockRect, dp(14), dp(14), paint)
        drawPressFill(
            canvas,
            lockRect,
            lockDownX,
            lockDownY,
            longPressProgress(now, lockDownTime, lockPointerId != -1 && !lockLongPressHandled),
            Color.argb(185, 255, 255, 255)
        )
        drawPressFill(
            canvas,
            lockRect,
            lockTapX,
            lockTapY,
            lockTapProgress,
            Color.argb(150, 255, 255, 255)
        )
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 13f * resources.displayMetrics.scaledDensity
        paint.color = if (panelLocked) Color.rgb(12, 10, 6) else Color.rgb(8, 13, 22)
        canvas.drawText(if (panelLocked) "UNLOCK" else "LOCK", lockRect.centerX(), lockRect.centerY() + paint.textSize * 0.35f, paint)

        if (panelLocked) {
            return
        }

        paint.color = Color.argb(if (handleDragging) 245 else 220, 220, 252, 248)
        canvas.drawRoundRect(handleRect, dp(14), dp(14), paint)
        val handleProgress = longPressProgress(now, handleDownTime, handlePointerId != -1 && !handleDragging)
        drawPressFill(
            canvas,
            handleRect,
            handleDownX,
            handleDownY,
            handleProgress,
            Color.argb(180, 94, 234, 212)
        )
        drawPressFill(
            canvas,
            handleRect,
            handleTapX,
            handleTapY,
            handleTapProgress,
            Color.argb(140, 94, 234, 212)
        )
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        paint.color = Color.rgb(8, 13, 22)
        val handleText = when {
            handleDragging -> "DRAG TO RESIZE"
            handlePointerId != -1 -> "RESIZE PANEL"
            controlsExpanded -> "HIDE BUTTONS"
            else -> "SHOW BUTTONS"
        }
        canvas.drawText(handleText, handleRect.centerX(), handleRect.centerY() + paint.textSize * 0.35f, paint)
    }

    private fun longPressProgress(now: Long, start: Long, active: Boolean): Float {
        if (!active || start == 0L) return 0f
        return ((now - start).toFloat() / ViewConfiguration.getLongPressTimeout()).coerceIn(0f, 1f)
    }

    private fun drawPressFill(canvas: Canvas, rect: RectF, x: Float, y: Float, progress: Float, color: Int) {
        if (progress <= 0f || rect.isEmpty) return
        val startX = x.coerceIn(rect.left, rect.right)
        val startY = y.coerceIn(rect.top, rect.bottom)
        val radius = max(
            max(hypot(startX - rect.left, startY - rect.top), hypot(startX - rect.right, startY - rect.top)),
            max(hypot(startX - rect.left, startY - rect.bottom), hypot(startX - rect.right, startY - rect.bottom))
        ) * progress

        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(rect, dp(14), dp(14), Path.Direction.CW)
        canvas.clipPath(clipPath)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(startX, startY, radius, paint)
        canvas.restoreToCount(save)
    }

    private fun drawButtons(canvas: Canvas) {
        var buttonMask = 0
        for (pointer in pointers.values) {
            for (button in buttons) {
                if (!button.pulseCoin && button.rect.contains(pointer.x, pointer.y)) {
                    buttonMask = buttonMask or button.bit
                }
            }
        }

        if (expandProgress == 0f) {
            return
        }

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        for ((index, button) in buttons.withIndex()) {
            val active = !button.pulseCoin && (buttonMask and button.bit) != 0
            if (button.rect.isEmpty) continue
            val cx = button.rect.centerX()
            val cy = button.rect.centerY()
            val radius = button.rect.width() / 2f
            val led = buttonLedIntensity(index)
            
            val lit = active || led > 0
            val glowIntensity = if (active) 1.0f else if (led > 0) led / 255f else 0.15f
            
            val currentColor = if (lit) {
                Color.rgb(
                    blend(Color.red(button.baseColor), 255, (glowIntensity * 100).toInt()),
                    blend(Color.green(button.baseColor), 255, (glowIntensity * 100).toInt()),
                    blend(Color.blue(button.baseColor), 255, (glowIntensity * 100).toInt())
                )
            } else {
                button.baseColor
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.argb((120 * expandProgress).toInt().coerceIn(0, 255), 4, 8, 14)
            canvas.drawCircle(cx, cy, radius * 0.9f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND

            val alphaBase = if (lit) (glowIntensity * 255).toInt().coerceIn(0, 255) else 40
            val expandedAlphaBase = (alphaBase * expandProgress).toInt().coerceIn(0, 255)
            
            iconPath.reset()
            iconPath.addCircle(cx, cy, radius * 0.82f, android.graphics.Path.Direction.CW)
            
            val iconRadius = radius * 0.40f
            var drawTextLabel = false
            
            when (button.label) {
                "TRI" -> {
                    val h = iconRadius * 0.866f
                    iconPath.moveTo(cx, cy - iconRadius)
                    iconPath.lineTo(cx + h, cy + iconRadius * 0.5f)
                    iconPath.lineTo(cx - h, cy + iconRadius * 0.5f)
                    iconPath.close()
                }
                "SQR" -> {
                    val r = iconRadius * 0.75f
                    iconPath.addRect(cx - r, cy - r, cx + r, cy + r, android.graphics.Path.Direction.CW)
                }
                "X" -> {
                    val r = iconRadius * 0.7f
                    iconPath.moveTo(cx - r, cy - r)
                    iconPath.lineTo(cx + r, cy + r)
                    iconPath.moveTo(cx + r, cy - r)
                    iconPath.lineTo(cx - r, cy + r)
                }
                "O" -> {
                    iconPath.addCircle(cx, cy, iconRadius * 0.85f, android.graphics.Path.Direction.CW)
                }
                else -> {
                    drawTextLabel = true
                }
            }
            
            val coreAlpha = (if (active) 255f else 255f * max(0.4f, glowIntensity)) * expandProgress
            val textAlpha = (if (active) 255f else 255f * max(0.5f, glowIntensity)) * expandProgress

            if (!drawTextLabel) {
                paint.strokeWidth = radius * 0.25f
                paint.color = Color.argb((expandedAlphaBase * 0.3f).toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)
                
                paint.strokeWidth = radius * 0.12f
                paint.color = Color.argb((expandedAlphaBase * 0.7f).toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)
                
                paint.strokeWidth = radius * 0.05f
                paint.color = if (active) Color.argb((255 * expandProgress).toInt().coerceIn(0, 255), 255, 255, 255) else Color.argb(coreAlpha.toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)
            } else {
                paint.strokeWidth = radius * 0.15f
                paint.color = Color.argb((expandedAlphaBase * 0.3f).toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)
                
                paint.strokeWidth = radius * 0.05f
                paint.color = if (active) Color.argb((255 * expandProgress).toInt().coerceIn(0, 255), 255, 255, 255) else Color.argb(coreAlpha.toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)

                paint.style = Paint.Style.FILL
                paint.textSize = if (button.rect.width() < dp(70)) {
                    11f * resources.displayMetrics.scaledDensity
                } else {
                    16f * resources.displayMetrics.scaledDensity
                }
                
                paint.color = if (active) Color.argb((255 * expandProgress).toInt().coerceIn(0, 255), 255, 255, 255) else Color.argb(textAlpha.toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawText(button.label, cx, cy + paint.textSize * 0.36f, paint)
            }
        }
    }

    private fun sliderLedColor(cell: Int): Int {
        val base = cell * 3
        if (base + 2 >= sliderLEDs.size) return Color.TRANSPARENT
        return Color.rgb(
            sliderLEDs[base].coerceIn(0, 255),
            sliderLEDs[base + 1].coerceIn(0, 255),
            sliderLEDs[base + 2].coerceIn(0, 255)
        )
    }

    private fun buttonLedIntensity(buttonIndex: Int): Int {
        val ledIndex = when (buttonIndex) {
            0 -> 6
            1 -> 7
            2 -> 8
            3 -> 9
            else -> -1
        }
        return if (ledIndex in buttonLEDs.indices) buttonLEDs[ledIndex].coerceIn(0, 255) else 0
    }

    private fun blend(base: Int, lit: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return ((base * (255 - a) + lit.coerceIn(0, 255) * a) / 255).coerceIn(0, 255)
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}
