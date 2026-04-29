package com.lunastratos.remotecontrol.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lunastratos.remotecontrol.data.Device
import com.lunastratos.remotecontrol.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onClick: (Device) -> Unit,
    private val onMore: (Device, android.view.View) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items = mutableListOf<Device>()

    fun submit(list: List<Device>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(device: Device) {
            b.deviceName.text = device.name
            b.itemCount.text = "${device.items.size} 항목"
            b.rowContent.setOnClickListener { onClick(device) }
            b.btnMore.setOnClickListener { onMore(device, it) }
        }
    }
}
