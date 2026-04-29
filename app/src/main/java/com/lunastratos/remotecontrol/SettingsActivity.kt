package com.lunastratos.remotecontrol

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        settings = Settings.get(this)

        binding.inputImportUrl.setText(settings.importUrl)
        binding.switchShowLogs.isChecked = settings.showLogs
        binding.switchHideUrl.isChecked = settings.hideUrl
    }

    override fun onPause() {
        super.onPause()
        // Persist whatever the user typed/toggled. Cheaper than per-keystroke writes
        // and there's no way to leave this screen without going through onPause.
        settings.importUrl = binding.inputImportUrl.text?.toString()?.trim().orEmpty()
        settings.showLogs = binding.switchShowLogs.isChecked
        settings.hideUrl = binding.switchHideUrl.isChecked
    }
}
