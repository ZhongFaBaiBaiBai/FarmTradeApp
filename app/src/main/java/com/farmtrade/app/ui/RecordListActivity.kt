package com.farmtrade.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.ActivityRecordListBinding
import com.farmtrade.app.databinding.DialogQuickAddBinding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 买卖记录主界面。
 *
 * - 顶部绿色标题栏 + 过滤芯片（全部 / 买入 / 卖出 / 当日 / 本月）
 * - 今日汇总卡片：当日买入总额 / 卖出总额 / 净额（绿色渐变背景）
 * - 记录列表 RecyclerView（数据来自 [DatabaseHelper.getAllRecords]）
 * - FloatingActionButton 打开"快速添加"底部弹窗（拍照记录 / 语音记录 / 手动填写）
 * - 底部导航：记录(当前) / 图表 / 汇总 / 设置
 *
 * 列表条目点击进入 [AddRecordActivity] 编辑；长按弹出删除确认弹窗。
 * 添加 / 编辑 / 快速记录返回后通过 [onActivityResult] 自动刷新列表。
 */
@Suppress("DEPRECATION") // 使用 startActivityForResult / onActivityResult
class RecordListActivity : AppCompatActivity(), RecordAdapter.OnRecordClickListener {

    private lateinit var binding: ActivityRecordListBinding
    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var adapter: RecordAdapter

    /** 全部记录（未过滤，按时间倒序） */
    private var allRecords: List<Record> = emptyList()

    /** 当前选中的过滤类型 */
    private var currentFilter: Int = FILTER_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        databaseHelper = DatabaseHelper(this)

        setupRecyclerView()
        setupFilterChips()
        setupBottomNavigation()
        setupFab()

        loadRecords()
    }

    // ==================== 初始化 ====================

    private fun setupRecyclerView() {
        adapter = RecordAdapter(mutableListOf(), this)
        binding.rvRecordList.layoutManager = LinearLayoutManager(this)
        binding.rvRecordList.adapter = adapter
    }

    private fun setupFilterChips() {
        binding.chipGroupFilter.setOnCheckedChangeListener { _, checkedId ->
            currentFilter = when (checkedId) {
                R.id.chipAll -> FILTER_ALL
                R.id.chipBuy -> FILTER_BUY
                R.id.chipSell -> FILTER_SELL
                R.id.chipToday -> FILTER_TODAY
                R.id.chipMonth -> FILTER_MONTH
                else -> FILTER_ALL
            }
            applyFilter()
        }
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        // 高亮当前页（记录），之后再注册监听，避免初始化时触发跳转
        bottomNav.selectedItemId = R.id.nav_record
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chart -> {
                    startActivity(Intent(this, ChartActivity::class.java))
                    finish()
                }
                R.id.nav_summary -> {
                    startActivity(Intent(this, SummaryActivity::class.java))
                    finish()
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
                R.id.nav_record -> {
                    // 已在记录页，不跳转
                }
            }
            true
        }
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener { showQuickAddDialog() }
    }

    // ==================== 数据加载与过滤 ====================

    /**
     * 从数据库加载全部记录，刷新列表与今日汇总。
     */
    private fun loadRecords() {
        allRecords = databaseHelper.getAllRecords()
        applyFilter()
        updateTodaySummary()
    }

    /**
     * 依据当前过滤类型对全部记录做过滤并刷新适配器。
     */
    private fun applyFilter() {
        val today = dateStr("yyyy-MM-dd")
        val month = dateStr("yyyy-MM")
        val filtered: List<Record> = when (currentFilter) {
            FILTER_BUY -> allRecords.filter { it.direction == "买入" }
            FILTER_SELL -> allRecords.filter { it.direction == "卖出" }
            FILTER_TODAY -> allRecords.filter { it.dateTime.startsWith(today) }
            FILTER_MONTH -> allRecords.filter { it.dateTime.startsWith(month) }
            else -> allRecords
        }
        adapter.updateData(filtered)
    }

    /**
     * 计算并展示今日汇总：当日买入总额 / 卖出总额 / 净额。
     * 汇总始终基于今日数据，不受过滤芯片影响。
     */
    private fun updateTodaySummary() {
        val today = dateStr("yyyy-MM-dd")
        val todayRecords = allRecords.filter { it.dateTime.startsWith(today) }

        var buyTotal = 0.0
        var sellTotal = 0.0
        todayRecords.forEach { r ->
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
        val dialog = BottomSheetDialog(this)
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
        sheetBinding.btnManualRecord.setOnClickListener {
            dialog.dismiss()
            // 手动填写 -> 新增记录
            startActivityForResult(
                Intent(this, AddRecordActivity::class.java),
                REQ_ADD_RECORD
            )
        }
        sheetBinding.btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun startQuickRecord(mode: String) {
        val intent = Intent(this, QuickRecordActivity::class.java)
        intent.putExtra(EXTRA_RECORD_MODE, mode)
        startActivityForResult(intent, REQ_QUICK_RECORD)
    }

    // ==================== 记录条目点击 / 长按 ====================

    override fun onItemClick(record: Record, position: Int) {
        // 点击 -> 跳转 AddRecordActivity 编辑该记录
        val intent = Intent(this, AddRecordActivity::class.java)
        intent.putExtra(EXTRA_RECORD, record)
        startActivityForResult(intent, REQ_ADD_RECORD)
    }

    override fun onItemLongClick(record: Record, position: Int) {
        // 长按 -> 删除确认
        AlertDialog.Builder(this)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 从添加 / 编辑 / 快速记录界面返回且结果正常时刷新列表
        if (resultCode == RESULT_OK) {
            loadRecords()
        }
    }

    // ==================== 工具方法 ====================

    private fun dateStr(pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_BUY = 1
        private const val FILTER_SELL = 2
        private const val FILTER_TODAY = 3
        private const val FILTER_MONTH = 4

        const val REQ_ADD_RECORD = 1001
        const val REQ_QUICK_RECORD = 1002

        /** 传给 [AddRecordActivity] 的待编辑记录（Parcelable）。 */
        const val EXTRA_RECORD = "extra_record"

        /** 传给 [QuickRecordActivity] 的录入模式：[Record.SOURCE_PHOTO] 或 [Record.SOURCE_VOICE]。 */
        const val EXTRA_RECORD_MODE = "extra_record_mode"
    }
}
