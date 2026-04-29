package com.lunastratos.remotecontrol.data

import android.content.Context

class Settings private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** When false, WebSocket log lines (connect/close/error) are suppressed in the result card. */
    var showLogs: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LOGS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_LOGS, value).apply()
        }

    /** Default URL pre-filled into the JSON 가져오기 dialog. */
    var importUrl: String
        get() = prefs.getString(KEY_IMPORT_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_IMPORT_URL, value).apply()
        }

    /** When true, item cards on the device detail screen omit the method+URL subtitle. */
    var hideUrl: Boolean
        get() = prefs.getBoolean(KEY_HIDE_URL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDE_URL, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "remotecontrol.settings"
        private const val KEY_SHOW_LOGS = "show_logs"
        private const val KEY_IMPORT_URL = "import_url"
        private const val KEY_HIDE_URL = "hide_url"

        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context).also { instance = it }
            }
    }
}
