package com.lunastratos.remotecontrol

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lunastratos.remotecontrol.data.Device
import com.lunastratos.remotecontrol.data.DeviceRepository
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.databinding.ActivityMainBinding
import com.lunastratos.remotecontrol.databinding.DialogImportJsonBinding
import com.lunastratos.remotecontrol.net.HttpExecutor
import com.lunastratos.remotecontrol.ui.DeviceAdapter
import com.lunastratos.remotecontrol.ui.QrShare
import com.lunastratos.remotecontrol.ui.SimpleInputDialog
import com.lunastratos.remotecontrol.work.AutoBackupWorker
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: DeviceRepository
    private lateinit var adapter: DeviceAdapter
    private lateinit var settings: Settings
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result?.contents ?: return@registerForActivityResult
        // Treat the scan as either a URL (fetch + import) or raw JSON (import directly).
        if (text.startsWith("http://") || text.startsWith("https://")) {
            settings.importUrl = text
            fetchAndImport(text)
        } else {
            chooseImportMode(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        repo = DeviceRepository.get(this)
        settings = Settings.get(this)
        HttpExecutor.bindSettings(settings)

        adapter = DeviceAdapter(
            onClick = { openDevice(it) },
            onMore = { device, view -> showDeviceMenu(device, view) },
            onDragStart = { vh -> if (!settings.isLocked) itemTouchHelper.startDrag(vh) }
        )
        binding.devicesList.layoutManager = LinearLayoutManager(this)
        binding.devicesList.adapter = adapter

        itemTouchHelper = ItemTouchHelper(reorderCallback)
        itemTouchHelper.attachToRecyclerView(binding.devicesList)

        binding.fabAdd.setOnClickListener { gateLock { promptCreateDevice() } }

        // Schedule auto-backup according to current settings; cheap no-op when disabled.
        AutoBackupWorker.reschedule(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = repo.all()
        adapter.submit(list)
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun promptCreateDevice() {
        SimpleInputDialog.showText(
            context = this,
            title = getString(R.string.add_device),
            hint = getString(R.string.enter_device_name)
        ) { name ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) {
                repo.addDevice(trimmed)
                refresh()
            }
        }
    }

    private fun showDeviceMenu(device: Device, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.rename)
            menu.add(R.string.delete)
            setOnMenuItemClickListener { mi ->
                when (mi.title) {
                    getString(R.string.rename) -> {
                        gateLock {
                            SimpleInputDialog.showText(
                                context = this@MainActivity,
                                title = getString(R.string.rename),
                                prefill = device.name
                            ) { newName ->
                                val trimmed = newName.trim()
                                if (trimmed.isNotEmpty()) {
                                    device.name = trimmed
                                    repo.updateDevice(device)
                                    refresh()
                                }
                            }
                        }
                        true
                    }
                    getString(R.string.delete) -> {
                        gateLock {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(device.name)
                                .setMessage(R.string.delete)
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.delete) { _, _ ->
                                    repo.deleteDevice(device.id)
                                    refresh()
                                }
                                .show()
                        }
                        true
                    }
                    else -> false
                }
            }
        }.show()
    }

    private fun openDevice(device: Device) {
        startActivity(Intent(this, DeviceDetailActivity::class.java).apply {
            putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID, device.id)
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
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
        // Lock toggle title flips with state so users see what tapping does.
        menu.findItem(R.id.action_lock_toggle).title = getString(
            if (settings.isLocked) R.string.read_only_engaged else R.string.settings_section_lock
        )
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_lock_toggle).title = getString(
            if (settings.isLocked) R.string.read_only_engaged else R.string.settings_section_lock
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> { exportJson(); true }
            R.id.action_import -> { promptImportJson(); true }
            R.id.action_qr_share -> { showQrShare(); true }
            R.id.action_qr_scan -> {
                qrScanLauncher.launch(ScanOptions().apply {
                    setPrompt(getString(R.string.qr_scan_prompt))
                    setOrientationLocked(false)
                    setBeepEnabled(false)
                })
                true
            }
            R.id.action_lock_toggle -> { toggleLock(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportJson() {
        val json = repo.exportJson()
        val view = layoutInflater.inflate(R.layout.dialog_multiline_input, null)
        val input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input)
        input.setText(json)
        AlertDialog.Builder(this)
            .setTitle(R.string.export_json)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.export_json) { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_TEXT, json)
                    putExtra(Intent.EXTRA_TITLE, "remotecontrol-devices.json")
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.export_json)))
            }
            .show()
    }

    private fun showQrShare() {
        // QR caps out around ~2.9KB even at low EC; full device JSON exceeds this for any
        // realistic install. Encode the user's stored import URL when present (so a peer can
        // scan to fetch). Otherwise prompt them to paste a URL.
        val url = settings.importUrl
        if (url.isBlank()) {
            SimpleInputDialog.showText(
                context = this,
                title = getString(R.string.qr_share),
                hint = getString(R.string.import_url_hint)
            ) { typed ->
                val trimmed = typed.trim()
                if (trimmed.isNotEmpty()) {
                    settings.importUrl = trimmed
                    QrShare.show(this, trimmed)
                }
            }
        } else {
            QrShare.show(this, url)
        }
    }

    private fun promptImportJson() {
        val view = DialogImportJsonBinding.inflate(layoutInflater)
        val savedUrl = settings.importUrl
        if (savedUrl.isNotBlank()) view.inputUrl.setText(savedUrl)

        view.btnFetch.setOnClickListener {
            val url = view.inputUrl.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) return@setOnClickListener
            view.btnFetch.isEnabled = false
            view.btnFetch.text = getString(R.string.polling)
            lifecycleScope.launch {
                val r = HttpExecutor.fetchText(url)
                view.btnFetch.isEnabled = true
                view.btnFetch.text = getString(R.string.fetch_from_url)
                if (r.success) {
                    view.inputJson.setText(r.body)
                    settings.importUrl = url
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "${getString(R.string.fetch_failed)} [${r.code}] ${r.body.take(120)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.import_json)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val trimmed = view.inputJson.text?.toString()?.trim().orEmpty()
                if (trimmed.isEmpty()) return@setPositiveButton
                chooseImportMode(trimmed)
            }
            .show()
    }

    private fun fetchAndImport(url: String) {
        lifecycleScope.launch {
            val r = HttpExecutor.fetchText(url)
            if (!r.success) {
                Toast.makeText(this@MainActivity, R.string.fetch_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            chooseImportMode(r.body)
        }
    }

    private fun chooseImportMode(json: String) = gateLock {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_json)
            .setItems(
                arrayOf(getString(R.string.import_replace), getString(R.string.import_merge))
            ) { _, which ->
                val merge = which == 1
                val n = repo.importJson(json, merge = merge)
                if (n < 0) {
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.imported_count, n),
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
            }
            .show()
    }

    private fun toggleLock() {
        if (settings.readOnlyPin.isBlank()) {
            Toast.makeText(this, R.string.settings, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (settings.isLocked) {
            SimpleInputDialog.showText(
                context = this,
                title = getString(R.string.enter_pin),
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            ) { pin ->
                if (settings.unlock(pin)) {
                    invalidateOptionsMenu()
                } else {
                    Toast.makeText(this, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            settings.readOnlyEngaged = true
            invalidateOptionsMenu()
        }
    }

    private fun gateLock(block: () -> Unit) {
        if (!settings.isLocked) { block(); return }
        SimpleInputDialog.showText(
            context = this,
            title = getString(R.string.enter_pin),
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) { pin ->
            if (settings.unlock(pin)) { block(); invalidateOptionsMenu() }
            else Toast.makeText(this, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
        }
    }

    private val reorderCallback = object : ItemTouchHelper.Callback() {
        override fun isItemViewSwipeEnabled() = false
        override fun isLongPressDragEnabled() = false
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            val src = repo.all()
            val fromItem = adapter.rawAt(from) ?: return false
            val toItem = adapter.rawAt(to) ?: return false
            val srcFrom = src.indexOfFirst { it.id == fromItem.id }
            val srcTo = src.indexOfFirst { it.id == toItem.id }
            if (srcFrom < 0 || srcTo < 0) return false
            repo.reorderDevices(srcFrom, srcTo)
            refresh()
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    }
}
