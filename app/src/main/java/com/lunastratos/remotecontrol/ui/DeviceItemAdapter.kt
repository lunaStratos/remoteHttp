package com.lunastratos.remotecontrol.ui

import android.annotation.SuppressLint
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.lunastratos.remotecontrol.R
import com.lunastratos.remotecontrol.data.ConnectionState
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.ItemType
import com.lunastratos.remotecontrol.data.Protocol
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.data.StringPreset
import com.lunastratos.remotecontrol.databinding.ItemDeviceItemBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DeviceItemAdapter(
    private val onRun: (DeviceItem) -> Unit,
    private val onEdit: (DeviceItem) -> Unit,
    private val onDelete: (DeviceItem) -> Unit,
    private val onIntStep: (DeviceItem, Int) -> Unit,
    private val onPresetClick: (DeviceItem, StringPreset) -> Unit,
    private val onPauseToggle: (DeviceItem) -> Unit,
    private val onFavoriteToggle: (DeviceItem) -> Unit,
    private val onDuplicate: (DeviceItem) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<DeviceItemAdapter.VH>() {

    private val all = mutableListOf<DeviceItem>()
    private val visible = mutableListOf<DeviceItem>()
    private var query: String = ""

    /** Underlying list as displayed (after filter + favorite-first sort). */
    fun current(): List<DeviceItem> = visible.toList()

    fun rawAt(idx: Int): DeviceItem? = visible.getOrNull(idx)

    fun submit(list: List<DeviceItem>) {
        all.clear()
        all.addAll(list)
        applyFilter()
    }

    fun update(item: DeviceItem) {
        val srcIdx = all.indexOfFirst { it.id == item.id }
        if (srcIdx >= 0) all[srcIdx] = item
        val idx = visible.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            visible[idx] = item
            notifyItemChanged(idx)
        }
    }

    fun setQuery(q: String) {
        query = q.trim()
        applyFilter()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        visible.clear()
        val pool = if (query.isEmpty()) {
            all.toList()
        } else {
            val needle = query.lowercase()
            all.filter {
                it.name.orEmpty().lowercase().contains(needle) ||
                    it.url.orEmpty().lowercase().contains(needle) ||
                    it.unit.orEmpty().lowercase().contains(needle)
            }
        }
        // Favorites pinned to the top, otherwise preserve the device's stored order.
        visible.addAll(pool.sortedByDescending { it.favorite })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDeviceItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(visible[position])
    }

    override fun getItemCount(): Int = visible.size

    inner class VH(private val b: ItemDeviceItemBinding) : RecyclerView.ViewHolder(b.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: DeviceItem) {
            val ctx = b.root.context
            val typeLabel = when (item.type) {
                ItemType.STATUS_QUERY -> ctx.getString(R.string.type_label_status)
                ItemType.INT_COMMAND -> "🔢"
                ItemType.STRING_COMMAND -> "🔤"
                ItemType.MACRO -> "▶"
            }
            b.itemTitle.text = "[$typeLabel] ${item.name}"
            b.itemSubtitle.text = subtitleFor(item)

            val isCommand = item.type == ItemType.INT_COMMAND ||
                item.type == ItemType.STRING_COMMAND
            val shouldHide = isCommand && Settings.get(ctx).hideUrl
            b.itemSubtitle.visibility = if (shouldHide) View.GONE else View.VISIBLE

            renderResult(item)
            renderConnectionDot(item)

            // INT_COMMAND: stepper row sits *below* the edit/delete icons. The top-row Run
            // button is hidden so we don't show two run buttons at once.
            val isInt = item.type == ItemType.INT_COMMAND
            b.intActionRow.visibility = if (isInt) View.VISIBLE else View.GONE
            b.btnRun.visibility = if (isInt) View.GONE else View.VISIBLE

            // Pause toggle is only meaningful for STATUS_QUERY (HTTP polling / WS connection).
            val isStatus = item.type == ItemType.STATUS_QUERY
            b.btnPause.visibility = if (isStatus) View.VISIBLE else View.GONE
            if (isStatus) {
                b.btnPause.setImageResource(
                    if (item.pollingPaused) android.R.drawable.ic_media_play
                    else android.R.drawable.ic_media_pause
                )
                b.btnPause.contentDescription = ctx.getString(
                    if (item.pollingPaused) R.string.resume else R.string.pause
                )
            }

            b.btnFavorite.setImageResource(
                if (item.favorite) R.drawable.ic_star_filled else R.drawable.ic_star
            )
            b.btnFavorite.contentDescription = ctx.getString(
                if (item.favorite) R.string.favorite_off else R.string.favorite
            )

            // Read-only: keep destructive controls visible but disabled — feels less jumpy
            // than removing them, and the lock pin lifts in one place.
            val locked = Settings.get(ctx).isLocked
            b.btnEdit.isEnabled = !locked
            b.btnDelete.isEnabled = !locked
            b.btnDuplicate.isEnabled = !locked
            b.dragHandle.visibility = if (locked) View.GONE else View.VISIBLE

            renderPresetChips(item)

            b.btnRun.setOnClickListener { onRun(item) }
            b.btnRunInt.setOnClickListener { onRun(item) }
            b.btnEdit.setOnClickListener { onEdit(item) }
            b.btnDelete.setOnClickListener { onDelete(item) }
            b.btnPause.setOnClickListener { onPauseToggle(item) }
            b.btnFavorite.setOnClickListener { onFavoriteToggle(item) }
            b.btnDuplicate.setOnClickListener { onDuplicate(item) }
            b.btnDecrement.setOnClickListener { onIntStep(item, -1) }
            b.btnIncrement.setOnClickListener { onIntStep(item, +1) }

            b.dragHandle.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(this)
                false
            }
        }

        private fun subtitleFor(item: DeviceItem): String = when (item.protocol) {
            Protocol.HTTP, Protocol.WEBSOCKET -> "${item.method.name} ${item.url}"
            Protocol.MQTT -> "MQTT ${item.mqttBrokerUrl} · ${item.mqttTopic}"
            Protocol.MODBUS -> "Modbus ${item.modbusHost} #${item.modbusAddress}"
        }

        private fun renderConnectionDot(item: DeviceItem) {
            val isLive = item.type == ItemType.STATUS_QUERY &&
                (item.protocol == Protocol.WEBSOCKET || item.protocol == Protocol.MQTT)
            if (!isLive) {
                b.connDot.visibility = View.GONE
                return
            }
            b.connDot.visibility = View.VISIBLE
            val color = when (item.connectionState) {
                ConnectionState.CONNECTED -> R.color.conn_connected
                ConnectionState.CONNECTING -> R.color.conn_connecting
                ConnectionState.DISCONNECTED -> R.color.conn_disconnected
                ConnectionState.ERROR -> R.color.conn_error
                ConnectionState.IDLE -> R.color.conn_idle
            }
            b.connDot.background.setTint(ContextCompat.getColor(b.root.context, color))
        }

        private fun renderResult(item: DeviceItem) {
            val raw = item.lastResult
            if (raw.isNullOrBlank()) {
                b.itemResult.visibility = View.GONE
                applyErrorStroke(false)
                return
            }
            b.itemResult.visibility = View.VISIBLE
            val ctx = b.root.context

            // Append unit suffix when the body is a single short numeric line.
            val withUnit = appendUnit(raw, item.unit)

            val codeMatch = LEADING_CODE.find(withUnit)
            val code = codeMatch?.groupValues?.get(1)?.toIntOrNull()

            val builder = SpannableStringBuilder(withUnit)
            if (codeMatch != null && code != null) {
                builder.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(ctx, statusColorRes(code))),
                    codeMatch.range.first,
                    codeMatch.range.last + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            item.lastResultAt?.let { ts ->
                val time = TIME_FMT.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
                val start = builder.length
                builder.append("  ·  ").append(time)
                builder.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.status_time)),
                    start,
                    builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            b.itemResult.text = builder
            applyErrorStroke(code != null && code !in 200..299)
        }

        /**
         * If [unit] is set and the body looks like a bare number (or a single rule line
         * `label: 23.4`), append " unit". Multi-line / complex bodies are left alone since
         * the unit applies to one extracted value at a time.
         */
        private fun appendUnit(raw: String, unit: String?): String {
            if (unit.isNullOrBlank()) return raw
            val lines = raw.split('\n')
            val out = StringBuilder()
            for ((i, line) in lines.withIndex()) {
                if (i > 0) out.append('\n')
                val (head, tail) = splitNumericTail(line)
                out.append(head)
                if (tail != null) {
                    out.append(tail).append(' ').append(unit)
                } else {
                    out.append(line.substring(head.length))
                }
            }
            return out.toString()
        }

        /** Returns (prefix, numeric-suffix) if the line ends with a number, else (line, null). */
        private fun splitNumericTail(line: String): Pair<String, String?> {
            val m = TRAILING_NUM.find(line) ?: return line to null
            return line.substring(0, m.range.first) to m.value
        }

        private fun applyErrorStroke(error: Boolean) {
            val ctx = b.root.context
            if (error) {
                b.root.strokeColor = ContextCompat.getColor(ctx, R.color.status_error_stroke)
                b.root.strokeWidth = (2 * ctx.resources.displayMetrics.density).toInt()
            } else {
                b.root.strokeWidth = 0
            }
        }

        private fun statusColorRes(code: Int): Int = when (code) {
            in 200..299 -> R.color.status_2xx
            in 300..399 -> R.color.status_3xx
            in 400..499 -> R.color.status_4xx
            in 500..599 -> R.color.status_5xx
            else -> R.color.status_5xx
        }

        private fun renderPresetChips(item: DeviceItem) {
            b.presetChips.removeAllViews()
            if (item.type != ItemType.STRING_COMMAND || item.stringPresets.isEmpty()) {
                b.presetChips.visibility = View.GONE
                return
            }
            b.presetChips.visibility = View.VISIBLE
            val ctx = b.presetChips.context
            for (preset in item.stringPresets) {
                val chip = Chip(ctx).apply {
                    text = preset.label.ifBlank { preset.value }
                    isClickable = true
                    isCheckable = false
                    setOnClickListener { onPresetClick(item, preset) }
                }
                b.presetChips.addView(chip)
            }
        }
    }

    companion object {
        private val LEADING_CODE = Regex("""^\[(\d+)\]""")
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val TRAILING_NUM = Regex("""-?\d+(\.\d+)?$""")
    }
}
