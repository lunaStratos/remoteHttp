package com.lunastratos.remotecontrol.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lunastratos.remotecontrol.R
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.DeviceRepository
import com.lunastratos.remotecontrol.databinding.ActivityWidgetConfigureBinding

class WidgetConfigureActivity : AppCompatActivity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val binding = ActivityWidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = DeviceRepository.get(this)
        // Flatten devices → items so the picker shows "Device · ItemName".
        val flat = mutableListOf<Pair<String, DeviceItem>>()
        for (d in repo.all()) {
            for (it in d.items) flat += d.name to it
        }
        if (flat.isEmpty()) {
            Toast.makeText(this, R.string.widget_no_items, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val labels = flat.map { (deviceName, item) -> "$deviceName · ${item.name}" }
        binding.itemPicker.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, labels
        )
        binding.itemPicker.setOnItemClickListener { _, _, pos, _ ->
            val item = flat[pos].second
            WidgetPrefs.setItem(this, widgetId, item.id)
            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            setResult(Activity.RESULT_OK, resultValue)
            RemoteWidgetProvider.refreshAll(applicationContext)
            finish()
        }
    }
}
