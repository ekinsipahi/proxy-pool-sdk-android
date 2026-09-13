package com.proxypool.sdk

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The device end of the reverse tunnel.
 *
 * A device behind carrier NAT cannot be dialled, so it dials *out* to the
 * coordinator and holds one WebSocket open. Every customer connection that
 * exits through this device is multiplexed over that single socket:
 *
 * ```
 * binary frame = [1B op][4B streamId big-endian][payload]
 *   1 OPEN     coordinator -> device   payload = "host:port"
 *   2 DATA     both ways               payload = raw bytes
 *   3 CLOSE    both ways
 *   4 OPEN_OK  device -> coordinator   the TCP connect succeeded
 *   5 OPEN_ERR device -> coordinator   it did not; payload = reason
 * ```
 *
 * Two things in here are not obvious and are the difference between a demo and
 * something you can ship:
 *
 * **Backpressure.** A phone's uplink is a fraction of what a customer pulls
 * through it. Without a brake, a 100 MB download would queue in OkHttp's send
 * buffer until the app is killed for memory. So a stream's reader parks itself
 * whenever the WebSocket's outbound queue is already large, which stops it
 * reading the destination socket, which TCP propagates back to the destination.
 *
 * **Ordering.** Inbound DATA frames are written to their socket on the
 * WebSocket's own reader thread. Handing them to a pool would reorder bytes
 * within a stream and corrupt the customer's data — a class of bug that shows
 * up as "TLS handshake failed sometimes" and takes days to find.
 */
internal class TunnelClient(
    private val config: SdkConfig,
    private val identity: Identity,
    private val appId: String,
    private val onState: (SdkState) -> Unit,
) {

    private val client = OkHttpClient.Builder()
        // The coordinator pings; answering keeps a NAT mapping alive and lets
        // both sides notice a dead cellular link in seconds instead of minutes.
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val sockets = ConcurrentHashMap<Int, Socket>()
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ppsdk-stream").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ppsdk-sched").apply { isDaemon = true }
    }

    private val bytesUp = AtomicLong()
    private val bytesDown = AtomicLong()
    private val served = AtomicLong()

    @Volatile private var running = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var country: String = ""
    @Volatile private var state = SdkState()
    private var backoffMs = 1_000L

    fun start() {
        if (running) return
        running = true
        connect()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { ws?.send(JSONObject().put("op", "bye").toString()) }
        runCatching { ws?.close(1000, "stopped") }
        ws = null
        closeAllStreams()
        publish(SdkState.Status.IDLE, "")
    }

    fun shutdown() {
        stop()
        scheduler.shutdownNow()
        pool.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    fun snapshot(): SdkState = state

    // ------------------------------------------------------------------ //
    // connection
    // ------------------------------------------------------------------ //
    private fun connect() {
        if (!running) return
        publish(SdkState.Status.CONNECTING, "")
        val request = Request.Builder().url(config.coordinatorUrl).build()
        ws = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect(why: String) {
        if (!running) return
        // Jitter matters at scale: without it, every device in a country
        // reconnects in lockstep after a coordinator restart and the stampede
        // knocks it over again.
        val delay = backoffMs + Random.nextLong(0, backoffMs / 2 + 1)
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        publish(SdkState.Status.RECONNECTING, why)
        scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = JSONObject()
                .put("op", "hello")
                .put("v", PROTOCOL_VERSION)
                .put("apiKey", config.apiKey)
                // Identity is the coordinator's to assign: we present the token
                // it gave us last time and nothing else. An empty token means
                // "I am new", and we are told who we are in the welcome.
                .put("token", identity.token)
                .put("platform", "android")
                .put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                .put("appId", appId)
                .put("sdk", ProxyPoolSdk.VERSION)
                .put("maxStreams", config.maxStreams)
                // The service never starts the client without consent; sending
                // it explicitly means the coordinator refuses a tampered build
                // rather than trusting that the check happened.
                .put("consent", true)
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("op")) {
                "welcome" -> {
                    backoffMs = 1_000L
                    country = message.optString("country", "")
                    identity.accept(
                        message.optString("deviceId", ""),
                        message.optString("token", ""),
                    )
                    scheduleStats()
                    publish(SdkState.Status.CONNECTED, "")
                    log("connected as ${identity.deviceId} ($country)")
                }
                "error" -> {
                    // A refusal is a decision, not a blip: reconnecting in a
                    // loop against a bad key just burns the user's battery.
                    val detail = message.optString("message", message.optString("code"))
                    running = false
                    publish(SdkState.Status.REFUSED, detail)
                    log("refused: $detail")
                    runCatching { webSocket.close(1000, "refused") }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            if (data.size < HEADER) return
            val op = data[0].toInt()
            val id = ByteBuffer.wrap(data, 1, 4).int
            when (op) {
                OP_OPEN -> openStream(webSocket, id, String(data, HEADER, data.size - HEADER))
                OP_DATA -> {
                    val socket = sockets[id] ?: return
                    try {
                        socket.getOutputStream().apply {
                            write(data, HEADER, data.size - HEADER)
                            flush()
                        }
                        bytesUp.addAndGet((data.size - HEADER).toLong())
                    } catch (_: Exception) {
                        closeStream(id)
                        send(webSocket, OP_CLOSE, id)
                    }
                }
                OP_CLOSE -> closeStream(id)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closeAllStreams()
            scheduleReconnect(t.message ?: t.javaClass.simpleName)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeAllStreams()
            if (running) scheduleReconnect(reason.ifEmpty { "closed" })
        }
    }

    // ------------------------------------------------------------------ //
    // streams
    // ------------------------------------------------------------------ //
    private fun openStream(webSocket: WebSocket, id: Int, target: String) {
        if (sockets.size >= config.maxStreams) {
            send(webSocket, OP_OPEN_ERR, id, "device at stream limit".toByteArray())
            return
        }
        pool.execute {
            val separator = target.lastIndexOf(':')
            val host = target.substring(0, separator.coerceAtLeast(0)).trim('[', ']')
            val port = target.substring(separator + 1).toIntOrNull() ?: -1
            if (host.isEmpty() || port !in 1..65535) {
                send(webSocket, OP_OPEN_ERR, id, "bad target".toByteArray())
                return@execute
            }

            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                // Checked only now, because only now is the name resolved. A
                // hostname pointing at 192.168.1.1 is the interesting case:
                // without this the SDK is a pivot into the user's home network,
                // which is a far worse betrayal than the bandwidth itself.
                if (isPrivate(socket.inetAddress)) {
                    runCatching { socket.close() }
                    send(webSocket, OP_OPEN_ERR, id, "private address refused".toByteArray())
                    return@execute
                }
                socket.tcpNoDelay = true
                socket.soTimeout = IDLE_TIMEOUT_MS
            } catch (e: Exception) {
                runCatching { socket.close() }
                send(webSocket, OP_OPEN_ERR, id, (e.javaClass.simpleName).toByteArray())
                return@execute
            }

            sockets[id] = socket
            served.incrementAndGet()
            send(webSocket, OP_OPEN_OK, id)
            publish(state.status, state.detail)

            val buffer = ByteArray(CHUNK)
            try {
                val input = socket.getInputStream()
                while (running) {
                    awaitSendQueue(webSocket)
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        bytesDown.addAndGet(read.toLong())
                        if (!send(webSocket, OP_DATA, id, buffer.copyOf(read))) break
                    }
                }
            } catch (_: Exception) {
                // A dead destination is ordinary; the CLOSE below is the answer.
            } finally {
                closeStream(id)
                send(webSocket, OP_CLOSE, id)
                publish(state.status, state.detail)
            }
        }
    }

    /**
     * Park until the WebSocket's outbound queue drains below the watermark.
     *
     * This is the whole backpressure mechanism. Blocking *this* thread is the
     * point: it stops reading the destination socket, its receive window closes,
     * and the sender slows down — instead of the phone buffering a download it
     * cannot upload at the same speed.
     */
    private fun awaitSendQueue(webSocket: WebSocket) {
        var waited = 0L
        while (running && webSocket.queueSize() > QUEUE_WATERMARK && waited < QUEUE_WAIT_MAX_MS) {
            Thread.sleep(QUEUE_POLL_MS)
            waited += QUEUE_POLL_MS
        }
    }

    private fun closeStream(id: Int) {
        sockets.remove(id)?.let { runCatching { it.close() } }
    }

    private fun closeAllStreams() {
        sockets.keys.toList().forEach { closeStream(it) }
    }

    // ------------------------------------------------------------------ //
    // wire
    // ------------------------------------------------------------------ //
    private fun send(
        webSocket: WebSocket,
        op: Int,
        id: Int,
        payload: ByteArray = EMPTY,
    ): Boolean {
        val frame = ByteBuffer.allocate(HEADER + payload.size)
            .put(op.toByte())
            .putInt(id)
            .put(payload)
            .array()
        return runCatching { webSocket.send(frame.toByteString()) }.getOrDefault(false)
    }

    private fun scheduleStats() {
        scheduler.scheduleWithFixedDelay({
            val socket = ws ?: return@scheduleWithFixedDelay
            // Diagnostics only. The coordinator pays on what it relayed, never
            // on what a device claims — otherwise this frame would be the
            // payout-fraud surface.
            runCatching {
                socket.send(
                    JSONObject()
                        .put("op", "stats")
                        .put("up", bytesUp.get())
                        .put("down", bytesDown.get())
                        .put("streams", sockets.size)
                        .toString()
                )
            }
        }, 60, 60, TimeUnit.SECONDS)
    }

    private fun publish(status: SdkState.Status, detail: String) {
        state = SdkState(
            status = status,
            detail = detail,
            bytesUp = bytesUp.get(),
            bytesDown = bytesDown.get(),
            activeStreams = sockets.size,
            servedStreams = served.get(),
            country = country,
        )
        onState(state)
    }

    private fun log(message: String) {
        if (config.debugLogging) Log.i("ProxyPoolSdk", message)
    }

    private fun isPrivate(address: InetAddress?): Boolean =
        address == null || address.isLoopbackAddress || address.isSiteLocalAddress ||
            address.isLinkLocalAddress || address.isAnyLocalAddress ||
            address.isMulticastAddress

    companion object {
        const val PROTOCOL_VERSION = 1

        private const val OP_OPEN = 1
        private const val OP_DATA = 2
        private const val OP_CLOSE = 3
        private const val OP_OPEN_OK = 4
        private const val OP_OPEN_ERR = 5

        private const val HEADER = 5
        private const val CHUNK = 16 * 1024
        private val EMPTY = ByteArray(0)

        private const val CONNECT_TIMEOUT_MS = 15_000
        /** A destination that sends nothing for this long is abandoned. */
        private const val IDLE_TIMEOUT_MS = 120_000

        /** Outbound bytes already queued before a reader starts waiting. */
        private const val QUEUE_WATERMARK = 512L * 1024
        private const val QUEUE_POLL_MS = 25L
        /** Give up waiting eventually, so a wedged socket cannot pin a thread. */
        private const val QUEUE_WAIT_MAX_MS = 60_000L
    }
}
