package com.fridayssheep.divaslider.ui.control

import android.graphics.Color
import com.fridayssheep.divaslider.input.BUTTON_CIRCLE
import com.fridayssheep.divaslider.input.BUTTON_CROSS
import com.fridayssheep.divaslider.input.BUTTON_NAV
import com.fridayssheep.divaslider.input.BUTTON_SERVICE
import com.fridayssheep.divaslider.input.BUTTON_SQUARE
import com.fridayssheep.divaslider.input.BUTTON_START
import com.fridayssheep.divaslider.input.BUTTON_TEST
import com.fridayssheep.divaslider.input.BUTTON_TRIANGLE

internal data class PadButtonSpec(
    val label: String,
    val bit: Int,
    val baseColor: Int
)

internal data class ToolButtonSpec(
    val label: String,
    val bit: Int,
    val baseColor: Int,
    val pulseCoin: Boolean = false,
    val pulse: Boolean = false
)

internal object ControlSpecs {
    val padButtons: List<PadButtonSpec> = listOf(
        PadButtonSpec("TRI", BUTTON_TRIANGLE, Color.rgb(134, 246, 207)),
        PadButtonSpec("SQR", BUTTON_SQUARE, Color.rgb(224, 102, 255)),
        PadButtonSpec("X", BUTTON_CROSS, Color.rgb(80, 139, 255)),
        PadButtonSpec("O", BUTTON_CIRCLE, Color.rgb(255, 115, 129))
    )

    val startButton = PadButtonSpec("START", BUTTON_START, Color.rgb(250, 204, 21))

    val toolButtons: List<ToolButtonSpec> = listOf(
        ToolButtonSpec("TEST", BUTTON_TEST, Color.rgb(94, 234, 212), pulse = true),
        ToolButtonSpec("SERVICE", BUTTON_SERVICE, Color.rgb(94, 234, 212), pulse = true),
        ToolButtonSpec("COIN", 0, Color.rgb(241, 198, 75), pulseCoin = true),
        ToolButtonSpec("NAV", BUTTON_NAV, Color.rgb(56, 189, 248))
    )
}
