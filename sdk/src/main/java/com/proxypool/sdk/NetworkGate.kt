package com.proxypool.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Decides, continuously, whether this device may share right now.
 *
 * The SDK does not merely check the rules once at start: a phone moves from
 * Wi-Fi to cellular mid-download, and a tunnel that keeps running through that
 * transition spends the user's data allowance on someone else's traffic. So the
 * gate watches the network, the charger and the screen, and calls back the
 * moment the answer changes — the service then connects or disconnects.
 *
 * [reason] is written to be shown to a user, not only logged, because "paused —
 * you are on mobile data" is the difference between a user who trusts the
 * feature and one who uninstalls.
 */
internal class NetworkGate(
    private val context: Context,
    private val config: SdkConfig,
    private val onChanged: () -> Unit,
) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null

    /** Empty when sharing is allowed; otherwise why it is not. */
    fun reason(): String {
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            ?: return "No network"
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "No internet"
        }
        // VALIDATED means the OS actually reached the internet through it. A
        // captive-portal Wi-Fi is "connected" and routes nothing, and without
        // this check the SDK would reconnect in a tight loop on hotel Wi-Fi.
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return "Network not usable yet"
        }
        if (config.wifiOnly &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        ) {
            return "Paused on mobile data"
        }
        if (config.requireCharging && !isCharging()) {
            return "Paused until charging"
        }
        if (config.minBatteryPercent > 0 && batteryPercent() in 0 until config.minBatteryPercent) {
            return "Paused on low battery"
        }
        if (config.onlyWhenScreenOff && power.isInteractive) {
            return "Paused while the screen is on"
        }
        return ""
    }

    fun allowed(): Boolean = reason().isEmpty()

    fun start() {
        if (networkCallback == null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChanged()
                override fun onLost(network: Network) = onChanged()
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = onChanged()
            }
            networkCallback = callback
            // registerDefaultNetworkCallback follows whichever network the
            // system is actually using, which is the one our sockets ride.
            runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        }
        if (receiver == null) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                // Screen state is only delivered to a registered receiver,
                // never to a manifest one, so it has to be wired here.
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            val broadcast = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = onChanged()
            }
            receiver = broadcast
            runCatching { context.registerReceiver(broadcast, filter) }
        }
    }

    fun stop() {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun isCharging(): Boolean {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return true
        return manager.isCharging
    }

    /** Battery percentage, or -1 when the device has no battery (a TV box). */
    private fun batteryPercent(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else -1
    }
}
