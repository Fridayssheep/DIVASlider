package com.fridayssheep.divaslider

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import okio.ByteString.Companion.toByteString

class MainActivity : Activity() {
    private val input = DivaInputState()
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var controllerPage: FrameLayout
    private lateinit var settingsPage: LinearLayout
    private lateinit var controlView: DivaControlView
    private lateinit var statusChip: TextView
    private lateinit var settingsStatus: TextView
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var udpButton: Button
    private lateinit var wsButton: Button
    private lateinit var connectButton: Button
    private lateinit var playConnectButton: Button
    private var ledPoller: LedPoller? = null
    private var mode = SenderMode.UDP
    private var sender: InputSender? = null
    private var connected = false
    private var connectButtonActiveStyle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("diva_slider", Context.MODE_PRIVATE)
        mode = loadMode()

        root = FrameLayout(this)
        controllerPage = buildControllerPage()
        settingsPage = buildSettingsPage()
        root.addView(controllerPage, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(settingsPage, FrameLayout.LayoutParams(dp(360), dp(300), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12)
            rightMargin = dp(12)
        })
        setContentView(root)

        showController()
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        input.clear()
        super.onPause()
    }

    override fun onDestroy() {
        stopSender()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::settingsPage.isInitialized && settingsPage.visibility == View.VISIBLE) {
            showController()
            return
        }
        super.onBackPressed()
    }

    private fun buildControllerPage(): FrameLayout {
        controlView = DivaControlView(this, input)
        statusChip = chip("Disconnected")
        connectButton = quietButton("Connect").apply {
            setOnClickListener {
                if (connected) {
                    stopSender()
                } else {
                    startSenderFromPrefs()
                }
            }
        }

        val settings = quietButton("SET").apply {
            setOnClickListener { showSettings() }
        }

        return FrameLayout(this).apply {
            setBackgroundColor(Palette.ink)
            addView(controlView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(statusChip, FrameLayout.LayoutParams(dp(210), dp(42), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(14)
                topMargin = dp(10)
            })
            addView(connectButton, FrameLayout.LayoutParams(dp(104), dp(42), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                rightMargin = dp(76)
            })
            addView(settings, FrameLayout.LayoutParams(dp(56), dp(42), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                rightMargin = dp(14)
            })
        }
    }

    private fun buildSettingsPage(): LinearLayout {
        hostEdit = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "PC IP or 127.0.0.1"
            setText(prefs.getString(KEY_HOST, ""))
            textSize = 18f
            setTextColor(Palette.text)
            setHintTextColor(Palette.muted)
            setSelectAllOnFocus(true)
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(Palette.panel, dp(14), Palette.stroke, 1)
        }
        portEdit = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = defaultPort(mode).toString()
            setText(loadPort().toString())
            textSize = 18f
            setTextColor(Palette.text)
            setHintTextColor(Palette.muted)
            setSelectAllOnFocus(true)
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(Palette.panel, dp(14), Palette.stroke, 1)
        }
        udpButton = modeButton("UDP")
        wsButton = modeButton("WebSocket")
        settingsStatus = smallLabel("Ready")

        val title = TextView(this).apply {
            text = "Settings"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.text)
        }
        val close = quietButton("Close").apply {
            setOnClickListener { showController() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(close, LinearLayout.LayoutParams(dp(82), dp(42)))
        }

        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(udpButton, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(wsButton, LinearLayout.LayoutParams(0, dp(52), 1f).withMarginLeft(dp(10)))
        }
        val endpointLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("Host"), LinearLayout.LayoutParams(0, dp(28), 1f))
            addView(label("Port"), LinearLayout.LayoutParams(dp(104), dp(28)).withMarginLeft(dp(10)))
        }
        val endpointRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hostEdit, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(portEdit, LinearLayout.LayoutParams(dp(104), dp(50)).withMarginLeft(dp(10)))
        }

        val save = quietButton("Save").apply {
            setOnClickListener {
                saveSettings()
                showController()
            }
        }
        playConnectButton = filledButton("Connect and Play").apply {
            setOnClickListener {
                saveSettings()
                startSenderFromPrefs()
                showController()
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(save, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(playConnectButton, LinearLayout.LayoutParams(0, dp(52), 1.35f).withMarginLeft(dp(10)))
        }

        udpButton.setOnClickListener {
            val previousMode = mode
            mode = SenderMode.UDP
            applyDefaultPortForMode(previousMode)
            updateModeButtons()
        }
        wsButton.setOnClickListener {
            val previousMode = mode
            mode = SenderMode.WS
            applyDefaultPortForMode(previousMode)
            updateModeButtons()
        }
        updateModeButtons()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(header, LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
            addView(endpointLabels, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).withTopMargin(dp(4)))
            addView(endpointRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            addView(label("Transport"), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).withTopMargin(dp(8)))
            addView(transportRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            addView(settingsStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).withTopMargin(dp(6)))
            addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).withTopMargin(dp(6)))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(242, 10, 16, 28), dp(22), Color.argb(150, 220, 252, 248), 1)
            visibility = View.GONE
            alpha = 0f
            scaleX = 0.96f
            scaleY = 0.96f
            addView(scroll, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
    }

    private fun startSenderFromPrefs() {
        val host = prefs.getString(KEY_HOST, "")?.trim().orEmpty()
        val port = loadPort()
        startSender(host, port)
    }

    private fun startSender(host: String, port: Int) {
        stopSender()
        hideKeyboard()
        if (host.isBlank()) {
            setStatus("Missing host", false)
            showSettings()
            return
        }
        ledPoller?.stop()
        val nextSender: InputSender = when (mode) {
            SenderMode.UDP -> UdpSender(host, port, input)
            SenderMode.WS -> WebSocketSender(host, port, input)
        }
        sender = nextSender
        ledPoller = LedPoller(host, controlView).also { it.start() }
        connected = true
        setStatus("Connecting ${mode.label} $host:$port", true)
        nextSender.start { text ->
            runOnUiThread {
                val ok = !text.startsWith("error", ignoreCase = true) &&
                    !text.contains("failed", ignoreCase = true)
                connected = ok
                setStatus(text.replace("ws", "WebSocket").replace("udp", "UDP"), ok)
            }
        }
    }

    private fun stopSender() {
        sender?.stop()
        ledPoller?.stop()
        ledPoller = null
        sender = null
        connected = false
        input.clear()
        setStatus("Disconnected", false)
    }

    private fun saveSettings() {
        val host = hostEdit.text.toString().trim()
        val port = portFromText(portEdit.text.toString(), defaultPort(mode))
        portEdit.setText(port.toString())
        prefs.edit()
            .putString(KEY_HOST, host)
            .putString(KEY_MODE, mode.name)
            .putInt(KEY_PORT, port)
            .apply()
        settingsStatus.text = if (host.isBlank()) "Host is empty" else "Saved ${mode.label} $host:$port"
    }

    private fun showSettings() {
        hostEdit.setText(prefs.getString(KEY_HOST, ""))
        mode = loadMode()
        portEdit.setText(loadPort().toString())
        portEdit.hint = defaultPort(mode).toString()
        updateModeButtons()
        val params = settingsPage.layoutParams as FrameLayout.LayoutParams
        params.width = min(dp(360), (resources.displayMetrics.widthPixels * 0.72f).toInt())
        params.height = min(dp(300), (resources.displayMetrics.heightPixels * 0.78f).toInt())
        settingsPage.layoutParams = params
        settingsPage.pivotX = params.width.toFloat()
        settingsPage.pivotY = 0f
        settingsPage.animate().cancel()
        settingsPage.visibility = View.VISIBLE
        settingsPage.alpha = 0f
        settingsPage.scaleX = 0.96f
        settingsPage.scaleY = 0.96f
        settingsPage.bringToFront()
        settingsPage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(160)
            .start()
    }

    private fun showController() {
        hideKeyboard()
        hideSystemBars()
        if (!::settingsPage.isInitialized || settingsPage.visibility != View.VISIBLE) {
            return
        }
        settingsPage.animate().cancel()
        settingsPage.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(130)
            .withEndAction {
                settingsPage.visibility = View.GONE
            }
            .start()
    }

    private fun setStatus(text: String, ok: Boolean) {
        if (::statusChip.isInitialized) {
            statusChip.text = text
            statusChip.background = rounded(if (ok) Palette.live else Palette.panel, dp(18), Palette.stroke, 1)
        }
        if (::connectButton.isInitialized) {
            connectButton.text = if (connected) "Stop" else "Connect"
            styleConnectButton(connected)
        }
        if (::settingsStatus.isInitialized) {
            settingsStatus.text = text
        }
    }

    private fun updateModeButtons() {
        udpButton.isSelected = mode == SenderMode.UDP
        wsButton.isSelected = mode == SenderMode.WS
        if (::portEdit.isInitialized) {
            portEdit.hint = defaultPort(mode).toString()
        }
        styleModeButton(udpButton, udpButton.isSelected)
        styleModeButton(wsButton, wsButton.isSelected)
    }

    private fun loadMode(): SenderMode {
        val raw = prefs.getString(KEY_MODE, SenderMode.UDP.name) ?: SenderMode.UDP.name
        return runCatching { SenderMode.valueOf(raw) }.getOrDefault(SenderMode.UDP)
    }

    private fun loadPort(): Int {
        val saved = prefs.getInt(KEY_PORT, defaultPort(mode))
        return if (saved in 1..65535) saved else defaultPort(mode)
    }

    private fun applyDefaultPortForMode(previousMode: SenderMode) {
        if (!::portEdit.isInitialized) return
        val current = portFromText(portEdit.text.toString(), defaultPort(previousMode))
        if (portEdit.text.isBlank() || current == defaultPort(previousMode)) {
            portEdit.setText(defaultPort(mode).toString())
        }
        portEdit.hint = defaultPort(mode).toString()
    }

    private fun defaultPort(senderMode: SenderMode): Int = when (senderMode) {
        SenderMode.UDP -> 52468
        SenderMode.WS -> 52469
    }

    private fun portFromText(raw: String, fallback: Int): Int {
        val value = raw.trim().toIntOrNull()
        return if (value != null && value in 1..65535) value else fallback
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Palette.muted)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun smallLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Palette.muted)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun chip(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Palette.text)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        background = rounded(Palette.panel, dp(18), Palette.stroke, 1)
    }

    private fun filledButton(text: String) = AnimatedFillButton(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(4, 12, 18))
        setBaseBackground(Palette.accent, dp(14), Color.TRANSPARENT, 0)
        fillColor = Color.argb(165, 255, 255, 255)
        minHeight = 0
        minWidth = 0
        stateListAnimator = null
    }

    private fun quietButton(text: String) = AnimatedFillButton(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Palette.text)
        setBaseBackground(Palette.panel, dp(14), Palette.stroke, 1)
        fillColor = Color.argb(150, 94, 234, 212)
        minHeight = 0
        minWidth = 0
        stateListAnimator = null
    }

    private fun modeButton(text: String) = AnimatedFillButton(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        minHeight = 0
        minWidth = 0
        stateListAnimator = null
    }

    private fun styleModeButton(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) Color.rgb(2, 13, 18) else Palette.text)
        val background = if (selected) Palette.accent else Palette.panel
        if (button is AnimatedFillButton) {
            button.setBaseBackground(background, dp(14), Palette.stroke, 1)
            button.fillColor = if (selected) Color.argb(155, 255, 255, 255) else Color.argb(150, 94, 234, 212)
        } else {
            button.background = rounded(background, dp(14), Palette.stroke, 1)
        }
    }

    private fun styleConnectButton(active: Boolean) {
        val textColor = if (active) Color.rgb(4, 12, 18) else Palette.text
        val background = if (active) Palette.accent else Palette.panel
        val stroke = if (active) Color.TRANSPARENT else Palette.stroke
        val strokeWidth = if (active) 0 else 1
        val tapFill = if (active) Color.argb(155, 255, 255, 255) else Color.argb(150, 94, 234, 212)
        if (connectButton is AnimatedFillButton) {
            val button = connectButton as AnimatedFillButton
            if (connectButtonActiveStyle != active) {
                connectButtonActiveStyle = active
                button.animateToBackground(
                    background,
                    dp(14),
                    stroke,
                    strokeWidth,
                    textColor,
                    tapFill,
                    updateTextColorAfterFill = active
                )
            } else {
                button.fillColor = tapFill
            }
        } else {
            connectButton.setTextColor(textColor)
            connectButton.background = rounded(background, dp(14), stroke, strokeWidth)
        }
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun LinearLayout.LayoutParams.withMarginLeft(value: Int): LinearLayout.LayoutParams {
        leftMargin = value
        return this
    }

    private fun LinearLayout.LayoutParams.withTopMargin(value: Int): LinearLayout.LayoutParams {
        topMargin = value
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_MODE = "mode"
        const val KEY_PORT = "port"
    }
}

private class AnimatedFillButton(context: Context) : Button(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private var radiusPx = 0f
    private var fillX = 0f
    private var fillY = 0f
    private var fillProgress = 0f
    private var fillAnimator: ValueAnimator? = null
    var fillColor: Int = Color.argb(150, 94, 234, 212)

    init {
        setWillNotDraw(false)
    }

    fun setBaseBackground(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) {
        radiusPx = radius.toFloat()
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    fun animateToBackground(
        color: Int,
        radius: Int,
        strokeColor: Int,
        strokeWidth: Int,
        textColor: Int,
        nextFillColor: Int,
        updateTextColorAfterFill: Boolean
    ) {
        fillAnimator?.cancel()
        if (!updateTextColorAfterFill) {
            setTextColor(textColor)
        }
        radiusPx = radius.toFloat()
        fillX = width * 0.5f
        fillY = height * 0.5f
        fillColor = color
        fillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fillProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setBaseBackground(color, radius, strokeColor, strokeWidth)
                    if (updateTextColorAfterFill) {
                        setTextColor(textColor)
                    }
                    fillColor = nextFillColor
                    fillProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            fillX = event.x.coerceIn(0f, width.toFloat())
            fillY = event.y.coerceIn(0f, height.toFloat())
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && isEnabled) {
            startFill()
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (fillProgress > 0f && width > 0 && height > 0) {
            val maxRadius = max(
                max(hypot(fillX, fillY), hypot(width - fillX, fillY)),
                max(hypot(fillX, height - fillY), hypot(width - fillX, height - fillY))
            )
            val save = canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radiusPx, radiusPx, Path.Direction.CW)
            canvas.clipPath(clipPath)
            fillPaint.color = fillColor
            canvas.drawCircle(fillX, fillY, maxRadius * fillProgress, fillPaint)
            canvas.restoreToCount(save)
        }
        super.onDraw(canvas)
    }

    private fun startFill() {
        fillAnimator?.cancel()
        fillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fillProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fillProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }
}

private enum class SenderMode(val label: String) {
    UDP("UDP"),
    WS("WebSocket")
}

private interface InputSender {
    fun start(onStatus: (String) -> Unit)
    fun stop()
}

private class DivaInputState {
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

private class UdpSender(
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

private class WebSocketSender(
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

private fun buildDivaPacket(input: DivaInputState, sequence: Int): ByteArray {
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

private class LedPoller(
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

private class DivaControlView(context: Context, private val input: DivaInputState) : View(context) {
    private data class PointerState(var x: Float, var y: Float)
    private data class PadButton(
        val label: String,
        val bit: Int,
        val baseColor: Int,
        val pulseCoin: Boolean = false,
        val rect: RectF = RectF()
    )

    private val pointers = LinkedHashMap<Int, PointerState>()
    private val pressure = ByteArray(32)
    private val sliderLEDs = IntArray(96)
    private val buttonLEDs = IntArray(10)
    private val buttons = listOf(
        PadButton("TRI", BUTTON_TRIANGLE, Color.rgb(134, 246, 207)),
        PadButton("SQR", BUTTON_SQUARE, Color.rgb(224, 102, 255)),
        PadButton("X", BUTTON_CROSS, Color.rgb(80, 139, 255)),
        PadButton("O", BUTTON_CIRCLE, Color.rgb(255, 115, 129)),
        PadButton("START", BUTTON_START, Color.rgb(238, 244, 255)),
        PadButton("COIN", 0, Color.rgb(241, 198, 75), pulseCoin = true)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sliderRect = RectF()
    private val buttonRect = RectF()
    private val railRect = RectF()
    private val handleRect = RectF()
    private val lockRect = RectF()
    private var backgroundShader: LinearGradient? = null
    private var controlsExpanded = false
    private var expandProgress = 0f
    private var panelHeightRatio = 0f
    private var expandAnimator: android.animation.ValueAnimator? = null
    private var panelLocked = false
    private var handlePointerId = -1
    private var lockPointerId = -1
    private var handleDownX = 0f
    private var handleDownY = 0f
    private var lockDownX = 0f
    private var lockDownY = 0f
    private var handleDragStartRatio = 0f
    private var handleDownTime = 0L
    private var handleDragging = false
    private var lockDownTime = 0L
    private var lockLongPressHandled = false
    private var handleTapX = 0f
    private var handleTapY = 0f
    private var handleTapProgress = 0f
    private var lockTapX = 0f
    private var lockTapY = 0f
    private var lockTapProgress = 0f
    private var handleTapAnimator: ValueAnimator? = null
    private var lockTapAnimator: ValueAnimator? = null
    private val iconPath = android.graphics.Path()
    private val clipPath = Path()
    private val progressTicker = object : Runnable {
        override fun run() {
            updateLongPressActions(SystemClock.uptimeMillis())
            if (handlePointerId != -1 || lockPointerId != -1) {
                invalidate()
                postDelayed(this, 16L)
            }
        }
    }

    init {
        setBackgroundColor(Palette.ink)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        isLongClickable = false
        isHapticFeedbackEnabled = false
        setOnLongClickListener { true }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        backgroundShader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Palette.ink, Color.rgb(13, 35, 42), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        layoutRects()
        drawBackground(canvas)
        drawSlider(canvas)
        drawHandle(canvas)
        drawButtons(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                parent?.requestDisallowInterceptTouchEvent(true)
                val x = event.getX(index)
                val y = event.getY(index)
                val pointerId = event.getPointerId(index)
                if (lockRect.contains(x, y)) {
                    lockPointerId = pointerId
                    lockDownX = x
                    lockDownY = y
                    lockDownTime = event.eventTime
                    lockLongPressHandled = false
                    removeCallbacks(progressTicker)
                    post(progressTicker)
                    return true
                }
                if (!panelLocked && handleRect.contains(x, y)) {
                    handlePointerId = pointerId
                    handleDownX = x
                    handleDownY = y
                    handleDragStartRatio = panelHeightRatio
                    handleDownTime = event.eventTime
                    handleDragging = false
                    removeCallbacks(progressTicker)
                    post(progressTicker)
                    return true
                }
                pointers[pointerId] = PointerState(x, y)
                pulseCoinIfNeeded(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val longPressTimeout = ViewConfiguration.getLongPressTimeout()
                if (lockPointerId != -1) {
                    val lockIndex = event.findPointerIndex(lockPointerId)
                    if (lockIndex >= 0) {
                        val x = event.getX(lockIndex)
                        val y = event.getY(lockIndex)
                        if (!lockLongPressHandled &&
                            lockRect.contains(x, y) &&
                            event.eventTime - lockDownTime >= longPressTimeout) {
                            panelLocked = !panelLocked
                            if (panelLocked) {
                                controlsExpanded = true
                                expandAnimator?.cancel()
                                expandProgress = 1f
                                pointers.clear()
                                input.update(0, ByteArray(32))
                            }
                            lockLongPressHandled = true
                            invalidate()
                            return true
                        }
                    }
                }

                if (handlePointerId != -1 && !panelLocked) {
                    val handleIndex = event.findPointerIndex(handlePointerId)
                    if (handleIndex >= 0) {
                        val y = event.getY(handleIndex)
                        if (!handleDragging &&
                            event.eventTime - handleDownTime >= longPressTimeout) {
                            beginHandleResize()
                        }
                        if (handleDragging) {
                            val heightDelta = handleDownY - y
                            panelHeightRatio = (handleDragStartRatio + heightDelta / max(1f, height.toFloat()))
                                .coerceIn(minPanelRatio(), maxPanelRatio())
                            invalidate()
                            return true
                        }
                    }
                }

                for (i in 0 until event.pointerCount) {
                    pointers[event.getPointerId(i)]?.let {
                        it.x = event.getX(i)
                        it.y = event.getY(i)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                if (pointerId == lockPointerId) {
                    if (!lockLongPressHandled && lockRect.contains(event.getX(index), event.getY(index))) {
                        startPanelControlTap(lockRect, event.getX(index), event.getY(index), false)
                    }
                    lockPointerId = -1
                    lockLongPressHandled = false
                    removeCallbacks(progressTicker)
                    return true
                }
                if (pointerId == handlePointerId) {
                    val wasDragging = handleDragging
                    handlePointerId = -1
                    handleDragging = false
                    removeCallbacks(progressTicker)
                    if (!wasDragging && !panelLocked && handleRect.contains(event.getX(index), event.getY(index))) {
                        startPanelControlTap(handleRect, event.getX(index), event.getY(index), true)
                        toggleControls()
                    }
                    return true
                }
                pointers.remove(pointerId)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointers.clear()
                    handlePointerId = -1
                    lockPointerId = -1
                    handleDragging = false
                    lockLongPressHandled = false
                    removeCallbacks(progressTicker)
                }
            }
        }
        rebuildInput()
        invalidate()
        return true
    }

    fun updateLEDs(slider: IntArray, buttons: IntArray) {
        sliderLEDs.fill(0)
        buttonLEDs.fill(0)
        slider.copyInto(sliderLEDs, endIndex = min(slider.size, sliderLEDs.size))
        buttons.copyInto(buttonLEDs, endIndex = min(buttons.size, buttonLEDs.size))
        invalidate()
    }

    private fun toggleControls() {
        if (panelLocked) return
        controlsExpanded = !controlsExpanded
        pointers.clear()
        input.update(0, ByteArray(32))
        expandAnimator?.cancel()
        expandAnimator = android.animation.ValueAnimator.ofFloat(expandProgress, if (controlsExpanded) 1f else 0f).apply {
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                expandProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun beginHandleResize() {
        if (handleDragging || panelLocked) return
        handleDragging = true
        controlsExpanded = true
        expandAnimator?.cancel()
        expandProgress = 1f
        pointers.clear()
        input.update(0, ByteArray(32))
    }

    private fun startPanelControlTap(rect: RectF, x: Float, y: Float, handle: Boolean) {
        if (rect.isEmpty) return
        val animator = if (handle) {
            handleTapAnimator
        } else {
            lockTapAnimator
        }
        animator?.cancel()
        if (handle) {
            handleTapX = x
            handleTapY = y
        } else {
            lockTapX = x
            lockTapY = y
        }
        val nextAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (handle) {
                    handleTapProgress = it.animatedValue as Float
                } else {
                    lockTapProgress = it.animatedValue as Float
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (handle) {
                        handleTapProgress = 0f
                    } else {
                        lockTapProgress = 0f
                    }
                    invalidate()
                }
            })
            start()
        }
        if (handle) {
            handleTapAnimator = nextAnimator
        } else {
            lockTapAnimator = nextAnimator
        }
    }

    private fun updateLongPressActions(now: Long) {
        val longPressTimeout = ViewConfiguration.getLongPressTimeout()
        if (lockPointerId != -1 &&
            !lockLongPressHandled &&
            now - lockDownTime >= longPressTimeout) {
            panelLocked = !panelLocked
            if (panelLocked) {
                controlsExpanded = true
                expandAnimator?.cancel()
                expandProgress = 1f
                pointers.clear()
                input.update(0, ByteArray(32))
            }
            lockLongPressHandled = true
        }

        if (handlePointerId != -1 &&
            !handleDragging &&
            !panelLocked &&
            now - handleDownTime >= longPressTimeout) {
            beginHandleResize()
        }
    }

    private fun rebuildInput() {
        layoutRects()
        pressure.fill(0)
        var buttonMask = 0

        for (pointer in pointers.values) {
            if (expandProgress > 0f && buttonRect.contains(pointer.x, pointer.y)) {
                for (button in buttons) {
                    if (!button.pulseCoin && button.rect.contains(pointer.x, pointer.y)) {
                        buttonMask = buttonMask or button.bit
                    }
                }
                continue
            }

            if (handleRect.contains(pointer.x, pointer.y) || lockRect.contains(pointer.x, pointer.y)) {
                continue
            }

            if (sliderRect.contains(pointer.x, pointer.y)) {
                val cell = min(31, max(0, ((pointer.x - sliderRect.left) / sliderRect.width() * 32).toInt()))
                pressure[cell] = 0x80.toByte()
                continue
            }
        }

        input.update(buttonMask, pressure)
    }

    private fun pulseCoinIfNeeded(x: Float, y: Float) {
        layoutRects()
        for (button in buttons) {
            if (button.pulseCoin && button.rect.contains(x, y)) {
                input.pulseCoin()
                break
            }
        }
    }

    private fun layoutRects() {
        val w = width.toFloat()
        val h = height.toFloat()
        val outer = dp(18)
        sliderRect.set(0f, 0f, w, h)
        railRect.set(sliderRect)

        val collapsedHeight = dp(34)
        val defaultExpandedHeight = min(dp(150), h * 0.34f)
        if (panelHeightRatio == 0f && h > 0f) {
            panelHeightRatio = (defaultExpandedHeight / h).coerceIn(minPanelRatio(), maxPanelRatio())
        }
        val expandedHeight = (h * panelHeightRatio).coerceIn(defaultExpandedHeight, h * maxPanelRatio())
        val panelHeight = collapsedHeight + (expandedHeight - collapsedHeight) * expandProgress
        buttonRect.set(outer, h - panelHeight - dp(10), w - outer, h - dp(10))
        handleRect.set(w * 0.5f - dp(70), buttonRect.top - dp(4), w * 0.5f + dp(70), buttonRect.top + dp(26))
        lockRect.set(buttonRect.left + dp(10), buttonRect.top - dp(4), buttonRect.left + dp(66), buttonRect.top + dp(26))

        val gap = dp(18)
        if (expandProgress == 0f) {
            for (button in buttons) button.rect.setEmpty()
            return
        }

        val sideReserve = min(dp(110), buttonRect.width() * 0.13f)
        val mainAreaWidth = buttonRect.width() - sideReserve - gap
        val diameter = min((mainAreaWidth - gap * 3f) / 4f, buttonRect.height() * 0.72f)
        val top = buttonRect.centerY() - diameter / 2f + dp(8)
        val startLeft = buttonRect.left + (mainAreaWidth - diameter * 4f - gap * 3f) / 2f
        for (i in 0 until 4) {
            val left = startLeft + i * (diameter + gap)
            buttons[i].rect.set(left, top, left + diameter, top + diameter)
        }

        val smallDiameter = min(diameter * 0.48f, sideReserve - dp(20))
        val smallLeft = buttonRect.right - smallDiameter - dp(10)
        val startTop = buttonRect.top + dp(24)
        buttons[4].rect.set(smallLeft, startTop, smallLeft + smallDiameter, startTop + smallDiameter)
        val coinTop = buttonRect.bottom - smallDiameter - dp(10)
        buttons[5].rect.set(smallLeft, coinTop, smallLeft + smallDiameter, coinTop + smallDiameter)
    }

    private fun minPanelRatio(): Float {
        if (height <= 0) return 0.2f
        return (min(dp(150), height * 0.34f) / height).coerceAtLeast(0.18f)
    }

    private fun maxPanelRatio(): Float = 0.70f

    private fun drawBackground(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = backgroundShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(120, 210, 232, 244)
        canvas.drawRect(0f, height * 0.52f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.argb(80, 24, 34, 48)
        canvas.drawRect(0f, height * 0.56f, width.toFloat(), height.toFloat(), paint)

        paint.color = Color.argb(55, 220, 252, 248)
        paint.strokeWidth = 1f
        val step = max(28f, width / 30f)
        var x = -height * 0.25f
        while (x < width) {
            canvas.drawLine(x, 0f, x + height * 0.35f, height.toFloat(), paint)
            x += step
        }
    }

    private fun drawSlider(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(17, 24, 39)
        canvas.drawRect(sliderRect, paint)

        val cellWidth = sliderRect.width() / 32f
        for (i in 0 until 32) {
            val left = sliderRect.left + i * cellWidth
            val rect = RectF(left, sliderRect.top, left + cellWidth, sliderRect.bottom)
            val ledColor = sliderLedColor(i)
            val led = max(Color.red(ledColor), max(Color.green(ledColor), Color.blue(ledColor)))
            val baseColor = if (i < 4 || i >= 28) Color.rgb(30, 41, 59) else Color.rgb(17, 24, 39)
            paint.color = when {
                led > 0 -> {
                    val alpha = (led * 0.55f).toInt()
                    Color.rgb(
                        blend(Color.red(baseColor), Color.red(ledColor), alpha),
                        blend(Color.green(baseColor), Color.green(ledColor), alpha),
                        blend(Color.blue(baseColor), Color.blue(ledColor), alpha)
                    )
                }
                else -> baseColor
            }
            canvas.drawRect(rect, paint)
            if (pressure[i].toInt() != 0) {
                paint.color = Color.argb(135, 255, 238, 170)
                canvas.drawRect(rect, paint)
            }
            paint.color = Color.rgb(38, 48, 67)
            canvas.drawRect(left, sliderRect.top, left + 1f, sliderRect.bottom, paint)
        }

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 42f * resources.displayMetrics.scaledDensity
        paint.color = Color.argb(180, 226, 232, 240)
        canvas.drawText("L", sliderRect.left + cellWidth * 2f, sliderRect.centerY(), paint)
        canvas.drawText("R", sliderRect.right - cellWidth * 2f, sliderRect.centerY(), paint)
    }

    private fun drawHandle(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((150 + 75 * expandProgress).toInt().coerceIn(0, 255), 12, 18, 30)
        canvas.drawRoundRect(buttonRect, dp(16), dp(16), paint)

        paint.color = if (panelLocked) {
            Color.argb(235, 245, 158, 11)
        } else {
            Color.argb(220, 220, 252, 248)
        }
        canvas.drawRoundRect(lockRect, dp(14), dp(14), paint)
        drawPressFill(
            canvas,
            lockRect,
            lockDownX,
            lockDownY,
            longPressProgress(now, lockDownTime, lockPointerId != -1 && !lockLongPressHandled),
            Color.argb(185, 255, 255, 255)
        )
        drawPressFill(
            canvas,
            lockRect,
            lockTapX,
            lockTapY,
            lockTapProgress,
            Color.argb(150, 255, 255, 255)
        )
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 13f * resources.displayMetrics.scaledDensity
        paint.color = if (panelLocked) Color.rgb(12, 10, 6) else Color.rgb(8, 13, 22)
        canvas.drawText(if (panelLocked) "UNLOCK" else "LOCK", lockRect.centerX(), lockRect.centerY() + paint.textSize * 0.35f, paint)

        if (panelLocked) {
            return
        }

        paint.color = Color.argb(if (handleDragging) 245 else 220, 220, 252, 248)
        canvas.drawRoundRect(handleRect, dp(14), dp(14), paint)
        val handleProgress = longPressProgress(now, handleDownTime, handlePointerId != -1 && !handleDragging)
        drawPressFill(
            canvas,
            handleRect,
            handleDownX,
            handleDownY,
            handleProgress,
            Color.argb(180, 94, 234, 212)
        )
        drawPressFill(
            canvas,
            handleRect,
            handleTapX,
            handleTapY,
            handleTapProgress,
            Color.argb(140, 94, 234, 212)
        )
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        paint.color = Color.rgb(8, 13, 22)
        val handleText = when {
            handleDragging -> "DRAG TO RESIZE"
            handlePointerId != -1 -> "RESIZE PANEL"
            controlsExpanded -> "HIDE BUTTONS"
            else -> "SHOW BUTTONS"
        }
        canvas.drawText(handleText, handleRect.centerX(), handleRect.centerY() + paint.textSize * 0.35f, paint)
    }

    private fun longPressProgress(now: Long, start: Long, active: Boolean): Float {
        if (!active || start == 0L) return 0f
        return ((now - start).toFloat() / ViewConfiguration.getLongPressTimeout()).coerceIn(0f, 1f)
    }

    private fun drawPressFill(canvas: Canvas, rect: RectF, x: Float, y: Float, progress: Float, color: Int) {
        if (progress <= 0f || rect.isEmpty) return
        val startX = x.coerceIn(rect.left, rect.right)
        val startY = y.coerceIn(rect.top, rect.bottom)
        val radius = max(
            max(hypot(startX - rect.left, startY - rect.top), hypot(startX - rect.right, startY - rect.top)),
            max(hypot(startX - rect.left, startY - rect.bottom), hypot(startX - rect.right, startY - rect.bottom))
        ) * progress

        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(rect, dp(14), dp(14), Path.Direction.CW)
        canvas.clipPath(clipPath)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(startX, startY, radius, paint)
        canvas.restoreToCount(save)
    }

    private fun drawButtons(canvas: Canvas) {
        var buttonMask = 0
        for (pointer in pointers.values) {
            for (button in buttons) {
                if (!button.pulseCoin && button.rect.contains(pointer.x, pointer.y)) {
                    buttonMask = buttonMask or button.bit
                }
            }
        }

        if (expandProgress == 0f) {
            return
        }

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        for ((index, button) in buttons.withIndex()) {
            val active = !button.pulseCoin && (buttonMask and button.bit) != 0
            if (button.rect.isEmpty) continue
            val cx = button.rect.centerX()
            val cy = button.rect.centerY()
            val radius = button.rect.width() / 2f
            val led = buttonLedIntensity(index)
            
            val lit = active || led > 0
            val glowIntensity = if (active) 1.0f else if (led > 0) led / 255f else 0.15f
            
            val currentColor = if (lit) {
                Color.rgb(
                    blend(Color.red(button.baseColor), 255, (glowIntensity * 100).toInt()),
                    blend(Color.green(button.baseColor), 255, (glowIntensity * 100).toInt()),
                    blend(Color.blue(button.baseColor), 255, (glowIntensity * 100).toInt())
                )
            } else {
                button.baseColor
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.argb((120 * expandProgress).toInt().coerceIn(0, 255), 4, 8, 14)
            canvas.drawCircle(cx, cy, radius * 0.9f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND

            val alphaBase = if (lit) (glowIntensity * 255).toInt().coerceIn(0, 255) else 40
            val expandedAlphaBase = (alphaBase * expandProgress).toInt().coerceIn(0, 255)
            
            iconPath.reset()
            iconPath.addCircle(cx, cy, radius * 0.82f, android.graphics.Path.Direction.CW)
            
            val iconRadius = radius * 0.40f
            var drawTextLabel = false
            
            when (button.label) {
                "TRI" -> {
                    val h = iconRadius * 0.866f
                    iconPath.moveTo(cx, cy - iconRadius)
                    iconPath.lineTo(cx + h, cy + iconRadius * 0.5f)
                    iconPath.lineTo(cx - h, cy + iconRadius * 0.5f)
                    iconPath.close()
                }
                "SQR" -> {
                    val r = iconRadius * 0.75f
                    iconPath.addRect(cx - r, cy - r, cx + r, cy + r, android.graphics.Path.Direction.CW)
                }
                "X" -> {
                    val r = iconRadius * 0.7f
                    iconPath.moveTo(cx - r, cy - r)
                    iconPath.lineTo(cx + r, cy + r)
                    iconPath.moveTo(cx + r, cy - r)
                    iconPath.lineTo(cx - r, cy + r)
                }
                "O" -> {
                    iconPath.addCircle(cx, cy, iconRadius * 0.85f, android.graphics.Path.Direction.CW)
                }
                else -> {
                    drawTextLabel = true
                }
            }
            
            val coreAlpha = (if (active) 255f else 255f * max(0.4f, glowIntensity)) * expandProgress
            val textAlpha = (if (active) 255f else 255f * max(0.5f, glowIntensity)) * expandProgress

            if (!drawTextLabel) {
                paint.strokeWidth = radius * 0.25f
                paint.color = Color.argb((expandedAlphaBase * 0.3f).toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)
                
                paint.strokeWidth = radius * 0.12f
                paint.color = Color.argb((expandedAlphaBase * 0.7f).toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)
                
                paint.strokeWidth = radius * 0.05f
                paint.color = if (active) Color.argb((255 * expandProgress).toInt().coerceIn(0, 255), 255, 255, 255) else Color.argb(coreAlpha.toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)
            } else {
                paint.strokeWidth = radius * 0.15f
                paint.color = Color.argb((expandedAlphaBase * 0.3f).toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)
                
                paint.strokeWidth = radius * 0.05f
                paint.color = if (active) Color.argb((255 * expandProgress).toInt().coerceIn(0, 255), 255, 255, 255) else Color.argb(coreAlpha.toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawPath(iconPath, paint)

                paint.style = Paint.Style.FILL
                paint.textSize = if (button.rect.width() < dp(70)) {
                    11f * resources.displayMetrics.scaledDensity
                } else {
                    16f * resources.displayMetrics.scaledDensity
                }
                
                paint.color = if (active) Color.argb((255 * expandProgress).toInt().coerceIn(0, 255), 255, 255, 255) else Color.argb(textAlpha.toInt().coerceIn(0, 255), Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))
                canvas.drawText(button.label, cx, cy + paint.textSize * 0.36f, paint)
            }
        }
    }

    private fun sliderLedColor(cell: Int): Int {
        val base = cell * 3
        if (base + 2 >= sliderLEDs.size) return Color.TRANSPARENT
        return Color.rgb(
            sliderLEDs[base].coerceIn(0, 255),
            sliderLEDs[base + 1].coerceIn(0, 255),
            sliderLEDs[base + 2].coerceIn(0, 255)
        )
    }

    private fun buttonLedIntensity(buttonIndex: Int): Int {
        val ledIndex = when (buttonIndex) {
            0 -> 6
            1 -> 7
            2 -> 8
            3 -> 9
            else -> -1
        }
        return if (ledIndex in buttonLEDs.indices) buttonLEDs[ledIndex].coerceIn(0, 255) else 0
    }

    private fun blend(base: Int, lit: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return ((base * (255 - a) + lit.coerceIn(0, 255) * a) / 255).coerceIn(0, 255)
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density
}

private object Palette {
    val ink: Int = Color.rgb(5, 10, 18)
    val panel: Int = Color.rgb(15, 23, 42)
    val stroke: Int = Color.rgb(43, 65, 84)
    val text: Int = Color.rgb(236, 254, 255)
    val muted: Int = Color.rgb(148, 163, 184)
    val accent: Int = Color.rgb(94, 234, 212)
    val hot: Int = Color.rgb(245, 158, 11)
    val live: Int = Color.rgb(17, 94, 89)
}

private const val BUTTON_CIRCLE = 0x01
private const val BUTTON_CROSS = 0x02
private const val BUTTON_SQUARE = 0x04
private const val BUTTON_TRIANGLE = 0x08
private const val BUTTON_START = 0x10
private const val BUTTON_COIN = 0x80
