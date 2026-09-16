package com.proxypool.sdk

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The Proxy Pool bandwidth SDK.
 *
 * A partner app embeds this to let *consenting* users contribute idle bandwidth
 * as a residential or mobile exit node, and is paid per active device per day.
 *
 * ```kotlin
 * ProxyPoolSdk.init(context, SdkConfig(apiKey = BuildConfig.PROXYPOOL_KEY,
 *                                      coordinatorUrl = "wss://coordinator.example.com"))
 * ProxyPoolSdk.onState = { state -> render(state) }
 *
 * // ...after the user has actually read a disclosure and agreed:
 * ProxyPoolSdk.setConsent(context, true)
 * ProxyPoolSdk.start(context)
 * ```
 *
 * ### The one rule
 *
 * Nothing runs without [setConsent]`(context, true)`, and [setConsent]`(…, false)`
 * stops it instantly and permanently until the user says otherwise. This is not
 * defensive coding, it is the product: every bandwidth network that has been
 * sued was sued over disclosure, not over the tunnel. Your integration must show
 * the user, in plain language, that their connection will carry other people's
 * web traffic, what they get for it, and how to stop — before calling this.
 */
object ProxyPoolSdk {

    const val VERSION = "1.0.0"

    private const val KEY_CONSENT = "consent"
    private const val KEY_CONSENT_AT = "consent_at"

    @Volatile
    internal var config: SdkConfig? = null
        private set

    /** Title of the ongoing notification. Set before [start] to match your app.
     *  Kept intentionally generic so it reads as a neutral background task. */
    @Volatile
    var notificationTitle: String = "Web data processing"

    /** Small icon for the ongoing notification. */
    @Volatile
    var notificationIcon: Int = android.R.drawable.stat_sys_upload

    /** The latest known state. Also delivered to [onState]. */
    @Volatile
    var state: SdkState = SdkState()
        private set

    /**
     * Called whenever [state] changes, always on the main thread, so it can
     * update UI directly.
     */
    @Volatile
    var onState: ((SdkState) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    /** Configure the SDK. Safe to call again to change settings. */
    @JvmStatic
    fun init(context: Context, config: SdkConfig) {
        this.config = config
    }

    @JvmStatic
    fun init(context: Context, apiKey: String, coordinatorUrl: String? = null) =
        init(
            context,
            if (coordinatorUrl.isNullOrBlank()) SdkConfig(apiKey)
            else SdkConfig(apiKey, coordinatorUrl)
        )

    /**
     * The id the coordinator assigned this device, or "" before the first
     * successful connection.
     *
     * Pseudonymous, and issued by the server — see [Identity] for why the
     * device is not allowed to pick it.
     */
    @JvmStatic
    fun deviceId(context: Context): String = Identity(context).deviceId

    // ------------------------------------------------------------------ //
    // consent
    // ------------------------------------------------------------------ //
    @JvmStatic
    fun hasConsent(context: Context): Boolean =
        Prefs.of(context).getBoolean(KEY_CONSENT, false)

    /** When the user opted in (epoch millis), or 0. Keep it for your audit trail. */
    @JvmStatic
    fun consentGrantedAt(context: Context): Long =
        Prefs.of(context).getLong(KEY_CONSENT_AT, 0L)

    /**
     * Record the user's decision.
     *
     * Withdrawing consent tears the tunnel down immediately — not at the next
     * app launch, not when the current transfer finishes.
     */
    @JvmStatic
    fun setConsent(context: Context, granted: Boolean) {
        Prefs.of(context).edit()
            .putBoolean(KEY_CONSENT, granted)
            .putLong(KEY_CONSENT_AT, if (granted) System.currentTimeMillis() else 0L)
            .apply()
        if (granted) {
            publish(SdkState(SdkState.Status.IDLE))
        } else {
            stop(context)
            // Withdrawing consent forgets the network identity too. Keeping it
            // would mean a user who opted out still has a row on our side
            // waiting to be reattached; "stop" should mean stop.
            Identity(context).clear()
            publish(SdkState(SdkState.Status.NEEDS_CONSENT, "Consent withdrawn"))
        }
    }

    // ------------------------------------------------------------------ //
    // lifecycle
    // ------------------------------------------------------------------ //
    /** Start sharing, if the user has consented and conditions allow. */
    @JvmStatic
    fun start(context: Context) {
        val app = context.applicationContext
        if (config == null) {
            publish(SdkState(SdkState.Status.IDLE, "init() has not been called"))
            return
        }
        if (!hasConsent(app)) {
            publish(SdkState(SdkState.Status.NEEDS_CONSENT, "Consent required"))
            return
        }
        val intent = Intent(app, TunnelService::class.java)
        // startForegroundService is required from Oreo on; the service then has
        // ~5s to post its notification, which TunnelService does in onCreate.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    /** Stop sharing now. */
    @JvmStatic
    fun stop(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, TunnelService::class.java))
        publish(SdkState(SdkState.Status.IDLE))
    }

    /** True while the tunnel is live and able to carry traffic. */
    @JvmStatic
    val isSharing: Boolean
        get() = state.status == SdkState.Status.CONNECTED

    internal fun publish(next: SdkState) {
        state = next
        val listener = onState ?: return
        main.post { listener(next) }
    }
}
