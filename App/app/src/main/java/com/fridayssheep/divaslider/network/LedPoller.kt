package com.fridayssheep.divaslider.network

import android.os.SystemClock
import com.fridayssheep.divaslider.ui.DivaControlView
import java.util.concurrent.TimeUnit
import kotlin.math.min
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class LedPoller(
    private val host: String,
    private val controlView: DivaControlView
) {
    @Volatile private var running = true
    private val client = OkHttpClient.Builder()
        .callTimeout(900, TimeUnit.MILLISECONDS)
        .build()
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread {
            val request = Request.Builder()
                .url("http://$host:52469/status")
                .build()
            while (running) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            val leds = JSONObject(body).optJSONObject("leds")
                            val slider = leds?.optJSONArray("slider")?.toIntArray(96) ?: IntArray(96)
                            val buttons = leds?.optJSONArray("buttons")?.toIntArray(10) ?: IntArray(10)
                            controlView.post {
                                controlView.updateLEDs(slider, buttons)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    controlView.post {
                        controlView.updateLEDs(IntArray(96), IntArray(10))
                    }
                }
                SystemClock.sleep(80)
            }
        }.apply {
            name = "DIVA LED Poller"
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        thread?.interrupt()
        thread = null
        controlView.post {
            controlView.updateLEDs(IntArray(96), IntArray(10))
        }
    }
}

private fun org.json.JSONArray.toIntArray(size: Int): IntArray {
    val out = IntArray(size)
    for (i in 0 until min(size, length())) {
        out[i] = optInt(i).coerceIn(0, 255)
    }
    return out
}
