package com.lunastratos.remotecontrol.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lunastratos.remotecontrol.R
import com.lunastratos.remotecontrol.data.Device
import com.lunastratos.remotecontrol.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onClick: (Device) -> Unit,
    private val onMore: (Device, View) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val all = mutableListOf<Device>()
    private val visible = mutableListOf<Device>()
    private var query = ""

    fun current(): List<Device> = visible.toList()
    fun rawAt(idx: Int): Device? = visible.getOrNull(idx)

    fun submit(list: List<Device>) {
        all.clear()
        all.addAll(list)
        applyFilter()
    }

    fun setQuery(q: String) {
        query = q.trim()
        applyFilter()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        visible.clear()
        if (query.isEmpty()) {
            visible.addAll(all)
        } else {
            val needle = query.lowercase()
            visible.addAll(all.filter { d ->
                d.name.lowercase().contains(needle) ||
                    d.items.any {
                        it.name.lowercase().contains(needle) ||
                            it.url.lowercase().contains(needle)
                    }
            })
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(visible[position])
    }

    override fun getItemCount(): Int = visible.size

    inner class VH(private val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(device: Device) {
            b.deviceName.text = device.name
            b.itemCount.text = b.root.context.getString(R.string.items_count, device.items.size)
            b.rowContent.setOnClickListener { onClick(device) }
            b.btnMore.setOnClickListener { onMore(device, it) }
            b.dragHandle.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(this)
                false
            }
        }
    }
}
