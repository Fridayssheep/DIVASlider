package com.fridayssheep.divaslider.network

import android.os.SystemClock
import com.fridayssheep.divaslider.input.DivaInputState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

internal class WebSocketSender(
    private val host: String,
    private val port: Int,
    private val input: DivaInputState
) : InputSender {
    @Volatile private var running = true
    @Volatile private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var thread: Thread? = null

    override fun start(onStatus: (String) -> Unit) {
        running = true
        thread = Thread {
            var sequence = 0
            try {
                val opened = CountDownLatch(1)
                val failed = CountDownLatch(1)
                val httpClient = OkHttpClient.Builder()
                    .pingInterval(10, TimeUnit.SECONDS)
                    .build()
                client = httpClient
                val request = Request.Builder()
                    .url("ws://$host:$port/ws")
                    .build()
                val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        onStatus("ws error: ${t.javaClass.simpleName}")
                        failed.countDown()
                    }
                })
                webSocket = socket
                while (opened.count > 0 && failed.count > 0 && running) {
                    SystemClock.sleep(1)
                }
                if (!running || failed.count == 0L) {
                    socket.cancel()
                    return@Thread
                }
                onStatus("ws $host:$port")

                while (running) {
                    val packet = buildDivaPacket(input, sequence++)
                    if (!socket.send(packet.toByteString())) {
                        onStatus("ws send failed")
                        break
                    }
                    SystemClock.sleep(1)
                }
                socket.close(1000, "stop")
            } catch (t: Throwable) {
                onStatus("error: ${t.javaClass.simpleName}")
            } finally {
                webSocket = null
                client?.dispatcher?.executorService?.shutdown()
                client?.connectionPool?.evictAll()
                client = null
            }
        }.apply {
            name = "DIVA WS Sender"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun stop() {
        running = false
        webSocket?.cancel()
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
        thread?.interrupt()
        thread = null
    }
}
