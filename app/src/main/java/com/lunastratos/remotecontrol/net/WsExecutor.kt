package com.lunastratos.remotecontrol.net

import com.lunastratos.remotecontrol.data.DeviceItem
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

object WsExecutor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    interface Callback {
        fun onMessage(text: String)
        fun onStatus(message: String)
    }

    /**
     * Open a WebSocket subscription described by [item]. Caller must invoke [WebSocket.close]
     * (or [WebSocket.cancel]) when finished. URLs may be ws:// or wss://; http(s):// is also
     * accepted because OkHttp performs the same upgrade either way.
     */
    fun connect(item: DeviceItem, callback: Callback): WebSocket {
        val builder = Request.Builder().url(item.url)
        for (h in item.headers) {
            if (h.key.isNotBlank()) builder.addHeader(h.key, h.value)
        }
        return client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
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
                callback.onStatus("[ws closed $code] $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                callback.onStatus("[ws error $code] ${t.message ?: t::class.java.simpleName}")
            }
        })
    }
}
