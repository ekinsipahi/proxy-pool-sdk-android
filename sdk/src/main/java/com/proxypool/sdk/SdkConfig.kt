package com.proxypool.sdk

/**
 * How a partner app wants the SDK to behave.
 *
 * The defaults are the conservative ones: share only on unmetered Wi-Fi, only
 * while the screen is off, and never on a low battery. A partner that knows its
 * device is a mains-powered TV box ([forAndroidBox]) opts out of the battery and
 * idle rules explicitly, rather than the SDK guessing and quietly draining a
 * phone.
 *
 * Getting this wrong is not a performance bug, it is an uninstall: a user who
 * notices their data allowance gone or their phone hot has been harmed, and they
 * are right to be angry.
 */
data class SdkConfig(
    /** The partner key issued by the dashboard. Identifies who gets paid. */
    val apiKey: String,

    /**
     * Coordinator WebSocket URL. Defaults to the production tunnel; override it
     * only to point at your own coordinator or, during local development, at
     * `ws://10.0.2.2:8765` (the host machine as seen from the Android emulator).
     * Production is always `wss://`.
     */
    val coordinatorUrl: String = "wss://node.ipsterr.com",

    /**
     * Share only on an unmetered network (Wi-Fi/Ethernet).
     *
     * On by default because the alternative spends the user's mobile data. A
     * partner that has genuinely told its users otherwise — and pays for it,
     * since cellular devices are the premium tier — can turn it off.
     */
    val wifiOnly: Boolean = true,

    /** Share only while charging. Irrelevant on a mains-powered box. */
    val requireCharging: Boolean = false,

    /** Stop below this battery percentage. 0 disables the check. */
    val minBatteryPercent: Int = 20,

    /**
     * Share only while the screen is off.
     *
     * "Idle bandwidth" is the promise the user agreed to; competing with the
     * app the user is actually looking at is not idle bandwidth.
     */
    val onlyWhenScreenOff: Boolean = true,

    /** Concurrent customer connections this device will carry. */
    val maxStreams: Int = 8,

    /** Log tunnel activity to logcat. Leave off in a release build. */
    val debugLogging: Boolean = false,
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey is required" }
        require(maxStreams in 1..64) { "maxStreams must be 1..64" }
    }

    companion object {
        /**
         * Sensible settings for an always-on, mains-powered Android TV box or
         * signage player: no battery or screen rules, more concurrency.
         *
         * These devices are the best inventory in the network — a stable
         * residential IP, online 24/7, nothing competing for the uplink — which
         * is why they are worth configuring properly.
         */
        fun forAndroidBox(apiKey: String, coordinatorUrl: String): SdkConfig = SdkConfig(
            apiKey = apiKey,
            coordinatorUrl = coordinatorUrl,
            wifiOnly = true,
            requireCharging = false,
            minBatteryPercent = 0,
            onlyWhenScreenOff = false,
            maxStreams = 24,
        )
    }
}
