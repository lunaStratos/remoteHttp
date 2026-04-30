package com.lunastratos.remotecontrol.net

import com.lunastratos.remotecontrol.data.Device
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.ItemType
import com.lunastratos.remotecontrol.data.Protocol
import kotlinx.coroutines.delay

/**
 * Sequential runner for [ItemType.MACRO] items. Each step looks up its target item on the
 * same device and dispatches a one-shot execution through the existing per-protocol
 * executors (HTTP / MQTT / Modbus). STATUS_QUERY targets are not supported (they're a
 * subscription model) and produce a synthetic skip line.
 *
 * The runner ignores transport failures mid-sequence — the user wants the rest of the
 * macro to keep going so a single bad address doesn't strand a "stop everything" sequence.
 */
object MacroExecutor {

    suspend fun run(
        macro: DeviceItem,
        device: Device,
        onStep: (DeviceItem, HttpExecutor.Result) -> Unit
    ): HttpExecutor.Result {
        if (macro.type != ItemType.MACRO) {
            return HttpExecutor.Result(false, -1, "not a macro")
        }
        val byId = device.items.associateBy { it.id }
        val log = StringBuilder()
        var ok = true
        for ((idx, step) in macro.macroSteps.withIndex()) {
            val target = byId[step.targetItemId]
            if (target == null) {
                val r = HttpExecutor.Result(false, -1, "step $idx: target removed")
                ok = false
                log.append("· #$idx: 항목 없음\n")
                onStep(macro, r)
                continue
            }
            val r = dispatch(target, step.value)
            if (!r.success) ok = false
            log.append("· #$idx ${target.name}: [${r.code}]\n")
            onStep(target, r)
            if (step.delayMsAfter > 0) delay(step.delayMsAfter)
        }
        return HttpExecutor.Result(ok, if (ok) 200 else -1, log.toString().trimEnd())
    }

    private suspend fun dispatch(target: DeviceItem, value: String): HttpExecutor.Result {
        // STATUS_QUERY isn't a one-shot — running it inside a macro would just trigger one HTTP poll
        // for HTTP/Modbus, but for WS/MQTT it's nonsensical. Allow HTTP/Modbus, reject the rest.
        return when (target.type) {
            ItemType.STATUS_QUERY -> when (target.protocol) {
                Protocol.HTTP -> HttpExecutor.execute(target, value.ifEmpty { null })
                Protocol.MODBUS -> ModbusExecutor.read(target)
                else -> HttpExecutor.Result(false, -1, "WS/MQTT 상태조회는 매크로 대상이 될 수 없습니다")
            }
            ItemType.INT_COMMAND -> when (target.protocol) {
                Protocol.MQTT -> MqttExecutor.publish(target, value)
                Protocol.MODBUS -> {
                    val n = value.trim().toIntOrNull() ?: 0
                    ModbusExecutor.write(target, n)
                }
                Protocol.HTTP -> HttpExecutor.execute(target, value)
                Protocol.WEBSOCKET -> HttpExecutor.Result(false, -1, "WS는 명령 대상이 아닙니다")
            }
            ItemType.STRING_COMMAND -> when (target.protocol) {
                Protocol.MQTT -> MqttExecutor.publish(target, value)
                Protocol.HTTP -> HttpExecutor.execute(target, value)
                Protocol.WEBSOCKET, Protocol.MODBUS ->
                    HttpExecutor.Result(false, -1, "지원되지 않는 프로토콜")
            }
            ItemType.MACRO -> HttpExecutor.Result(false, -1, "중첩 매크로는 지원되지 않습니다")
        }
    }
}
