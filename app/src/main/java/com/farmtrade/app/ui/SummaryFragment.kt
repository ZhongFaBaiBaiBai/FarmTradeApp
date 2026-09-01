package com.farmtrade.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.FragmentSummaryBinding
import com.farmtrade.app.util.ExportHelper
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import java.util.Calendar
import java.util.HashMap

/**
 * 汇总统计 Fragment。
 *
 * 新增：净重总和卡片（总重 - 车重 的合计）
 */
class SummaryFragment : Fragment() {

    private enum class SummaryMode { TODAY, MONTH, QUARTER, YEAR }

    private data class CompBar(val label: String, val buy: Double, val sell: Double)

    private var _binding: FragmentSummaryBinding? = null
    private val binding get() = _binding!!

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var barChart: BarChart

    private var summaryMode = SummaryMode.MONTH
    private val selCal: Calendar = Calendar.getInstance()

    private val typeAdapter = TypeBreakdownAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = DatabaseHelper(requireContext())
        initBarChart()
        setupToggle()
        setupDateSelector()
        setupExportButton()
        setupTypeList()
        binding.togglePeriod.check(R.id.btnPeriodMonth)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 初始化 ====================

    private fun initBarChart() {
        barChart = BarChart(requireContext()).apply {
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
        binding.rvTypeBreakdown.layoutManager = LinearLayoutManager(requireContext())
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
        var totalNetWeight = 0.0 // 净重总和（总重 - 车重）
        for (r in records) {
            if (r.direction == "买入") totalBuy += r.totalAmount
            else if (r.direction == "卖出") totalSell += r.totalAmount
            // 累计净重：只对计重模式的记录计算
            if (r.measureMode == Record.MODE_WEIGHT_JIN || r.measureMode == Record.MODE_WEIGHT_KG) {
                val netWeight = r.grossWeight - r.vehicleWeight
                if (netWeight > 0) {
                    // 如果是公斤模式，转换成斤显示
                    totalNetWeight += if (r.measureMode == Record.MODE_WEIGHT_KG) netWeight * 2 else netWeight
                }
            }
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

        // 净重总和
        binding.tvTotalNetWeight.text = "${Record.formatNumber(totalNetWeight)} 斤"
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

        val buySet = BarDataSet(buyEntries, "买入").apply {
            setColor(GREEN)
            setValueTextSize(9f)
            setDrawValues(false)
        }
        val sellSet = BarDataSet(sellEntries, "卖出").apply {
            setColor(ORANGE)
            setValueTextSize(9f)
            setDrawValues(false)
        }

        val data = BarData(buySet, sellSet)
        val groupSpace = 0.3f
        val barSpace = 0.05f
        data.setBarWidth(0.3f)

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

    private fun buildComparison(): List<CompBar> {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        return when (summaryMode) {
            SummaryMode.TODAY -> {
                val d = selCal.get(Calendar.DAY_OF_MONTH)
                val monthRecords = dbHelper.getRecordsByMonth(y, m)
                val byDay = HashMap<Int, DoubleArray>()
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
        // 导出功能暂时放在这里，后续可以移到标题栏
    }

    fun exportCurrentPeriod() {
        val records = loadPeriodRecords()
        if (records.isEmpty()) {
            Toast.makeText(requireContext(), "当前周期暂无数据可导出", Toast.LENGTH_SHORT).show()
            return
        }
        ExportHelper.exportToExcel(requireContext(), records, "汇总_${dateLabelForFile()}")
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
        val tv = TextView(requireContext()).apply {
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
