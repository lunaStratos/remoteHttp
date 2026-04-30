package com.lunastratos.remotecontrol.net

import com.lunastratos.remotecontrol.data.ConnectionState
import com.lunastratos.remotecontrol.data.DeviceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * MQTT pub/sub bridge. STATUS_QUERY uses [connect] to keep a persistent client subscribed
 * to [DeviceItem.mqttTopic]. INT/STRING_COMMAND use [publish] for a one-shot publish using
 * the body template (with `{{value}}` substitution).
 */
object MqttExecutor {

    interface Callback {
        fun onMessage(text: String)
        fun onStatus(message: String)
        fun onState(state: ConnectionState) {}
    }

    /** Open a subscription described by [item]. Caller must invoke [MqttAsyncClient.disconnect]. */
    fun connect(item: DeviceItem, callback: Callback): MqttAsyncClient {
        val clientId = item.mqttClientId.ifBlank { "remotecontrol-" + UUID.randomUUID() }
        val client = MqttAsyncClient(item.mqttBrokerUrl, clientId, MemoryPersistence())
        callback.onState(ConnectionState.CONNECTING)
        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                callback.onState(ConnectionState.DISCONNECTED)
                callback.onStatus(
                    "[mqtt error] ${cause?.message ?: "connection lost"}"
                )
                // Paho's automatic-reconnect handles re-attempts when enabled; we surface state only.
            }
            override fun messageArrived(topic: String, message: MqttMessage) {
                callback.onMessage(String(message.payload, Charsets.UTF_8))
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        try {
            client.connect(buildConnectOptions(item, autoReconnect = true), null, object : org.eclipse.paho.client.mqttv3.IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    callback.onState(ConnectionState.CONNECTED)
                    callback.onStatus("[mqtt 0] connected")
                    try {
                        client.subscribe(item.mqttTopic, item.mqttQos)
                    } catch (t: Throwable) {
                        callback.onState(ConnectionState.ERROR)
                        callback.onStatus(
                            "[mqtt error] subscribe failed: ${t.message ?: t::class.java.simpleName}"
                        )
                    }
                }
                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?
                ) {
                    callback.onState(ConnectionState.ERROR)
                    callback.onStatus(
                        "[mqtt error] ${exception?.message ?: "connect failed"}"
                    )
                }
            })
        } catch (t: MqttException) {
            callback.onState(ConnectionState.ERROR)
            callback.onStatus("[mqtt error] ${t.message ?: t::class.java.simpleName}")
        }
        return client
    }

    /** One-shot publish for INT/STRING_COMMAND. Result mirrors HttpExecutor.Result for the UI. */
    suspend fun publish(item: DeviceItem, substitution: String?): HttpExecutor.Result =
        withContext(Dispatchers.IO) {
            val clientId = item.mqttClientId.ifBlank { "remotecontrol-" + UUID.randomUUID() }
            val client = MqttAsyncClient(item.mqttBrokerUrl, clientId, MemoryPersistence())
            try {
                val token = client.connect(buildConnectOptions(item, autoReconnect = false))
                token.waitForCompletion(10_000)
                val payload = item.bodyTemplate
                    .replace("{{value}}", substitution ?: "")
                    .toByteArray(Charsets.UTF_8)
                val pubToken = client.publish(item.mqttTopic, payload, item.mqttQos, false)
                pubToken.waitForCompletion(10_000)
                client.disconnect().waitForCompletion(2_000)
                HttpExecutor.Result(true, 200, "published ${payload.size}B to ${item.mqttTopic}")
            } catch (t: Throwable) {
                runCatching { client.disconnectForcibly() }
                HttpExecutor.Result(false, -1, t.message ?: t::class.java.simpleName)
            }
        }

    private fun buildConnectOptions(item: DeviceItem, autoReconnect: Boolean): MqttConnectOptions =
        MqttConnectOptions().apply {
            isAutomaticReconnect = autoReconnect
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            if (item.mqttUsername.isNotBlank()) userName = item.mqttUsername
            if (item.mqttPassword.isNotBlank()) password = item.mqttPassword.toCharArray()
        }
}
