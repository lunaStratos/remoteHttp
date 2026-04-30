package com.lunastratos.remotecontrol

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.lunastratos.remotecontrol.data.ConnectionState
import com.lunastratos.remotecontrol.data.Device
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.DeviceRepository
import com.lunastratos.remotecontrol.data.ItemType
import com.lunastratos.remotecontrol.data.Protocol
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.databinding.ActivityDeviceDetailBinding
import com.lunastratos.remotecontrol.net.HttpExecutor
import com.lunastratos.remotecontrol.net.MacroExecutor
import com.lunastratos.remotecontrol.net.ModbusExecutor
import com.lunastratos.remotecontrol.net.MqttExecutor
import com.lunastratos.remotecontrol.net.WsExecutor
import com.lunastratos.remotecontrol.ui.DeviceItemAdapter
import com.lunastratos.remotecontrol.ui.ItemEditDialog
import com.lunastratos.remotecontrol.ui.SimpleInputDialog
import com.lunastratos.remotecontrol.util.JsonPathUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.MqttAsyncClient

class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDetailBinding
    private lateinit var repo: DeviceRepository
    private lateinit var device: Device
    private lateinit var adapter: DeviceItemAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val pollingJobs = mutableMapOf<String, Job>()
    private val wsHandles = mutableMapOf<String, WsExecutor.Handle>()
    private val mqttClients = mutableMapOf<String, MqttAsyncClient>()
    private val wsValues = mutableMapOf<String, LinkedHashMap<String, String>>()
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        repo = DeviceRepository.get(this)
        settings = Settings.get(this)
        HttpExecutor.bindSettings(settings)
        WsExecutor.bindSettings(settings)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val loaded = deviceId?.let { repo.get(it) }
        if (loaded == null) {
            finish()
            return
        }
        device = loaded
        supportActionBar?.title = device.name

        adapter = DeviceItemAdapter(
            onRun = { runItem(it) },
            onEdit = { editItem(it) },
            onDelete = { confirmDelete(it) },
            onIntStep = { item, sign -> stepIntCommand(item, sign) },
            onPresetClick = { item, preset -> executeOnce(item, preset.value) },
            onPauseToggle = { togglePolling(it) },
            onFavoriteToggle = { toggleFavorite(it) },
            onDuplicate = { duplicateItem(it) },
            onDragStart = { vh -> if (!settings.isLocked) itemTouchHelper.startDrag(vh) }
        )
        binding.itemsList.layoutManager = LinearLayoutManager(this)
        binding.itemsList.adapter = adapter
        (binding.itemsList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        itemTouchHelper = ItemTouchHelper(reorderCallback)
        itemTouchHelper.attachToRecyclerView(binding.itemsList)

        binding.btnAddStatus.setOnClickListener { gateLock { showItemEditor(ItemType.STATUS_QUERY, null) } }
        binding.btnAddInt.setOnClickListener { gateLock { showItemEditor(ItemType.INT_COMMAND, null) } }
        binding.btnAddString.setOnClickListener { gateLock { showItemEditor(ItemType.STRING_COMMAND, null) } }
        binding.btnAddMacro.setOnClickListener { gateLock { showItemEditor(ItemType.MACRO, null) } }

        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as? SearchView
        searchView?.queryHint = getString(R.string.search_hint)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.setQuery(newText.orEmpty())
                return true
            }
        })
        return true
    }

    override fun onResume() {
        super.onResume()
        repo.get(device.id)?.let {
            device = it
            refresh()
            startStatusPolling()
        }
        if (!settings.showLogs) restoreCachedWsResults()
    }

    override fun onPause() {
        super.onPause()
        stopStatusPolling()
    }

    private fun refresh() {
        adapter.submit(device.items.toList())
        binding.emptyText.visibility =
            if (device.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showItemEditor(type: ItemType, existing: DeviceItem?) {
        // Macro targets exclude the macro itself + other macros (no nesting).
        val macroTargets = device.items.filter {
            it.type != ItemType.MACRO && it.id != existing?.id
        }
        ItemEditDialog.show(this, type, existing, macroTargets) { updated ->
            repo.upsertItem(device.id, updated)
            device = repo.get(device.id) ?: device
            refresh()
            restartPollingFor(updated)
        }
    }

    private fun editItem(item: DeviceItem) = gateLock { showItemEditor(item.type, item) }

    private fun confirmDelete(item: DeviceItem) = gateLock {
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(R.string.delete)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                stopFor(item.id)
                wsValues.remove(item.id)
                repo.deleteItem(device.id, item.id)
                device = repo.get(device.id) ?: device
                refresh()
            }
            .show()
    }

    private fun duplicateItem(item: DeviceItem) = gateLock {
        repo.duplicateItem(device.id, item.id)
        device = repo.get(device.id) ?: device
        refresh()
    }

    private fun toggleFavorite(item: DeviceItem) {
        item.favorite = !item.favorite
        repo.upsertItem(device.id, item)
        device = repo.get(device.id) ?: device
        refresh()
    }

    private fun runItem(item: DeviceItem) {
        when (item.type) {
            ItemType.STATUS_QUERY -> {
                if (item.protocol == Protocol.WEBSOCKET) {
                    restartPollingFor(item)
                } else {
                    executeOnce(item, null)
                }
            }
            ItemType.INT_COMMAND -> {
                SimpleInputDialog.showText(
                    context = this,
                    title = item.name,
                    hint = getString(R.string.enter_int_value),
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                ) { raw ->
                    val n = raw.trim().toIntOrNull() ?: return@showText
                    val min = item.intMin
                    val max = item.intMax
                    if (min != null && n < min) {
                        Toast.makeText(this, getString(R.string.warn_int_min, min), Toast.LENGTH_SHORT).show()
                        return@showText
                    }
                    if (max != null && n > max) {
                        Toast.makeText(this, getString(R.string.warn_int_max, max), Toast.LENGTH_SHORT).show()
                        return@showText
                    }
                    executeOnce(item, n.toString())
                }
            }
            ItemType.STRING_COMMAND -> promptStringValue(item)
            ItemType.MACRO -> runMacro(item)
        }
    }

    private fun runMacro(item: DeviceItem) {
        item.lastResult = getString(R.string.polling)
        adapter.update(item)
        lifecycleScope.launch {
            val r = MacroExecutor.run(item, device) { target, stepResult ->
                target.lastResult = formatHttpResult(stepResult.code, stepResult.body)
                target.lastResultAt = System.currentTimeMillis()
                adapter.update(target)
            }
            recordResult(item, formatHttpResult(r.code, r.body))
        }
    }

    private fun promptStringValue(item: DeviceItem) {
        SimpleInputDialog.showText(
            context = this,
            title = item.name,
            hint = getString(R.string.enter_string_value)
        ) { raw -> executeOnce(item, raw) }
    }

    private val lastIntValues = mutableMapOf<String, Int>()

    private fun stepIntCommand(item: DeviceItem, sign: Int) {
        val step = item.intStep.takeIf { it != 0 } ?: 1
        val start = lastIntValues[item.id] ?: item.intMin ?: 0
        var next = start + sign * step
        item.intMin?.let { if (next < it) next = it }
        item.intMax?.let { if (next > it) next = it }
        if (next == lastIntValues[item.id]) {
            Toast.makeText(
                this,
                getString(if (sign < 0) R.string.warn_at_min else R.string.warn_at_max),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        lastIntValues[item.id] = next
        executeOnce(item, next.toString())
    }

    private fun restoreCachedWsResults() {
        for (it in device.items) {
            if (it.type != ItemType.STATUS_QUERY || it.protocol != Protocol.WEBSOCKET) continue
            val cache = wsValues[it.id]
            it.lastResult = if (cache.isNullOrEmpty()) {
                null
            } else {
                cache.entries.joinToString("\n") { e -> "${e.key}: ${e.value}" }
            }
            adapter.update(it)
        }
    }

    private fun executeOnce(item: DeviceItem, substitution: String?) {
        item.lastResult = getString(R.string.polling)
        adapter.update(item)
        lifecycleScope.launch {
            val r = when (item.protocol) {
                Protocol.MQTT -> MqttExecutor.publish(item, substitution)
                Protocol.MODBUS -> {
                    val intValue = substitution?.trim()?.toIntOrNull() ?: 0
                    ModbusExecutor.write(item, intValue)
                }
                Protocol.HTTP -> HttpExecutor.execute(item, substitution)
                Protocol.WEBSOCKET -> {
                    // WS commands aren't supported — the dialog disables this combo, but if a
                    // legacy item slips through we surface a clear error rather than masquerading
                    // as an HTTP request.
                    HttpExecutor.Result(false, -1, getString(R.string.protocol_invalid))
                }
            }
            recordResult(item, formatHttpResult(r.code, r.body))
        }
    }

    private fun formatHttpResult(code: Int, body: String): String =
        if (settings.showLogs) "[$code] ${body.take(500)}" else "[$code]"

    private fun recordResult(item: DeviceItem, text: String) {
        item.lastResult = text
        item.lastResultAt = System.currentTimeMillis()
        adapter.update(item)
    }

    private fun startStatusPolling() {
        stopStatusPolling()
        for (item in device.items) {
            if (item.type == ItemType.STATUS_QUERY && !item.pollingPaused) startStatusFor(item)
        }
    }

    private fun stopStatusPolling() {
        pollingJobs.values.forEach { it.cancel() }
        pollingJobs.clear()
        wsHandles.values.forEach { it.cancel() }
        wsHandles.clear()
        mqttClients.values.forEach { runCatching { it.disconnectForcibly() } }
        mqttClients.clear()
        wsValues.clear()
    }

    private fun restartPollingFor(item: DeviceItem) {
        stopFor(item.id)
        wsValues.remove(item.id)
        if (item.type == ItemType.STATUS_QUERY && !item.pollingPaused) startStatusFor(item)
    }

    private fun togglePolling(item: DeviceItem) {
        if (item.type != ItemType.STATUS_QUERY) return
        item.pollingPaused = !item.pollingPaused
        if (item.pollingPaused) {
            stopFor(item.id)
        } else {
            startStatusFor(item)
        }
        adapter.update(item)
    }

    private fun stopFor(itemId: String) {
        pollingJobs.remove(itemId)?.cancel()
        wsHandles.remove(itemId)?.cancel()
        mqttClients.remove(itemId)?.let { runCatching { it.disconnectForcibly() } }
    }

    private fun startStatusFor(item: DeviceItem) {
        when (item.protocol) {
            Protocol.HTTP -> startPollingFor(item)
            Protocol.WEBSOCKET -> startWebSocketFor(item)
            Protocol.MQTT -> startMqttFor(item)
            Protocol.MODBUS -> startModbusPollingFor(item)
        }
    }

    private fun startPollingFor(item: DeviceItem) {
        val job = lifecycleScope.launch {
            while (true) {
                val r = HttpExecutor.execute(item, null)
                recordResult(item, formatHttpResult(r.code, r.body))
                delay(item.intervalMs.coerceAtLeast(500))
            }
        }
        pollingJobs[item.id] = job
    }

    private fun startWebSocketFor(item: DeviceItem) {
        val handle = WsExecutor.connect(item, object : WsExecutor.Callback {
            override fun onMessage(text: String) {
                formatWsMessage(item, text)?.let { postResult(item, it) }
            }
            override fun onStatus(message: String) {
                if (settings.showLogs) postResult(item, message)
            }
            override fun onState(state: ConnectionState) {
                lifecycleScope.launch(Dispatchers.Main) {
                    item.connectionState = state
                    adapter.update(item)
                }
            }
        })
        wsHandles[item.id] = handle
    }

    private fun startMqttFor(item: DeviceItem) {
        val client = MqttExecutor.connect(item, object : MqttExecutor.Callback {
            override fun onMessage(text: String) {
                formatWsMessage(item, text)?.let { postResult(item, it) }
            }
            override fun onStatus(message: String) {
                if (settings.showLogs) postResult(item, message)
            }
            override fun onState(state: ConnectionState) {
                lifecycleScope.launch(Dispatchers.Main) {
                    item.connectionState = state
                    adapter.update(item)
                }
            }
        })
        mqttClients[item.id] = client
    }

    private fun startModbusPollingFor(item: DeviceItem) {
        val job = lifecycleScope.launch {
            while (true) {
                val r = ModbusExecutor.read(item)
                recordResult(item, formatHttpResult(r.code, r.body))
                delay(item.intervalMs.coerceAtLeast(500))
            }
        }
        pollingJobs[item.id] = job
    }

    private fun formatWsMessage(item: DeviceItem, raw: String): String? {
        if (item.wsRules.isNotEmpty()) return applyWsRules(item, raw)
        val path = item.responsePath
        if (path.isBlank()) return raw.take(500)
        return JsonPathUtil.extract(raw, path)
    }

    private fun applyWsRules(item: DeviceItem, raw: String): String? {
        val cache = wsValues.getOrPut(item.id) { LinkedHashMap() }
        var matched = false
        for (rule in item.wsRules) {
            val isMatch = if (rule.matchPath.isBlank()) {
                true
            } else {
                JsonPathUtil.extract(raw, rule.matchPath) == rule.matchValue
            }
            if (!isMatch) continue
            val value = if (rule.valuePath.isBlank()) {
                raw.take(200)
            } else {
                JsonPathUtil.extract(raw, rule.valuePath) ?: continue
            }
            val label = rule.label.ifBlank { rule.valuePath.ifBlank { "?" } }
            cache[label] = value
            matched = true
        }
        if (!matched) return null
        return cache.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private fun postResult(item: DeviceItem, text: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            recordResult(item, text)
        }
    }

    /**
     * Drag-reorder: the visible list is favorite-sorted, so we resolve drag indexes back
     * to the device's source order before persisting.
     */
    private val reorderCallback = object : ItemTouchHelper.Callback() {
        override fun isItemViewSwipeEnabled() = false
        override fun isLongPressDragEnabled() = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int = makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        )

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            val fromItem = adapter.rawAt(from) ?: return false
            val toItem = adapter.rawAt(to) ?: return false
            val srcFrom = device.items.indexOfFirst { it.id == fromItem.id }
            val srcTo = device.items.indexOfFirst { it.id == toItem.id }
            if (srcFrom < 0 || srcTo < 0) return false
            repo.reorderItems(device.id, srcFrom, srcTo)
            device = repo.get(device.id) ?: device
            refresh()
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    }

    /** Run [block] only when the lock is disengaged; otherwise prompt for the PIN. */
    private fun gateLock(block: () -> Unit) {
        if (!settings.isLocked) {
            block()
            return
        }
        SimpleInputDialog.showText(
            context = this,
            title = getString(R.string.enter_pin),
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) { pin ->
            if (settings.unlock(pin)) {
                block()
            } else {
                Toast.makeText(this, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
