package com.farmtrade.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.ActivitySummaryBinding
import com.farmtrade.app.util.ExportHelper
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Calendar
import java.util.HashMap

/**
 * 汇总统计页面。
 *
 * 功能：
 * - 周期切换：当日 / 本月 / 本季 / 本年
 * - 日期选择器：◀ 上一个 / 标签 / ▶ 下一个（按 1 天/月/季/年 移动）
 * - 三张汇总卡片：买入总额(橙)、卖出总额(绿)、净收益(正绿负橙，带 +/- 前缀)
 * - 分组柱状图：周期对比（如查看 Q3，则显示 Q1/Q2/Q3 的买卖对比柱）
 * - 类型明细列表：所选周期内各类型总金额
 * - 导出当前周期数据为 Excel
 * - 底部导航：记录 / 图表 / 汇总(选中) / 设置
 */
class SummaryActivity : AppCompatActivity() {

    private enum class SummaryMode { TODAY, MONTH, QUARTER, YEAR }

    /** 一组对比数据：标签、买入、卖出 */
    private data class CompBar(val label: String, val buy: Double, val sell: Double)

    private lateinit var binding: ActivitySummaryBinding
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var barChart: BarChart

    private var summaryMode = SummaryMode.MONTH
    private val selCal: Calendar = Calendar.getInstance()

    private val typeAdapter = TypeBreakdownAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = DatabaseHelper(this)

        initBarChart()
        setupToggle()
        setupDateSelector()
        setupExportButton()
        setupTypeList()
        setupBottomNav()

        // 默认选中「本月」并触发一次刷新
        binding.togglePeriod.check(R.id.btnPeriodMonth)
    }

    // ==================== 初始化 ====================

    private fun initBarChart() {
        barChart = BarChart(this).apply {
            description.isEnabled = false
            legend.apply {
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                orientation = Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
                textSize = 12f
            }
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                setDrawGridLines(false)
                setCenterAxisLabels(true)
                textSize = 11f
            }
            axisLeft.axisMinimum = 0f
            axisLeft.textSize = 11f
            axisRight.isEnabled = false
            setFitBars(true)
            setNoDataText("暂无数据")
        }
        replaceView(binding.frameBarChart, barChart)
    }

    private fun setupToggle() {
        binding.togglePeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            summaryMode = when (checkedId) {
                R.id.btnPeriodToday -> SummaryMode.TODAY
                R.id.btnPeriodMonth -> SummaryMode.MONTH
                R.id.btnPeriodQuarter -> SummaryMode.QUARTER
                R.id.btnPeriodYear -> SummaryMode.YEAR
                else -> return@addOnButtonCheckedListener
            }
            resetSelectionToNow()
            refresh()
        }
    }

    private fun setupDateSelector() {
        binding.btnPrevDate.setOnClickListener { shiftSelection(-1) }
        binding.btnNextDate.setOnClickListener { shiftSelection(1) }
    }

    private fun setupTypeList() {
        binding.rvTypeBreakdown.layoutManager = LinearLayoutManager(this)
        binding.rvTypeBreakdown.adapter = typeAdapter
    }

    private fun shiftSelection(direction: Int) {
        when (summaryMode) {
            SummaryMode.TODAY -> selCal.add(Calendar.DAY_OF_YEAR, direction)
            SummaryMode.MONTH -> selCal.add(Calendar.MONTH, direction)
            SummaryMode.QUARTER -> selCal.add(Calendar.MONTH, direction * 3)
            SummaryMode.YEAR -> selCal.add(Calendar.YEAR, direction)
        }
        refresh()
    }

    private fun resetSelectionToNow() {
        selCal.timeInMillis = System.currentTimeMillis()
    }

    // ==================== 刷新 ====================

    private fun refresh() {
        updateLabel()
        val records = loadPeriodRecords()
        updateSummaryCards(records)
        updateComparisonChart()
        updateTypeBreakdown(records)
    }

    private fun updateLabel() {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        val d = selCal.get(Calendar.DAY_OF_MONTH)
        binding.tvDateLabel.text = when (summaryMode) {
            SummaryMode.TODAY -> String.format("%d年%d月%d日", y, m, d)
            SummaryMode.MONTH -> String.format("%d年%d月", y, m)
            SummaryMode.QUARTER -> String.format("%d年Q%d", y, quarterOf(m))
            SummaryMode.YEAR -> String.format("%d年", y)
        }
    }

    /** 加载当前所选周期的全部记录（用于汇总卡片和类型明细） */
    private fun loadPeriodRecords(): List<Record> {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        val d = selCal.get(Calendar.DAY_OF_MONTH)
        return when (summaryMode) {
            SummaryMode.TODAY -> dbHelper.getRecordsByDate(formatDateKey(y, m, d))
            SummaryMode.MONTH -> dbHelper.getRecordsByMonth(y, m)
            SummaryMode.QUARTER -> dbHelper.getRecordsByQuarter(y, quarterOf(m))
            SummaryMode.YEAR -> dbHelper.getRecordsByYear(y)
        }
    }

    // ==================== 汇总卡片 ====================

    private fun updateSummaryCards(records: List<Record>) {
        var totalBuy = 0.0
        var totalSell = 0.0
        for (r in records) {
            if (r.direction == "买入") totalBuy += r.totalAmount
            else if (r.direction == "卖出") totalSell += r.totalAmount
        }
        val net = totalSell - totalBuy

        binding.tvTotalBuy.text = "￥${Record.formatMoney(totalBuy)}"
        binding.tvTotalSell.text = "￥${Record.formatMoney(totalSell)}"

        val netText = when {
            net > 0 -> "+￥${Record.formatMoney(net)}"
            net < 0 -> "-￥${Record.formatMoney(-net)}"
            else -> "￥${Record.formatMoney(0.0)}"
        }
        binding.tvNetProfit.text = netText
        binding.tvNetProfit.setTextColor(if (net >= 0) GREEN else ORANGE)
    }

    // ==================== 周期对比柱状图 ====================

    private fun updateComparisonChart() {
        val groups = buildComparison()
        val hasData = groups.any { it.buy != 0.0 || it.sell != 0.0 }
        if (!hasData) {
            showEmptyView(binding.frameBarChart)
            return
        }

        val buyEntries = ArrayList<BarEntry>()
        val sellEntries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()
        groups.forEachIndexed { i, g ->
            buyEntries.add(BarEntry(i.toFloat(), g.buy.toFloat()))
            sellEntries.add(BarEntry(i.toFloat(), g.sell.toFloat()))
            labels.add(g.label)
        }

        // 买入：绿色
        val buySet = BarDataSet(buyEntries, "买入").apply {
            setColor(GREEN)
            setValueTextSize(9f)
            setDrawValues(false)
        }
        // 卖出：橙色
        val sellSet = BarDataSet(sellEntries, "卖出").apply {
            setColor(ORANGE)
            setValueTextSize(9f)
            setDrawValues(false)
        }

        val data = BarData(buySet, sellSet)
        val groupSpace = 0.3f
        val barSpace = 0.05f
        data.setBarWidth(0.3f)
        // 每组宽度 = (barWidth + barSpace) * 2 + groupSpace = (0.3 + 0.05) * 2 + 0.3 = 1.0

        barChart.data = data
        barChart.groupBars(0f, groupSpace, barSpace)
        barChart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(labels)
            setCenterAxisLabels(true)
            granularity = 1f
            setLabelCount(labels.size, false)
            axisMinimum = 0f
            axisMaximum = labels.size.toFloat()
        }
        barChart.notifyDataSetChanged()
        barChart.invalidate()
        replaceView(binding.frameBarChart, barChart)
    }

    /**
     * 构建周期对比数据。
     * - 当日：所选月 1 日 ~ 当日
     * - 本月：所选年 1 月 ~ 所选月
     * - 本季：所选年 Q1 ~ 所选季度
     * - 本年：所选年及前两年
     */
    private fun buildComparison(): List<CompBar> {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        return when (summaryMode) {
            SummaryMode.TODAY -> {
                val d = selCal.get(Calendar.DAY_OF_MONTH)
                val monthRecords = dbHelper.getRecordsByMonth(y, m)
                val byDay = HashMap<Int, DoubleArray>() // day -> [buy, sell]
                for (r in monthRecords) {
                    val day = r.dateTime.substring(8, 10).toIntOrNull() ?: continue
                    if (day > d) continue
                    val arr = byDay.getOrPut(day) { DoubleArray(2) }
                    if (r.direction == "买入") arr[0] += r.totalAmount else arr[1] += r.totalAmount
                }
                (1..d).map { day ->
                    val arr = byDay[day]
                    CompBar("${day}日", arr?.get(0) ?: 0.0, arr?.get(1) ?: 0.0)
                }
            }
            SummaryMode.MONTH -> {
                val yearRecords = dbHelper.getRecordsByYear(y)
                val byMonth = HashMap<Int, DoubleArray>()
                for (r in yearRecords) {
                    val month = r.dateTime.substring(5, 7).toIntOrNull() ?: continue
                    if (month > m) continue
                    val arr = byMonth.getOrPut(month) { DoubleArray(2) }
                    if (r.direction == "买入") arr[0] += r.totalAmount else arr[1] += r.totalAmount
                }
                (1..m).map { month ->
                    val arr = byMonth[month]
                    CompBar("${month}月", arr?.get(0) ?: 0.0, arr?.get(1) ?: 0.0)
                }
            }
            SummaryMode.QUARTER -> {
                val q = quarterOf(m)
                val yearRecords = dbHelper.getRecordsByYear(y)
                val byQ = HashMap<Int, DoubleArray>()
                for (r in yearRecords) {
                    val month = r.dateTime.substring(5, 7).toIntOrNull() ?: continue
                    val qq = quarterOf(month)
                    if (qq > q) continue
                    val arr = byQ.getOrPut(qq) { DoubleArray(2) }
                    if (r.direction == "买入") arr[0] += r.totalAmount else arr[1] += r.totalAmount
                }
                (1..q).map { qq ->
                    val arr = byQ[qq]
                    CompBar("Q$qq", arr?.get(0) ?: 0.0, arr?.get(1) ?: 0.0)
                }
            }
            SummaryMode.YEAR -> {
                (y - 2..y).map { year ->
                    val recs = dbHelper.getRecordsByYear(year)
                    var buy = 0.0
                    var sell = 0.0
                    for (r in recs) {
                        if (r.direction == "买入") buy += r.totalAmount else sell += r.totalAmount
                    }
                    CompBar("${year}年", buy, sell)
                }
            }
        }
    }

    // ==================== 类型明细 ====================

    private fun updateTypeBreakdown(records: List<Record>) {
        if (records.isEmpty()) {
            typeAdapter.setData(emptyList())
            return
        }
        val map = LinkedHashMap<String, Double>()
        for (r in records) {
            map[r.type] = (map[r.type] ?: 0.0) + r.totalAmount
        }
        val list = map.entries
            .sortedByDescending { it.value }
            .map { it.toPair() }
        typeAdapter.setData(list)
    }

    // ==================== 导出 ====================

    private fun setupExportButton() {
        val root = binding.root
        if (root !is ViewGroup || root.childCount == 0) return
        val header = root.getChildAt(0) as? ViewGroup ?: return
        val btn = TextView(this).apply {
            text = "导出"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(48, 8, 0, 8)
            setOnClickListener { exportCurrentPeriod() }
        }
        header.addView(btn)
    }

    private fun exportCurrentPeriod() {
        val records = loadPeriodRecords()
        if (records.isEmpty()) {
            Toast.makeText(this, "当前周期暂无数据可导出", Toast.LENGTH_SHORT).show()
            return
        }
        ExportHelper.exportToExcel(this, records, "汇总_${dateLabelForFile()}")
    }

    private fun dateLabelForFile(): String {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        val d = selCal.get(Calendar.DAY_OF_MONTH)
        return when (summaryMode) {
            SummaryMode.TODAY -> String.format("%04d-%02d-%02d", y, m, d)
            SummaryMode.MONTH -> String.format("%04d-%02d", y, m)
            SummaryMode.QUARTER -> String.format("%04d-Q%d", y, quarterOf(m))
            SummaryMode.YEAR -> y.toString()
        }
    }

    // ==================== 视图工具方法 ====================

    private fun replaceView(parent: ViewGroup, child: View) {
        parent.removeAllViews()
        parent.addView(
            child,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showEmptyView(parent: ViewGroup) {
        parent.removeAllViews()
        val tv = TextView(this).apply {
            text = "暂无数据"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#aaaaaa"))
            textSize = 16f
        }
        parent.addView(
            tv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    // ==================== 日期工具 ====================

    private fun quarterOf(month: Int): Int = (month - 1) / 3 + 1

    private fun formatDateKey(y: Int, m: Int, d: Int): String =
        String.format("%04d-%02d-%02d", y, m, d)

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
        nav.selectedItemId = R.id.nav_summary
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

    // ==================== 类型明细适配器 ====================

    private class TypeBreakdownAdapter :
        RecyclerView.Adapter<TypeBreakdownAdapter.VH>() {

        private val items = mutableListOf<Pair<String, Double>>()

        fun setData(data: List<Pair<String, Double>>) {
            items.clear()
            items.addAll(data)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(4, 14, 4, 14)
            }
            val tvName = TextView(ctx).apply {
                textSize = 18f
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val tvAmount = TextView(ctx).apply {
                textSize = 18f
                setTextColor(Color.parseColor("#2e7d32"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(tvName)
            row.addView(tvAmount)
            return VH(row, tvName, tvAmount)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, amount) = items[position]
            holder.tvName.text = name
            holder.tvAmount.text = "￥${Record.formatMoney(amount)}"
        }

        override fun getItemCount(): Int = items.size

        class VH(
            itemView: View,
            val tvName: TextView,
            val tvAmount: TextView
        ) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        private val GREEN = Color.parseColor("#2e7d32")
        private val ORANGE = Color.parseColor("#e65100")
    }
}
