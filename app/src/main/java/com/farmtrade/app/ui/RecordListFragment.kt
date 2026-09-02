package com.farmtrade.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * - 顶部绿色标题栏 + 过滤芯片（全部 / 买入 / 卖出 / 当日 / 本月）
 * - 今日汇总卡片：当日买入总额 / 卖出总额 / 净额
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

    /** 全部记录（未过滤，按时间倒序） */
    private var allRecords: List<Record> = emptyList()

    /** 当前选中的过滤类型 */
    private var currentFilter: Int = FILTER_ALL

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
        setupRecyclerView()
        setupFilterChips()
        setupFab()
        setupBatchManage()
        loadRecords()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 初始化 ====================

    private fun setupRecyclerView() {
        adapter = RecordAdapter(mutableListOf(), this)
        binding.rvRecordList.layoutManager = LinearLayoutManager(requireContext())
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

    private fun setupFab() {
        binding.fabAdd.setOnClickListener { showQuickAddDialog() }
    }

    // ==================== 批量管理 ====================

    private fun setupBatchManage() {
        binding.btnBatchManage.setOnClickListener {
            if (adapter.isSelected()) {
                adapter.exitMultiSelect()
                showNormalMode()
            } else {
                adapter.enterMultiSelect()
                showBatchMode()
            }
        }
        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll()
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
    }

    // ==================== 数据加载与过滤 ====================

    /**
     * 从数据库加载全部记录，刷新列表与今日汇总。
     */
    fun loadRecords() {
        allRecords = databaseHelper.getAllRecords()
        applyFilter()
        updateTodaySummary()
    }

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

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_BUY = 1
        private const val FILTER_SELL = 2
        private const val FILTER_TODAY = 3
        private const val FILTER_MONTH = 4

        private const val REQ_ADD_RECORD = 1001
        private const val REQ_QUICK_RECORD = 1002

        const val EXTRA_RECORD = "extra_record"
        const val EXTRA_RECORD_MODE = "extra_record_mode"
    }
}
