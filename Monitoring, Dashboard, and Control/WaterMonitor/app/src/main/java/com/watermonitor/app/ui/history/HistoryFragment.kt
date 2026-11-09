package com.watermonitor.app.ui.history

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.watermonitor.app.R
import com.watermonitor.app.data.history.*
import com.watermonitor.app.data.repository.FilterHealthRepository
import com.watermonitor.app.ui.filter.FilterFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

class HistoryFragment : Fragment() {
    private lateinit var content: LinearLayout
    private lateinit var summary: TextView
    private lateinit var sync: TextView
    private lateinit var official: TextView
    private lateinit var timeline: LinearLayout
    private lateinit var chart: HistoryChart
    private lateinit var monthPicker: Spinner
    private var selectedMonth = ArchiveCalendar.month(System.currentTimeMillis())
    private var selectedMetric = "ph"
    private var selectedKind = "all"
    private var eventLimit = 100
    private var local: List<HistoryRecord> = emptyList()
    private var cloud: List<HistoryRecord> = emptyList()
    private var monthJob: Job? = null
    private var cloudJob: Job? = null
    private val monthOptions = mutableListOf<String>()
    private val metrics = linkedMapOf("ph" to "pH", "tds" to "TDS (ppm)", "turbidity" to "Turbidity (NTU)", "tankLevel" to "Tank level (%)", "tankDistanceMm" to "Tank distance (mm)")
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 14f) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(ContextCompat.getColor(context, R.color.text_dark))
        setPadding(0, dp(7), 0, dp(7))
    }
    private fun card(): LinearLayout {
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(12)) }
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(18).toFloat(); setCardBackgroundColor(ContextCompat.getColor(context, R.color.glass_white))
            addView(box); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        }
        content.addView(card); return box
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(40)) }
        return ScrollView(requireContext()).apply { addView(content) }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        selectedMonth = savedInstanceState?.getString("month") ?: selectedMonth
        val top = card()
        top.addView(label(getString(R.string.history_title), 24f))
        top.addView(label(getString(R.string.history_local)))
        sync = label(""); top.addView(sync)
        top.addView(MaterialButton(requireContext()).apply {
            setText(R.string.history_sync)
            setOnClickListener { HistoryRepository.uploadClosedMonths(); loadCloud(); discoverMonths() }
        })
        top.addView(label(getString(R.string.history_month)))
        monthPicker = Spinner(requireContext()); top.addView(monthPicker)
        monthPicker.onItemSelectedListener = selection { position ->
            monthOptions.getOrNull(position)?.let { selectedMonth = it; eventLimit = 100; observeMonth() }
        }
        val statistics = card(); summary = label(""); statistics.addView(summary)
        statistics.addView(label(getString(R.string.history_coverage_note), 12f))
        val plot = card(); plot.addView(label(getString(R.string.history_chart), 20f))
        val metricPicker = Spinner(requireContext()); metricPicker.contentDescription = getString(R.string.history_metric)
        metricPicker.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, metrics.values.toList())
        metricPicker.onItemSelectedListener = selection { selectedMetric = metrics.keys.elementAt(it); render() }
        plot.addView(metricPicker)
        chart = HistoryChart(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(220)) }
        plot.addView(chart)
        val forecast = card(); forecast.addView(label(getString(R.string.history_official), 20f))
        official = label(""); forecast.addView(official)
        forecast.addView(label(getString(R.string.history_baseline_note), 12f))
        val events = card(); events.addView(label(getString(R.string.history_timeline), 20f))
        val kinds = listOf("all", "maintenance", "alert", "pump", "control", "command")
        events.addView(Spinner(requireContext()).apply {
            contentDescription = getString(R.string.history_filter)
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.history_all), "Maintenance", "Alerts", "Pump activity", "Control changes", "Commands"))
            onItemSelectedListener = selection { selectedKind = kinds[it]; eventLimit = 100; render() }
        })
        timeline = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }; events.addView(timeline)
        events.addView(MaterialButton(requireContext()).apply { setText(R.string.history_more); setOnClickListener { eventLimit += 100; render() } })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { HistoryRepository.status.collect { sync.text = it } }
                launch { HistoryDatabase.get(requireContext()).history().months().collect { updateMonths(it) } }
                launch { FilterHealthRepository.filterHealthFlow.collect { state ->
                    official.text = state.stages.joinToString("\n\n") {
                        FilterFormatter.stageName(requireContext(), it.stageKey) + "\n" + FilterFormatter.forecastLabel(requireContext(), it)
                    }.ifEmpty { getString(R.string.history_awaiting) }
                } }
            }
        }
        updateMonths(emptyList()); observeMonth(); discoverMonths()
    }
    private fun selection(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { action(position) }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    private fun updateMonths(extra: List<String>) {
        val all = (monthOptions + extra + selectedMonth + ArchiveCalendar.month(System.currentTimeMillis())).distinct().sortedDescending()
        if (all == monthOptions) return
        monthOptions.clear(); monthOptions.addAll(all)
        monthPicker.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            all.map { YearMonth.parse(it).format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.getDefault())) })
        monthPicker.setSelection(all.indexOf(selectedMonth).coerceAtLeast(0))
    }
    private fun discoverMonths() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { FirebaseFirestore.getInstance().collection("systems/${HistoryRepository.SYSTEM_ID}/months").get(Source.SERVER).await() }
                .onSuccess { updateMonths(it.documents.map { doc -> doc.id }) }
        }
    }
    private fun observeMonth() {
        monthJob?.cancel(); cloudJob?.cancel(); local = emptyList(); cloud = emptyList()
        monthJob = viewLifecycleOwner.lifecycleScope.launch {
            HistoryDatabase.get(requireContext()).history().observe(selectedMonth).collect { local = it; render() }
        }
        loadCloud()
    }
    private fun loadCloud() {
        if (selectedMonth >= ArchiveCalendar.month(System.currentTimeMillis())) return
        cloudJob?.cancel()
        val month = selectedMonth
        cloudJob = viewLifecycleOwner.lifecycleScope.launch {
            sync.setText(R.string.history_cloud_loading)
            try {
                val records = mutableListOf<HistoryRecord>()
                val query = FirebaseFirestore.getInstance().collection("systems/${HistoryRepository.SYSTEM_ID}/months/$month/records")
                    .orderBy(com.google.firebase.firestore.FieldPath.documentId()).limit(500)
                var last: com.google.firebase.firestore.DocumentSnapshot? = null
                do {
                    val page = (last?.let { query.startAfter(it) } ?: query).get(Source.SERVER).await()
                    records += page.documents.mapNotNull { doc ->
                        val time = doc.getLong("time") ?: return@mapNotNull null
                        HistoryRecord(doc.getString("id") ?: doc.id, month, time, doc.getString("kind") ?: "event",
                            HistoryRepository.gson.toJson(doc.get("payload")), true)
                    }
                    last = if (page.size() == 500) page.documents.last() else null
                } while (last != null)
                if (month == selectedMonth) { cloud = records; render(); sync.text = HistoryRepository.status.value }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { sync.text = getString(R.string.history_cloud_error, error.localizedMessage) }
        }
    }
    @Suppress("UNCHECKED_CAST") private fun payload(record: HistoryRecord): Map<String, Any?> =
        runCatching { HistoryRepository.gson.fromJson(record.payload, Map::class.java) as Map<String, Any?> }.getOrDefault(emptyMap())
    private fun render() {
        if (!::chart.isInitialized || !::timeline.isInitialized) return
        val rows = (cloud + local).distinctBy { it.id }.sortedWith(compareBy({ it.time }, { it.id }))
        val samples = rows.filter { it.kind == "sample" }.distinctBy { it.time / 60000 }
        val readings = samples.map { it to payload(it).mapAt("sample").mapAt("sensors") }
        chart.points = readings.mapNotNull { (record, sensor) -> (sensor[selectedMetric] as? Number)?.toDouble()
            ?.takeIf { it.isFinite() }?.let { record.time to it } }
        chart.contentDescription = "${metrics[selectedMetric]}: ${chart.points.size} recorded readings"
        val end = minOf(System.currentTimeMillis(), ArchiveCalendar.end(selectedMonth))
        val minutes = ((end - ArchiveCalendar.start(selectedMonth)) / 60000.0).coerceAtLeast(1.0)
        val lines = mutableListOf(getString(R.string.history_coverage, samples.size, samples.size / minutes * 100))
        for ((key, name) in metrics) {
            val values = readings.mapNotNull { (_, sensor) -> (sensor[key] as? Number)?.toDouble()?.takeIf { it.isFinite() } }
            if (values.isNotEmpty()) lines += String.format(Locale.getDefault(), "%s: %.2f avg · %.2f–%.2f", name, values.average(), values.min(), values.max())
        }
        for (pump in listOf("A", "B")) {
            val seconds = readings.zipWithNext().sumOf { (a, b) ->
                val before = (a.second["runtime$pump"] as? Number)?.toLong()
                val after = (b.second["runtime$pump"] as? Number)?.toLong()
                if (before != null && after != null && after >= before && after - before <= (b.first.time - a.first.time) / 1000 + 5) after - before else 0L
            }
            lines += String.format(Locale.getDefault(), "Pump %s: %.2f operating hours", pump, seconds / 3600.0)
        }
        lines += getString(R.string.history_pending, local.count { !it.uploaded && it.month < ArchiveCalendar.month(System.currentTimeMillis()) })
        summary.text = if (rows.isEmpty()) getString(R.string.history_empty) else lines.joinToString("\n")
        timeline.removeAllViews()
        val format = DateTimeFormatter.ofPattern("MMM d, uuuu · HH:mm", Locale.getDefault()).withZone(ArchiveCalendar.zone)
        val events = rows.asReversed().filter { it.kind != "sample" && (selectedKind == "all" || it.kind == selectedKind) }
        for (event in events.take(eventLimit)) {
            val p = payload(event)
            val details = when (event.kind) {
                "maintenance" -> "${p["stageKey"]} · ${p["action"]}\n${p["condition"]} · ${p["reason"] ?: ""}"
                "command" -> "${p["action"]}: ${p["enabled"]} · ${p["result"]}"
                "alert" -> "Leak: ${p["rainDetected"] ?: "unknown"} · Tank warning: ${p["tankWarning"] ?: "unknown"}"
                else -> p["value"]?.toString() ?: "Recorded state change"
            }
            timeline.addView(label("${format.format(Instant.ofEpochMilli(event.time))} · ${event.kind}\n$details"))
        }
        if (events.isEmpty()) timeline.addView(label(getString(R.string.history_empty)))
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("month", selectedMonth); super.onSaveInstanceState(outState) }
}
