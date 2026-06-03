package com.fridayssheep.divaslider.ui.control

import android.graphics.RectF
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
