package com.farmtrade.app.ui

import android.app.DatePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.FragmentChartBinding
import com.google.android.material.chip.Chip
import java.util.Calendar

/**
 * 数据统计 Fragment —— 纯「文字 + 数字」呈现，不再画图。
 *
 * - 周期切换：日 / 月 / 季 / 年
 * - 日期前后翻页
 * - 统计范围：全部记录 / 拍照识别 / 手动录入（可按来源剔除）
 * - 本期合计：笔数、合计金额、净重
 * - 按货物类型明细：每类的笔数与金额
 * - 按时间明细：每个有记录的时间点/月份的笔数与金额
 */
class ChartFragment : Fragment() {

    private enum class PeriodMode { DAY, MONTH, QUARTER, YEAR }

    private var _binding: FragmentChartBinding? = null
    private val binding get() = _binding!!

    private lateinit var dbHelper: DatabaseHelper

    private var periodMode = PeriodMode.MONTH
    /** 货物类型筛选；null = 全部 */
    private var typeFilter: String? = null
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
        setupToggle()
        setupPeriodSelector()
        setupDatePicker()
        setupTypeFilter()
        binding.togglePeriod.check(binding.btnPeriodMonth.id)
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 交互 ====================

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
        }
    }

    private fun setupPeriodSelector() {
        binding.btnPrev.setOnClickListener { shiftPeriod(-1) }
        binding.btnNext.setOnClickListener { shiftPeriod(1) }
    }

    /** 点日期文字直接弹日历选，不用一页页翻 */
    private fun setupDatePicker() {
        binding.tvPeriodLabel.setOnClickListener {
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    when (periodMode) {
                        PeriodMode.DAY -> selCal.set(year, month, day)
                        PeriodMode.MONTH -> selCal.set(year, month, 1)
                        PeriodMode.QUARTER -> selCal.set(year, month, 1)
                        PeriodMode.YEAR -> selCal.set(year, 0, 1)
                    }
                    refresh()
                },
                selCal.get(Calendar.YEAR),
                selCal.get(Calendar.MONTH),
                selCal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    /** 按货物类型筛选：全部 + 数据库里的所有类型 */
    private fun setupTypeFilter() {
        val group = binding.chipGroupTypeFilter
        group.removeAllViews()
        val types = mutableListOf("全部")
        types.addAll(dbHelper.getAllTypes().filter { it.isNotBlank() })

        types.forEachIndexed { index, name ->
            val chip = Chip(requireContext()).apply {
                text = name
                isCheckable = true
                isChecked = index == 0
                textSize = 14f
            }
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { g, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chip = g.findViewById<Chip>(checkedIds[0]) ?: return@setOnCheckedStateChangeListener
            typeFilter = if (chip.text.toString() == "全部") null else chip.text.toString()
            refresh()
        }
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
        val now = Calendar.getInstance()
        selCal.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
        refresh()
    }

    // ==================== 渲染 ====================

    private fun refresh() {
        updateLabel()
        var records = loadRangeRecords()
        typeFilter?.let { t -> records = records.filter { it.type == t } }
        records = records.sortedBy { it.dateTime }
        updateOverview(records)
        updateTypeTable(records)
        updatePeriodTable(records)
    }

    private fun updateLabel() {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        val d = selCal.get(Calendar.DAY_OF_MONTH)
        val label = when (periodMode) {
            PeriodMode.DAY -> String.format("%d年%d月%d日", y, m, d)
            PeriodMode.MONTH -> String.format("%d年%d月", y, m)
            PeriodMode.QUARTER -> String.format("%d年Q%d", y, quarterOf(m))
            PeriodMode.YEAR -> String.format("%d年", y)
        }
        binding.tvPeriodLabel.text = label + " ▼"
    }

    private fun loadRangeRecords(): List<Record> {
        val y = selCal.get(Calendar.YEAR)
        val m = selCal.get(Calendar.MONTH) + 1
        return when (periodMode) {
            PeriodMode.DAY -> {
                // 日视图给最近 7 天，方便对比
                val list = mutableListOf<Record>()
                for (i in 6 downTo 0) {
                    val c = (selCal.clone() as Calendar)
                    c.add(Calendar.DAY_OF_YEAR, -i)
                    list.addAll(dbHelper.getRecordsByDate(formatDateKey(c)))
                }
                list
            }
            PeriodMode.MONTH -> dbHelper.getRecordsByMonth(y, m)
            PeriodMode.QUARTER -> dbHelper.getRecordsByQuarter(y, quarterOf(m))
            PeriodMode.YEAR -> dbHelper.getRecordsByYear(y)
        }
    }

    private fun updateOverview(records: List<Record>) {
        binding.tvStatCount.text = records.size.toString()
        binding.tvStatAmount.text = "￥" + Record.formatMoney(records.sumOf { it.totalAmount })
    }

    /** 某批记录的净重合计，统一换算成「斤」 */
    private fun netJinOf(records: List<Record>): Double = records
        .filter { it.measureMode == Record.MODE_WEIGHT_KG || it.measureMode == Record.MODE_WEIGHT_JIN }
        .sumOf { r ->
            val net = r.grossWeight - r.vehicleWeight
            if (r.measureMode == Record.MODE_WEIGHT_KG) net * 2 else net
        }

    private fun updateTypeTable(records: List<Record>) {
        val container = binding.llTypeRows
        container.removeAllViews()
        if (records.isEmpty()) {
            binding.tvTypeEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvTypeEmpty.visibility = View.GONE
        val green = ContextCompat.getColor(requireContext(), R.color.green_primary)
        records.groupBy { it.type.ifBlank { "未分类" } }
            .mapValues { (_, list) ->
                Triple(list.sumOf { it.totalAmount }, list.size, netJinOf(list))
            }
            .toList()
            .sortedByDescending { it.second.first }
            .forEach { (type, info) ->
                addTableRow(
                    container,
                    type,
                    "${info.second} 笔",
                    Record.formatNumber(info.third),
                    "￥" + Record.formatMoney(info.first),
                    green
                )
            }
    }

    private fun updatePeriodTable(records: List<Record>) {
        val container = binding.llPeriodRows
        container.removeAllViews()
        if (records.isEmpty()) {
            binding.tvPeriodEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvPeriodEmpty.visibility = View.GONE

        val byMonthGranularity = periodMode == PeriodMode.YEAR
        val grouped = records.groupBy {
            if (byMonthGranularity) it.dateTime.substring(0, 7) else it.dateTime.substring(0, 10)
        }.toList().sortedBy { it.first }

        for ((key, list) in grouped) {
            addTableRow(
                container,
                labelOfKey(key, byMonthGranularity),
                "${list.size} 笔",
                null,
                "￥" + Record.formatMoney(list.sumOf { it.totalAmount })
            )
        }
    }

    private fun labelOfKey(key: String, monthGranularity: Boolean): String {
        return if (monthGranularity) {
            val mm = key.substring(5, 7).toIntOrNull() ?: return key
            "${mm}月"
        } else {
            val parts = key.split("-")
            if (parts.size >= 3) {
                "${parts[1].toIntOrNull() ?: parts[1]}/${parts[2].toIntOrNull() ?: parts[2]}"
            } else key
        }
    }

    /** 表格行：名称 | 笔数 | 净重 | 金额（净重传 null 时只渲染三列），末尾附一条分隔线 */
    private fun addTableRow(
        container: LinearLayout,
        name: String,
        count: String,
        netWeight: String?,
        amount: String,
        amountColor: Int? = null
    ) {
        val ctx = requireContext()
        val primary = ContextCompat.getColor(ctx, R.color.text_primary)
        val secondary = ContextCompat.getColor(ctx, R.color.text_secondary)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(11f), 0, dp(11f))
        }
        if (netWeight != null) {
            row.addView(cell(name, 1.0f, Gravity.START, color = primary))
            row.addView(cell(count, 0.55f, Gravity.CENTER, color = secondary, size = 13f))
            row.addView(cell(netWeight, 1.05f, Gravity.CENTER, color = secondary, size = 13f))
            row.addView(cell(amount, 1.15f, Gravity.END, bold = true, size = 15f, color = amountColor ?: primary))
        } else {
            row.addView(cell(name, 1.3f, Gravity.START, color = primary))
            row.addView(cell(count, 0.7f, Gravity.CENTER, color = secondary, size = 13f))
            row.addView(cell(amount, 1.2f, Gravity.END, bold = true, size = 15f, color = amountColor ?: primary))
        }
        container.addView(row)

        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider))
        })
    }

    private fun cell(
        text: String,
        weight: Float,
        gravity: Int,
        bold: Boolean = false,
        size: Float = 15f,
        color: Int
    ): TextView = TextView(requireContext()).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        this.gravity = gravity
        setTextColor(color)
        textSize = size
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    // ==================== 日期工具 ====================

    private fun quarterOf(month: Int): Int = (month - 1) / 3 + 1

    private fun formatDateKey(c: Calendar): String = String.format(
        "%04d-%02d-%02d",
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH)
    )
}
