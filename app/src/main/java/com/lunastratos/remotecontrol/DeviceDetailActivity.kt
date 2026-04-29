package com.lunastratos.remotecontrol

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lunastratos.remotecontrol.data.Device
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.DeviceRepository
import com.lunastratos.remotecontrol.data.ItemType
import com.lunastratos.remotecontrol.data.Protocol
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.databinding.ActivityDeviceDetailBinding
import com.lunastratos.remotecontrol.net.HttpExecutor
import com.lunastratos.remotecontrol.net.WsExecutor
import com.lunastratos.remotecontrol.util.JsonPathUtil
import com.lunastratos.remotecontrol.ui.DeviceItemAdapter
import com.lunastratos.remotecontrol.ui.ItemEditDialog
import com.lunastratos.remotecontrol.ui.SimpleInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket

class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDetailBinding
    private lateinit var repo: DeviceRepository
    private lateinit var device: Device
    private lateinit var adapter: DeviceItemAdapter

    private val pollingJobs = mutableMapOf<String, Job>()
    private val webSockets = mutableMapOf<String, WebSocket>()
    // itemId → ordered map of rule label → latest extracted value, used for multi-line render.
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
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val loaded = deviceId?.let { repo.get(it) }
        if (loaded == null) {
            finish()
            return
        }
        device = loaded
        // setSupportActionBar takes ownership of the title, so route through the support
        // action bar (or Activity.title) — direct toolbar.title gets overridden.
        supportActionBar?.title = device.name

        adapter = DeviceItemAdapter(
            onRun = { runItem(it) },
            onEdit = { editItem(it) },
            onDelete = { confirmDelete(it) },
            onIntStep = { item, sign -> stepIntCommand(item, sign) },
            onPresetClick = { item, preset -> executeOnce(item, preset.value) }
        )
        binding.itemsList.layoutManager = LinearLayoutManager(this)
        binding.itemsList.adapter = adapter

        binding.btnAddStatus.setOnClickListener { showItemEditor(ItemType.STATUS_QUERY, null) }
        binding.btnAddInt.setOnClickListener { showItemEditor(ItemType.INT_COMMAND, null) }
        binding.btnAddString.setOnClickListener { showItemEditor(ItemType.STRING_COMMAND, null) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Reload from repo in case device was edited elsewhere
        repo.get(device.id)?.let {
            device = it
            refresh()
            startStatusPolling()
        }
        // Settings may have been toggled in SettingsActivity while we were paused.
        // Drop any stale `[ws ...]` lines if logs are now off.
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
        ItemEditDialog.show(this, type, existing) { updated ->
            repo.upsertItem(device.id, updated)
            device = repo.get(device.id) ?: device
            refresh()
            restartPollingFor(updated)
        }
    }

    private fun editItem(item: DeviceItem) = showItemEditor(item.type, item)

    private fun confirmDelete(item: DeviceItem) {
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(R.string.delete)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                pollingJobs.remove(item.id)?.cancel()
                webSockets.remove(item.id)?.cancel()
                wsValues.remove(item.id)
                repo.deleteItem(device.id, item.id)
                device = repo.get(device.id) ?: device
                refresh()
            }
            .show()
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
                        Toast.makeText(this, "최소값 $min 이상", Toast.LENGTH_SHORT).show()
                        return@showText
                    }
                    if (max != null && n > max) {
                        Toast.makeText(this, "최대값 $max 이하", Toast.LENGTH_SHORT).show()
                        return@showText
                    }
                    executeOnce(item, n.toString())
                }
            }
            ItemType.STRING_COMMAND -> promptStringValue(item)
        }
    }

    private fun promptStringValue(item: DeviceItem) {
        // Inline preset chips on the card already cover quick selection. The ▶ button
        // is now a free-form input fallback for ad-hoc values.
        promptStringInput(item)
    }

    private fun promptStringInput(item: DeviceItem) {
        SimpleInputDialog.showText(
            context = this,
            title = item.name,
            hint = getString(R.string.enter_string_value)
        ) { raw ->
            executeOnce(item, raw)
        }
    }

    /** Per-INT_COMMAND running value, used as the base for +/- steps. */
    private val lastIntValues = mutableMapOf<String, Int>()

    private fun stepIntCommand(item: DeviceItem, sign: Int) {
        val step = item.intStep.takeIf { it != 0 } ?: 1
        val start = lastIntValues[item.id] ?: item.intMin ?: 0
        var next = start + sign * step
        item.intMin?.let { if (next < it) next = it }
        item.intMax?.let { if (next > it) next = it }
        if (next == lastIntValues[item.id]) {
            // Hit a clamp boundary; surface a brief hint and skip the request.
            Toast.makeText(
                this,
                if (sign < 0) "최소값입니다" else "최대값입니다",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        lastIntValues[item.id] = next
        executeOnce(item, next.toString())
    }

    /**
     * Re-render WS items from the rule cache so a stale log line ([ws connected] etc.)
     * doesn't linger after the user toggles logs off elsewhere. Items with no cached
     * rule data simply have their result cleared until the next message arrives.
     */
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
            val r = HttpExecutor.execute(item, substitution)
            item.lastResult = "[${r.code}] ${r.body.take(500)}"
            adapter.update(item)
        }
    }

    private fun startStatusPolling() {
        stopStatusPolling()
        for (item in device.items) {
            if (item.type == ItemType.STATUS_QUERY) startStatusFor(item)
        }
    }

    private fun stopStatusPolling() {
        pollingJobs.values.forEach { it.cancel() }
        pollingJobs.clear()
        webSockets.values.forEach { it.cancel() }
        webSockets.clear()
        wsValues.clear()
    }

    private fun restartPollingFor(item: DeviceItem) {
        pollingJobs.remove(item.id)?.cancel()
        webSockets.remove(item.id)?.cancel()
        wsValues.remove(item.id)
        if (item.type == ItemType.STATUS_QUERY) startStatusFor(item)
    }

    private fun startStatusFor(item: DeviceItem) {
        when (item.protocol) {
            Protocol.HTTP -> startPollingFor(item)
            Protocol.WEBSOCKET -> startWebSocketFor(item)
        }
    }

    private fun startPollingFor(item: DeviceItem) {
        val job = lifecycleScope.launch {
            while (true) {
                val r = HttpExecutor.execute(item, null)
                item.lastResult = "[${r.code}] ${r.body.take(500)}"
                adapter.update(item)
                delay(item.intervalMs.coerceAtLeast(500))
            }
        }
        pollingJobs[item.id] = job
    }

    private fun startWebSocketFor(item: DeviceItem) {
        val ws = WsExecutor.connect(item, object : WsExecutor.Callback {
            override fun onMessage(text: String) {
                formatWsMessage(item, text)?.let { postResult(item, it) }
            }
            override fun onStatus(message: String) {
                // Connect/close/error lines are diagnostic only — keep them out of the
                // result card unless the user explicitly opts in to logs.
                if (settings.showLogs) postResult(item, message)
            }
        })
        webSockets[item.id] = ws
    }

    /**
     * Returns the text to display for [raw], or null to skip the message entirely.
     * - rules non-empty: try each rule, accumulate `label: value` lines; null if no rule matched
     * - else empty responsePath: show raw payload (truncated)
     * - else responsePath set + match: show extracted value
     * - else responsePath set + miss: skip
     */
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
            item.lastResult = text
            adapter.update(item)
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
