package com.lunastratos.remotecontrol.net

import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster
import com.ghgande.j2mod.modbus.procimg.SimpleRegister
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.ModbusFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modbus TCP bridge. Reads back into HttpExecutor.Result so the UI rendering pipeline
 * (`[code] body`) keeps working unchanged. Code 200 = success, -1 = transport error,
 * everything else = passthrough exception code from the slave.
 */
object ModbusExecutor {

    suspend fun read(item: DeviceItem): HttpExecutor.Result =
        withContext(Dispatchers.IO) { runOp(item) { master -> doRead(master, item) } }

    suspend fun write(item: DeviceItem, value: Int): HttpExecutor.Result =
        withContext(Dispatchers.IO) { runOp(item) { master -> doWrite(master, item, value) } }

    private inline fun runOp(
        item: DeviceItem,
        op: (ModbusTCPMaster) -> String
    ): HttpExecutor.Result {
        val (host, port) = parseHostPort(item.modbusHost)
        val master = ModbusTCPMaster(host, port)
        return try {
            master.timeout = 5_000
            master.connect()
            HttpExecutor.Result(true, 200, op(master))
        } catch (t: Throwable) {
            HttpExecutor.Result(false, -1, t.message ?: t::class.java.simpleName)
        } finally {
            runCatching { master.disconnect() }
        }
    }

    private fun doRead(master: ModbusTCPMaster, item: DeviceItem): String {
        val unit = item.modbusUnitId
        val addr = item.modbusAddress
        val count = item.modbusCount.coerceAtLeast(1)
        return when (item.modbusFunction) {
            ModbusFunction.READ_HOLDING_REGISTERS ->
                master.readMultipleRegisters(unit, addr, count)
                    .joinToString(",") { it.value.toString() }
            ModbusFunction.READ_INPUT_REGISTERS ->
                master.readInputRegisters(unit, addr, count)
                    .joinToString(",") { it.value.toString() }
            ModbusFunction.READ_COILS ->
                master.readCoils(unit, addr, count)
                    .let { bv -> (0 until count).joinToString(",") { i -> if (bv.getBit(i)) "1" else "0" } }
            ModbusFunction.READ_DISCRETE_INPUTS ->
                master.readInputDiscretes(unit, addr, count)
                    .let { bv -> (0 until count).joinToString(",") { i -> if (bv.getBit(i)) "1" else "0" } }
            ModbusFunction.WRITE_SINGLE_REGISTER, ModbusFunction.WRITE_SINGLE_COIL ->
                throw IllegalStateException("Write function code on a status query")
        }
    }

    private fun doWrite(master: ModbusTCPMaster, item: DeviceItem, value: Int): String {
        val unit = item.modbusUnitId
        val addr = item.modbusAddress
        return when (item.modbusFunction) {
            ModbusFunction.WRITE_SINGLE_REGISTER -> {
                master.writeSingleRegister(unit, addr, SimpleRegister(value))
                "wrote $value @ $addr"
            }
            ModbusFunction.WRITE_SINGLE_COIL -> {
                master.writeCoil(unit, addr, value != 0)
                "wrote ${value != 0} @ $addr"
            }
            // Allow writes even when the configured function is a read code, by falling back
            // to FC06. This makes INT_COMMAND usable without forcing the user to pick a write
            // FC explicitly when the dialog default is a read code.
            else -> {
                master.writeSingleRegister(unit, addr, SimpleRegister(value))
                "wrote $value @ $addr"
            }
        }
    }

    private fun parseHostPort(raw: String): Pair<String, Int> {
        val trimmed = raw.trim()
        val idx = trimmed.lastIndexOf(':')
        if (idx <= 0) return trimmed to 502
        val host = trimmed.substring(0, idx)
        val port = trimmed.substring(idx + 1).toIntOrNull() ?: 502
        return host to port
    }
}
