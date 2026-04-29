package com.lunastratos.remotecontrol.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.lunastratos.remotecontrol.R
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.HttpHeader
import com.lunastratos.remotecontrol.data.HttpMethod
import com.lunastratos.remotecontrol.data.ItemType
import com.lunastratos.remotecontrol.data.Protocol
import com.lunastratos.remotecontrol.data.StringPreset
import com.lunastratos.remotecontrol.data.WsRule
import com.lunastratos.remotecontrol.databinding.DialogItemEditBinding
import com.lunastratos.remotecontrol.databinding.RowHeaderBinding
import com.lunastratos.remotecontrol.databinding.RowPresetBinding
import com.lunastratos.remotecontrol.databinding.RowWsRuleBinding

object ItemEditDialog {

    fun show(
        context: Context,
        type: ItemType,
        existing: DeviceItem?,
        onSave: (DeviceItem) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val b = DialogItemEditBinding.inflate(inflater)

        // Method spinner
        val methods = HttpMethod.values().map { it.name }
        b.spinnerMethod.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item, methods
        )

        // Field visibility per type
        when (type) {
            ItemType.STATUS_QUERY -> {
                b.protocolRow.visibility = View.VISIBLE
                b.intervalLayout.visibility = View.VISIBLE
                b.intRangeRow.visibility = View.GONE
                b.bodyLayout.visibility = View.VISIBLE
            }
            ItemType.INT_COMMAND -> {
                b.protocolRow.visibility = View.GONE
                b.intervalLayout.visibility = View.GONE
                b.intRangeRow.visibility = View.VISIBLE
                b.bodyLayout.visibility = View.VISIBLE
            }
            ItemType.STRING_COMMAND -> {
                b.protocolRow.visibility = View.GONE
                b.intervalLayout.visibility = View.GONE
                b.intRangeRow.visibility = View.GONE
                b.bodyLayout.visibility = View.VISIBLE
                b.presetsSection.visibility = View.VISIBLE
            }
        }

        // For STATUS_QUERY, the WebSocket option toggles HTTP-only fields off and reveals
        // WS-specific inputs. The body field is reused as the WS subscribe payload.
        val applyProtocolVisibility = { ws: Boolean ->
            if (type == ItemType.STATUS_QUERY) {
                val httpVisibility = if (ws) View.GONE else View.VISIBLE
                b.methodRow.visibility = httpVisibility
                b.intervalLayout.visibility = httpVisibility
                b.bodyLayout.visibility = View.VISIBLE
                b.bodyLayout.hint = context.getString(
                    if (ws) R.string.ws_send_message else R.string.body_template
                )
                b.responsePathLayout.visibility = if (ws) View.VISIBLE else View.GONE
                b.rulesSection.visibility = if (ws) View.VISIBLE else View.GONE
            }
        }
        b.protocolGroup.setOnCheckedChangeListener { _, checkedId ->
            applyProtocolVisibility(checkedId == R.id.radioWs)
        }

        // Pre-fill values
        existing?.let {
            b.inputName.setText(it.name)
            b.inputUrl.setText(it.url)
            b.spinnerMethod.setSelection(methods.indexOf(it.method.name).coerceAtLeast(0))
            b.inputInterval.setText(it.intervalMs.toString())
            b.inputBody.setText(it.bodyTemplate)
            it.intMin?.let { v -> b.inputIntMin.setText(v.toString()) }
            it.intMax?.let { v -> b.inputIntMax.setText(v.toString()) }
            b.inputIntStep.setText(it.intStep.toString())
            for (h in it.headers) addHeaderRow(inflater, b, h)
            for (p in it.stringPresets) addPresetRow(inflater, b, p)
            for (r in it.wsRules) addWsRuleRow(inflater, b, r)
            b.inputResponsePath.setText(it.responsePath)
            if (it.protocol == Protocol.WEBSOCKET) {
                b.radioWs.isChecked = true
                applyProtocolVisibility(true)
            }
        } ?: run {
            // Reasonable defaults
            b.inputInterval.setText("5000")
            when (type) {
                ItemType.STATUS_QUERY -> {
                    // Pre-fill with the BACnet/Telemetry sample so users see a working
                    // template instead of an empty form. They can clear or edit freely.
                    b.inputBody.setText(DEFAULT_WS_SUBSCRIBE)
                    b.inputResponsePath.setText(DEFAULT_RESPONSE_PATH)
                    addWsRuleRow(inflater, b, DEFAULT_WS_RULE)
                }
                ItemType.INT_COMMAND -> {
                    b.inputBody.setText("{\"value\": {{value}}}")
                    b.inputIntStep.setText("1")
                }
                ItemType.STRING_COMMAND -> {
                    b.inputBody.setText("{\"value\": {{value}}}")
                }
            }
        }

        b.btnAddHeader.setOnClickListener {
            addHeaderRow(inflater, b, HttpHeader("", ""))
        }
        b.btnAddPreset.setOnClickListener {
            addPresetRow(inflater, b, StringPreset())
        }
        b.btnAddRule.setOnClickListener {
            addWsRuleRow(inflater, b, WsRule())
        }

        val title = when (type) {
            ItemType.STATUS_QUERY -> R.string.status_query
            ItemType.INT_COMMAND -> R.string.int_command
            ItemType.STRING_COMMAND -> R.string.string_command
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(b.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = b.inputName.text?.toString()?.trim().orEmpty()
                val url = b.inputUrl.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || url.isEmpty()) return@setPositiveButton

                val method = HttpMethod.valueOf(b.spinnerMethod.selectedItem.toString())
                val interval = b.inputInterval.text?.toString()?.toLongOrNull() ?: 5000L
                val body = b.inputBody.text?.toString().orEmpty()
                val intMin = b.inputIntMin.text?.toString()?.toIntOrNull()
                val intMax = b.inputIntMax.text?.toString()?.toIntOrNull()
                val intStep = b.inputIntStep.text?.toString()?.toIntOrNull()
                    ?.takeIf { it != 0 } ?: 1
                val protocol = if (type == ItemType.STATUS_QUERY && b.radioWs.isChecked) {
                    Protocol.WEBSOCKET
                } else {
                    Protocol.HTTP
                }
                val responsePath = b.inputResponsePath.text?.toString()?.trim().orEmpty()

                val headers = mutableListOf<HttpHeader>()
                for (i in 0 until b.headersContainer.childCount) {
                    val rowBinding = RowHeaderBinding.bind(b.headersContainer.getChildAt(i))
                    val key = rowBinding.inputKey.text?.toString()?.trim().orEmpty()
                    val value = rowBinding.inputValue.text?.toString()?.trim().orEmpty()
                    if (key.isNotEmpty()) headers.add(HttpHeader(key, value))
                }

                val presets = mutableListOf<StringPreset>()
                if (type == ItemType.STRING_COMMAND) {
                    for (i in 0 until b.presetsContainer.childCount) {
                        val rowBinding = RowPresetBinding.bind(b.presetsContainer.getChildAt(i))
                        val label = rowBinding.inputLabel.text?.toString()?.trim().orEmpty()
                        val value = rowBinding.inputValue.text?.toString().orEmpty()
                        // Drop fully-empty rows. If the user filled only one side, keep the
                        // row using the filled side as both label and value.
                        if (label.isEmpty() && value.isEmpty()) continue
                        presets.add(
                            StringPreset(
                                label = label.ifEmpty { value },
                                value = value.ifEmpty { label }
                            )
                        )
                    }
                }

                val wsRules = mutableListOf<WsRule>()
                if (type == ItemType.STATUS_QUERY) {
                    for (i in 0 until b.rulesContainer.childCount) {
                        val rb = RowWsRuleBinding.bind(b.rulesContainer.getChildAt(i))
                        val rule = WsRule(
                            label = rb.inputLabel.text?.toString()?.trim().orEmpty(),
                            matchPath = rb.inputMatchPath.text?.toString()?.trim().orEmpty(),
                            matchValue = rb.inputMatchValue.text?.toString()?.trim().orEmpty(),
                            valuePath = rb.inputValuePath.text?.toString()?.trim().orEmpty()
                        )
                        // Drop fully-empty rows so users can leave stray inputs without saving them.
                        if (rule.label.isNotEmpty() ||
                            rule.matchPath.isNotEmpty() ||
                            rule.matchValue.isNotEmpty() ||
                            rule.valuePath.isNotEmpty()) {
                            wsRules.add(rule)
                        }
                    }
                }

                val updated = (existing?.copy(
                    name = name,
                    url = url,
                    method = method,
                    headers = headers,
                    protocol = protocol,
                    intervalMs = interval,
                    responsePath = responsePath,
                    wsRules = wsRules,
                    bodyTemplate = body,
                    intMin = intMin,
                    intMax = intMax,
                    intStep = intStep,
                    stringPresets = presets
                )) ?: DeviceItem(
                    type = type,
                    name = name,
                    url = url,
                    method = method,
                    headers = headers,
                    protocol = protocol,
                    intervalMs = interval,
                    responsePath = responsePath,
                    wsRules = wsRules,
                    bodyTemplate = body,
                    intMin = intMin,
                    intMax = intMax,
                    intStep = intStep,
                    stringPresets = presets
                )
                onSave(updated)
            }
            .show()
    }

    private fun addHeaderRow(
        inflater: LayoutInflater,
        b: DialogItemEditBinding,
        header: HttpHeader
    ) {
        val row = RowHeaderBinding.inflate(inflater, b.headersContainer, false)
        row.inputKey.setText(header.key)
        row.inputValue.setText(header.value)
        row.btnRemove.setOnClickListener {
            b.headersContainer.removeView(row.root)
        }
        b.headersContainer.addView(row.root)
    }

    private fun addPresetRow(
        inflater: LayoutInflater,
        b: DialogItemEditBinding,
        preset: StringPreset
    ) {
        val row = RowPresetBinding.inflate(inflater, b.presetsContainer, false)
        row.inputLabel.setText(preset.label)
        row.inputValue.setText(preset.value)
        row.btnRemove.setOnClickListener {
            b.presetsContainer.removeView(row.root)
        }
        b.presetsContainer.addView(row.root)
    }

    private fun addWsRuleRow(
        inflater: LayoutInflater,
        b: DialogItemEditBinding,
        rule: WsRule
    ) {
        val row = RowWsRuleBinding.inflate(inflater, b.rulesContainer, false)
        row.inputLabel.setText(rule.label)
        row.inputMatchPath.setText(rule.matchPath)
        row.inputMatchValue.setText(rule.matchValue)
        row.inputValuePath.setText(rule.valuePath)
        row.btnRemove.setOnClickListener {
            b.rulesContainer.removeView(row.root)
        }
        b.rulesContainer.addView(row.root)
    }

    /** Sample subscribe payload mirroring the documented BACnet/Telemetry stream. */
    private val DEFAULT_WS_SUBSCRIBE = """
        {
          "type": "set_filter",
          "filter": {
            "stage": "ALL",
            "node_ids": [
              "73fcb3865aa64b558427580417523f394f97541569d95f8cfc"
            ]
          }
        }
    """.trimIndent()

    private const val DEFAULT_RESPONSE_PATH =
        "data.envelope.body.Telemetry.payload.F64"

    private val DEFAULT_WS_RULE = WsRule(
        label = "온도",
        matchPath = "data.node_id",
        matchValue = "73fcb3865aa64b558427580417523f394f97541569d95f8cfc",
        valuePath = "data.envelope.body.Telemetry.payload.F64"
    )
}
