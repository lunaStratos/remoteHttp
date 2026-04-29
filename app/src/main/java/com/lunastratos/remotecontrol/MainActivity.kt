package com.lunastratos.remotecontrol

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lunastratos.remotecontrol.data.Device
import com.lunastratos.remotecontrol.data.DeviceRepository
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.databinding.ActivityMainBinding
import com.lunastratos.remotecontrol.databinding.DialogImportJsonBinding
import com.lunastratos.remotecontrol.net.HttpExecutor
import com.lunastratos.remotecontrol.ui.DeviceAdapter
import com.lunastratos.remotecontrol.ui.SimpleInputDialog
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: DeviceRepository
    private lateinit var adapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        repo = DeviceRepository.get(this)

        adapter = DeviceAdapter(
            onClick = { openDevice(it) },
            onMore = { device, view -> showDeviceMenu(device, view) }
        )
        binding.devicesList.layoutManager = LinearLayoutManager(this)
        binding.devicesList.adapter = adapter

        binding.fabAdd.setOnClickListener { promptCreateDevice() }
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
                        true
                    }
                    getString(R.string.delete) -> {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(device.name)
                            .setMessage(R.string.delete)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                repo.deleteDevice(device.id)
                                refresh()
                            }
                            .show()
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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> { exportJson(); true }
            R.id.action_import -> { promptImportJson(); true }
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

    private fun promptImportJson() {
        val view = DialogImportJsonBinding.inflate(layoutInflater)
        val savedUrl = Settings.get(this).importUrl
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

    private fun chooseImportMode(json: String) {
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
}
