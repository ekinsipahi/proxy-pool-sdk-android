package com.proxypool.sdk

import android.content.Context

/**
 * This device's identity on the network — **issued by the coordinator, not by
 * this SDK.**
 *
 * The device does not choose, or even claim, who it is. On the first connection
 * it presents nothing; the coordinator assigns an id and returns it together
 * with a signed token. From then on the SDK presents only that token, and the
 * coordinator reads the id out of it.
 *
 * The alternative — letting each install generate its own id — looks equivalent
 * and is not. Earnings are attributed to an id, so a self-declared id is a
 * self-declared claim on someone else's earnings, and nothing on the device can
 * be trusted to make it honestly. Identity therefore lives where the money is
 * counted.
 *
 * Nothing here identifies the *person*: no IMEI, no ANDROID_ID, no advertising
 * id. Clearing the app's data forgets the token, and the device rejoins as a new
 * one — which is the correct answer to a user who said "forget me".
 */
internal class Identity(context: Context) {

    private val prefs = Prefs.of(context)

    /** The signed token to present on the next connect, or "" when unknown. */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        private set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    /** The coordinator-assigned id, or "" before the first successful connect. */
    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        private set(value) {
            prefs.edit().putString(KEY_DEVICE_ID, value).apply()
        }

    /** Store what the coordinator just told us we are. */
    fun accept(deviceId: String, token: String) {
        if (deviceId.isNotBlank()) this.deviceId = deviceId
        if (token.isNotBlank()) this.token = token
    }

    /** Forget this identity — used when the user withdraws consent. */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_DEVICE_ID).apply()
    }

    private companion object {
        const val KEY_TOKEN = "device_token"
        const val KEY_DEVICE_ID = "device_id"
    }
}

/** One place that names the SDK's preference file, so nothing else guesses it. */
internal object Prefs {
    private const val NAME = "proxypool_sdk"

    fun of(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
