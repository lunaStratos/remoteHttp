package com.lunastratos.remotecontrol

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.lunastratos.remotecontrol.data.Settings

class RemoteControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Settings.get(this).themeMode)
    }
}
