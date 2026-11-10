package com.tr069.client.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tr069.client.R
import com.tr069.client.data.model.DeviceItem
import com.tr069.client.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onItemClick: (DeviceItem) -> Unit,
    private val onSummonClick: ((DeviceItem, com.google.android.material.button.MaterialButton) -> Unit)? = null,
    private val onTagClick: ((String) -> Unit)? = null
) : ListAdapter<DeviceItem, DeviceAdapter.DeviceViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(
        private val binding: ItemDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DeviceItem) {
            val context = binding.root.context

            // Model & Manufacturer
            val modelText = if (item.manufacturer != "Unknown") {
                "${item.manufacturer} ${item.productClass}"
            } else {
                item.productClass
            }
            binding.tvModel.text = modelText

            // Serial Number
            binding.tvSerial.text = "SN: ${item.serialNumber}"

            // Status Badge (Online / Offline)
            if (item.isOnline) {
                binding.tvStatusBadge.text = "● Online"
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_online)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_online))
            } else {
                binding.tvStatusBadge.text = "● Offline"
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_offline)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_offline))
            }

            // Customer Name & PPPoE Username
            val userDisplay = if (!item.customerName.isNullOrBlank() && !item.customerName.equals(item.pppoeUsername, ignoreCase = true)) {
                "${item.customerName} (${item.pppoeUsername})"
            } else {
                item.pppoeUsername
            }
            binding.tvPppoe.text = userDisplay

            // WiFi SSID
            binding.tvSsid.text = item.ssid

            // Rx Power (Redaman)
            if (item.rxPower != null) {
                val rxVal = item.rxPower
                val rxFormatted = String.format("%.1f dBm", rxVal)
                binding.tvRxPower.text = rxFormatted

                val colorRes = when {
                    rxVal >= -24.0 -> R.color.signal_good
                    rxVal >= -27.0 -> R.color.signal_warning
                    else -> R.color.signal_bad
                }
                binding.tvRxPower.setTextColor(ContextCompat.getColor(context, colorRes))
            } else {
                binding.tvRxPower.text = "N/A"
                binding.tvRxPower.setTextColor(ContextCompat.getColor(context, R.color.genie_text_muted))
            }

            // Last Inform
            binding.tvLastInform.text = "Inform: ${item.lastInform ?: "-"}"

            // Map Badge
            if (item.isMapped) {
                val odpLabel = if (!item.odpName.isNullOrBlank()) "ODP: ${item.odpName}" else "Terpetakan"
                binding.tvMapBadge.text = "📍 $odpLabel"
                binding.tvMapBadge.setBackgroundResource(R.drawable.bg_badge_mapped)
                binding.tvMapBadge.setTextColor(ContextCompat.getColor(context, R.color.status_info))
                binding.tvMapBadge.visibility = android.view.View.VISIBLE
            } else {
                binding.tvMapBadge.text = "⚠️ Belum Ada Titik"
                binding.tvMapBadge.setBackgroundResource(R.drawable.bg_badge_unmapped)
                binding.tvMapBadge.setTextColor(ContextCompat.getColor(context, R.color.genie_text_muted))
                binding.tvMapBadge.visibility = android.view.View.VISIBLE
            }

            // Tags from GenieACS _tags
            val displayTags = item.tags.filterNot {
                it.startsWith("LOC:", true) || it.startsWith("LOC_", true) || it.startsWith("LOC-", true)
            }.map { it.trim() }.filter { it.isNotEmpty() }

            if (displayTags.isNotEmpty()) {
                binding.tvDeviceTags.text = displayTags.joinToString("   ") { "🏷️ $it" }
                binding.tvDeviceTags.visibility = android.view.View.VISIBLE
                binding.tvDeviceTags.setOnClickListener {
                    onTagClick?.invoke(displayTags.first())
                }
            } else {
                binding.tvDeviceTags.visibility = android.view.View.GONE
            }

            // Quick Summon
            binding.btnQuickSummon.setOnClickListener {
                onSummonClick?.invoke(item, binding.btnQuickSummon)
            }

            // On click
            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<DeviceItem>() {
        override fun areItemsTheSame(oldItem: DeviceItem, newItem: DeviceItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DeviceItem, newItem: DeviceItem): Boolean {
            return oldItem == newItem
        }
    }
}
