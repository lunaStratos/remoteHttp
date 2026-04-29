package com.lunastratos.remotecontrol.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.lunastratos.remotecontrol.data.DeviceItem
import com.lunastratos.remotecontrol.data.ItemType
import com.lunastratos.remotecontrol.data.Settings
import com.lunastratos.remotecontrol.data.StringPreset
import com.lunastratos.remotecontrol.databinding.ItemDeviceItemBinding

class DeviceItemAdapter(
    private val onRun: (DeviceItem) -> Unit,
    private val onEdit: (DeviceItem) -> Unit,
    private val onDelete: (DeviceItem) -> Unit,
    private val onIntStep: (DeviceItem, Int) -> Unit,
    private val onPresetClick: (DeviceItem, StringPreset) -> Unit
) : RecyclerView.Adapter<DeviceItemAdapter.VH>() {

    private val items = mutableListOf<DeviceItem>()

    fun submit(list: List<DeviceItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun update(item: DeviceItem) {
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            items[idx] = item
            notifyItemChanged(idx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDeviceItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val b: ItemDeviceItemBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: DeviceItem) {
            val typeLabel = when (item.type) {
                ItemType.STATUS_QUERY -> "상태조회"
                ItemType.INT_COMMAND -> "Int"
                ItemType.STRING_COMMAND -> "String"
            }
            b.itemTitle.text = "[$typeLabel] ${item.name}"
            b.itemSubtitle.text = "${item.method.name} ${item.url}"
            // Hide URL/method only on command cards (INT/STRING) when the user opted in.
            // Status queries always show their address so the polling/WS target stays visible.
            val isCommand = item.type == ItemType.INT_COMMAND ||
                item.type == ItemType.STRING_COMMAND
            val shouldHide = isCommand && Settings.get(b.root.context).hideUrl
            b.itemSubtitle.visibility = if (shouldHide) View.GONE else View.VISIBLE

            val result = item.lastResult
            if (result.isNullOrBlank()) {
                b.itemResult.visibility = View.GONE
            } else {
                b.itemResult.visibility = View.VISIBLE
                b.itemResult.text = result
            }

            // INT_COMMAND: stepper row sits *below* the edit/delete icons. The top-row Run
            // button is hidden so we don't show two run buttons at once.
            val isInt = item.type == ItemType.INT_COMMAND
            b.intActionRow.visibility = if (isInt) View.VISIBLE else View.GONE
            b.btnRun.visibility = if (isInt) View.GONE else View.VISIBLE

            // STRING_COMMAND: render label-value presets as inline chips.
            renderPresetChips(item)

            b.btnRun.setOnClickListener { onRun(item) }
            b.btnRunInt.setOnClickListener { onRun(item) }
            b.btnEdit.setOnClickListener { onEdit(item) }
            b.btnDelete.setOnClickListener { onDelete(item) }
            b.btnDecrement.setOnClickListener { onIntStep(item, -1) }
            b.btnIncrement.setOnClickListener { onIntStep(item, +1) }
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
}
