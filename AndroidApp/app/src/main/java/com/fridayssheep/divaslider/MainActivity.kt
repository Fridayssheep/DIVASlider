package com.fridayssheep.divaslider

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.fridayssheep.divaslider.input.DivaInputState
import com.fridayssheep.divaslider.network.InputSender
import com.fridayssheep.divaslider.network.LedPoller
import com.fridayssheep.divaslider.network.UdpSender
import com.fridayssheep.divaslider.network.WebSocketSender
import com.fridayssheep.divaslider.settings.ConnectionSettings
import com.fridayssheep.divaslider.settings.SenderMode
import com.fridayssheep.divaslider.settings.SettingsPanel
import com.fridayssheep.divaslider.ui.AnimatedFillButton
import com.fridayssheep.divaslider.ui.DivaControlView
import com.fridayssheep.divaslider.ui.Palette

class MainActivity : Activity() {
    private val input = DivaInputState()
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var controllerPage: FrameLayout
    private lateinit var settingsPanel: SettingsPanel
    private lateinit var controlView: DivaControlView
    private lateinit var statusChip: TextView
    private lateinit var connectButton: Button
    private var ledPoller: LedPoller? = null
    private var sender: InputSender? = null
    private var connected = false
    private var connectButtonActiveStyle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("diva_slider", Context.MODE_PRIVATE)

        root = FrameLayout(this)
        controllerPage = buildControllerPage()
        settingsPanel = SettingsPanel(
            activity = this,
            prefs = prefs,
            onClose = { showController() },
            onConnectAndPlay = { settings -> startSender(settings) }
        )
        root.addView(controllerPage, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(settingsPanel.view, FrameLayout.LayoutParams(dp(360), dp(300), Gravity.TOP or Gravity.END).apply {
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
        if (::settingsPanel.isInitialized && settingsPanel.isVisible()) {
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

    private fun startSenderFromPrefs() {
        startSender(settingsPanel.currentSettings())
    }

    private fun startSender(settings: ConnectionSettings) {
        stopSender()
        hideKeyboard()
        if (settings.host.isBlank()) {
            setStatus("Missing host", false)
            showSettings()
            return
        }
        ledPoller?.stop()
        val nextSender: InputSender = when (settings.mode) {
            SenderMode.UDP -> UdpSender(settings.host, settings.port, input)
            SenderMode.WS -> WebSocketSender(settings.host, settings.port, input)
        }
        sender = nextSender
        ledPoller = LedPoller(settings.host, controlView).also { it.start() }
        connected = true
        setStatus("Connecting ${settings.mode.label} ${settings.host}:${settings.port}", true)
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

    private fun showSettings() {
        settingsPanel.show()
    }

    private fun showController() {
        hideKeyboard()
        hideSystemBars()
        settingsPanel.hide()
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
        if (::settingsPanel.isInitialized) {
            settingsPanel.updateStatus(text)
        }
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

    private fun chip(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Palette.text)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        background = rounded(Palette.panel, dp(18), Palette.stroke, 1)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
