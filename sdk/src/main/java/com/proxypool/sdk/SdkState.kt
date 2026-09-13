package com.proxypool.sdk

/**
 * What the SDK is doing, and how much it has carried.
 *
 * Deliberately a plain value type a partner can render directly: an app that
 * tells its user "sharing paused — you are on mobile data, 42 MB shared this
 * session" keeps the consent honest, and honest consent is the only thing
 * standing between this business and the lawsuits that ended Hola's.
 */
data class SdkState(
    val status: Status = Status.IDLE,
    /** Why the tunnel is not running, in words a user can read. */
    val detail: String = "",
    /** Bytes sent from this device toward destinations, this process. */
    val bytesUp: Long = 0,
    /** Bytes received from destinations and relayed on, this process. */
    val bytesDown: Long = 0,
    /** Customer connections currently open through this device. */
    val activeStreams: Int = 0,
    /** Customer connections served since the process started. */
    val servedStreams: Long = 0,
    /** Exit country the coordinator resolved for this device, or "" if unknown. */
    val country: String = "",
) {
    val totalBytes: Long get() = bytesUp + bytesDown

    enum class Status {
        /** Never started, or stopped. */
        IDLE,

        /** The user has not opted in; nothing will run. */
        NEEDS_CONSENT,

        /** Consent given, but a rule in [SdkConfig] says not now (metered, screen on, ...). */
        WAITING,

        /** Dialling the coordinator. */
        CONNECTING,

        /** Live: registered with the pool and able to carry traffic. */
        CONNECTED,

        /** Lost the tunnel, backing off before another attempt. */
        RECONNECTING,

        /** The coordinator refused this device; [detail] says why. Not retried blindly. */
        REFUSED,
    }
}
