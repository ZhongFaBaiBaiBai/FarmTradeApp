package com.farmtrade.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.farmtrade.app.R
import com.farmtrade.app.databinding.ActivityMainBinding

/**
 * 主 Activity，管理底部导航和 4 个 Fragment 的切换。
 *
 * Fragment 切换使用 hide/show 方式，避免重新加载，
 * 切换 tab 时页面状态保持不变。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var recordListFragment: RecordListFragment
    private lateinit var chartFragment: ChartFragment
    private lateinit var summaryFragment: SummaryFragment
    private lateinit var settingsFragment: SettingsFragment

    private var currentFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化所有 Fragment
        if (savedInstanceState == null) {
            recordListFragment = RecordListFragment()
            chartFragment = ChartFragment()
            summaryFragment = SummaryFragment()
            settingsFragment = SettingsFragment()

            // 默认显示记录列表
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, recordListFragment, "record")
                .add(R.id.fragmentContainer, chartFragment, "chart").hide(chartFragment)
                .add(R.id.fragmentContainer, summaryFragment, "summary").hide(summaryFragment)
                .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
                .commit()
            currentFragment = recordListFragment
        } else {
            // 恢复 Fragment 引用
            recordListFragment = supportFragmentManager.findFragmentByTag("record") as RecordListFragment
            chartFragment = supportFragmentManager.findFragmentByTag("chart") as ChartFragment
            summaryFragment = supportFragmentManager.findFragmentByTag("summary") as SummaryFragment
            settingsFragment = supportFragmentManager.findFragmentByTag("settings") as SettingsFragment
            currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        }

        setupBottomNav()
    }

    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_record
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_record -> switchFragment(recordListFragment)
                R.id.nav_chart -> switchFragment(chartFragment)
                R.id.nav_summary -> switchFragment(summaryFragment)
                R.id.nav_settings -> switchFragment(settingsFragment)
            }
            true
        }
    }

    /**
     * 切换 Fragment，使用 hide/show 避免重新创建。
     */
    private fun switchFragment(target: Fragment) {
        if (currentFragment == target) return

        val transaction = supportFragmentManager.beginTransaction()
        currentFragment?.let { transaction.hide(it) }
        transaction.show(target)
        transaction.commit()
        currentFragment = target
    }

    /**
     * 刷新记录列表（设置页清空数据后调用）
     */
    fun refreshRecordList() {
        recordListFragment.loadRecords()
    }
}
