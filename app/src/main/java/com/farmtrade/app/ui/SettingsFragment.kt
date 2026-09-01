package com.farmtrade.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.databinding.FragmentSettingsBinding
import com.farmtrade.app.util.DataExchangeHelper
import com.farmtrade.app.util.ExportHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置 Fragment：Excel 导出（查看） / JSON 导出（跨手机） / JSON 导入（合并） / 清空数据。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var dbHelper: DatabaseHelper
    private val dateStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())

    // ==================== SAF 文件选择器 ====================

    /** 导出 JSON：系统文件选择器让用户指定保存路径 */
    private val createJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch { exportJsonTo(uri) }
    }

    /** 导入 JSON：系统文件选择器让用户选要导入的文件 */
    private val openJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认导入")
            .setMessage("会把选到的 JSON 文件里的记录合并到本机当前数据库。\n内容完全相同的记录会自动跳过，不会重复。确认继续？")
            .setPositiveButton("开始导入") { _, _ ->
                lifecycleScope.launch { importJsonFrom(uri) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

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

        binding.btnExportExcel.setOnClickListener { exportExcel() }
        binding.btnExportJson.setOnClickListener {
            val filename = "FarmTrade_${dateStamp.format(Date())}.json"
            try {
                createJsonLauncher.launch(filename)
            } catch (e: Exception) {
                toast("无法打开文件选择器")
            }
        }
        binding.btnImportJson.setOnClickListener {
            try {
                openJsonLauncher.launch(arrayOf("application/json", "*/*"))
            } catch (e: Exception) {
                toast("无法打开文件选择器")
            }
        }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 信息展示 ====================

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
            "语音输入、手动记录，并提供图表统计与数据导出功能。所有数据均保存在本地手机中。\n\n" +
            "跨手机合并：在旧手机「导出为 JSON」，把文件传到新手机，在新手机「导入记录」即可。"
    }

    // ==================== 导出 ====================

    private fun exportExcel() {
        val records = dbHelper.getAllRecords()
        if (records.isEmpty()) {
            toast("暂无数据可导出")
            return
        }
        ExportHelper.exportToExcel(requireContext(), records, "全部记录")
    }

    private suspend fun exportJsonTo(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val json = DataExchangeHelper.toJsonBundle(requireContext(), dbHelper)
            requireContext().contentResolver.openOutputStream(uri)?.use { o ->
                o.write(json.toByteArray(Charsets.UTF_8))
            }
            withContext(Dispatchers.Main) {
                val cnt = dbHelper.getAllRecords().size
                toast("导出成功：共 $cnt 条记录")
            }
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) {
                toast("导出失败：${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    // ==================== 导入 ====================

    private suspend fun importJsonFrom(uri: Uri) {
        val result = withContext(Dispatchers.IO) {
            try {
                val json = requireContext().contentResolver.openInputStream(uri)?.use { i ->
                    i.readBytes().toString(Charsets.UTF_8)
                }
                if (json.isNullOrEmpty()) return@withContext null
                DataExchangeHelper.importFromJson(requireContext(), dbHelper, json)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    toast("导入失败：${t.message ?: t::class.java.simpleName}")
                }
                null
            }
        } ?: return

        (activity as? MainActivity)?.refreshRecordList()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("导入完成")
            .setMessage(result.summary)
            .setPositiveButton("好的", null)
            .setNegativeButton("通过微信/蓝牙分享当前文件") { _, _ -> shareFile(uri) }
            .show()
    }

    /** 把刚导入的文件分享出去，方便用户把文件转移到另一台手机 */
    private fun shareFile(uri: Uri) {
        try {
            // 授予临时读取权限给接收方 APP
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享 JSON 记录文件"))
        } catch (e: Exception) {
            toast("无法打开分享面板")
        }
    }

    // ==================== 清空 ====================

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清空数据")
            .setMessage("确认清空所有交易记录？\n此操作不可恢复！\n（建议先导出 JSON 做备份）")
            .setPositiveButton("确认") { _, _ -> clearAllData() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllData() {
        val count = dbHelper.getAllRecords().size
        if (count == 0) {
            toast("当前没有记录可清空")
            return
        }
        var deleted = 0
        for (record in dbHelper.getAllRecords()) {
            if (dbHelper.deleteRecord(record.id) > 0) deleted++
        }
        toast("已清空 $deleted 条记录")
        (activity as? MainActivity)?.refreshRecordList()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
}
