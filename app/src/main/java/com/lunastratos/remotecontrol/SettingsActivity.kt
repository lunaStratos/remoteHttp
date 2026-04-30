package com.lunastratos.remotecontrol

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.databinding.ActivitySettingsBinding
import com.lunastratos.remotecontrol.work.AutoBackupWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        binding.switchInsecureTls.isChecked = settings.insecureTls
        binding.inputReadOnlyPin.setText(settings.readOnlyPin)
        binding.switchReadOnlyEngaged.isChecked = settings.isLocked
        binding.inputAutoBackupHours.setText(settings.autoBackupHours.toString())
        renderLastBackup()

        val themeRadioId = when (settings.themeMode) {
            Settings.THEME_LIGHT -> R.id.themeLight
            Settings.THEME_DARK -> R.id.themeDark
            else -> R.id.themeSystem
        }
        binding.themeGroup.check(themeRadioId)
        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.themeLight -> Settings.THEME_LIGHT
                R.id.themeDark -> Settings.THEME_DARK
                else -> Settings.THEME_SYSTEM
            }
            if (newMode != settings.themeMode) {
                settings.themeMode = newMode
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        settings.importUrl = binding.inputImportUrl.text?.toString()?.trim().orEmpty()
        settings.showLogs = binding.switchShowLogs.isChecked
        settings.hideUrl = binding.switchHideUrl.isChecked
        settings.insecureTls = binding.switchInsecureTls.isChecked

        val newPin = binding.inputReadOnlyPin.text?.toString()?.trim().orEmpty()
        if (newPin != settings.readOnlyPin) {
            settings.readOnlyPin = newPin
            // Disengage when the PIN is wiped — otherwise the user can't unlock again.
            if (newPin.isBlank()) settings.readOnlyEngaged = false
        }
        // The toggle directly drives engagement, but only matters when a PIN exists.
        settings.readOnlyEngaged = binding.switchReadOnlyEngaged.isChecked && settings.readOnlyPin.isNotBlank()

        val hours = binding.inputAutoBackupHours.text?.toString()?.toIntOrNull() ?: 0
        if (hours != settings.autoBackupHours) {
            settings.autoBackupHours = hours.coerceAtLeast(0)
            AutoBackupWorker.reschedule(applicationContext)
        }
    }

    private fun renderLastBackup() {
        val ts = settings.lastBackupAt
        binding.lastBackupText.text = if (ts == 0L) {
            getString(R.string.last_backup_never)
        } else {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            getString(R.string.last_backup, fmt.format(Date(ts)))
        }
    }
}
