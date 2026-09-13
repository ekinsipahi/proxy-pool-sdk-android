package com.proxypool.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the tunnel alive for exactly as long as it is allowed to be.
 *
 * A foreground service, and not reluctantly: Android will freeze a background
 * process holding a socket, and — more to the point — a persistent notification
 * is the honest way to run this. A user sharing bandwidth can see it happening
 * and reach the off switch by tapping it. An SDK that hid this would deserve
 * every bit of the regulatory attention it got.
 *
 * The service owns the *policy* (consent plus [NetworkGate]); [TunnelClient]
 * owns the socket. When the gate closes mid-session — the user unplugs the
 * charger, walks off Wi-Fi, turns the screen on — the tunnel stops within
 * seconds and the notification says why.
 */
class TunnelService : Service() {

    private var client: TunnelClient? = null
    private var gate: NetworkGate? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText: String = ""

    override fun onCreate() {
        super.onCreate()
        // Android gives a foreground service ~5 seconds to post its
        // notification; do it before anything that could be slow.
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = ProxyPoolSdk.config
        if (config == null) {
            // init() was never called — most likely the system restarted the
            // service after the process died. Nothing can run without a key.
            stopSelf()
            return START_NOT_STICKY
        }

        if (client == null) {
            client = TunnelClient(
                config = config,
                identity = Identity(this),
                appId = packageName,
                onState = { state ->
                    ProxyPoolSdk.publish(state)
                    updateNotification(describe(state))
                },
            )
        }
        if (gate == null) {
            gate = NetworkGate(this, config) { evaluate() }.also { it.start() }
        }
        acquireWakeLock(config)
        evaluate()
        // START_STICKY: a box that reboots or an OS that reclaims memory should
        // bring sharing back on its own, or the network loses its best nodes to
        // nothing more than a low-memory morning.
        return START_STICKY
    }

    override fun onDestroy() {
        gate?.stop()
        gate = null
        client?.shutdown()
        client = null
        releaseWakeLock()
        ProxyPoolSdk.publish(SdkState(SdkState.Status.IDLE))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Consent and the gate decide, together, whether the tunnel may run. */
    private fun evaluate() {
        val client = this.client ?: return
        val gate = this.gate ?: return
        if (!ProxyPoolSdk.hasConsent(this)) {
            client.stop()
            ProxyPoolSdk.publish(SdkState(SdkState.Status.NEEDS_CONSENT, "Consent required"))
            updateNotification("Not sharing — consent required")
            stopSelf()
            return
        }
        val blocked = gate.reason()
        if (blocked.isEmpty()) {
            client.start()
        } else {
            client.stop()
            ProxyPoolSdk.publish(SdkState(SdkState.Status.WAITING, blocked))
            updateNotification(blocked)
        }
    }

    private fun describe(state: SdkState): String = when (state.status) {
        SdkState.Status.CONNECTED ->
            "Sharing • ${formatBytes(state.totalBytes)} • ${state.activeStreams} active"
        SdkState.Status.CONNECTING -> "Connecting…"
        SdkState.Status.RECONNECTING -> "Reconnecting…"
        SdkState.Status.REFUSED -> "Stopped — ${state.detail}"
        SdkState.Status.WAITING -> state.detail
        SdkState.Status.NEEDS_CONSENT -> "Not sharing — consent required"
        SdkState.Status.IDLE -> "Not sharing"
    }

    /**
     * A partial wake lock, and only where it is defensible.
     *
     * On a mains-powered box ([SdkConfig.forAndroidBox] turns the battery rules
     * off) letting the CPU sleep mid-relay drops customer connections. On a
     * battery device it is the user's battery, so the tunnel takes its chances
     * with Doze instead.
     */
    private fun acquireWakeLock(config: SdkConfig) {
        if (config.minBatteryPercent > 0 || config.requireCharging) return
        if (wakeLock != null) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ppsdk:tunnel").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    // ------------------------------------------------------------------ //
    // notification
    // ------------------------------------------------------------------ //
    private fun updateNotification(text: String) {
        if (text == lastText) return   // notifying on every byte is a battery bug
        lastText = text
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Bandwidth sharing", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while this device is sharing idle bandwidth" }
            )
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // Tapping the notification opens the host app, which is where the user
        // turns sharing off. An opt-out the user cannot find is not an opt-out.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, flags))
        }
        return builder
            .setContentTitle(ProxyPoolSdk.notificationTitle)
            .setContentText(text)
            .setSmallIcon(ProxyPoolSdk.notificationIcon)
            .setOngoing(true)
            .build()
    }

    internal companion object {
        private const val CHANNEL = "proxypool_sdk"
        private const val NOTIFICATION_ID = 4711

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
