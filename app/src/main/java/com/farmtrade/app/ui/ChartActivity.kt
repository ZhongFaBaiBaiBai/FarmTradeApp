package com.farmtrade.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.ActivityChartBinding
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
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Calendar

/**
 * 数据图表页面。
 *
 * 功能：
 * - 周期切换：按日 / 按月 / 按季 / 按年
 * - 周期选择器：◀ 上一个 / 标签 / ▶ 下一个
 * - 折线图：每日交易总额趋势（买入=绿色实线，卖出=橙色虚线）
 * - 横向柱状图：各类型占比（百分比）
 * - 底部导航：记录 / 图表(选中) / 汇总 / 设置
 *
 * 周期与 X 轴颗粒度说明：
 * - 按日：选中某天，折线图显示该天所在最近 7 天的每日趋势
 * - 按月：折线图 X 轴为该月每日
 * - 按季：折线图 X 轴为该季度每日
 * - 按年：折线图 X 轴为 12 个月（便于老年用户阅读）
 */
class ChartActivity : AppCompatActivity() {

    private enum class PeriodMode { DAY, MONTH, QUARTER, YEAR }

    private lateinit var binding: ActivityChartBinding
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var lineChart: LineChart
    private lateinit var barChart: HorizontalBarChart

    private var periodMode = PeriodMode.MONTH
    private val selCal: Calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = DatabaseHelper(this)

        initCharts()
        setupToggle()
        setupPeriodSelector()
        setupBottomNav()

        // 默认选中「按月」并触发一次刷新（会调用 resetSelectionToNow + refresh）
        binding.togglePeriod.check(R.id.btnPeriodMonth)
    }

    // ==================== 图表初始化 ====================

    private fun initCharts() {
        lineChart = LineChart(this).apply {
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

        barChart = HorizontalBarChart(this).apply {
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

    /** 根据当前周期模式，加载所选周期内的全部记录 */
    private fun loadRangeRecords(keys: List<String>): List<Record> {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        return when (periodMode) {
            // 按日：最近 7 天窗口，逐天查询后合并
            PeriodMode.DAY -> keys.flatMap { dbHelper.getRecordsByDate(it) }
            PeriodMode.MONTH -> dbHelper.getRecordsByMonth(y, m)
            PeriodMode.QUARTER -> dbHelper.getRecordsByQuarter(y, quarterOf(m))
            PeriodMode.YEAR -> dbHelper.getRecordsByYear(y)
        }
    }

    /** 生成所选周期的 X 轴 key 列表（按日颗粒度用 yyyy-MM-dd，按年用 yyyy-MM） */
    private fun getRangeKeys(): List<String> {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        return when (periodMode) {
            PeriodMode.DAY -> {
                // 最近 7 天（含选中日），从早到晚
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

        // 买入：绿色实线
        val buySet = LineDataSet(buyEntries, "买入").apply {
            setColor(GREEN)
            setCircleColor(GREEN)
            lineWidth = 2f
            circleRadius = 3f
            setDrawCircles(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        // 卖出：橙色虚线
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

    // ==================== 横向柱状图（类型占比） ====================

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

        // 按金额降序
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
        val tv = TextView(this).apply {
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
            // yyyy-MM -> "M月"
            val mm = key.substring(5, 7).toIntOrNull() ?: return key
            "${mm}月"
        } else {
            // yyyy-MM-dd -> "M/D"
            val parts = key.split("-")
            if (parts.size >= 3) {
                "${parts[1].toIntOrNull() ?: parts[1]}/${parts[2].toIntOrNull() ?: parts[2]}"
            } else {
                key
            }
        }
    }

    // ==================== 底部导航 ====================

    private fun setupBottomNav() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
        nav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_record -> "com.farmtrade.app.ui.RecordListActivity"
                R.id.nav_chart -> "com.farmtrade.app.ui.ChartActivity"
                R.id.nav_summary -> "com.farmtrade.app.ui.SummaryActivity"
                R.id.nav_settings -> "com.farmtrade.app.ui.SettingsActivity"
                else -> null
            }
            if (target != null && target != javaClass.name) {
                navigateTo(target)
            }
            true
        }
        nav.selectedItemId = R.id.nav_chart
    }

    private fun navigateTo(className: String) {
        try {
            val intent = Intent().setClassName(packageName, className)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "页面暂未开放", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private val GREEN = Color.parseColor("#2e7d32")
        private val ORANGE = Color.parseColor("#e65100")
    }
}
