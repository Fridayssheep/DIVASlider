package com.fridayssheep.divaslider.ui.control

import android.graphics.RectF

/**
 * Holds every laid-out rectangle for the control surface. Filled by
 * [ControlLayout.layout] and read by the input mapper and renderer. Pure data:
 * no Android view or animation state lives here.
 *
 * [padRects] is parallel-indexed with [ControlSpecs.padButtons]; [toolRects] is
 * parallel-indexed with [ControlSpecs.toolButtons]. An empty rect means the
 * element is not currently visible/hittable.
 */
internal class ControlGeometry {
    val slider = RectF()
    val rail = RectF()
    val panel = RectF()
    val handle = RectF()
    val lock = RectF()
    val menu = RectF()
    val start = RectF()
    val padRects: Array<RectF> = Array(ControlSpecs.padButtons.size) { RectF() }
    val toolRects: Array<RectF> = Array(ControlSpecs.toolButtons.size) { RectF() }
}
