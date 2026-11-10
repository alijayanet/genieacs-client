package com.tr069.client.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tr069.client.R
import com.tr069.client.data.model.DeviceItem
import com.tr069.client.databinding.ItemBulkDeviceBinding

class BulkDeviceAdapter(
    private val allItems: List<DeviceItem>,
    private val onSelectionChanged: (selectedCount: Int) -> Unit
) : RecyclerView.Adapter<BulkDeviceAdapter.ViewHolder>() {

    private var displayList: List<DeviceItem> = allItems.toList()
    val selectedIds: MutableSet<String> = allItems.map { it.id }.toMutableSet()

    init {
        onSelectionChanged(selectedIds.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBulkDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(displayList[position])
    }

    override fun getItemCount(): Int = displayList.size

    fun filter(query: String) {
        val q = query.trim().lowercase()
        displayList = if (q.isBlank()) {
            allItems
        } else {
            allItems.filter {
                it.pppoeUsername.lowercase().contains(q) ||
                it.serialNumber.lowercase().contains(q) ||
                it.productClass.lowercase().contains(q) ||
                it.manufacturer.lowercase().contains(q) ||
                it.id.lowercase().contains(q) ||
                (it.odpName != null && it.odpName.lowercase().contains(q)) ||
                it.tags.any { tag -> tag.lowercase().contains(q) }
            }
        }
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedIds.addAll(allItems.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun deselectAll() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    inner class ViewHolder(
        private val binding: ItemBulkDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DeviceItem) {
            val context = binding.root.context

            val displayName = if (item.pppoeUsername.isNotBlank() && item.pppoeUsername != "-") {
                item.pppoeUsername
            } else {
                item.serialNumber
            }
            binding.tvBulkDeviceName.text = displayName

            val modelText = if (item.manufacturer != "Unknown") "${item.manufacturer} ${item.productClass}" else item.productClass
            binding.tvBulkDeviceSub.text = "$modelText • SN: ${item.serialNumber}"

            if (item.isOnline) {
                binding.tvBulkStatusBadge.text = "● Online"
                binding.tvBulkStatusBadge.setBackgroundResource(R.drawable.bg_badge_online)
                binding.tvBulkStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_online))
            } else {
                binding.tvBulkStatusBadge.text = "● Offline"
                binding.tvBulkStatusBadge.setBackgroundResource(R.drawable.bg_badge_offline)
                binding.tvBulkStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_offline))
            }

            // Checkbox state
            binding.cbSelectDevice.isChecked = selectedIds.contains(item.id)

            val toggle = {
                if (selectedIds.contains(item.id)) {
                    selectedIds.remove(item.id)
                    binding.cbSelectDevice.isChecked = false
                } else {
                    selectedIds.add(item.id)
                    binding.cbSelectDevice.isChecked = true
                }
                onSelectionChanged(selectedIds.size)
            }

            binding.cbSelectDevice.setOnClickListener { toggle() }
            binding.root.setOnClickListener { toggle() }
        }
    }
}
