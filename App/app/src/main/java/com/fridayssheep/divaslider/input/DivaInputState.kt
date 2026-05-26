package com.fridayssheep.divaslider.input

import kotlin.math.max
import kotlin.math.min

internal class DivaInputState {
    private val lock = Any()
    private val slider = ByteArray(32)
    private var buttons: Int = 0
    private var pendingCoinPulses = 0

    fun update(buttonMask: Int, sliderPressure: ByteArray) {
        synchronized(lock) {
            buttons = buttonMask
            slider.fill(0)
            sliderPressure.copyInto(slider, endIndex = min(sliderPressure.size, slider.size))
        }
    }

    fun pulseCoin() {
        synchronized(lock) {
            pendingCoinPulses++
        }
    }

    fun snapshot(): Pair<Int, ByteArray> {
        synchronized(lock) {
            var outButtons = buttons
            if (pendingCoinPulses > 0) {
                pendingCoinPulses = max(0, pendingCoinPulses - 1)
                outButtons = outButtons or BUTTON_COIN
            }
            return outButtons to slider.copyOf()
        }
    }

    fun clear() {
        synchronized(lock) {
            buttons = 0
            slider.fill(0)
            pendingCoinPulses = 0
        }
    }
}
