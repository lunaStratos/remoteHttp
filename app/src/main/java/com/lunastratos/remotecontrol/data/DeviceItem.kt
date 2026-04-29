package com.lunastratos.remotecontrol.data

import java.util.UUID

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }

enum class ItemType { STATUS_QUERY, INT_COMMAND, STRING_COMMAND }

enum class Protocol { HTTP, WEBSOCKET }

data class HttpHeader(var key: String, var value: String)

/**
 * Labeled preset for STRING_COMMAND. Each preset renders as an inline button
 * with [label] showing on the card; tapping sends [value] as the substitution.
 */
data class StringPreset(var label: String = "", var value: String = "")

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

    // STATUS_QUERY only — selects between HTTP polling and a persistent WebSocket subscription.
    var protocol: Protocol = Protocol.HTTP,
    var intervalMs: Long = 5_000L,

    // STATUS_QUERY + WEBSOCKET only — dot-path applied to each incoming JSON message
    // to pluck out a single value. Numeric segments are treated as array indexes.
    // Empty = show the raw message. Ignored when wsRules is non-empty.
    var responsePath: String = "",

    // STATUS_QUERY + WEBSOCKET only — when non-empty, takes precedence over responsePath.
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

    // Last known value for status display (not persisted critical, but useful)
    @Transient var lastResult: String? = null
)
