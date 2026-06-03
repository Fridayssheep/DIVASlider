package com.fridayssheep.divaslider.ui.control

import com.fridayssheep.divaslider.input.BUTTON_NAV
import com.fridayssheep.divaslider.input.BUTTON_START
import kotlin.math.max
import kotlin.math.min

internal class InputFrame(
    val buttonMask: Int,
    val pressure: ByteArray
)

internal class ControlInputMapper {

    fun map(
        pointers: Collection<PointerXY>,
        geometry: ControlGeometry,
        navEnabled: Boolean,
        expandProgress: Float
    ): InputFrame {
        val pressure = ByteArray(32)
        var buttonMask = if (navEnabled) BUTTON_NAV else 0

        for (p in pointers) {
            var handledTool = false
            for ((index, spec) in ControlSpecs.toolButtons.withIndex()) {
                if (spec.bit != BUTTON_NAV && !spec.pulseCoin && !spec.pulse &&
                    geometry.toolRects[index].contains(p.x, p.y)
                ) {
                    buttonMask = buttonMask or spec.bit
                    handledTool = true
                }
            }
            if (handledTool) continue

            if (expandProgress > 0f && geometry.panel.contains(p.x, p.y)) {
                if (geometry.start.contains(p.x, p.y)) {
                    buttonMask = buttonMask or BUTTON_START
                    continue
                }
                for ((index, spec) in ControlSpecs.padButtons.withIndex()) {
                    if (geometry.padRects[index].contains(p.x, p.y)) {
                        buttonMask = buttonMask or spec.bit
                    }
                }
                continue
            }

            if (geometry.handle.contains(p.x, p.y) ||
                geometry.lock.contains(p.x, p.y) ||
                geometry.menu.contains(p.x, p.y)
            ) {
                continue
            }

            if (geometry.slider.contains(p.x, p.y)) {
                val cell = min(31, max(0, ((p.x - geometry.slider.left) / geometry.slider.width() * 32).toInt()))
                pressure[cell] = 0x80.toByte()
                continue
            }
        }

        return InputFrame(buttonMask, pressure)
    }
}

internal data class PointerXY(val x: Float, val y: Float)
