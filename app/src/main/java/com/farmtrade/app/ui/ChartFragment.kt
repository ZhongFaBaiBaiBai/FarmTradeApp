package com.farmtrade.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.FragmentChartBinding
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import java.util.Calendar

/**
 * 数据图表 Fragment。
 */
class ChartFragment : Fragment() {

    private enum class PeriodMode { DAY, MONTH, QUARTER, YEAR }

    private var _binding: FragmentChartBinding? = null
    private val binding get() = _binding!!

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var lineChart: LineChart
    private lateinit var barChart: HorizontalBarChart

    private var periodMode = PeriodMode.MONTH
    private val selCal: Calendar = Calendar.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = DatabaseHelper(requireContext())
        initCharts()
        setupToggle()
        setupPeriodSelector()
        binding.togglePeriod.check(R.id.btnPeriodMonth)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 图表初始化 ====================

    private fun initCharts() {
        lineChart = LineChart(requireContext()).apply {
            description.isEnabled = false
            legend.apply {
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                orientation = Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
                textSize = 13f
            }
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                setDrawGridLines(false)
                textSize = 11f
            }
            axisLeft.apply {
                axisMinimum = 0f
                textSize = 11f
            }
            axisRight.isEnabled = false
            setTouchEnabled(true)
            setPinchZoom(true)
            isDoubleTapToZoomEnabled = true
            setNoDataText("暂无数据")
        }
        replaceView(binding.frameLineChart, lineChart)

        barChart = HorizontalBarChart(requireContext()).apply {
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                setDrawGridLines(false)
                textSize = 11f
            }
            axisLeft.axisMinimum = 0f
            axisRight.axisMinimum = 0f
            setFitBars(true)
        }
        replaceView(binding.frameBarChart, barChart)
    }

    // ==================== 周期切换 ====================

    private fun setupToggle() {
        binding.togglePeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            periodMode = when (checkedId) {
                R.id.btnPeriodDay -> PeriodMode.DAY
                R.id.btnPeriodMonth -> PeriodMode.MONTH
                R.id.btnPeriodQuarter -> PeriodMode.QUARTER
                R.id.btnPeriodYear -> PeriodMode.YEAR
                else -> return@addOnButtonCheckedListener
            }
            resetSelectionToNow()
            refresh()
        }
    }

    private fun setupPeriodSelector() {
        binding.btnPrev.setOnClickListener { shiftPeriod(-1) }
        binding.btnNext.setOnClickListener { shiftPeriod(1) }
    }

    private fun shiftPeriod(direction: Int) {
        when (periodMode) {
            PeriodMode.DAY -> selCal.add(Calendar.DAY_OF_YEAR, direction)
            PeriodMode.MONTH -> selCal.add(Calendar.MONTH, direction)
            PeriodMode.QUARTER -> selCal.add(Calendar.MONTH, direction * 3)
            PeriodMode.YEAR -> selCal.add(Calendar.YEAR, direction)
        }
        refresh()
    }

    private fun resetSelectionToNow() {
        selCal.timeInMillis = System.currentTimeMillis()
    }

    // ==================== 刷新 ====================

    private fun refresh() {
        updateLabel()
        val keys = getRangeKeys()
        val records = loadRangeRecords(keys)
        updateLineChart(records, keys)
        updateBarChart(records)
    }

    private fun updateLabel() {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        val d = selCal.get(Calendar.DAY_OF_MONTH)
        binding.tvPeriodLabel.text = when (periodMode) {
            PeriodMode.DAY -> String.format("%d年%d月%d日", y, m, d)
            PeriodMode.MONTH -> String.format("%d年%d月", y, m)
            PeriodMode.QUARTER -> String.format("%d年Q%d", y, quarterOf(m))
            PeriodMode.YEAR -> String.format("%d年", y)
        }
    }

    private fun loadRangeRecords(keys: List<String>): List<Record> {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        return when (periodMode) {
            PeriodMode.DAY -> keys.flatMap { dbHelper.getRecordsByDate(it) }
            PeriodMode.MONTH -> dbHelper.getRecordsByMonth(y, m)
            PeriodMode.QUARTER -> dbHelper.getRecordsByQuarter(y, quarterOf(m))
            PeriodMode.YEAR -> dbHelper.getRecordsByYear(y)
        }
    }

    private fun getRangeKeys(): List<String> {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        return when (periodMode) {
            PeriodMode.DAY -> {
                val list = mutableListOf<String>()
                for (i in 6 downTo 0) {
                    val c = (selCal.clone() as Calendar)
                    c.add(Calendar.DAY_OF_YEAR, -i)
                    list.add(formatDateKey(c))
                }
                list
            }
            PeriodMode.MONTH -> {
                val days = daysInMonth(y, m)
                (1..days).map { formatDateKey(y, m, it) }
            }
            PeriodMode.QUARTER -> {
                val q = quarterOf(m)
                val startMonth = (q - 1) * 3 + 1
                val endMonth = startMonth + 2
                val list = mutableListOf<String>()
                for (mm in startMonth..endMonth) {
                    val days = daysInMonth(y, mm)
                    for (dd in 1..days) {
                        list.add(formatDateKey(y, mm, dd))
                    }
                }
                list
            }
            PeriodMode.YEAR -> (1..12).map { formatMonthKey(y, it) }
        }
    }

    // ==================== 折线图 ====================

    private fun updateLineChart(records: List<Record>, keys: List<String>) {
        if (records.isEmpty()) {
            showEmptyView(binding.frameLineChart)
            return
        }

        val isMonthGranularity = periodMode == PeriodMode.YEAR
        val buyByIndex = FloatArray(keys.size)
        val sellByIndex = FloatArray(keys.size)
        val keyToIndex = HashMap<String, Int>()
        keys.forEachIndexed { i, k -> keyToIndex[k] = i }

        for (r in records) {
            val key = if (isMonthGranularity) r.dateTime.substring(0, 7) else r.dateTime.substring(0, 10)
            val idx = keyToIndex[key] ?: continue
            if (r.direction == "买入") {
                buyByIndex[idx] += r.totalAmount.toFloat()
            } else if (r.direction == "卖出") {
                sellByIndex[idx] += r.totalAmount.toFloat()
            }
        }

        val buyEntries = ArrayList<Entry>()
        val sellEntries = ArrayList<Entry>()
        for (i in keys.indices) {
            buyEntries.add(Entry(i.toFloat(), buyByIndex[i]))
            sellEntries.add(Entry(i.toFloat(), sellByIndex[i]))
        }

        val buySet = LineDataSet(buyEntries, "买入").apply {
            setColor(GREEN)
            setCircleColor(GREEN)
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircles(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        val sellSet = LineDataSet(sellEntries, "卖出").apply {
            setColor(ORANGE)
            setCircleColor(ORANGE)
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircles(true)
            setDrawValues(false)
            enableDashedLine(10f, 6f, 0f)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        lineChart.data = LineData(buySet, sellSet)
        val labels = keys.map { formatXLabel(it, isMonthGranularity) }
        lineChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        lineChart.xAxis.setLabelCount(minOf(6, labels.size).coerceAtLeast(1), false)
        lineChart.notifyDataSetChanged()
        lineChart.invalidate()
        replaceView(binding.frameLineChart, lineChart)
    }

    // ==================== 横向柱状图 ====================

    private fun updateBarChart(records: List<Record>) {
        if (records.isEmpty()) {
            showEmptyView(binding.frameBarChart)
            return
        }

        val typeTotals = LinkedHashMap<String, Double>()
        var grandTotal = 0.0
        for (r in records) {
            typeTotals[r.type] = (typeTotals[r.type] ?: 0.0) + r.totalAmount
            grandTotal += r.totalAmount
        }

        val sorted = typeTotals.entries.sortedByDescending { it.value }
        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()
        sorted.forEachIndexed { i, entry ->
            val pct = if (grandTotal > 0) (entry.value / grandTotal * 100.0).toFloat() else 0f
            entries.add(BarEntry(i.toFloat(), pct))
            labels.add(entry.key)
        }

        val set = BarDataSet(entries, "类型占比").apply {
            setColor(GREEN)
            setValueTextSize(12f)
            setDrawValues(true)
            setValueFormatter(object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    String.format("%.1f%%", value)
            })
        }

        barChart.data = BarData(set).apply { setBarWidth(0.6f) }
        barChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        barChart.xAxis.setLabelCount(labels.size, true)
        barChart.notifyDataSetChanged()
        barChart.invalidate()
        replaceView(binding.frameBarChart, barChart)
    }

    // ==================== 视图工具方法 ====================

    private fun replaceView(frame: FrameLayout, chart: View) {
        frame.removeAllViews()
        frame.addView(
            chart,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showEmptyView(frame: FrameLayout) {
        frame.removeAllViews()
        val tv = TextView(requireContext()).apply {
            text = "暂无数据"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#aaaaaa"))
            textSize = 16f
        }
        frame.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ==================== 日期工具 ====================

    private fun quarterOf(month: Int): Int = (month - 1) / 3 + 1

    private fun daysInMonth(year: Int, month: Int): Int {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private fun formatDateKey(c: Calendar): String = String.format(
        "%04d-%02d-%02d",
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH)
    )

    private fun formatDateKey(y: Int, m: Int, d: Int): String =
        String.format("%04d-%02d-%02d", y, m, d)

    private fun formatMonthKey(y: Int, m: Int): String =
        String.format("%04d-%02d", y, m)

    private fun formatXLabel(key: String, isMonthGranularity: Boolean): String {
        return if (isMonthGranularity) {
            val mm = key.substring(5, 7).toIntOrNull() ?: return key
            "${mm}月"
        } else {
            val parts = key.split("-")
            if (parts.size >= 3) {
                "${parts[1].toIntOrNull() ?: parts[1]}/${parts[2].toIntOrNull() ?: parts[2]}"
            } else {
                key
            }
        }
    }

    companion object {
        private val GREEN = Color.parseColor("#2e7d32")
        private val ORANGE = Color.parseColor("#e65100")
    }
}
