package com.fridayssheep.divaslider.network

import android.os.SystemClock
import com.fridayssheep.divaslider.input.DivaInputState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

internal class UdpSender(
    private val host: String,
    private val port: Int,
    private val input: DivaInputState
) : InputSender {
    @Volatile private var running = true
    private var thread: Thread? = null

    override fun start(onStatus: (String) -> Unit) {
        running = true
        thread = Thread {
            var sequence = 0
            try {
                val remote = InetSocketAddress(host, port)
                DatagramSocket().use { socket ->
                    socket.connect(remote)
                    onStatus("udp $host:$port")
                    while (running) {
                        val packet = buildDivaPacket(input, sequence++)
                        socket.send(DatagramPacket(packet, packet.size, remote))
                        SystemClock.sleep(1)
                    }
                }
            } catch (t: Throwable) {
                onStatus("error: ${t.javaClass.simpleName}")
            }
        }.apply {
            name = "DIVA UDP Sender"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}
