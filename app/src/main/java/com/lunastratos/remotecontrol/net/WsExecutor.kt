package com.lunastratos.remotecontrol.net

import com.lunastratos.remotecontrol.data.ConnectionState
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Long-lived WebSocket bridge with exponential-backoff auto-reconnect.
 *
 * Returns a [Handle] instead of a raw [WebSocket] so callers can still cancel the entire
 * subscription (including any pending reconnect timer) when the activity is paused.
 */
object WsExecutor {

    private val secureClient = buildClient(insecure = false)
    private val insecureClient = buildClient(insecure = true)

    private fun client(insecure: Boolean): OkHttpClient =
        if (insecure) insecureClient else secureClient

    private var insecureTlsResolver: () -> Boolean = { false }
    fun bindSettings(settings: Settings) {
        insecureTlsResolver = { settings.insecureTls }
    }

    interface Callback {
        fun onMessage(text: String)
        fun onStatus(message: String)
        /** Lifecycle event that drives the connection-state dot on the card. */
        fun onState(state: ConnectionState) {}
    }

    /** Cancellable subscription. */
    class Handle internal constructor() {
        @Volatile internal var ws: WebSocket? = null
        @Volatile internal var cancelled: Boolean = false
        @Volatile internal var reconnectThread: Thread? = null
        fun cancel() {
            cancelled = true
            ws?.cancel()
            reconnectThread?.interrupt()
        }
    }

    /**
     * Open a WebSocket subscription described by [item]. Auto-reconnects with exponential
     * backoff (500ms → 8s) on any onClosed / onFailure that wasn't user-cancelled.
     */
    fun connect(item: DeviceItem, callback: Callback): Handle {
        val handle = Handle()
        openInternal(item, callback, handle, attempt = 0)
        return handle
    }

    private fun openInternal(
        item: DeviceItem,
        callback: Callback,
        handle: Handle,
        attempt: Int
    ) {
        if (handle.cancelled) return
        callback.onState(if (attempt == 0) ConnectionState.CONNECTING else ConnectionState.CONNECTING)
        val builder = Request.Builder().url(item.url)
        for (h in item.headers) {
            if (h.key.isNotBlank()) builder.addHeader(h.key, h.value)
        }
        val ws = client(insecureTlsResolver()).newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                callback.onState(ConnectionState.CONNECTED)
                callback.onStatus("[ws ${response.code}] connected")
                val payload = item.bodyTemplate
                if (payload.isNotBlank()) webSocket.send(payload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                callback.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                callback.onMessage(bytes.hex())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                callback.onState(ConnectionState.DISCONNECTED)
                callback.onStatus("[ws closed $code] $reason")
                scheduleReconnect(item, callback, handle, attempt + 1)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                callback.onState(ConnectionState.ERROR)
                callback.onStatus("[ws error $code] ${t.message ?: t::class.java.simpleName}")
                scheduleReconnect(item, callback, handle, attempt + 1)
            }
        })
        handle.ws = ws
    }

    private fun scheduleReconnect(
        item: DeviceItem,
        callback: Callback,
        handle: Handle,
        attempt: Int
    ) {
        if (handle.cancelled) return
        val delay = (500L * (1 shl (attempt - 1).coerceAtMost(4))).coerceAtMost(8_000L)
        handle.reconnectThread = Thread {
            try {
                Thread.sleep(delay)
                openInternal(item, callback, handle, attempt)
            } catch (_: InterruptedException) {
                // cancelled — drop quietly
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun buildClient(insecure: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
        if (insecure) {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trust), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, trust)
            b.hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }
}
