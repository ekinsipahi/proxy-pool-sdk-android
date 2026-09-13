package com.proxypool.sample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.proxypool.sdk.ProxyPoolSdk
import com.proxypool.sdk.SdkConfig
import com.proxypool.sdk.SdkState

/**
 * The reference integration: a consent screen and a live status readout.
 *
 * It is intentionally plain and intentionally wordy. The disclosure text is the
 * part a partner must not trim — a user who later finds out their connection was
 * carrying strangers' web traffic, and cannot remember being told, is a support
 * ticket at best and a regulator at worst.
 *
 * Everything here is built in code so the module needs no resource files, and it
 * is navigable with a TV remote (every control is focusable, in order), because
 * the boxes are where this SDK earns most.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var toggle: Button
    private lateinit var deviceLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ws://10.0.2.2:8765 is the host machine as seen from the emulator, and
        // is the SdkConfig default. On a real box point this at your server.
        ProxyPoolSdk.init(
            this,
            SdkConfig(
                apiKey = "DEV-TEST-KEY",
                coordinatorUrl = "ws://10.0.2.2:8765",
                // Relaxed so the demo actually runs while you watch it; the
                // library defaults (Wi-Fi only, screen off) are the ones a real
                // phone app should ship.
                wifiOnly = false,
                onlyWhenScreenOff = false,
                minBatteryPercent = 0,
                debugLogging = true,
            ),
        )
        ProxyPoolSdk.notificationTitle = "ProxyPool demo"
        ProxyPoolSdk.onState = ::render

        setContentView(buildUi())
        render(ProxyPoolSdk.state)
        requestNotificationPermission()
    }

    override fun onDestroy() {
        ProxyPoolSdk.onState = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //
    // ui
    // ------------------------------------------------------------------ //
    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(40), dp(32), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "Share idle bandwidth"
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text = """
                With your permission this device will carry other people's web
                requests while it is idle, acting as an exit point on the Proxy
                Pool network. Your app keeps working normally.

                • Only web traffic (ports 80 and 443), never your own data
                • Nothing you type, store or browse is read or sent
                • You can turn this off at any time, and it stops immediately
            """.trimIndent()
            textSize = 15f
            setPadding(0, dp(16), 0, dp(24))
        })

        toggle = focusable(Button(this).apply {
            setOnClickListener { onToggle() }
        })
        root.addView(toggle)

        root.addView(focusable(Button(this).apply {
            text = "Withdraw consent"
            setOnClickListener {
                ProxyPoolSdk.setConsent(this@MainActivity, false)
                render(ProxyPoolSdk.state)
            }
        }))

        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, dp(28), 0, dp(4))
        }
        detail = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
        }
        root.addView(status)
        root.addView(detail)

        deviceLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(deviceLabel)

        return ScrollView(this).apply {
            addView(root)
            isFillViewport = true
        }
    }

    private fun onToggle() {
        if (isRunning(ProxyPoolSdk.state)) {
            ProxyPoolSdk.stop(this)
        } else {
            // In a real app this is where the user has just read the text above
            // and pressed an affirmative button — exactly this order.
            ProxyPoolSdk.setConsent(this, true)
            ProxyPoolSdk.start(this)
        }
        render(ProxyPoolSdk.state)
    }

    /**
     * Consent and running are different things: a user can have agreed while
     * the tunnel sits paused on mobile data. The button follows what is
     * actually happening, or it offers to stop something that is not running.
     */
    private fun isRunning(state: SdkState) = when (state.status) {
        SdkState.Status.CONNECTED, SdkState.Status.CONNECTING,
        SdkState.Status.RECONNECTING, SdkState.Status.WAITING -> true
        else -> false
    }

    private fun render(state: SdkState) {
        toggle.text = if (isRunning(state)) "Stop sharing" else "I agree - start sharing"
        status.text = when (state.status) {
            SdkState.Status.CONNECTED -> "Connected${countrySuffix(state)}"
            SdkState.Status.CONNECTING -> "Connecting…"
            SdkState.Status.RECONNECTING -> "Reconnecting…"
            SdkState.Status.WAITING -> "Waiting"
            SdkState.Status.REFUSED -> "Refused"
            SdkState.Status.NEEDS_CONSENT -> "Consent required"
            SdkState.Status.IDLE -> "Not sharing"
        }
        status.setTextColor(
            if (state.status == SdkState.Status.CONNECTED) Color.parseColor("#2E7D32")
            else Color.DKGRAY
        )
        val traffic = "${bytes(state.bytesDown)} in / ${bytes(state.bytesUp)} out • " +
            "${state.activeStreams} active, ${state.servedStreams} served"
        detail.text = listOf(state.detail, traffic).filter { it.isNotBlank() }.joinToString("\n")
        // Empty until the coordinator has assigned one — the device does not
        // get to name itself, so before the first connect there is no id.
        val id = ProxyPoolSdk.deviceId(this)
        deviceLabel.text = if (id.isBlank()) "device: not registered yet" else "device: $id"
    }

    private fun countrySuffix(state: SdkState) =
        if (state.country.isBlank() || state.country == "ZZ") "" else " • ${state.country}"

    private fun bytes(value: Long): String = when {
        value >= 1_000_000_000 -> "%.1f GB".format(value / 1_000_000_000.0)
        value >= 1_000_000 -> "%.1f MB".format(value / 1_000_000.0)
        value >= 1_000 -> "%.0f KB".format(value / 1_000.0)
        else -> "$value B"
    }

    private fun <T : android.view.View> focusable(view: T): T = view.apply {
        isFocusable = true
        isFocusableInTouchMode = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        if (this is TextView) gravity = Gravity.CENTER
    }

    private fun requestNotificationPermission() {
        // From Android 13 a foreground service can run without it, but its
        // notification is silently hidden — and an invisible "we are sharing
        // your bandwidth" notice defeats the point of having one.
        if (Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
