package com.fridayssheep.divaslider.settings

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fridayssheep.divaslider.ui.AnimatedFillButton
import com.fridayssheep.divaslider.ui.Palette
import kotlin.math.min

internal data class ConnectionSettings(
    val host: String,
    val port: Int,
    val mode: SenderMode
)

internal class SettingsPanel(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val onClose: () -> Unit,
    private val onConnectAndPlay: (ConnectionSettings) -> Unit
) {
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var udpButton: Button
    private lateinit var wsButton: Button
    private lateinit var statusLabel: TextView
    private var mode = loadMode()
    val view: LinearLayout = buildView()

    fun isVisible(): Boolean = view.visibility == View.VISIBLE

    fun currentSettings(): ConnectionSettings {
        mode = loadMode()
        return ConnectionSettings(
            host = prefs.getString(KEY_HOST, "")?.trim().orEmpty(),
            port = loadPort(),
            mode = mode
        )
    }

    fun show() {
        hostEdit.setText(prefs.getString(KEY_HOST, ""))
        mode = loadMode()
        portEdit.setText(loadPort().toString())
        portEdit.hint = defaultPort(mode).toString()
        updateModeButtons()

        val params = view.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(dp(360), dp(300), Gravity.TOP or Gravity.END)
        params.width = min(dp(360), (activity.resources.displayMetrics.widthPixels * 0.72f).toInt())
        params.height = min(dp(300), (activity.resources.displayMetrics.heightPixels * 0.78f).toInt())
        view.layoutParams = params
        view.pivotX = params.width.toFloat()
        view.pivotY = 0f
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.scaleX = 0.96f
        view.scaleY = 0.96f
        view.bringToFront()
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(160)
            .start()
    }

    fun hide() {
        if (!isVisible()) return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(130)
            .withEndAction {
                view.visibility = View.GONE
            }
            .start()
    }

    fun updateStatus(text: String) {
        if (::statusLabel.isInitialized) {
            statusLabel.text = text
        }
    }

    private fun buildView(): LinearLayout {
        hostEdit = EditText(activity).apply {
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
        portEdit = EditText(activity).apply {
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
        statusLabel = smallLabel("Ready")

        val title = TextView(activity).apply {
            text = "Settings"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.text)
        }
        val close = quietButton("Close").apply {
            setOnClickListener { onClose() }
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(close, LinearLayout.LayoutParams(dp(82), dp(42)))
        }

        val transportRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(udpButton, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(wsButton, LinearLayout.LayoutParams(0, dp(52), 1f).withMarginLeft(dp(10)))
        }
        val endpointLabels = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("Host"), LinearLayout.LayoutParams(0, dp(28), 1f))
            addView(label("Port"), LinearLayout.LayoutParams(dp(104), dp(28)).withMarginLeft(dp(10)))
        }
        val endpointRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hostEdit, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(portEdit, LinearLayout.LayoutParams(dp(104), dp(50)).withMarginLeft(dp(10)))
        }

        val save = quietButton("Save").apply {
            setOnClickListener {
                saveSettings()
                onClose()
            }
        }
        val connectAndPlay = filledButton("Connect and Play").apply {
            setOnClickListener {
                val settings = saveSettings()
                onConnectAndPlay(settings)
                onClose()
            }
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(save, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(connectAndPlay, LinearLayout.LayoutParams(0, dp(52), 1.35f).withMarginLeft(dp(10)))
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

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(header, LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
            addView(endpointLabels, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).withTopMargin(dp(4)))
            addView(endpointRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            addView(label("Transport"), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).withTopMargin(dp(8)))
            addView(transportRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            addView(statusLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).withTopMargin(dp(6)))
            addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).withTopMargin(dp(6)))
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(242, 10, 16, 28), dp(22), Color.argb(150, 220, 252, 248), 1)
            visibility = View.GONE
            alpha = 0f
            scaleX = 0.96f
            scaleY = 0.96f
            addView(scroll, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
    }

    private fun saveSettings(): ConnectionSettings {
        val host = hostEdit.text.toString().trim()
        val port = portFromText(portEdit.text.toString(), defaultPort(mode))
        portEdit.setText(port.toString())
        prefs.edit()
            .putString(KEY_HOST, host)
            .putString(KEY_MODE, mode.name)
            .putInt(KEY_PORT, port)
            .apply()
        statusLabel.text = if (host.isBlank()) "Host is empty" else "Saved ${mode.label} $host:$port"
        return ConnectionSettings(host, port, mode)
    }

    private fun updateModeButtons() {
        udpButton.isSelected = mode == SenderMode.UDP
        wsButton.isSelected = mode == SenderMode.WS
        portEdit.hint = defaultPort(mode).toString()
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

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Palette.muted)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun smallLabel(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTextColor(Palette.muted)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun filledButton(text: String) = AnimatedFillButton(activity).apply {
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

    private fun quietButton(text: String) = AnimatedFillButton(activity).apply {
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

    private fun modeButton(text: String) = AnimatedFillButton(activity).apply {
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

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_MODE = "mode"
        const val KEY_PORT = "port"
    }
}
