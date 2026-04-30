package com.lunastratos.remotecontrol.widget

import android.content.Context

/** Maps widget instance ids to the DeviceItem id they should run. */
object WidgetPrefs {
    private const val PREFS_NAME = "remotecontrol.widgets"

    fun itemIdFor(context: Context, widgetId: Int): String? =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(widgetId), null)

    fun setItem(context: Context, widgetId: Int, itemId: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(widgetId), itemId)
            .apply()
    }

    fun clear(context: Context, widgetId: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(widgetId))
            .apply()
    }

    private fun key(widgetId: Int) = "widget_$widgetId"
}
