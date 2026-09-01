package com.farmtrade.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.pm.PackageInfoCompat
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.databinding.FragmentSettingsBinding
import com.farmtrade.app.util.ExportHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置 Fragment。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var dbHelper: DatabaseHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dbHelper = DatabaseHelper(requireContext())
        loadAppInfo()
        binding.btnExportAll.setOnClickListener { exportAllData() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadAppInfo() {
        val appName = getString(R.string.app_name)
        binding.tvAppName.text = appName

        var versionName = "1.0"
        var versionCode = 1L
        try {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            versionName = info.versionName ?: "1.0"
            versionCode = PackageInfoCompat.getLongVersionCode(info)
        } catch (e: Exception) {
        }
        binding.tvAppVersion.text = "版本：v$versionName ($versionCode)"

        binding.tvAbout.text = "$appName 是一款专为农户设计的买卖记账工具，支持拍照识别、" +
            "语音输入、手动记录，并提供图表统计与数据导出功能。所有数据均保存在本地手机中。"
    }

    private fun exportAllData() {
        val records = dbHelper.getAllRecords()
        if (records.isEmpty()) {
            Toast.makeText(requireContext(), "暂无数据可导出", Toast.LENGTH_SHORT).show()
            return
        }
        ExportHelper.exportToExcel(requireContext(), records, "全部记录")
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清空数据")
            .setMessage("确认清空所有交易记录？\n此操作不可恢复！")
            .setPositiveButton("确认") { _, _ -> clearAllData() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllData() {
        val records = dbHelper.getAllRecords()
        if (records.isEmpty()) {
            Toast.makeText(requireContext(), "当前没有记录可清空", Toast.LENGTH_SHORT).show()
            return
        }
        var deleted = 0
        for (record in records) {
            if (dbHelper.deleteRecord(record.id) > 0) {
                deleted++
            }
        }
        Toast.makeText(requireContext(), "已清空 $deleted 条记录", Toast.LENGTH_LONG).show()
        // 通知记录列表刷新
        (activity as? MainActivity)?.refreshRecordList()
    }
}
