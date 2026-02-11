package com.brayden.lark.ui.main

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.brayden.lark.ble.OmiDevice
import com.brayden.lark.databinding.ItemDeviceBinding

class DeviceListAdapter(
    private val onConnectClick: (OmiDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<OmiDevice>()

    @SuppressLint("NotifyDataSetChanged")
    fun addDevice(device: OmiDevice) {
        val existing = devices.indexOfFirst { it.address == device.address }
        if (existing >= 0) {
            devices[existing] = device
            notifyItemChanged(existing)
        } else {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clear() {
        devices.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(
        private val binding: ItemDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: OmiDevice) {
            binding.tvDeviceName.text = device.name
            binding.tvDeviceAddress.text = device.address
            binding.tvRssi.text = "${device.rssi} dBm"
            binding.btnConnect.setOnClickListener {
                onConnectClick(device)
            }
        }
    }
}
