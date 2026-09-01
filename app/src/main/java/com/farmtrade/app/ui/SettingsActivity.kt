package com.farmtrade.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.databinding.ActivitySettingsBinding
import com.farmtrade.app.util.ExportHelper
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置页面。
 *
 * 功能：
 * - 应用信息（版本、名称）
 * - 导出全部数据为 Excel
 * - 清空所有数据（带确认弹窗）
 * - 关于文字
 * - 底部导航：记录 / 图表 / 汇总 / 设置(选中)
 *
 * 使用 ScrollView + LinearLayout 简单布局，大字体，适合老年用户。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var dbHelper: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = DatabaseHelper(this)

        loadAppInfo()

        binding.btnExportAll.setOnClickListener { exportAllData() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }

        setupBottomNav()
    }

    /** 加载应用信息：版本号、应用名称、关于文字 */
    private fun loadAppInfo() {
        val appName = getString(R.string.app_name)
        binding.tvAppName.text = appName

        var versionName = "1.0"
        var versionCode = 1L
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            versionName = info.versionName ?: "1.0"
            versionCode = PackageInfoCompat.getLongVersionCode(info)
        } catch (e: Exception) {
            // 获取失败时使用默认值
        }
        binding.tvAppVersion.text = "版本：v$versionName ($versionCode)"

        binding.tvAbout.text = "$appName 是一款专为农户设计的买卖记账工具，支持拍照识别、" +
            "语音输入、手动记录，并提供图表统计与数据导出功能。所有数据均保存在本地手机中。"
    }

    /** 导出全部数据为 Excel */
    private fun exportAllData() {
        val records = dbHelper.getAllRecords()
        if (records.isEmpty()) {
            Toast.makeText(this, "暂无数据可导出", Toast.LENGTH_SHORT).show()
            return
        }
        ExportHelper.exportToExcel(this, records, "全部记录")
    }

    /** 清空确认弹窗 */
    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空数据")
            .setMessage("确认清空所有交易记录？\n此操作不可恢复！")
            .setPositiveButton("确认") { _, _ -> clearAllData() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 清空所有记录 */
    private fun clearAllData() {
        val records = dbHelper.getAllRecords()
        if (records.isEmpty()) {
            Toast.makeText(this, "当前没有记录可清空", Toast.LENGTH_SHORT).show()
            return
        }
        var deleted = 0
        for (record in records) {
            if (dbHelper.deleteRecord(record.id) > 0) {
                deleted++
            }
        }
        Toast.makeText(this, "已清空 $deleted 条记录", Toast.LENGTH_LONG).show()
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
        nav.selectedItemId = R.id.nav_settings
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
}
