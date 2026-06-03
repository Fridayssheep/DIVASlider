package com.fridayssheep.divaslider.ui.control

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import com.fridayssheep.divaslider.input.BUTTON_NAV
import com.fridayssheep.divaslider.input.BUTTON_START
import com.fridayssheep.divaslider.ui.Palette
import kotlin.math.hypot
import kotlin.math.max
internal class PressFeedback(
    val panelLocked: Boolean,
    val handleDragging: Boolean,
    val handlePressed: Boolean,
    val controlsExpanded: Boolean,
    val lockDownX: Float,
    val lockDownY: Float,
    val lockLongPress: Float,
    val lockTapX: Float,
    val lockTapY: Float,
    val lockTap: Float,
    val handleDownX: Float,
    val handleDownY: Float,
    val handleLongPress: Float,
    val handleTapX: Float,
    val handleTapY: Float,
    val handleTap: Float
)

internal class ControlRenderer(
    private val density: Float,
    private val scaledDensity: Float
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPath = Path()
    private val clipPath = Path()
    private var backgroundShader: LinearGradient? = null

    private fun dp(value: Int): Float = value * density

    fun onSizeChanged(w: Int, h: Int) {
        backgroundShader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            Palette.ink, Color.rgb(13, 35, 42), Shader.TileMode.CLAMP
        )
    }

    private fun blend(base: Int, lit: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return ((base * (255 - a) + lit.coerceIn(0, 255) * a) / 255).coerceIn(0, 255)
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        g: ControlGeometry,
        frame: InputFrame,
        sliderLEDs: IntArray,
        buttonLEDs: IntArray,
        navEnabled: Boolean,
        expandProgress: Float,
        toolMenuOpen: Boolean,
        toolMenuProgress: Float,
        press: PressFeedback,
        isToolHighlighted: (String, Long) -> Boolean
    ) {
        drawBackground(canvas, width, height)
        drawSlider(canvas, g, sliderLEDs, frame.pressure)
        drawHandle(canvas, g, expandProgress, press)
        drawButtons(
            canvas, g, frame.buttonMask, buttonLEDs, navEnabled, expandProgress,
            toolMenuOpen, toolMenuProgress, isToolHighlighted
        )
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int) {
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
    private fun sliderLedColor(sliderLEDs: IntArray, cell: Int): Int {
        val base = cell * 3
        if (base + 2 >= sliderLEDs.size) return Color.TRANSPARENT
        return Color.rgb(
            sliderLEDs[base].coerceIn(0, 255),
            sliderLEDs[base + 1].coerceIn(0, 255),
            sliderLEDs[base + 2].coerceIn(0, 255)
        )
    }

    private fun buttonLedIntensity(buttonLEDs: IntArray, buttonIndex: Int): Int {
        val ledIndex = when (buttonIndex) {
            0 -> 6
            1 -> 7
            2 -> 8
            3 -> 9
            else -> -1
        }
        return if (ledIndex in buttonLEDs.indices) buttonLEDs[ledIndex].coerceIn(0, 255) else 0
    }

    private fun drawSlider(canvas: Canvas, g: ControlGeometry, sliderLEDs: IntArray, pressure: ByteArray) {
        val sliderRect = g.slider
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(17, 24, 39)
        canvas.drawRect(sliderRect, paint)

        val cellWidth = sliderRect.width() / 32f
        for (i in 0 until 32) {
            val left = sliderRect.left + i * cellWidth
            val rect = RectF(left, sliderRect.top, left + cellWidth, sliderRect.bottom)
            val ledColor = sliderLedColor(sliderLEDs, i)
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
        paint.textSize = 42f * scaledDensity
        paint.color = Color.argb(180, 226, 232, 240)
        canvas.drawText("L", sliderRect.left + cellWidth * 2f, sliderRect.centerY(), paint)
        canvas.drawText("R", sliderRect.right - cellWidth * 2f, sliderRect.centerY(), paint)
    }
    private fun drawButtons(
        canvas: Canvas,
        g: ControlGeometry,
        buttonMask: Int,
        buttonLEDs: IntArray,
        navEnabled: Boolean,
        expandProgress: Float,
        toolMenuOpen: Boolean,
        toolMenuProgress: Float,
        isToolHighlighted: (String, Long) -> Boolean
    ) {
        if (expandProgress == 0f) return

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        for ((index, spec) in ControlSpecs.padButtons.withIndex()) {
            val rect = g.padRects[index]
            val active = (buttonMask and spec.bit) != 0
            if (rect.isEmpty) continue
            drawPadButton(canvas, rect, spec, active, buttonLedIntensity(buttonLEDs, index), navEnabled, expandProgress)
        }

        drawStartButton(canvas, g.start, (buttonMask and BUTTON_START) != 0, expandProgress)
        drawToolMenu(canvas, g, buttonMask, toolMenuOpen, toolMenuProgress, isToolHighlighted)
    }

    private fun drawPadButton(
        canvas: Canvas,
        rect: RectF,
        spec: PadButtonSpec,
        active: Boolean,
        led: Int,
        navEnabled: Boolean,
        expandProgress: Float
    ) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val radius = rect.width() / 2f

        val lit = active || led > 0
        val glowIntensity = if (active) 1.0f else if (led > 0) led / 255f else 0.15f

        val currentColor = if (lit) {
            Color.rgb(
                blend(Color.red(spec.baseColor), 255, (glowIntensity * 100).toInt()),
                blend(Color.green(spec.baseColor), 255, (glowIntensity * 100).toInt()),
                blend(Color.blue(spec.baseColor), 255, (glowIntensity * 100).toInt())
            )
        } else {
            spec.baseColor
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.argb((120 * expandProgress).toInt().coerceIn(0, 255), 4, 8, 14)
        canvas.drawCircle(cx, cy, radius * 0.9f, paint)
        if (led > 0) {
            val fillAlpha = (52f * (led / 255f) * expandProgress).toInt().coerceIn(0, 52)
            paint.color = Color.argb(fillAlpha, Color.red(spec.baseColor), Color.green(spec.baseColor), Color.blue(spec.baseColor))
            canvas.drawCircle(cx, cy, radius * 0.9f, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND

        val alphaBase = if (lit) (glowIntensity * 255).toInt().coerceIn(0, 255) else 40
        val expandedAlphaBase = (alphaBase * expandProgress).toInt().coerceIn(0, 255)

        iconPath.reset()
        iconPath.addCircle(cx, cy, radius * 0.82f, Path.Direction.CW)

        val iconRadius = radius * 0.40f
        val drawTextLabel = buildPadIcon(spec.label, cx, cy, iconRadius, navEnabled)

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
            paint.textSize = if (rect.width() < dp(70)) 11f * scaledDensity else 16f * scaledDensity
            paint.color = if (active) Color.argb((255 * expandProgress).toInt().coerceIn(0, 255), 255, 255, 255) else Color.argb(textAlpha.toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
            canvas.drawText(spec.label, cx, cy + paint.textSize * 0.36f, paint)
        }
    }
    private fun buildPadIcon(label: String, cx: Float, cy: Float, iconRadius: Float, navEnabled: Boolean): Boolean {
        if (navEnabled) {
            val h = iconRadius * 0.866f
            when (label) {
                "TRI" -> { iconPath.moveTo(cx, cy - iconRadius); iconPath.lineTo(cx + h, cy + iconRadius * 0.5f); iconPath.lineTo(cx - h, cy + iconRadius * 0.5f); iconPath.close() }
                "SQR" -> { iconPath.moveTo(cx - iconRadius, cy); iconPath.lineTo(cx + iconRadius * 0.5f, cy - h); iconPath.lineTo(cx + iconRadius * 0.5f, cy + h); iconPath.close() }
                "X" -> { iconPath.moveTo(cx - h, cy - iconRadius * 0.5f); iconPath.lineTo(cx + h, cy - iconRadius * 0.5f); iconPath.lineTo(cx, cy + iconRadius); iconPath.close() }
                "O" -> { iconPath.moveTo(cx + iconRadius, cy); iconPath.lineTo(cx - iconRadius * 0.5f, cy - h); iconPath.lineTo(cx - iconRadius * 0.5f, cy + h); iconPath.close() }
                else -> return true
            }
        } else {
            when (label) {
                "TRI" -> { val h = iconRadius * 0.866f; iconPath.moveTo(cx, cy - iconRadius); iconPath.lineTo(cx + h, cy + iconRadius * 0.5f); iconPath.lineTo(cx - h, cy + iconRadius * 0.5f); iconPath.close() }
                "SQR" -> { val r = iconRadius * 0.75f; iconPath.addRect(cx - r, cy - r, cx + r, cy + r, Path.Direction.CW) }
                "X" -> { val r = iconRadius * 0.7f; iconPath.moveTo(cx - r, cy - r); iconPath.lineTo(cx + r, cy + r); iconPath.moveTo(cx + r, cy - r); iconPath.lineTo(cx - r, cy + r) }
                "O" -> iconPath.addCircle(cx, cy, iconRadius * 0.85f, Path.Direction.CW)
                else -> return true
            }
        }
        return false
    }
    private fun drawHandle(canvas: Canvas, g: ControlGeometry, expandProgress: Float, press: PressFeedback) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((150 + 75 * expandProgress).toInt().coerceIn(0, 255), 12, 18, 30)
        canvas.drawRoundRect(g.panel, dp(16), dp(16), paint)

        paint.color = if (press.panelLocked) Color.argb(235, 245, 158, 11) else Color.argb(220, 220, 252, 248)
        canvas.drawRoundRect(g.lock, dp(14), dp(14), paint)
        drawPressFill(canvas, g.lock, press.lockDownX, press.lockDownY, press.lockLongPress, Color.argb(185, 255, 255, 255))
        drawPressFill(canvas, g.lock, press.lockTapX, press.lockTapY, press.lockTap, Color.argb(150, 255, 255, 255))
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 13f * scaledDensity
        paint.color = if (press.panelLocked) Color.rgb(12, 10, 6) else Color.rgb(8, 13, 22)
        canvas.drawText(if (press.panelLocked) "UNLOCK" else "LOCK", g.lock.centerX(), g.lock.centerY() + paint.textSize * 0.35f, paint)

        if (press.panelLocked) return

        paint.color = Color.argb(if (press.handleDragging) 245 else 220, 220, 252, 248)
        canvas.drawRoundRect(g.handle, dp(14), dp(14), paint)
        drawPressFill(canvas, g.handle, press.handleDownX, press.handleDownY, press.handleLongPress, Color.argb(180, 94, 234, 212))
        drawPressFill(canvas, g.handle, press.handleTapX, press.handleTapY, press.handleTap, Color.argb(140, 94, 234, 212))
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 12f * scaledDensity
        paint.color = Color.rgb(8, 13, 22)
        val handleText = when {
            press.handleDragging -> "DRAG TO RESIZE"
            press.handlePressed -> "RESIZE PANEL"
            press.controlsExpanded -> "HIDE BUTTONS"
            else -> "SHOW BUTTONS"
        }
        canvas.drawText(handleText, g.handle.centerX(), g.handle.centerY() + paint.textSize * 0.35f, paint)
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
    private fun drawStartButton(canvas: Canvas, rect: RectF, active: Boolean, expandProgress: Float) {
        if (rect.isEmpty) return
        val spec = ControlSpecs.startButton
        val alpha = (expandProgress * 255).toInt().coerceIn(0, 255)
        val radius = rect.width() / 2f

        paint.style = Paint.Style.FILL
        paint.color = if (active) {
            Color.argb(220 * alpha / 255, Color.red(spec.baseColor), Color.green(spec.baseColor), Color.blue(spec.baseColor))
        } else {
            Color.argb(118 * alpha / 255, 4, 8, 14)
        }
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2)
        paint.color = Color.argb(
            (if (active) 245 else 205) * alpha / 255,
            Color.red(spec.baseColor), Color.green(spec.baseColor), Color.blue(spec.baseColor)
        )
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius - dp(1), paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 10f * scaledDensity
        paint.color = if (active) {
            Color.argb(alpha, 4, 16, 24)
        } else {
            Color.argb(alpha, Color.red(spec.baseColor), Color.green(spec.baseColor), Color.blue(spec.baseColor))
        }
        canvas.drawText(spec.label, rect.centerX(), rect.centerY() + paint.textSize * 0.35f, paint)
    }

    private fun drawToolMenu(
        canvas: Canvas,
        g: ControlGeometry,
        buttonMask: Int,
        toolMenuOpen: Boolean,
        toolMenuProgress: Float,
        isToolHighlighted: (String, Long) -> Boolean
    ) {
        if (g.menu.isEmpty) return
        val menuRect = g.menu
        val menuRadius = menuRect.width() / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (toolMenuOpen) Color.argb(220, 94, 234, 212) else Color.argb(118, 4, 8, 14)
        canvas.drawCircle(menuRect.centerX(), menuRect.centerY(), menuRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2)
        paint.color = Color.argb(if (toolMenuOpen) 245 else 205, 94, 234, 212)
        canvas.drawCircle(menuRect.centerX(), menuRect.centerY(), menuRadius - dp(1), paint)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.style = Paint.Style.FILL
        paint.textSize = 10f * scaledDensity
        paint.color = if (toolMenuOpen) Color.rgb(4, 16, 24) else Color.rgb(94, 234, 212)
        canvas.drawText("MENU", menuRect.centerX(), menuRect.centerY() + paint.textSize * 0.34f, paint)

        if (toolMenuProgress == 0f) return

        val now = SystemClock.uptimeMillis()
        for ((index, spec) in ControlSpecs.toolButtons.withIndex()) {
            val rect = g.toolRects[index]
            if (rect.isEmpty) continue
            val active = !spec.pulseCoin && !spec.pulse && (buttonMask and spec.bit) != 0
            val radius = rect.width() / 2f
            val cx = rect.centerX()
            val cy = rect.centerY()
            val alpha = (toolMenuProgress * 255).toInt().coerceIn(0, 255)
            val pressed = active || isToolHighlighted(spec.label, now)

            paint.style = Paint.Style.FILL
            paint.color = Color.argb((if (pressed) 230 else 176) * alpha / 255, 4, 8, 14)
            canvas.drawCircle(cx, cy, radius * 0.95f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2)
            val strokeAlpha = if (pressed) (alpha * 1.3f).toInt().coerceIn(0, 255) else alpha
            paint.color = if (pressed) Color.argb(strokeAlpha, 255, 255, 255)
            else Color.argb(strokeAlpha, Color.red(spec.baseColor), Color.green(spec.baseColor), Color.blue(spec.baseColor))
            canvas.drawCircle(cx, cy, radius * 0.88f, paint)

            paint.style = Paint.Style.FILL
            paint.textSize = if (spec.label.length > 5) 9f * scaledDensity else 12f * scaledDensity
            paint.color = if (pressed) Color.argb(alpha, 255, 255, 255)
            else Color.argb(alpha, Color.red(spec.baseColor), Color.green(spec.baseColor), Color.blue(spec.baseColor))
            canvas.drawText(spec.label, cx, cy + paint.textSize * 0.35f, paint)
        }
    }
}
