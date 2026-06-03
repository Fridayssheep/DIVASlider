package com.fridayssheep.divaslider.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.fridayssheep.divaslider.input.BUTTON_NAV
import com.fridayssheep.divaslider.input.DivaInputState
import com.fridayssheep.divaslider.ui.control.ControlGeometry
import com.fridayssheep.divaslider.ui.control.ControlInputMapper
import com.fridayssheep.divaslider.ui.control.ControlLayout
import com.fridayssheep.divaslider.ui.control.ControlRenderer
import com.fridayssheep.divaslider.ui.control.ControlSpecs
import com.fridayssheep.divaslider.ui.control.InputFrame
import com.fridayssheep.divaslider.ui.control.PointerXY
import com.fridayssheep.divaslider.ui.control.PressFeedback
import kotlin.math.max
import kotlin.math.min

/**
 * Thin coordination layer for the controller surface. Owns the touch state
 * machine and animations, and delegates pure work: [ControlLayout] computes
 * rectangles, [ControlInputMapper] turns pointers into an [InputFrame] (the
 * single source of truth for pressed buttons), [ControlRenderer] draws.
 */
internal class DivaControlView(context: Context, private val input: DivaInputState) : View(context) {
    private data class PointerState(var x: Float, var y: Float)

    private val pointers = LinkedHashMap<Int, PointerState>()
    private val sliderLEDs = IntArray(96)
    private val buttonLEDs = IntArray(10)

    private val geometry = ControlGeometry()
    private val layout = ControlLayout(resources.displayMetrics.density)
    private val mapper = ControlInputMapper()
    private val renderer = ControlRenderer(
        resources.displayMetrics.density,
        resources.displayMetrics.scaledDensity
    )
    private var currentFrame = InputFrame(0, ByteArray(32))

    // UI / animation state (read by layout + renderer each frame).
    private var controlsExpanded = false
    private var toolMenuOpen = false
    private var navEnabled = false
    private var panelLocked = false
    private var toolMenuProgress = 0f
    private var expandProgress = 0f
    private var panelHeightRatio = 0f
    private var toolMenuAnimator: ValueAnimator? = null
    private var expandAnimator: ValueAnimator? = null
    private val toolHighlightUntil = HashMap<String, Long>()

    // Touch state machine for the handle (resize/expand) and lock controls.
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
        renderer.onSizeChanged(w, h)
        if (panelHeightRatio == 0f && h > 0) {
            panelHeightRatio = layout.defaultPanelRatio(h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        layoutGeometry()
        val now = SystemClock.uptimeMillis()
        renderer.draw(
            canvas, width, height, geometry, currentFrame, sliderLEDs, buttonLEDs,
            navEnabled, expandProgress, toolMenuOpen, toolMenuProgress,
            pressFeedback(now)
        ) { label, ts -> isToolButtonHighlighted(label, ts) }
    }

    private fun layoutGeometry() {
        layout.layout(geometry, width, height, expandProgress, panelHeightRatio, toolMenuProgress)
    }

    private fun pressFeedback(now: Long) = PressFeedback(
        panelLocked = panelLocked,
        handleDragging = handleDragging,
        handlePressed = handlePointerId != -1,
        controlsExpanded = controlsExpanded,
        lockDownX = lockDownX,
        lockDownY = lockDownY,
        lockLongPress = longPressProgress(now, lockDownTime, lockPointerId != -1 && !lockLongPressHandled),
        lockTapX = lockTapX,
        lockTapY = lockTapY,
        lockTap = lockTapProgress,
        handleDownX = handleDownX,
        handleDownY = handleDownY,
        handleLongPress = longPressProgress(now, handleDownTime, handlePointerId != -1 && !handleDragging),
        handleTapX = handleTapX,
        handleTapY = handleTapY,
        handleTap = handleTapProgress
    )

    private fun longPressProgress(now: Long, start: Long, active: Boolean): Float {
        if (!active || start == 0L) return 0f
        return ((now - start).toFloat() / ViewConfiguration.getLongPressTimeout()).coerceIn(0f, 1f)
    }

    private fun isToolButtonHighlighted(label: String, now: Long): Boolean {
        val until = toolHighlightUntil[label] ?: return false
        if (now <= until) return true
        toolHighlightUntil.remove(label)
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                parent?.requestDisallowInterceptTouchEvent(true)
                val x = event.getX(index)
                val y = event.getY(index)
                val pointerId = event.getPointerId(index)
                if (geometry.lock.contains(x, y)) {
                    lockPointerId = pointerId
                    lockDownX = x
                    lockDownY = y
                    lockDownTime = event.eventTime
                    lockLongPressHandled = false
                    removeCallbacks(progressTicker)
                    post(progressTicker)
                    return true
                }
                if (!panelLocked && geometry.handle.contains(x, y)) {
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
                if (expandProgress > 0f && geometry.menu.contains(x, y)) {
                    toggleToolMenu()
                    return true
                }
                if (handleToolTap(x, y)) {
                    return true
                }
                pointers[pointerId] = PointerState(x, y)
            }
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (handleUp(event)) return true
            }
        }
        rebuildInput()
        invalidate()
        return true
    }
    private fun handleMove(event: MotionEvent) {
        val longPressTimeout = ViewConfiguration.getLongPressTimeout()
        if (lockPointerId != -1) {
            val lockIndex = event.findPointerIndex(lockPointerId)
            if (lockIndex >= 0) {
                val x = event.getX(lockIndex)
                val y = event.getY(lockIndex)
                if (!lockLongPressHandled &&
                    geometry.lock.contains(x, y) &&
                    event.eventTime - lockDownTime >= longPressTimeout) {
                    toggleLock()
                    lockLongPressHandled = true
                    invalidate()
                    return
                }
            }
        }

        if (handlePointerId != -1 && !panelLocked) {
            val handleIndex = event.findPointerIndex(handlePointerId)
            if (handleIndex >= 0) {
                val y = event.getY(handleIndex)
                if (!handleDragging && event.eventTime - handleDownTime >= longPressTimeout) {
                    beginHandleResize()
                }
                if (handleDragging) {
                    val heightDelta = handleDownY - y
                    panelHeightRatio = (handleDragStartRatio + heightDelta / max(1f, height.toFloat()))
                        .coerceIn(layout.minPanelRatio(height), layout.maxPanelRatio())
                    invalidate()
                    return
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

    /** Returns true if the event was consumed by a handle/lock control. */
    private fun handleUp(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        if (pointerId == lockPointerId) {
            if (!lockLongPressHandled && geometry.lock.contains(event.getX(index), event.getY(index))) {
                startPanelControlTap(geometry.lock, event.getX(index), event.getY(index), false)
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
            if (!wasDragging && !panelLocked && geometry.handle.contains(event.getX(index), event.getY(index))) {
                startPanelControlTap(geometry.handle, event.getX(index), event.getY(index), true)
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
        return false
    }

    private fun toggleLock() {
        panelLocked = !panelLocked
        if (panelLocked) {
            controlsExpanded = true
            expandAnimator?.cancel()
            expandProgress = 1f
            pointers.clear()
            navEnabled = false
            clearInput()
        }
    }
    fun updateLEDs(slider: IntArray, buttons: IntArray) {
        sliderLEDs.fill(0)
        buttonLEDs.fill(0)
        slider.copyInto(sliderLEDs, endIndex = min(slider.size, sliderLEDs.size))
        buttons.copyInto(buttonLEDs, endIndex = min(buttons.size, buttonLEDs.size))
        invalidate()
    }

    private fun rebuildInput() {
        layoutGeometry()
        val frame = mapper.map(
            pointers.values.map { PointerXY(it.x, it.y) },
            geometry,
            navEnabled,
            expandProgress
        )
        currentFrame = frame
        input.update(frame.buttonMask, frame.pressure)
    }

    /** Clears the sent input and the cached frame together so highlight never lags behind input. */
    private fun clearInput() {
        currentFrame = InputFrame(0, ByteArray(32))
        input.update(0, ByteArray(32))
    }

    private fun handleToolTap(x: Float, y: Float): Boolean {
        layoutGeometry()
        for ((index, spec) in ControlSpecs.toolButtons.withIndex()) {
            val rect = geometry.toolRects[index]
            if (spec.bit == BUTTON_NAV && rect.contains(x, y)) {
                navEnabled = !navEnabled
                rebuildInput()
                invalidate()
                return true
            }
            if (spec.pulseCoin && rect.contains(x, y)) {
                flashToolButton(spec.label)
                input.pulseCoin()
                return true
            }
            if (spec.pulse && rect.contains(x, y)) {
                flashToolButton(spec.label)
                val heldButtons = if (navEnabled) BUTTON_NAV else 0
                input.update(heldButtons or spec.bit, ByteArray(32))
                postDelayed({ rebuildInput() }, 120L)
                return true
            }
        }
        return false
    }

    private fun flashToolButton(label: String) {
        toolHighlightUntil[label] = SystemClock.uptimeMillis() + 120L
        invalidate()
        postDelayed({ invalidate() }, 140L)
    }

    private fun updateLongPressActions(now: Long) {
        val longPressTimeout = ViewConfiguration.getLongPressTimeout()
        if (lockPointerId != -1 && !lockLongPressHandled && now - lockDownTime >= longPressTimeout) {
            toggleLock()
            lockLongPressHandled = true
        }
        if (handlePointerId != -1 && !handleDragging && !panelLocked && now - handleDownTime >= longPressTimeout) {
            beginHandleResize()
        }
    }
    private fun toggleToolMenu() {
        toolMenuOpen = !toolMenuOpen
        toolMenuAnimator?.cancel()
        toolMenuAnimator = ValueAnimator.ofFloat(toolMenuProgress, if (toolMenuOpen) 1f else 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                toolMenuProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun toggleControls() {
        if (panelLocked) return
        controlsExpanded = !controlsExpanded
        pointers.clear()
        navEnabled = false
        if (!controlsExpanded) {
            toolMenuOpen = false
            toolMenuProgress = 0f
            toolMenuAnimator?.cancel()
        }
        clearInput()
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(expandProgress, if (controlsExpanded) 1f else 0f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
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
        navEnabled = false
        toolMenuOpen = false
        toolMenuProgress = 0f
        toolMenuAnimator?.cancel()
        clearInput()
    }

    private fun startPanelControlTap(rect: android.graphics.RectF, x: Float, y: Float, handle: Boolean) {
        if (rect.isEmpty) return
        (if (handle) handleTapAnimator else lockTapAnimator)?.cancel()
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
                if (handle) handleTapProgress = it.animatedValue as Float
                else lockTapProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (handle) handleTapProgress = 0f else lockTapProgress = 0f
                    invalidate()
                }
            })
            start()
        }
        if (handle) handleTapAnimator = nextAnimator else lockTapAnimator = nextAnimator
    }
}
