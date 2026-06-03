package com.fridayssheep.divaslider.ui.control

import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.max

/**
 * Pure layout calculation for the control surface. Reads view size and the
 * current animation/resize state, writes every rectangle into [ControlGeometry].
 * Holds no mutable state of its own beyond the screen density.
 */
internal class ControlLayout(private val density: Float) {

    private fun dp(value: Int): Float = value * density

    fun minPanelRatio(height: Int): Float {
        if (height <= 0) return 0.2f
        return (min(dp(150), height * 0.34f) / height).coerceAtLeast(0.18f)
    }

    fun maxPanelRatio(): Float = 0.70f

    /** Default panel height ratio used to seed state once the view is sized. */
    fun defaultPanelRatio(height: Int): Float {
        if (height <= 0) return 0f
        val defaultExpandedHeight = min(dp(150), height * 0.34f)
        return (defaultExpandedHeight / height).coerceIn(minPanelRatio(height), maxPanelRatio())
    }

    fun layout(
        g: ControlGeometry,
        width: Int,
        height: Int,
        expandProgress: Float,
        panelHeightRatio: Float,
        toolMenuProgress: Float
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val outer = dp(18)
        g.slider.set(0f, 0f, w, h)
        g.rail.set(g.slider)

        val collapsedHeight = dp(34)
        val defaultExpandedHeight = min(dp(150), h * 0.34f)
        val expandedHeight = (h * panelHeightRatio).coerceIn(defaultExpandedHeight, h * maxPanelRatio())
        val panelHeight = collapsedHeight + (expandedHeight - collapsedHeight) * expandProgress
        g.panel.set(outer, h - panelHeight - dp(10), w - outer, h - dp(10))
        g.handle.set(w * 0.5f - dp(70), g.panel.top - dp(4), w * 0.5f + dp(70), g.panel.top + dp(26))
        g.lock.set(g.panel.left + dp(10), g.panel.top - dp(4), g.panel.left + dp(66), g.panel.top + dp(26))

        val gap = dp(18)
        if (expandProgress == 0f) {
            g.padRects.forEach { it.setEmpty() }
            g.toolRects.forEach { it.setEmpty() }
            g.start.setEmpty()
            g.menu.setEmpty()
            return
        }

        val menuDiameter = min(dp(54), g.panel.height() * 0.40f)
        g.menu.set(
            g.panel.right - menuDiameter - dp(12),
            g.panel.bottom - menuDiameter - dp(12),
            g.panel.right - dp(12),
            g.panel.bottom - dp(12)
        )

        val mainAreaWidth = g.menu.left - g.panel.left - gap
        val startSize = menuDiameter
        val startLeft = g.panel.right - dp(12) - startSize
        val startTop = g.panel.top + dp(12)
        g.start.set(startLeft, startTop, startLeft + startSize, startTop + startSize)

        val diameter = min((mainAreaWidth - gap * 3f) / 4f, g.panel.height() * 0.72f)
        val top = g.panel.centerY() - diameter / 2f + dp(8)
        val buttonsLeft = g.panel.left + (mainAreaWidth - diameter * 4f - gap * 3f) / 2f
        for (i in 0 until 4) {
            val left = buttonsLeft + i * (diameter + gap)
            g.padRects[i].set(left, top, left + diameter, top + diameter)
        }

        layoutToolButtons(g, menuDiameter, toolMenuProgress)
    }

    private fun layoutToolButtons(g: ControlGeometry, menuDiameter: Float, toolMenuProgress: Float) {
        val count = g.toolRects.size
        val toolSize = min(dp(58), menuDiameter * 0.92f).coerceAtLeast(dp(52))
        val availableUp = (g.menu.centerY() - dp(66) - toolSize / 2f).coerceAtLeast(0f)
        val availableLeft = (g.menu.centerX() - dp(10) - toolSize / 2f).coerceAtLeast(0f)
        val preferredStep = toolSize + dp(8)
        val minStep = toolSize + dp(2)
        val verticalStep = min(preferredStep, availableUp / count)

        for (index in 0 until count) {
            val offsetX: Float
            val offsetY: Float
            if (verticalStep >= minStep) {
                offsetX = 0f
                offsetY = -verticalStep * (index + 1)
            } else {
                val compressedStep = availableUp / count
                val neededX = sqrt(max(minStep * minStep - compressedStep * compressedStep, 0f))
                val xStep = min(neededX, availableLeft / count)
                offsetX = -xStep * (index + 1)
                offsetY = -compressedStep * (index + 1)
            }
            val cx = g.menu.centerX() + offsetX * toolMenuProgress
            val cy = g.menu.centerY() + offsetY * toolMenuProgress
            if (toolMenuProgress == 0f) {
                g.toolRects[index].setEmpty()
            } else {
                g.toolRects[index].set(
                    cx - toolSize / 2f,
                    cy - toolSize / 2f,
                    cx + toolSize / 2f,
                    cy + toolSize / 2f
                )
            }
        }
    }
}
