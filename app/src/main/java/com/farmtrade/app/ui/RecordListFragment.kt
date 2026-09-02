package com.farmtrade.app.ui

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.DialogQuickAddBinding
import com.farmtrade.app.databinding.FragmentRecordListBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 买卖记录列表 Fragment。
 *
 * - 顶部绿色标题栏 + 过滤芯片（全部 / 买入 / 卖出）+ 日期范围选择器
 * - 汇总卡片：可切换 今日/本月/本季/本年 的买入总额 / 卖出总额 / 净额
 * - 记录列表 RecyclerView
 * - FloatingActionButton 打开"快速添加"底部弹窗
 *
 * 列表条目点击进入 AddRecordActivity 编辑；长按弹出删除确认弹窗。
 */
@Suppress("DEPRECATION")
class RecordListFragment : Fragment(), RecordAdapter.OnRecordClickListener {

    private var _binding: FragmentRecordListBinding? = null
    private val binding get() = _binding!!

    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var adapter: RecordAdapter

    /** SharedPreferences 持久化用户偏好（排序/筛选/汇总范围） */
    private lateinit var sp: SharedPreferences

    /** 全部记录（未过滤，按时间倒序） */
    private var allRecords: List<Record> = emptyList()

    /** 交易方向筛选：全部 / 买入 / 卖出 */
    private var tradeFilter: Int = TRADE_ALL

    /** 日期范围筛选：RANGE_NONE / TODAY / MONTH / QUARTER / YEAR / CUSTOM */
    private var dateRangePreset: Int = RANGE_NONE

    /** 自定义日期范围（当 dateRangePreset == RANGE_CUSTOM 时生效），格式 "yyyy-MM-dd" */
    private var customDateStart: String? = null
    private var customDateEnd: String? = null

    /** 汇总卡片范围：SUMMARY_TODAY / MONTH / QUARTER / YEAR */
    private var summaryRange: Int = SUMMARY_TODAY

    /** 搜索关键词（按日期筛选，如 "2025-08" 或 "2025-08-15" 或 "08-15"） */
    private var searchQuery: String? = null

    /** 排序模式 */
    private var sortMode: Int = SORT_TIME_DESC

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        databaseHelper = DatabaseHelper(requireContext())

        // 读取持久化偏好
        sp = requireContext().getSharedPreferences(PREFS_NAME, 0)
        sortMode = sp.getInt(KEY_SORT, SORT_TIME_DESC)
        tradeFilter = sp.getInt(KEY_TRADE, TRADE_ALL)
        summaryRange = sp.getInt(KEY_SUMMARY, SUMMARY_TODAY)

        setupRecyclerView()
        setupTradeFilter()
        setupDateRangeFilter()
        setupSummaryRange()
        setupFab()
        setupBatchManage()
        setupSearch()
        setupSort()

        // 恢复 ChipGroup 选中
        restoreChipSelection()

        loadRecords()
    }

    /** 恢复 ChipGroup 选中状态（setupTradeFilter 里的 setOnCheckedChangeListener 会触发 applyFilter，
     * 但因为 loadRecords 尚未执行，所以先 restore 再 loadRecords） */
    private fun restoreChipSelection() {
        val chipId = when (tradeFilter) {
            TRADE_BUY -> R.id.chipBuy
            TRADE_SELL -> R.id.chipSell
            else -> R.id.chipAll
        }
        // 临时移除 listener，避免触发 applyFilter（此时 allRecords 还空）
        binding.chipGroupFilter.setOnCheckedChangeListener(null)
        binding.chipGroupFilter.check(chipId)
        // 恢复 listener
        setupTradeFilter()
        updateDateFilterChipLabel()
        updateSummaryRangeUI()
    }

    /** 保存偏好：排序/交易方向/汇总范围 */
    private fun savePrefs() {
        sp.edit()
            .putInt(KEY_SORT, sortMode)
            .putInt(KEY_TRADE, tradeFilter)
            .putInt(KEY_SUMMARY, summaryRange)
            .apply()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 初始化 ====================

    private fun setupRecyclerView() {
        adapter = RecordAdapter(this)
        binding.rvRecordList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecordList.adapter = adapter
    }

    private fun setupTradeFilter() {
        binding.chipGroupFilter.setOnCheckedChangeListener { _, checkedId ->
            exitMultiSelectIfActive()
            tradeFilter = when (checkedId) {
                R.id.chipBuy -> TRADE_BUY
                R.id.chipSell -> TRADE_SELL
                else -> TRADE_ALL
            }
            savePrefs()
            applyFilter()
        }
    }

    /** 日期范围筛选 chip：点击弹出预设 + 自定义对话框 */
    private fun setupDateRangeFilter() {
        binding.chipDateFilter.setOnClickListener {
            exitMultiSelectIfActive()
            showDateRangeDialog()
        }
        updateDateFilterChipLabel()
    }

    /** 汇总卡片标题：点击弹出范围选择 */
    private fun setupSummaryRange() {
        binding.tvSummaryTitle.setOnClickListener {
            val options = arrayOf("今日汇总 ▼", "本月汇总 ▼", "本季汇总 ▼", "本年汇总 ▼")
            val values = intArrayOf(SUMMARY_TODAY, SUMMARY_MONTH, SUMMARY_QUARTER, SUMMARY_YEAR)
            val idx = values.indexOf(summaryRange)
            AlertDialog.Builder(requireContext())
                .setTitle("选择汇总范围")
                .setSingleChoiceItems(arrayOf("今日", "本月", "本季", "本年"), idx) { dialog, which ->
                    summaryRange = values[which]
                    savePrefs()
                    updateSummaryRangeUI()
                    updateSummary()
                    dialog.dismiss()
                }
                .show()
        }
    }

    /** 更新日期筛选 chip 的文字（显示当前状态） */
    private fun updateDateFilterChipLabel() {
        val label = when (dateRangePreset) {
            RANGE_TODAY -> "今日 ▼"
            RANGE_MONTH -> "本月 ▼"
            RANGE_QUARTER -> "本季 ▼"
            RANGE_YEAR -> "本年 ▼"
            RANGE_CUSTOM -> {
                val s = customDateStart ?: ""
                val e = customDateEnd ?: ""
                if (s.isNotEmpty() && e.isNotEmpty()) "$s ~ $e ▼" else "全部时间 ▼"
            }
            else -> "全部时间 ▼"
        }
        binding.chipDateFilter.text = label
    }

    /** 更新汇总卡片标题和方向标签文字 */
    private fun updateSummaryRangeUI() {
        val (title, buyLabel, sellLabel) = when (summaryRange) {
            SUMMARY_MONTH -> Triple("本月汇总 ▼", "本月买入", "本月卖出")
            SUMMARY_QUARTER -> Triple("本季汇总 ▼", "本季买入", "本季卖出")
            SUMMARY_YEAR -> Triple("本年汇总 ▼", "本年买入", "本年卖出")
            else -> Triple("今日汇总 ▼", "今日买入", "今日卖出")
        }
        binding.tvSummaryTitle.text = title
        binding.tvBuyLabel.text = buyLabel
        binding.tvSellLabel.text = sellLabel
    }

    /** 日期范围选择对话框 */
    private fun showDateRangeDialog() {
        val currentIdx = when (dateRangePreset) {
            RANGE_NONE -> 0
            RANGE_TODAY -> 1
            RANGE_MONTH -> 2
            RANGE_QUARTER -> 3
            RANGE_YEAR -> 4
            RANGE_CUSTOM -> 5
            else -> 0
        }
        AlertDialog.Builder(requireContext())
            .setTitle("日期范围筛选")
            .setSingleChoiceItems(
                arrayOf("全部时间", "今日", "本月", "本季", "本年", "自定义范围..."),
                currentIdx
            ) { dialog, which ->
                when (which) {
                    0 -> { dateRangePreset = RANGE_NONE; customDateStart = null; customDateEnd = null }
                    1 -> { dateRangePreset = RANGE_TODAY; customDateStart = null; customDateEnd = null }
                    2 -> { dateRangePreset = RANGE_MONTH; customDateStart = null; customDateEnd = null }
                    3 -> { dateRangePreset = RANGE_QUARTER; customDateStart = null; customDateEnd = null }
                    4 -> { dateRangePreset = RANGE_YEAR; customDateStart = null; customDateEnd = null }
                    5 -> {
                        dialog.dismiss()
                        showCustomDateRangeDialog()
                        return@setSingleChoiceItems
                    }
                }
                updateDateFilterChipLabel()
                applyFilter()
                dialog.dismiss()
            }
            .show()
    }

    /** 自定义日期范围：两个 DatePickerDialog */
    private fun showCustomDateRangeDialog() {
        val calendar = java.util.Calendar.getInstance()
        val today = dateStr("yyyy-MM-dd")

        // 先选开始日期
        val startPicker = android.app.DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                customDateStart = "%04d-%02d-%02d".format(y, m + 1, d)
                // 再选结束日期
                android.app.DatePickerDialog(
                    requireContext(),
                    { _, y2, m2, d2 ->
                        customDateEnd = "%04d-%02d-%02d".format(y2, m2 + 1, d2)
                        // 确保 start <= end
                        val s = customDateStart!!
                        val e = customDateEnd!!
                        if (s > e) {
                            customDateStart = e; customDateEnd = s
                        }
                        dateRangePreset = RANGE_CUSTOM
                        updateDateFilterChipLabel()
                        applyFilter()
                    },
                    calendar.get(java.util.Calendar.YEAR),
                    calendar.get(java.util.Calendar.MONTH),
                    calendar.get(java.util.Calendar.DAY_OF_MONTH)
                ).apply {
                    setTitle("选择结束日期（含）")
                    show()
                }
            },
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        )
        startPicker.setTitle("选择开始日期（含）")
        startPicker.show()
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener { showQuickAddDialog() }
    }

    // ==================== 搜索 ====================

    private fun setupSearch() {
        binding.ivSearch.setOnClickListener {
            val edit = android.widget.EditText(requireContext())
            edit.hint = "输入日期，如 2025-08 或 8-15"
            edit.inputType = android.text.InputType.TYPE_CLASS_TEXT
            edit.setText(searchQuery ?: "")
            edit.selectAll()
            val container = android.widget.FrameLayout(requireContext()).apply {
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                addView(edit)
            }
            AlertDialog.Builder(requireContext())
                .setTitle(if (searchQuery != null) "搜索（当前: $searchQuery）" else "按日期搜索")
                .setMessage("可输入:\n• 2025-08 → 某月\n• 2025-08-15 → 某天\n• 08-15 → 某天(任意年份)")
                .setView(container)
                .setPositiveButton("搜索") { _, _ ->
                    searchQuery = edit.text.toString().trim().ifEmpty { null }
                    applyFilter()
                }
                .setNeutralButton("清除") { _, _ ->
                    searchQuery = null
                    applyFilter()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ==================== 排序 ====================

    private fun setupSort() {
        binding.ivSort.setOnClickListener {
            exitMultiSelectIfActive()
            val modes = arrayOf(
                "默认（按时间倒序）",
                "类型→单价分组（单价↓）",
                "类型→单价分组（单价↑）",
                "日期→类型→单价分组（单价↓）",
                "日期→类型→单价分组（单价↑）"
            )
            val labels = arrayOf("时间", "类型↓", "类型↑", "日期↓", "日期↑")
            AlertDialog.Builder(requireContext())
                .setTitle("排序方式")
                .setSingleChoiceItems(modes, sortMode) { dialog, which ->
                    sortMode = which
                    savePrefs()
                    applyFilter()
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "已切换排序: ${labels[which]}", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    // ==================== 批量管理 ====================

    private fun setupBatchManage() {
        binding.btnBatchManage.setOnClickListener {
            if (adapter.isMultiSelect()) {
                adapter.exitMultiSelect()
                showNormalMode()
            } else {
                adapter.enterMultiSelect()
                showBatchMode()
            }
        }
        binding.btnSelectAll.setOnClickListener {
            adapter.selectAllOrNone()
        }
        binding.btnBatchDelete.setOnClickListener {
            val ids = adapter.getSelectedIds()
            if (ids.isEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setMessage("请先勾选要删除的记录")
                    .setPositiveButton("知道了", null)
                    .show()
                return@setOnClickListener
            }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("批量删除")
                .setMessage("确定删除选中的 ${ids.size} 条记录吗？此操作不可撤销。")
                .setPositiveButton("删除") { _, _ ->
                    ids.forEach { databaseHelper.deleteRecord(it) }
                    adapter.exitMultiSelect()
                    showNormalMode()
                    loadRecords()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showBatchMode() {
        binding.btnBatchManage.text = "完成"
        binding.btnSelectAll.visibility = View.VISIBLE
        binding.btnBatchDelete.visibility = View.VISIBLE
        binding.tvSelectedCount.visibility = View.VISIBLE
        binding.fabAdd.visibility = View.GONE
    }

    private fun showNormalMode() {
        binding.btnBatchManage.text = "管理"
        binding.btnSelectAll.visibility = View.GONE
        binding.btnBatchDelete.visibility = View.GONE
        binding.tvSelectedCount.visibility = View.GONE
        binding.tvSelectedCount.text = ""
        binding.fabAdd.visibility = View.VISIBLE
    }

    override fun onSelectionChanged(selectedCount: Int) {
        binding.tvSelectedCount.text = "已选 $selectedCount 条"
        binding.btnBatchDelete.isEnabled = selectedCount > 0
        binding.btnBatchDelete.alpha = if (selectedCount > 0) 1.0f else 0.4f
        // 全选按钮文字 toggle
        binding.btnSelectAll.text = if (adapter.isAllSelected()) "取消全选" else "全选"
    }

    /** 切换排序/过滤前退出多选，保持 UI 和数据一致 */
    private fun exitMultiSelectIfActive() {
        if (adapter.isMultiSelect()) {
            adapter.exitMultiSelect()
            showNormalMode()
        }
    }

    // ==================== 数据加载与过滤 ====================

    /**
     * 从数据库加载全部记录，刷新列表与今日汇总。
     */
    fun loadRecords() {
        allRecords = databaseHelper.getAllRecords()
        applyFilter()
        updateSummary()
    }

    private fun applyFilter() {
        // 交易方向筛选
        var filtered = when (tradeFilter) {
            TRADE_BUY -> allRecords.filter { it.direction == "买入" }
            TRADE_SELL -> allRecords.filter { it.direction == "卖出" }
            else -> allRecords
        }
        // 日期范围筛选
        filtered = filtered.filter { record -> isInDateRange(record.dateTime) }
        // 搜索过滤：支持 yyyy-MM / yyyy-MM-dd / M-d / MM-dd 等格式
        searchQuery?.let { q ->
            val normalized = normalizeSearchQuery(q)
            filtered = filtered.filter { record ->
                record.dateTime.contains(normalized) || record.dateTime.contains(q)
            }
        }
        // 排序
        when (sortMode) {
            SORT_TYPE_PRICE_DESC -> adapter.updateGroupedByType(filtered, priceDesc = true)
            SORT_TYPE_PRICE_ASC -> adapter.updateGroupedByType(filtered, priceDesc = false)
            SORT_DATE_TYPE_PRICE_DESC -> adapter.updateGroupedByDate(filtered, priceDesc = true)
            SORT_DATE_TYPE_PRICE_ASC -> adapter.updateGroupedByDate(filtered, priceDesc = false)
            else -> {
                // 默认时间倒序
                val sorted = filtered.sortedByDescending { it.dateTime }
                adapter.updateFlat(sorted)
            }
        }
    }

    /** 判断 dateTime (yyyy-MM-dd HH:mm:ss) 是否在当前日期范围内 */
    private fun isInDateRange(dateTime: String): Boolean {
        if (dateRangePreset == RANGE_NONE) return true
        val datePart = dateTime.substringBefore(' ') // yyyy-MM-dd
        val (start, end) = getDateRangeBounds(dateRangePreset, customDateStart, customDateEnd)
        return datePart >= start && datePart <= end
    }

    /** 汇总卡片：按 summaryRange 计算买入/卖出/净额 */
    private fun updateSummary() {
        val (start, end) = getSummaryRangeBounds(summaryRange)
        val records = allRecords.filter { record ->
            val datePart = record.dateTime.substringBefore(' ')
            datePart >= start && datePart <= end
        }

        var buyTotal = 0.0
        var sellTotal = 0.0
        records.forEach { r ->
            when (r.direction) {
                "买入" -> buyTotal += r.totalAmount
                "卖出" -> sellTotal += r.totalAmount
            }
        }
        val net = sellTotal - buyTotal

        binding.tvTodayBuy.text = "￥${Record.formatMoney(buyTotal)}"
        binding.tvTodaySell.text = "￥${Record.formatMoney(sellTotal)}"
        binding.tvTodayNet.text = "￥${Record.formatMoney(net)}"
    }

    // ==================== 快速添加底部弹窗 ====================

    private fun showQuickAddDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetBinding = DialogQuickAddBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.btnPhotoRecord.setOnClickListener {
            dialog.dismiss()
            startQuickRecord(Record.SOURCE_PHOTO)
        }
        sheetBinding.btnVoiceRecord.setOnClickListener {
            dialog.dismiss()
            startQuickRecord(Record.SOURCE_VOICE)
        }
        sheetBinding.btnLedgerRecord.setOnClickListener {
            dialog.dismiss()
            startQuickRecord(QuickRecordActivity.EXTRA_MODE_LEDGER)
        }
        sheetBinding.btnManualBatch.setOnClickListener {
            dialog.dismiss()
            // 手动批量：直接跳转 LedgerReviewActivity，不传 JSON 即为手动模式
            val intent = Intent(requireContext(), LedgerReviewActivity::class.java)
            startActivityForResult(intent, REQ_QUICK_RECORD)
        }
        sheetBinding.btnManualRecord.setOnClickListener {
            dialog.dismiss()
            startActivityForResult(
                Intent(requireContext(), AddRecordActivity::class.java),
                REQ_ADD_RECORD
            )
        }
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun startQuickRecord(mode: String) {
        val intent = Intent(requireContext(), QuickRecordActivity::class.java)
        intent.putExtra(EXTRA_RECORD_MODE, mode)
        startActivityForResult(intent, REQ_QUICK_RECORD)
    }

    // ==================== 记录条目点击 / 长按 ====================

    override fun onItemClick(record: Record, position: Int) {
        val intent = Intent(requireContext(), AddRecordActivity::class.java)
        intent.putExtra(EXTRA_RECORD, record)
        startActivityForResult(intent, REQ_ADD_RECORD)
    }

    override fun onItemLongClick(record: Record, position: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除记录")
            .setMessage("确定删除该条「${record.type}」记录吗？")
            .setPositiveButton("删除") { _, _ ->
                databaseHelper.deleteRecord(record.id)
                loadRecords()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 返回刷新 ====================

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            loadRecords()
        }
    }

    // ==================== 工具方法 ====================

    private fun dateStr(pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    /** 把 "8-5" → "08-05"，"2025-8-15" → "2025-08-15" */
    private fun normalizeSearchQuery(q: String): String {
        return q.split("-").joinToString("-") { part ->
            part.trim().padStart(2, '0')
        }
    }

    /**
     * 获取日期范围的 start/end (yyyy-MM-dd)，用于列表筛选。
     * 当 preset == RANGE_CUSTOM 时使用 customStart/customEnd。
     */
    private fun getDateRangeBounds(
        preset: Int,
        customStart: String?,
        customEnd: String?
    ): Pair<String, String> {
        val cal = java.util.Calendar.getInstance()
        val today = dateStr("yyyy-MM-dd")
        return when (preset) {
            RANGE_TODAY -> Pair(today, today)
            RANGE_MONTH -> {
                val firstDay = cal.apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }
                    .time.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it) }
                val lastDay = cal.apply {
                    set(java.util.Calendar.DAY_OF_MONTH,
                        cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                }.time.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it) }
                Pair(firstDay, lastDay)
            }
            RANGE_QUARTER -> getQuarterRangeBounds(cal)
            RANGE_YEAR -> {
                val year = cal.get(java.util.Calendar.YEAR)
                Pair("$year-01-01", "$year-12-31")
            }
            RANGE_CUSTOM -> Pair(customStart ?: "0000-00-00", customEnd ?: "9999-12-31")
            else -> Pair("0000-00-00", "9999-12-31")
        }
    }

    /** 获取汇总卡片范围的 start/end */
    private fun getSummaryRangeBounds(range: Int): Pair<String, String> {
        val cal = java.util.Calendar.getInstance()
        val today = dateStr("yyyy-MM-dd")
        return when (range) {
            SUMMARY_TODAY -> Pair(today, today)
            SUMMARY_MONTH -> {
                val firstDay = cal.apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }
                    .time.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it) }
                val lastDay = cal.apply {
                    set(java.util.Calendar.DAY_OF_MONTH,
                        cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                }.time.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it) }
                Pair(firstDay, lastDay)
            }
            SUMMARY_QUARTER -> getQuarterRangeBounds(cal)
            SUMMARY_YEAR -> {
                val year = cal.get(java.util.Calendar.YEAR)
                Pair("$year-01-01", "$year-12-31")
            }
            else -> Pair(today, today)
        }
    }

    /** 自然季度范围：Q1(1-3月), Q2(4-6月), Q3(7-9月), Q4(10-12月) */
    private fun getQuarterRangeBounds(cal: java.util.Calendar): Pair<String, String> {
        val year = cal.get(java.util.Calendar.YEAR)
        val month0 = cal.get(java.util.Calendar.MONTH) // 0-based: 0=Jan
        val quarter = month0 / 3 // 0, 1, 2, 3
        val startMonth = quarter * 3 + 1 // 1, 4, 7, 10
        val endMonth = startMonth + 2    // 3, 6, 9, 12
        val endDay = cal.apply {
            set(java.util.Calendar.YEAR, year)
            set(java.util.Calendar.MONTH, endMonth - 1)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val start = "%04d-%02d-01".format(year, startMonth)
        val end = "%04d-%02d-%02d".format(year, endMonth, endDay)
        return Pair(start, end)
    }

    companion object {
        // SharedPreferences
        private const val PREFS_NAME = "record_list_prefs"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_TRADE = "trade_filter"
        private const val KEY_SUMMARY = "summary_range"

        // 交易方向筛选
        private const val TRADE_ALL = 0
        private const val TRADE_BUY = 1
        private const val TRADE_SELL = 2

        // 日期范围筛选（列表）
        private const val RANGE_NONE = 0
        private const val RANGE_TODAY = 1
        private const val RANGE_MONTH = 2
        private const val RANGE_QUARTER = 3
        private const val RANGE_YEAR = 4
        private const val RANGE_CUSTOM = 5

        // 汇总卡片范围
        private const val SUMMARY_TODAY = 0
        private const val SUMMARY_MONTH = 1
        private const val SUMMARY_QUARTER = 2
        private const val SUMMARY_YEAR = 3

        // 排序
        private const val SORT_TIME_DESC = 0
        private const val SORT_TYPE_PRICE_DESC = 1
        private const val SORT_TYPE_PRICE_ASC = 2
        private const val SORT_DATE_TYPE_PRICE_DESC = 3
        private const val SORT_DATE_TYPE_PRICE_ASC = 4

        private const val REQ_ADD_RECORD = 1001
        private const val REQ_QUICK_RECORD = 1002

        const val EXTRA_RECORD = "extra_record"
        const val EXTRA_RECORD_MODE = "extra_record_mode"
    }
}
