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

    /**
     * Theme mode. Maps directly to AppCompatDelegate.MODE_NIGHT_*:
     * -1 = follow system (default), 1 = light, 2 = dark.
     */
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
        }

    /**
     * When true, OkHttp clients used by HTTP / WebSocket trust any TLS certificate.
     * For LAN devices with self-signed certs only — never enable in production.
     */
    var insecureTls: Boolean
        get() = prefs.getBoolean(KEY_INSECURE_TLS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INSECURE_TLS, value).apply()
        }

    /**
     * Read-only lock PIN. Empty = unlocked. When set, destructive actions (add/edit/delete,
     * macro authoring) require the PIN.
     */
    var readOnlyPin: String
        get() = prefs.getString(KEY_READ_ONLY_PIN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_READ_ONLY_PIN, value).apply()
        }

    /** When true, the read-only lock is currently engaged (independent of PIN existence). */
    var readOnlyEngaged: Boolean
        get() = prefs.getBoolean(KEY_READ_ONLY_ENGAGED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_READ_ONLY_ENGAGED, value).apply()
        }

    /** Periodic auto-backup interval in hours. 0 = disabled. */
    var autoBackupHours: Int
        get() = prefs.getInt(KEY_AUTO_BACKUP_HOURS, 0)
        set(value) {
            prefs.edit().putInt(KEY_AUTO_BACKUP_HOURS, value).apply()
        }

    /** Path of the most recent auto-backup file (or empty if never). */
    var lastBackupPath: String
        get() = prefs.getString(KEY_LAST_BACKUP_PATH, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LAST_BACKUP_PATH, value).apply()
        }

    /** Epoch ms of the last successful auto-backup. */
    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BACKUP_AT, value).apply()
        }

    /** Locks down read-only mode and clears the engaged flag if the PIN matches. */
    fun unlock(pin: String): Boolean {
        if (pin == readOnlyPin) {
            readOnlyEngaged = false
            return true
        }
        return false
    }

    /** Whether a destructive UI action should be blocked. */
    val isLocked: Boolean
        get() = readOnlyPin.isNotBlank() && readOnlyEngaged

    companion object {
        private const val PREFS_NAME = "remotecontrol.settings"
        private const val KEY_SHOW_LOGS = "show_logs"
        private const val KEY_IMPORT_URL = "import_url"
        private const val KEY_HIDE_URL = "hide_url"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_INSECURE_TLS = "insecure_tls"
        private const val KEY_READ_ONLY_PIN = "read_only_pin"
        private const val KEY_READ_ONLY_ENGAGED = "read_only_engaged"
        private const val KEY_AUTO_BACKUP_HOURS = "auto_backup_hours"
        private const val KEY_LAST_BACKUP_PATH = "last_backup_path"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"

        const val THEME_SYSTEM = -1
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context).also { instance = it }
            }
    }
}
