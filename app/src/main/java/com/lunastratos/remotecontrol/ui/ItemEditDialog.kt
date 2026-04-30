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
import com.lunastratos.remotecontrol.data.MacroStep
import com.lunastratos.remotecontrol.data.ModbusFunction
import com.lunastratos.remotecontrol.data.Protocol
import com.lunastratos.remotecontrol.data.StringPreset
import com.lunastratos.remotecontrol.data.WsRule
import com.lunastratos.remotecontrol.databinding.DialogItemEditBinding
import com.lunastratos.remotecontrol.databinding.RowHeaderBinding
import com.lunastratos.remotecontrol.databinding.RowMacroStepBinding
import com.lunastratos.remotecontrol.databinding.RowPresetBinding
import com.lunastratos.remotecontrol.databinding.RowWsRuleBinding

object ItemEditDialog {

    fun show(
        context: Context,
        type: ItemType,
        existing: DeviceItem?,
        macroTargets: List<DeviceItem> = emptyList(),
        onSave: (DeviceItem) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val b = DialogItemEditBinding.inflate(inflater)

        val methods = HttpMethod.values().map { it.name }
        b.spinnerMethod.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item, methods
        )

        val functions = ModbusFunction.values()
        b.spinnerModbusFunction.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            functions.map { "FC${it.code} ${it.name}" }
        )

        val isMacro = type == ItemType.MACRO

        b.protocolRow.visibility = if (isMacro) View.GONE else View.VISIBLE
        b.radioWs.visibility = if (type == ItemType.STATUS_QUERY) View.VISIBLE else View.GONE
        b.radioModbus.visibility = if (type == ItemType.STRING_COMMAND || isMacro) View.GONE else View.VISIBLE

        b.intRangeRow.visibility = if (type == ItemType.INT_COMMAND) View.VISIBLE else View.GONE
        b.presetsSection.visibility = if (type == ItemType.STRING_COMMAND) View.VISIBLE else View.GONE
        b.macroSection.visibility = if (isMacro) View.VISIBLE else View.GONE

        fun applyProtocolVisibility(protocol: Protocol) {
            b.methodRow.visibility = View.GONE
            b.intervalLayout.visibility = View.GONE
            b.bodyLayout.visibility = View.GONE
            b.responsePathLayout.visibility = View.GONE
            b.rulesSection.visibility = View.GONE
            b.mqttSection.visibility = View.GONE
            b.modbusSection.visibility = View.GONE
            b.authSection.visibility = View.GONE
            if (isMacro) return
            when (protocol) {
                Protocol.HTTP -> {
                    b.methodRow.visibility = View.VISIBLE
                    b.bodyLayout.visibility = View.VISIBLE
                    b.bodyLayout.hint = context.getString(R.string.body_template)
                    b.authSection.visibility = View.VISIBLE
                    if (type == ItemType.STATUS_QUERY) b.intervalLayout.visibility = View.VISIBLE
                }
                Protocol.WEBSOCKET -> {
                    b.bodyLayout.visibility = View.VISIBLE
                    b.bodyLayout.hint = context.getString(R.string.ws_send_message)
                    b.responsePathLayout.visibility = View.VISIBLE
                    b.rulesSection.visibility = View.VISIBLE
                }
                Protocol.MQTT -> {
                    b.mqttSection.visibility = View.VISIBLE
                    if (type == ItemType.STATUS_QUERY) {
                        b.responsePathLayout.visibility = View.VISIBLE
                        b.rulesSection.visibility = View.VISIBLE
                    } else {
                        b.bodyLayout.visibility = View.VISIBLE
                        b.bodyLayout.hint = context.getString(R.string.body_template)
                    }
                }
                Protocol.MODBUS -> {
                    b.modbusSection.visibility = View.VISIBLE
                    if (type == ItemType.STATUS_QUERY) b.intervalLayout.visibility = View.VISIBLE
                }
            }
        }

        b.protocolGroup.setOnCheckedChangeListener { _, checkedId ->
            val protocol = when (checkedId) {
                R.id.radioWs -> Protocol.WEBSOCKET
                R.id.radioMqtt -> Protocol.MQTT
                R.id.radioModbus -> Protocol.MODBUS
                else -> Protocol.HTTP
            }
            applyProtocolVisibility(protocol)
        }

        existing?.let {
            b.inputName.setText(it.name)
            b.inputUrl.setText(it.url)
            b.inputUnit.setText(it.unit)
            b.switchFavorite.isChecked = it.favorite
            b.spinnerMethod.setSelection(methods.indexOf(it.method.name).coerceAtLeast(0))
            b.inputInterval.setText(it.intervalMs.toString())
            b.inputBody.setText(it.bodyTemplate)
            it.intMin?.let { v -> b.inputIntMin.setText(v.toString()) }
            it.intMax?.let { v -> b.inputIntMax.setText(v.toString()) }
            b.inputIntStep.setText(it.intStep.toString())
            for (h in it.headers) addHeaderRow(inflater, b, h)
            for (p in it.stringPresets) addPresetRow(inflater, b, p)
            for (r in it.wsRules) addWsRuleRow(inflater, b, r)
            for (s in it.macroSteps) addMacroRow(inflater, b, s, macroTargets)
            b.inputResponsePath.setText(it.responsePath)

            b.inputAuthUrl.setText(it.authTokenUrl)
            b.inputAuthBody.setText(it.authBody)
            b.inputAuthPath.setText(it.authTokenPath)

            b.inputMqttBroker.setText(it.mqttBrokerUrl)
            b.inputMqttTopic.setText(it.mqttTopic)
            b.inputMqttQos.setText(it.mqttQos.toString())
            b.inputMqttClientId.setText(it.mqttClientId)
            b.inputMqttUser.setText(it.mqttUsername)
            b.inputMqttPass.setText(it.mqttPassword)

            b.inputModbusHost.setText(it.modbusHost)
            b.inputModbusUnit.setText(it.modbusUnitId.toString())
            b.inputModbusAddr.setText(it.modbusAddress.toString())
            b.inputModbusCount.setText(it.modbusCount.toString())
            b.spinnerModbusFunction.setSelection(
                functions.indexOf(it.modbusFunction).coerceAtLeast(0)
            )

            val radioId = when (it.protocol) {
                Protocol.HTTP -> R.id.radioHttp
                Protocol.WEBSOCKET -> R.id.radioWs
                Protocol.MQTT -> R.id.radioMqtt
                Protocol.MODBUS -> R.id.radioModbus
            }
            b.protocolGroup.check(radioId)
            applyProtocolVisibility(it.protocol)
        } ?: run {
            b.inputInterval.setText("5000")
            b.inputMqttQos.setText("0")
            b.inputModbusUnit.setText("1")
            b.inputModbusAddr.setText("0")
            b.inputModbusCount.setText("1")
            b.inputAuthPath.setText("access_token")
            when (type) {
                ItemType.STATUS_QUERY -> {
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
                ItemType.MACRO -> {
                    // No protocol fields apply.
                }
            }
            applyProtocolVisibility(Protocol.HTTP)
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
        b.btnAddMacroStep.setOnClickListener {
            if (macroTargets.isEmpty()) {
                AlertDialog.Builder(context)
                    .setMessage(R.string.macro_no_targets)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
                return@setOnClickListener
            }
            addMacroRow(inflater, b, MacroStep(targetItemId = macroTargets.first().id), macroTargets)
        }

        val title = when (type) {
            ItemType.STATUS_QUERY -> R.string.status_query
            ItemType.INT_COMMAND -> R.string.int_command
            ItemType.STRING_COMMAND -> R.string.string_command
            ItemType.MACRO -> R.string.macro
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(b.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = b.inputName.text?.toString()?.trim().orEmpty()
                val url = b.inputUrl.text?.toString()?.trim().orEmpty()
                val unit = b.inputUnit.text?.toString()?.trim().orEmpty()
                val favorite = b.switchFavorite.isChecked

                val protocol = if (isMacro) Protocol.HTTP else when (b.protocolGroup.checkedRadioButtonId) {
                    R.id.radioWs -> Protocol.WEBSOCKET
                    R.id.radioMqtt -> Protocol.MQTT
                    R.id.radioModbus -> Protocol.MODBUS
                    else -> Protocol.HTTP
                }

                if (name.isEmpty()) return@setPositiveButton
                if (!isMacro) {
                    when (protocol) {
                        Protocol.HTTP, Protocol.WEBSOCKET -> if (url.isEmpty()) return@setPositiveButton
                        Protocol.MQTT -> {
                            if (b.inputMqttBroker.text.isNullOrBlank()) return@setPositiveButton
                            if (b.inputMqttTopic.text.isNullOrBlank()) return@setPositiveButton
                        }
                        Protocol.MODBUS -> {
                            if (b.inputModbusHost.text.isNullOrBlank()) return@setPositiveButton
                        }
                    }
                }

                val method = HttpMethod.valueOf(b.spinnerMethod.selectedItem.toString())
                val interval = b.inputInterval.text?.toString()?.toLongOrNull() ?: 5000L
                val body = b.inputBody.text?.toString().orEmpty()
                val intMin = b.inputIntMin.text?.toString()?.toIntOrNull()
                val intMax = b.inputIntMax.text?.toString()?.toIntOrNull()
                val intStep = b.inputIntStep.text?.toString()?.toIntOrNull()
                    ?.takeIf { it != 0 } ?: 1
                val responsePath = b.inputResponsePath.text?.toString()?.trim().orEmpty()

                val authUrl = b.inputAuthUrl.text?.toString()?.trim().orEmpty()
                val authBody = b.inputAuthBody.text?.toString().orEmpty()
                val authPath = b.inputAuthPath.text?.toString()?.trim()?.ifBlank { "access_token" } ?: "access_token"

                val mqttBroker = b.inputMqttBroker.text?.toString()?.trim().orEmpty()
                val mqttTopic = b.inputMqttTopic.text?.toString()?.trim().orEmpty()
                val mqttQos = (b.inputMqttQos.text?.toString()?.toIntOrNull() ?: 0).coerceIn(0, 2)
                val mqttClientId = b.inputMqttClientId.text?.toString()?.trim().orEmpty()
                val mqttUser = b.inputMqttUser.text?.toString().orEmpty()
                val mqttPass = b.inputMqttPass.text?.toString().orEmpty()

                val modbusHost = b.inputModbusHost.text?.toString()?.trim().orEmpty()
                val modbusUnit = b.inputModbusUnit.text?.toString()?.toIntOrNull() ?: 1
                val modbusAddr = b.inputModbusAddr.text?.toString()?.toIntOrNull() ?: 0
                val modbusCount = (b.inputModbusCount.text?.toString()?.toIntOrNull() ?: 1)
                    .coerceAtLeast(1)
                val modbusFn = functions[
                    b.spinnerModbusFunction.selectedItemPosition.coerceIn(0, functions.size - 1)
                ]

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
                            valuePath = rb.inputValuePath.text?.toString()?.trim().orEmpty(),
                            unit = rb.inputUnit.text?.toString()?.trim().orEmpty()
                        )
                        if (rule.label.isNotEmpty() ||
                            rule.matchPath.isNotEmpty() ||
                            rule.matchValue.isNotEmpty() ||
                            rule.valuePath.isNotEmpty() ||
                            rule.unit.isNotEmpty()) {
                            wsRules.add(rule)
                        }
                    }
                }

                val macroSteps = mutableListOf<MacroStep>()
                if (isMacro) {
                    for (i in 0 until b.macroContainer.childCount) {
                        val rb = RowMacroStepBinding.bind(b.macroContainer.getChildAt(i))
                        val targetIdx = rb.spinnerTarget.selectedItemPosition
                        val target = macroTargets.getOrNull(targetIdx) ?: continue
                        macroSteps.add(MacroStep(
                            targetItemId = target.id,
                            value = rb.inputValue.text?.toString().orEmpty(),
                            delayMsAfter = rb.inputDelay.text?.toString()?.toLongOrNull() ?: 0L
                        ))
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
                    stringPresets = presets,
                    macroSteps = macroSteps,
                    unit = unit,
                    favorite = favorite,
                    authTokenUrl = authUrl,
                    authBody = authBody,
                    authTokenPath = authPath,
                    mqttBrokerUrl = mqttBroker,
                    mqttClientId = mqttClientId,
                    mqttTopic = mqttTopic,
                    mqttQos = mqttQos,
                    mqttUsername = mqttUser,
                    mqttPassword = mqttPass,
                    modbusHost = modbusHost,
                    modbusUnitId = modbusUnit,
                    modbusFunction = modbusFn,
                    modbusAddress = modbusAddr,
                    modbusCount = modbusCount
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
                    stringPresets = presets,
                    macroSteps = macroSteps,
                    unit = unit,
                    favorite = favorite,
                    authTokenUrl = authUrl,
                    authBody = authBody,
                    authTokenPath = authPath,
                    mqttBrokerUrl = mqttBroker,
                    mqttClientId = mqttClientId,
                    mqttTopic = mqttTopic,
                    mqttQos = mqttQos,
                    mqttUsername = mqttUser,
                    mqttPassword = mqttPass,
                    modbusHost = modbusHost,
                    modbusUnitId = modbusUnit,
                    modbusFunction = modbusFn,
                    modbusAddress = modbusAddr,
                    modbusCount = modbusCount
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
        row.inputUnit.setText(rule.unit)
        row.btnRemove.setOnClickListener {
            b.rulesContainer.removeView(row.root)
        }
        b.rulesContainer.addView(row.root)
    }

    private fun addMacroRow(
        inflater: LayoutInflater,
        b: DialogItemEditBinding,
        step: MacroStep,
        targets: List<DeviceItem>
    ) {
        val row = RowMacroStepBinding.inflate(inflater, b.macroContainer, false)
        val labels = targets.map { "[${typeShortLabel(it.type)}] ${it.name}" }
        row.spinnerTarget.adapter = ArrayAdapter(
            b.root.context,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        val sel = targets.indexOfFirst { it.id == step.targetItemId }.coerceAtLeast(0)
        row.spinnerTarget.setSelection(sel)
        row.inputValue.setText(step.value)
        row.inputDelay.setText(step.delayMsAfter.toString())
        row.btnRemove.setOnClickListener {
            b.macroContainer.removeView(row.root)
        }
        b.macroContainer.addView(row.root)
    }

    private fun typeShortLabel(type: ItemType): String = when (type) {
        ItemType.STATUS_QUERY -> "S"
        ItemType.INT_COMMAND -> "I"
        ItemType.STRING_COMMAND -> "T"
        ItemType.MACRO -> "M"
    }

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
