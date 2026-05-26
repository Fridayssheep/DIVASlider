package com.fridayssheep.divaslider.network

import com.fridayssheep.divaslider.input.DivaInputState

internal fun buildDivaPacket(input: DivaInputState, sequence: Int): ByteArray {
    val (buttons, slider) = input.snapshot()
    val packet = ByteArray(43)
    packet[0] = 42
    packet[1] = 'D'.code.toByte()
    packet[2] = 'V'.code.toByte()
    packet[3] = 'S'.code.toByte()
    packet[4] = (sequence ushr 24).toByte()
    packet[5] = (sequence ushr 16).toByte()
    packet[6] = (sequence ushr 8).toByte()
    packet[7] = sequence.toByte()
    packet[8] = buttons.toByte()
    packet[9] = (buttons ushr 8).toByte()
    slider.copyInto(packet, destinationOffset = 10)
    return packet
}
