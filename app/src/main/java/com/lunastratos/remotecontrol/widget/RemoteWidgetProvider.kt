package com.lunastratos.remotecontrol.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lunastratos.remotecontrol.R
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.DeviceRepository
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.net.HttpExecutor
import com.lunastratos.remotecontrol.net.MacroExecutor
import com.lunastratos.remotecontrol.net.ModbusExecutor
import com.lunastratos.remotecontrol.net.MqttExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tap-to-run widget bound to a single [DeviceItem]. Configuration is kept in shared prefs
 * keyed by appWidgetId, so a long-press 위젯 추가 → configure flow installs durable shortcuts.
 *
 * Status queries kick off a one-shot read; commands send their default value (presets[0]
 * for STRING, intMin / 0 for INT). The widget is intentionally simple — full polling and
 * stepper UX live in the device detail screen.
 */
class RemoteWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) renderIdle(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_RUN) return
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (widgetId == -1) return
        val itemId = WidgetPrefs.itemIdFor(context, widgetId) ?: return
        val (device, item) = DeviceRepository.get(context).findItem(itemId) ?: return

        val mgr = AppWidgetManager.getInstance(context)
        renderRunning(context, mgr, widgetId, item)

        scope.launch {
            HttpExecutor.bindSettings(Settings.get(context))
            val r = when (item.type) {
                com.lunastratos.remotecontrol.data.ItemType.STATUS_QUERY -> when (item.protocol) {
                    com.lunastratos.remotecontrol.data.Protocol.HTTP ->
                        HttpExecutor.execute(item, null)
                    com.lunastratos.remotecontrol.data.Protocol.MODBUS ->
                        ModbusExecutor.read(item)
                    else ->
                        HttpExecutor.Result(false, -1, "WS/MQTT not supported on widget")
                }
                com.lunastratos.remotecontrol.data.ItemType.INT_COMMAND -> {
                    val v = (item.intMin ?: 0).toString()
                    when (item.protocol) {
                        com.lunastratos.remotecontrol.data.Protocol.MQTT ->
                            MqttExecutor.publish(item, v)
                        com.lunastratos.remotecontrol.data.Protocol.MODBUS ->
                            ModbusExecutor.write(item, item.intMin ?: 0)
                        else -> HttpExecutor.execute(item, v)
                    }
                }
                com.lunastratos.remotecontrol.data.ItemType.STRING_COMMAND -> {
                    val v = item.stringPresets.firstOrNull()?.value.orEmpty()
                    when (item.protocol) {
                        com.lunastratos.remotecontrol.data.Protocol.MQTT ->
                            MqttExecutor.publish(item, v)
                        else -> HttpExecutor.execute(item, v)
                    }
                }
                com.lunastratos.remotecontrol.data.ItemType.MACRO ->
                    MacroExecutor.run(item, device) { _, _ -> }
            }
            renderResult(context, mgr, widgetId, item, r)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetPrefs.clear(context, id)
    }

    private fun renderIdle(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
        val itemId = WidgetPrefs.itemIdFor(ctx, widgetId)
        val views = RemoteViews(ctx.packageName, R.layout.widget_remote)
        if (itemId == null) {
            views.setTextViewText(R.id.widgetTitle, ctx.getString(R.string.widget_label))
            views.setTextViewText(R.id.widgetStatus, ctx.getString(R.string.widget_no_items))
            mgr.updateAppWidget(widgetId, views)
            return
        }
        val item = DeviceRepository.get(ctx).findItem(itemId)?.second
        if (item == null) {
            views.setTextViewText(R.id.widgetTitle, ctx.getString(R.string.widget_item_missing))
            views.setTextViewText(R.id.widgetStatus, "")
            mgr.updateAppWidget(widgetId, views)
            return
        }
        views.setTextViewText(R.id.widgetTitle, item.name)
        views.setTextViewText(R.id.widgetStatus, "")
        views.setOnClickPendingIntent(
            R.id.widgetButton,
            buildRunIntent(ctx, widgetId)
        )
        mgr.updateAppWidget(widgetId, views)
    }

    private fun renderRunning(ctx: Context, mgr: AppWidgetManager, widgetId: Int, item: DeviceItem) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_remote)
        views.setTextViewText(R.id.widgetTitle, item.name)
        views.setTextViewText(R.id.widgetStatus, ctx.getString(R.string.polling))
        views.setOnClickPendingIntent(R.id.widgetButton, buildRunIntent(ctx, widgetId))
        mgr.updateAppWidget(widgetId, views)
    }

    private fun renderResult(
        ctx: Context,
        mgr: AppWidgetManager,
        widgetId: Int,
        item: DeviceItem,
        r: HttpExecutor.Result
    ) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val views = RemoteViews(ctx.packageName, R.layout.widget_remote)
        views.setTextViewText(R.id.widgetTitle, item.name)
        views.setTextViewText(R.id.widgetStatus, "[${r.code}] · $time")
        views.setOnClickPendingIntent(R.id.widgetButton, buildRunIntent(ctx, widgetId))
        mgr.updateAppWidget(widgetId, views)
    }

    private fun buildRunIntent(ctx: Context, widgetId: Int): PendingIntent {
        val intent = Intent(ctx, RemoteWidgetProvider::class.java).apply {
            action = ACTION_RUN
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            // Include widgetId in data so each widget has a unique PendingIntent.
            data = android.net.Uri.parse("rc://widget/$widgetId")
        }
        return PendingIntent.getBroadcast(
            ctx,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_RUN = "com.lunastratos.remotecontrol.widget.RUN"

        /** Forces a redraw of every widget — call after configure persists a selection. */
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RemoteWidgetProvider::class.java))
            val intent = Intent(ctx, RemoteWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            ctx.sendBroadcast(intent)
        }
    }
}
