package com.lunastratos.remotecontrol.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

class DeviceRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        // Legacy stringPresets were a list of plain strings. Accept both forms so older
        // saved data and previously exported JSON imports keep loading.
        .registerTypeAdapter(StringPreset::class.java, StringPresetDeserializer)
        .create()

    private val devices: MutableList<Device> = load().toMutableList()

    fun all(): List<Device> = devices.toList()

    fun get(deviceId: String): Device? = devices.firstOrNull { it.id == deviceId }

    fun addDevice(name: String): Device {
        val d = Device(name = name)
        devices.add(d)
        persist()
        return d
    }

    fun updateDevice(device: Device) {
        val idx = devices.indexOfFirst { it.id == device.id }
        if (idx >= 0) {
            devices[idx] = device
            persist()
        }
    }

    fun deleteDevice(deviceId: String) {
        devices.removeAll { it.id == deviceId }
        persist()
    }

    fun upsertItem(deviceId: String, item: DeviceItem) {
        val device = get(deviceId) ?: return
        val idx = device.items.indexOfFirst { it.id == item.id }
        if (idx >= 0) device.items[idx] = item else device.items.add(item)
        persist()
    }

    fun deleteItem(deviceId: String, itemId: String) {
        val device = get(deviceId) ?: return
        device.items.removeAll { it.id == itemId }
        persist()
    }

    /** Returns a fresh copy of [itemId] with a new id, appended to the same device. */
    fun duplicateItem(deviceId: String, itemId: String): DeviceItem? {
        val device = get(deviceId) ?: return null
        val src = device.items.firstOrNull { it.id == itemId } ?: return null
        // Round-trip through gson to deep-copy mutable nested lists (headers, presets, …).
        val json = gson.toJson(src)
        val copy = gson.fromJson(json, DeviceItem::class.java).apply {
            // copy() can't override the val id; rebuild via reflection-free path.
        }
        // The data class id is `val`, so swap into a new instance preserving everything else.
        val cloned = DeviceItem(
            type = copy.type,
            name = copy.name + " (복사)",
            url = copy.url,
            method = copy.method,
            headers = copy.headers.toMutableList(),
            protocol = copy.protocol,
            intervalMs = copy.intervalMs,
            responsePath = copy.responsePath,
            wsRules = copy.wsRules.toMutableList(),
            bodyTemplate = copy.bodyTemplate,
            intMin = copy.intMin,
            intMax = copy.intMax,
            intStep = copy.intStep,
            stringPresets = copy.stringPresets.toMutableList(),
            macroSteps = copy.macroSteps.toMutableList(),
            unit = copy.unit,
            favorite = copy.favorite,
            authTokenUrl = copy.authTokenUrl,
            authBody = copy.authBody,
            authTokenPath = copy.authTokenPath,
            mqttBrokerUrl = copy.mqttBrokerUrl,
            mqttClientId = copy.mqttClientId,
            mqttTopic = copy.mqttTopic,
            mqttQos = copy.mqttQos,
            mqttUsername = copy.mqttUsername,
            mqttPassword = copy.mqttPassword,
            modbusHost = copy.modbusHost,
            modbusUnitId = copy.modbusUnitId,
            modbusFunction = copy.modbusFunction,
            modbusAddress = copy.modbusAddress,
            modbusCount = copy.modbusCount
        )
        device.items.add(cloned)
        persist()
        return cloned
    }

    fun reorderItems(deviceId: String, fromIdx: Int, toIdx: Int) {
        val device = get(deviceId) ?: return
        if (fromIdx !in device.items.indices || toIdx !in device.items.indices) return
        val moved = device.items.removeAt(fromIdx)
        device.items.add(toIdx, moved)
        persist()
    }

    fun reorderDevices(fromIdx: Int, toIdx: Int) {
        if (fromIdx !in devices.indices || toIdx !in devices.indices) return
        val moved = devices.removeAt(fromIdx)
        devices.add(toIdx, moved)
        persist()
    }

    fun findItem(itemId: String): Pair<Device, DeviceItem>? {
        for (d in devices) {
            val it = d.items.firstOrNull { i -> i.id == itemId } ?: continue
            return d to it
        }
        return null
    }

    /** Serialize the entire device list to JSON for backup/sharing. */
    fun exportJson(): String = gson.toJson(devices)

    /**
     * Restore devices from a JSON string previously produced by [exportJson].
     * Returns the number of devices imported, or -1 on parse failure.
     * If [merge] is true, devices with new ids are appended; existing ids are replaced.
     * If false, the existing list is wiped and replaced.
     */
    fun importJson(json: String, merge: Boolean = false): Int {
        return try {
            val type = object : TypeToken<List<Device>>() {}.type
            val incoming: List<Device> = gson.fromJson(json, type) ?: return -1
            if (!merge) {
                devices.clear()
                devices.addAll(incoming)
            } else {
                for (d in incoming) {
                    val idx = devices.indexOfFirst { it.id == d.id }
                    if (idx >= 0) devices[idx] = d else devices.add(d)
                }
            }
            persist()
            incoming.size
        } catch (t: Throwable) {
            -1
        }
    }

    private fun load(): List<Device> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Device>>() {}.type
            val list = gson.fromJson<List<Device>>(raw, type) ?: emptyList()
            list.forEach { d -> d.items.forEach { it.normalize() } }
            list
        } catch (t: Throwable) {
            emptyList()
        }
    }

    // Older saved JSON predates several string fields. Gson leaves those slots null
    // even though Kotlin's type system says they can't be — patch them back to "" so
    // downstream non-null reads (isBlank, lowercase, …) don't NPE.
    private fun DeviceItem.normalize() {
        name = denull(name)
        url = denull(url)
        responsePath = denull(responsePath)
        bodyTemplate = denull(bodyTemplate)
        unit = denull(unit)
        authTokenUrl = denull(authTokenUrl)
        authBody = denull(authBody)
        authTokenPath = denull(authTokenPath).ifEmpty { "access_token" }
        mqttBrokerUrl = denull(mqttBrokerUrl)
        mqttClientId = denull(mqttClientId)
        mqttTopic = denull(mqttTopic)
        mqttUsername = denull(mqttUsername)
        mqttPassword = denull(mqttPassword)
        modbusHost = denull(modbusHost)
    }

    private fun denull(s: String?): String = s ?: ""

    private fun persist() {
        prefs.edit().putString(KEY_DEVICES, gson.toJson(devices)).apply()
    }

    companion object {
        private const val PREFS_NAME = "remotecontrol.devices"
        private const val KEY_DEVICES = "devices_json"

        @Volatile private var instance: DeviceRepository? = null
        fun get(context: Context): DeviceRepository =
            instance ?: synchronized(this) {
                instance ?: DeviceRepository(context).also { instance = it }
            }
    }

    private object StringPresetDeserializer : JsonDeserializer<StringPreset> {
        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): StringPreset {
            // Legacy form: bare string — used as both label and value.
            if (json.isJsonPrimitive) {
                val s = json.asString
                return StringPreset(label = s, value = s)
            }
            val obj = json.asJsonObject
            return StringPreset(
                label = obj.get("label")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                value = obj.get("value")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            )
        }
    }
}
