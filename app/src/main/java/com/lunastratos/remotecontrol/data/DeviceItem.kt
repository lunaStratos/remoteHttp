package com.lunastratos.remotecontrol.data

import java.util.UUID

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

enum class ItemType { STATUS_QUERY, INT_COMMAND, STRING_COMMAND, MACRO }

/** Connection lifecycle hint surfaced on the card for live protocols (WS / MQTT). */
enum class ConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

enum class Protocol { HTTP, WEBSOCKET, MQTT, MODBUS }

/**
 * Modbus function codes we support. Read codes are valid on STATUS_QUERY; write codes
 * are valid on INT_COMMAND. Modbus is not exposed for STRING_COMMAND because registers
 * are inherently numeric.
 */
enum class ModbusFunction(val code: Int) {
    READ_HOLDING_REGISTERS(3),
    READ_INPUT_REGISTERS(4),
    READ_COILS(1),
    READ_DISCRETE_INPUTS(2),
    WRITE_SINGLE_REGISTER(6),
    WRITE_SINGLE_COIL(5)
}

data class HttpHeader(var key: String, var value: String)

/**
 * Labeled preset for STRING_COMMAND. Each preset renders as an inline button
 * with [label] showing on the card; tapping sends [value] as the substitution.
 */
data class StringPreset(var label: String = "", var value: String = "")

/**
 * Reference to one step in a [ItemType.MACRO] sequence. [targetItemId] points at another
 * DeviceItem on the same device; [value] is substituted into its body / URL placeholder
 * (ignored for STATUS_QUERY targets); [delayMsAfter] pauses before running the next step.
 */
data class MacroStep(
    var targetItemId: String = "",
    var value: String = "",
    var delayMsAfter: Long = 0L
)

/**
 * Conditional display rule for STATUS_QUERY + WEBSOCKET messages.
 * If the JSON value at [matchPath] equals [matchValue] (string compare on toString of the
 * extracted value), the value at [valuePath] is rendered as `"$label: $value"` in the result
 * card. Multiple rules accumulate into a multi-line result keyed by label.
 *
 * Empty matchPath = always match (useful for unconditional extraction with a label).
 */
data class WsRule(
    var label: String = "",
    var matchPath: String = "",
    var matchValue: String = "",
    var valuePath: String = ""
)

data class DeviceItem(
    val id: String = UUID.randomUUID().toString(),
    val type: ItemType,
    var name: String,
    var url: String,
    var method: HttpMethod = HttpMethod.GET,
    var headers: MutableList<HttpHeader> = mutableListOf(),

    // Selects between HTTP polling, persistent WebSocket subscription, MQTT pub/sub,
    // and Modbus TCP. WEBSOCKET is only valid for STATUS_QUERY; MODBUS is only valid for
    // STATUS_QUERY and INT_COMMAND.
    var protocol: Protocol = Protocol.HTTP,
    var intervalMs: Long = 5_000L,

    // STATUS_QUERY: JSON dot-path applied to each incoming message (WS or MQTT) to pluck
    // a single value. Numeric segments are treated as array indexes. Empty = show the raw
    // payload. Ignored when wsRules is non-empty.
    var responsePath: String = "",

    // STATUS_QUERY: when non-empty, takes precedence over responsePath. Applies to WS and
    // MQTT subscriptions.
    var wsRules: MutableList<WsRule> = mutableListOf(),

    // INT_COMMAND / STRING_COMMAND: bodyTemplate uses {{value}} placeholder.
    // INT_COMMAND optional min/max bounds.
    var bodyTemplate: String = "",
    var intMin: Int? = null,
    var intMax: Int? = null,
    // INT_COMMAND: how much each + / − tap changes the current value.
    var intStep: Int = 1,

    // STRING_COMMAND: list of inline preset buttons. Tapping a button substitutes
    // [StringPreset.value] into the request body / URL placeholder.
    var stringPresets: MutableList<StringPreset> = mutableListOf(),

    // MACRO: ordered list of (targetItemId, value, delay) triples to execute in sequence.
    var macroSteps: MutableList<MacroStep> = mutableListOf(),

    // Display-only: appended to the result body so the value reads as `23.4 °C` etc.
    var unit: String = "",
    // Surfaces a star on the card; sort key for the device detail list.
    var favorite: Boolean = false,
    // Optional Bearer token endpoint. When non-blank, the executor fetches a token
    // (form-url-encoded if [authBody] looks like form data, JSON otherwise) before each
    // request and rewrites any `Authorization: Bearer __auto__` header with the response
    // value at [authTokenPath] (dot-path, defaults to "access_token").
    var authTokenUrl: String = "",
    var authBody: String = "",
    var authTokenPath: String = "access_token",

    // ── MQTT-specific ────────────────────────────────────────────────────────────
    // Broker URI, e.g. "tcp://broker.hivemq.com:1883" or "ssl://...".
    var mqttBrokerUrl: String = "",
    // Optional client id; auto-generated at runtime when blank.
    var mqttClientId: String = "",
    // Subscribe topic (STATUS_QUERY) or publish topic (INT/STRING_COMMAND).
    var mqttTopic: String = "",
    var mqttQos: Int = 0,
    var mqttUsername: String = "",
    var mqttPassword: String = "",

    // ── Modbus TCP-specific ──────────────────────────────────────────────────────
    // "host" or "host:port" — port defaults to 502 when omitted.
    var modbusHost: String = "",
    var modbusUnitId: Int = 1,
    var modbusFunction: ModbusFunction = ModbusFunction.READ_HOLDING_REGISTERS,
    var modbusAddress: Int = 0,
    var modbusCount: Int = 1,

    // Last known value for status display (not persisted critical, but useful)
    @Transient var lastResult: String? = null,
    @Transient var lastResultAt: Long? = null,
    @Transient var pollingPaused: Boolean = false,
    @Transient var connectionState: ConnectionState = ConnectionState.IDLE
)
