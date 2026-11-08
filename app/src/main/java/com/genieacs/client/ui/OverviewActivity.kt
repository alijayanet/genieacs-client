package com.genieacs.client.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.genieacs.client.R
import com.genieacs.client.data.model.DeviceItem
import com.genieacs.client.data.network.GenieAcsNbiService
import com.genieacs.client.data.pref.AppPreferences
import com.genieacs.client.databinding.ActivityOverviewBinding
import com.genieacs.client.ui.view.DonutChartView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

class OverviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOverviewBinding
    private lateinit var prefs: AppPreferences
    private lateinit var nbiService: GenieAcsNbiService
    private var allDevices: List<DeviceItem> = emptyList()

    companion object {
        const val EXTRA_FILTER_TYPE = "extra_filter_type"
        const val EXTRA_SEARCH_QUERY = "extra_search_query"
        const val EXTRA_SELECTED_TAG = "extra_selected_tag"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        nbiService = GenieAcsNbiService(prefs.serverUrl, prefs.username, prefs.password)

        setupToolbar()
        loadFromCache()
        loadLiveDevices(silent = allDevices.isNotEmpty())

        binding.swipeRefresh.setOnRefreshListener {
            loadLiveDevices(silent = false)
        }
    }

    private fun setupToolbar() {
        binding.tvServerSubtitle.text = prefs.serverUrl
        binding.btnBack.setOnClickListener {
            finish()
        }
        binding.btnRefresh.setOnClickListener {
            loadLiveDevices(silent = false)
        }
    }

    private fun loadFromCache() {
        val cachedJson = prefs.cachedDevicesJson
        if (cachedJson.isNotBlank()) {
            try {
                val type = object : TypeToken<List<DeviceItem>>() {}.type
                val cached: List<DeviceItem>? = Gson().fromJson(cachedJson, type)
                if (!cached.isNullOrEmpty()) {
                    allDevices = cached
                    renderAllCharts(cached)
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadLiveDevices(silent: Boolean) {
        if (!silent) {
            binding.swipeRefresh.isRefreshing = true
        }

        lifecycleScope.launch {
            val res = nbiService.getDevices()
            binding.swipeRefresh.isRefreshing = false

            res.onSuccess { list ->
                allDevices = list
                renderAllCharts(list)
                try {
                    prefs.cachedDevicesJson = Gson().toJson(list)
                } catch (_: Exception) {}
            }.onFailure { err ->
                if (!silent) {
                    Toast.makeText(this@OverviewActivity, "Gagal memuat overview: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderAllCharts(devices: List<DeviceItem>) {
        val total = devices.size
        val onlineCount = devices.count { it.isOnline }
        val offlineCount = total - onlineCount
        val badRxCount = devices.count { it.rxPower != null && it.rxPower < -27.0 }

        // 1. Summary Strip
        binding.tvSummaryTotal.text = total.toString()
        binding.tvSummaryOnline.text = onlineCount.toString()
        binding.tvSummaryOffline.text = offlineCount.toString()
        binding.tvSummaryBadRx.text = badRxCount.toString()

        // 2. CHART 1: Online Status
        val onlineSlices = listOf(
            DonutChartView.Slice("Online", onlineCount, Color.parseColor("#10B981")),
            DonutChartView.Slice("Offline", offlineCount, Color.parseColor("#EF4444"))
        )
        val onlineRate = if (total > 0) "${(onlineCount * 100 / total)}%" else "0%"
        binding.chartOnline.setData(onlineSlices, primaryText = onlineCount.toString(), secondaryText = "Online ($onlineRate)")
        populateLegend(binding.legendOnline, onlineSlices, total) { slice ->
            if (slice.label.equals("Online", true)) openMainWithFilter("ONLINE") else openMainWithFilter("OFFLINE")
        }
        binding.cardOnlineStatus.setOnClickListener {
            openMainWithFilter("ONLINE")
        }

        // 3. CHART 2: Optical RX Power
        val goodRx = devices.count { it.rxPower != null && it.rxPower >= -24.0 }
        val warnRx = devices.count { it.rxPower != null && it.rxPower < -24.0 && it.rxPower >= -27.0 }
        val critRx = badRxCount
        val unkRx = total - (goodRx + warnRx + critRx)

        val rxSlices = mutableListOf<DonutChartView.Slice>().apply {
            if (goodRx > 0) add(DonutChartView.Slice("Bagus (≥-24 dBm)", goodRx, Color.parseColor("#3B82F6")))
            if (warnRx > 0) add(DonutChartView.Slice("Sedang (-24..-27)", warnRx, Color.parseColor("#F59E0B")))
            if (critRx > 0) add(DonutChartView.Slice("Kritis (<-27 dBm)", critRx, Color.parseColor("#EF4444")))
            if (unkRx > 0) add(DonutChartView.Slice("N/A / Offline", unkRx, Color.parseColor("#64748B")))
        }
        val safePercent = if (total > 0) "${((goodRx + warnRx) * 100 / total)}%" else "0%"
        binding.chartPonPower.setData(rxSlices, primaryText = safePercent, secondaryText = "Sinyal Aman")
        populateLegend(binding.legendPonPower, rxSlices, total) { slice ->
            if (slice.label.contains("Kritis")) {
                openMainWithFilter("BAD_RX")
            } else {
                openMainWithFilter("ALL")
            }
        }
        binding.cardPonPower.setOnClickListener {
            openMainWithFilter("BAD_RX")
        }

        // 4. CHART 3: Vendor & Model ONT
        val vendorGroups = devices.groupBy { dev ->
            val m = dev.manufacturer.uppercase()
            when {
                m.contains("ZTE") || m.contains("ZICG") -> "ZTE"
                m.contains("HUAWEI") -> "Huawei"
                m.contains("FIBERHOME") || m.contains("CIOT") -> "FiberHome"
                m.contains("VSOL") || m.contains("CDATA") || m.contains("C-DATA") -> "VSOL / C-Data"
                m.contains("HSGQ") || m.contains("BDCOM") -> "HSGQ / BDCOM"
                dev.productClass.isNotBlank() && dev.productClass != "Modem" -> dev.productClass
                else -> "Lainnya"
            }
        }.mapValues { it.value.size }.toList().sortedByDescending { it.second }

        val vendorColors = listOf("#3B82F6", "#EC4899", "#8B5CF6", "#10B981", "#F97316", "#06B6D4", "#64748B")
        val vendorSlices = vendorGroups.take(5).mapIndexed { idx, (vendor, count) ->
            DonutChartView.Slice(vendor, count, Color.parseColor(vendorColors[idx % vendorColors.size]))
        }.toMutableList()

        val otherVendorCount = vendorGroups.drop(5).sumOf { it.second }
        if (otherVendorCount > 0) {
            vendorSlices.add(DonutChartView.Slice("Lainnya", otherVendorCount, Color.parseColor("#64748B")))
        }
        binding.chartOntType.setData(vendorSlices, primaryText = vendorGroups.size.toString(), secondaryText = "Model Merek")
        populateLegend(binding.legendOntType, vendorSlices, total) { slice ->
            openMainWithSearch(slice.label)
        }
        binding.cardOntType.setOnClickListener {
            openMainWithSearch(vendorSlices.firstOrNull()?.label ?: "")
        }

        // 5. CHART 4: Access Type / Mode PON
        val ponGroups = devices.groupBy { dev ->
            val p = dev.ponMode?.uppercase() ?: ""
            when {
                p.contains("GPON") -> "GPON"
                p.contains("EPON") -> "EPON"
                p.contains("ETHERNET") -> "Ethernet"
                else -> "GPON"
            }
        }.mapValues { it.value.size }

        val gponCount = ponGroups["GPON"] ?: 0
        val eponCount = ponGroups["EPON"] ?: 0
        val etherCount = ponGroups["Ethernet"] ?: 0

        val ponSlices = mutableListOf<DonutChartView.Slice>().apply {
            if (gponCount > 0) add(DonutChartView.Slice("GPON", gponCount, Color.parseColor("#06B6D4")))
            if (eponCount > 0) add(DonutChartView.Slice("EPON", eponCount, Color.parseColor("#84CC16")))
            if (etherCount > 0) add(DonutChartView.Slice("Ethernet", etherCount, Color.parseColor("#6366F1")))
        }
        val mainPon = if (gponCount >= eponCount) "GPON" else "EPON"
        binding.chartAccessType.setData(ponSlices, primaryText = mainPon, secondaryText = "Mode Utama")
        populateLegend(binding.legendAccessType, ponSlices, total) { slice ->
            openMainWithSearch(slice.label)
        }
        binding.cardAccessType.setOnClickListener {
            openMainWithSearch("GPON")
        }

        // 6. CHART 5: Pemetaan Lapangan & ODP
        val mappedOdp = devices.count { it.isMapped && !it.odpName.isNullOrBlank() }
        val mappedGpsOnly = devices.count { it.isMapped && it.odpName.isNullOrBlank() }
        val unmapped = devices.count { !it.isMapped }

        val mapSlices = mutableListOf<DonutChartView.Slice>().apply {
            if (mappedOdp > 0) add(DonutChartView.Slice("Terpetakan ODP", mappedOdp, Color.parseColor("#3B82F6")))
            if (mappedGpsOnly > 0) add(DonutChartView.Slice("Titik GPS Saja", mappedGpsOnly, Color.parseColor("#10B981")))
            if (unmapped > 0) add(DonutChartView.Slice("Belum Ada Titik", unmapped, Color.parseColor("#64748B")))
        }
        val mapRate = if (total > 0) "${((total - unmapped) * 100 / total)}%" else "0%"
        binding.chartOdpMap.setData(mapSlices, primaryText = mapRate, secondaryText = "Terpetakan")
        populateLegend(binding.legendOdpMap, mapSlices, total) {
            startActivity(Intent(this, NetworkMapActivity::class.java))
        }
        binding.cardOdpMap.setOnClickListener {
            startActivity(Intent(this, NetworkMapActivity::class.java))
        }

        // 7. CHART 6: Distribusi Tag Pelanggan
        val tagCounts = devices.flatMap { it.tags }
            .filterNot { it.startsWith("LOC:", true) || it.startsWith("LOC_", true) || it.startsWith("LOC-", true) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }

        val tagColors = listOf("#F59E0B", "#8B5CF6", "#10B981", "#3B82F6", "#EC4899", "#64748B")
        val topTags = tagCounts.take(4).mapIndexed { idx, (tag, count) ->
            DonutChartView.Slice(tag, count, Color.parseColor(tagColors[idx % tagColors.size]))
        }.toMutableList()

        val otherTagsCount = tagCounts.drop(4).sumOf { it.second }
        if (otherTagsCount > 0) {
            topTags.add(DonutChartView.Slice("Tag Lainnya", otherTagsCount, Color.parseColor("#64748B")))
        }

        binding.chartTags.setData(topTags, primaryText = tagCounts.size.toString(), secondaryText = "Total Tag")
        populateLegend(binding.legendTags, topTags, total) { slice ->
            openMainWithTag(slice.label)
        }
        binding.cardTags.setOnClickListener {
            val firstTag = tagCounts.firstOrNull()?.first
            if (!firstTag.isNullOrBlank()) openMainWithTag(firstTag)
        }
    }

    private fun populateLegend(
        container: LinearLayout,
        slices: List<DonutChartView.Slice>,
        total: Int,
        onClick: ((DonutChartView.Slice) -> Unit)? = null
    ) {
        container.removeAllViews()
        val density = resources.displayMetrics.density

        for (slice in slices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                if (onClick != null) {
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(R.drawable.bg_badge_tag)
                    setOnClickListener { onClick(slice) }
                }
            }

            // Dot Indicator
            val dot = View(this).apply {
                val size = (8 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (6 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(slice.color)
                }
            }

            // Label
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = slice.label
                setTextColor(ContextCompat.getColor(this@OverviewActivity, R.color.genie_text_secondary))
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            // Count & Percent
            val percent = if (total > 0) (slice.count * 100 / total) else 0
            val valText = TextView(this).apply {
                text = "${slice.count} ($percent%)"
                setTextColor(ContextCompat.getColor(this@OverviewActivity, R.color.genie_text_primary))
                textSize = 10.5f
                paint.isFakeBoldText = true
            }

            row.addView(dot)
            row.addView(label)
            row.addView(valText)
            container.addView(row)
        }
    }

    private fun openMainWithFilter(filterType: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FILTER_TYPE, filterType)
        }
        startActivity(intent)
        finish()
    }

    private fun openMainWithSearch(query: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SEARCH_QUERY, query)
        }
        startActivity(intent)
        finish()
    }

    private fun openMainWithTag(tag: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SELECTED_TAG, tag)
        }
        startActivity(intent)
        finish()
    }
}
