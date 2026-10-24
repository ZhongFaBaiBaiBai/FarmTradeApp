package com.farmtrade.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.ActivityAddRecordBinding
import com.farmtrade.app.databinding.DialogCustomTypeBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 添加 / 编辑 记录界面
 *
 * 功能：
 * - 日期时间自动填充当前时间（只读）
 * - 买卖方向 买入/卖出 切换
 * - 类型 chips（来自 [DatabaseHelper.getAllTypes]） + "+ 自定义"
 * - 计量方式：按重量(公斤) / 按重量(斤) / 按数量
 * - 重量区：总重 / 车重 / 净重自动计算
 * - 单价 + 合计金额自动预览
 * - 保存：新增或更新记录
 * - 编辑模式：通过 [EXTRA_RECORD] 或 [EXTRA_RECORD_ID] 传入记录并预填
 */
class AddRecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddRecordBinding
    private val dbHelper by lazy { DatabaseHelper(this) }

    /** 编辑中的记录（新增时为 null） */
    private var editingRecord: Record? = null
    private var currentDirection = "买入"
    private var selectedType = ""
    private var measureMode = Record.MODE_WEIGHT_KG

    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** 监听重量/数量/单价变化，实时刷新净重与合计 */
    private val recalcWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = recalcNetAndTotal()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ivBack.setOnClickListener { finish() }

        // 1. 日期时间（可点击 → 滚轮 + 文本输入双入口弹窗）
        binding.rowDateTime.setOnClickListener {
            DatePickerDialogs.open(this, binding.tvDateTime.text.toString()) { formatted ->
                binding.tvDateTime.text = formatted
                toast("日期时间已更新")
            }
        }
        // 先给个默认当前时间（编辑预填或 prefillFromRecord 会覆盖）
        binding.tvDateTime.text = dateTimeFormat.format(Date())

        // 2. 买卖方向
        binding.toggleDirection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentDirection = when (checkedId) {
                    binding.btnBuy.id -> "买入"
                    binding.btnSell.id -> "卖出"
                    else -> currentDirection
                }
            }
        }

        // 3. 类型 chips
        binding.chipGroupType.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = group.findViewById<Chip>(checkedIds[0])
                selectedType = chip.text.toString()
            }
        }
        binding.chipCustom.setOnClickListener { showCustomTypeDialog() }

        // 4. 计量方式
        binding.toggleMeasureMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    binding.btnModeKg.id -> Record.MODE_WEIGHT_KG
                    binding.btnModeJin.id -> Record.MODE_WEIGHT_JIN
                    binding.btnModeQty.id -> Record.MODE_QUANTITY
                    else -> measureMode
                }
                applyMeasureMode(mode)
            }
        }

        // 5. 重量 / 数量 / 单价 输入监听
        binding.etGrossWeight.addTextChangedListener(recalcWatcher)
        binding.etTareWeight.addTextChangedListener(recalcWatcher)
        binding.etUnitPrice.addTextChangedListener(recalcWatcher)
        binding.etQuantity.addTextChangedListener(recalcWatcher)
        // 数量模式下单位名称变化时更新单价标签
        binding.etUnitName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (measureMode == Record.MODE_QUANTITY) {
                    binding.tvUnitPriceLabel.text = "单价（元/${s?.toString().orEmpty().ifBlank { "个" }}）"
                }
            }
        })

        // 6. 拍照识别
        // 7. 保存
        binding.btnSave.setOnClickListener { saveRecord() }

        // 9. 编辑预填 or 初始化默认
        @Suppress("DEPRECATION")
        val passedRecord = intent.getParcelableExtra<Record>(EXTRA_RECORD)
        val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        when {
            passedRecord != null -> prefillFromRecord(passedRecord)
            recordId > 0 -> dbHelper.getAllRecords().firstOrNull { it.id == recordId }
                ?.let { prefillFromRecord(it) }
            else -> {
                buildTypeChips()
                binding.toggleDirection.check(binding.btnBuy.id)
                binding.toggleMeasureMode.check(binding.btnModeKg.id)
                applyMeasureMode(Record.MODE_WEIGHT_KG)
            }
        }
        recalcNetAndTotal()
    }

    // ==================== 类型 chips ====================

    /**
     * 构建类型 chips，[selected] 为需要选中的类型（编辑预填时使用）；
     * 若 [selected] 不在数据库中，会先持久化再加入。
     */
    private fun buildTypeChips(selected: String? = null) {
        // 先摘下「+ 自定义」chip，重建类型列表后再挂回去，避免自定义入口被一起删掉
        val customChip = binding.chipCustom
        (customChip.parent as? android.view.ViewGroup)?.removeView(customChip)
        binding.chipGroupType.removeAllViews()
        var types = dbHelper.getAllTypes()
        if (selected != null && selected !in types) {
            dbHelper.addCustomType(selected)
            types = dbHelper.getAllTypes()
        }
        val target = selected ?: types.firstOrNull()
        var chipToSelect: Chip? = null
        for (type in types) {
            val chip = Chip(this).apply {
                text = type
                isCheckable = true
                isClickable = true
                textSize = 15f
                id = View.generateViewId()
                // 长按类型 → 重命名 / 删除
                setOnLongClickListener {
                    showTypeMenu(type)
                    true
                }
            }
            binding.chipGroupType.addView(chip)
            if (type == target) chipToSelect = chip
        }
        // 重新挂回「+ 自定义」
        binding.chipGroupType.addView(customChip)
        customChip.setOnClickListener { showCustomTypeDialog() }

        chipToSelect?.let {
            it.isChecked = true
            selectedType = it.text.toString()
        } ?: run {
            selectedType = types.firstOrNull().orEmpty()
        }
    }

    private fun showCustomTypeDialog() {
        val dv = DialogCustomTypeBinding.inflate(layoutInflater)
        dv.tvDialogTitle.text = "新建货物类型"
        val dialog = MaterialAlertDialogBuilder(this).setView(dv.root).create()
        dv.btnAdd.setOnClickListener {
            val name = dv.etTypeName.text?.toString().orEmpty().trim()
            if (name.isBlank()) {
                toast("请输入类型名称")
                return@setOnClickListener
            }
            if (dbHelper.addCustomType(name)) {
                buildTypeChips(name)
                toast("已添加：$name")
                dialog.dismiss()
            } else {
                toast("该类型已存在")
            }
        }
        dialog.show()
    }

    // ==================== 货物类型管理 ====================

    /**
     * 长按类型 chip 弹出的菜单。
     * 注意：AlertDialog 里 setMessage 和 setItems 同时用会冲突（列表项会被吞掉），
     * 所以把「使用条数」放进标题，只保留 setItems。
     */
    private fun showTypeMenu(type: String) {
        val count = dbHelper.countRecordsOfType(type)
        val title = if (count > 0) "$type（$count 条记录）" else type
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(arrayOf("重命名", "删除")) { _, which ->
                when (which) {
                    0 -> showRenameTypeDialog(type)
                    1 -> confirmDeleteType(type, count)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 重命名：同时更新历史记录里的类型名 */
    private fun showRenameTypeDialog(oldName: String) {
        val dv = DialogCustomTypeBinding.inflate(layoutInflater)
        dv.tvDialogTitle.text = "重命名类型"
        dv.etTypeName.setText(oldName)
        dv.etTypeName.setSelection(dv.etTypeName.text?.length ?: 0)
        dv.btnAdd.text = "保存"

        val dialog = MaterialAlertDialogBuilder(this).setView(dv.root).create()
        dv.btnAdd.setOnClickListener {
            val name = dv.etTypeName.text?.toString().orEmpty().trim()
            if (name.isBlank()) { toast("请输入类型名称"); return@setOnClickListener }
            if (name == oldName) { dialog.dismiss(); return@setOnClickListener }
            if (dbHelper.renameType(oldName, name)) {
                buildTypeChips(name)
                toast("已重命名为：$name")
                dialog.dismiss()
            } else {
                toast("重命名失败：该类型可能已存在")
            }
        }
        dialog.show()
    }

    /** 删除类型（历史记录保留原类型名） */
    private fun confirmDeleteType(type: String, count: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除类型")
            .setMessage(
                if (count > 0)
                    "确定删除「$type」吗？\n\n已有 $count 条记录仍保留这个类型名，不会丢失；只是以后新建记录时不再出现该选项。"
                else
                    "确定删除「$type」吗？"
            )
            .setPositiveButton("删除") { _, _ ->
                dbHelper.deleteCustomType(type)
                buildTypeChips()
                toast("已删除：$type")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 计量方式 ====================

    private fun applyMeasureMode(mode: String) {
        measureMode = mode
        if (mode == Record.MODE_QUANTITY) {
            binding.layoutWeightSection.visibility = View.GONE
            binding.layoutQuantitySection.visibility = View.VISIBLE
            binding.tvUnitPriceLabel.text = "单价（元/${binding.etUnitName.text?.toString().orEmpty().ifBlank { "个" }}）"
        } else {
            binding.layoutWeightSection.visibility = View.VISIBLE
            binding.layoutQuantitySection.visibility = View.GONE
            // 公斤和斤模式下，单价单位都是"元/斤"
            binding.tvUnitPriceLabel.text = "单价（元/斤）"
        }
        recalcNetAndTotal()
    }

    // ==================== 净重 / 合计 自动计算 ====================

    private fun recalcNetAndTotal() {
        if (!this::binding.isInitialized) return
        val gross = binding.etGrossWeight.text?.toString().orEmpty().toDoubleOrNull() ?: 0.0
        val tare = binding.etTareWeight.text?.toString().orEmpty().toDoubleOrNull() ?: 0.0
        val qty = binding.etQuantity.text?.toString().orEmpty().toDoubleOrNull() ?: 0.0
        val price = binding.etUnitPrice.text?.toString().orEmpty().toDoubleOrNull() ?: 0.0
        if (measureMode == Record.MODE_QUANTITY) {
            binding.tvNetWeight.text = Record.formatNumber(qty)
            binding.tvTotalPreview.text = "￥${Record.formatMoney(qty * price)}"
        } else {
            val net = (gross - tare).coerceAtLeast(0.0)
            binding.tvNetWeight.text = Record.formatNumber(net)
            // 公斤和斤模式下，单价都是"元/斤"
            // 公斤模式：总额 = 净重(公斤) × 2 × 单价
            // 斤模式：总额 = 净重(斤) × 单价
            val total = if (measureMode == Record.MODE_WEIGHT_KG) {
                net * 2 * price
            } else {
                net * price
            }
            binding.tvTotalPreview.text = "￥${Record.formatMoney(total)}"
        }
    }

    // ==================== 编辑预填 ====================

    private fun prefillFromRecord(r: Record) {
        editingRecord = r
        binding.tvDateTime.text = r.dateTime.ifBlank { dateTimeFormat.format(Date()) }
        currentDirection = r.direction
        binding.toggleDirection.check(if (r.direction == "卖出") binding.btnSell.id else binding.btnBuy.id)
        selectedType = r.type
        buildTypeChips(r.type)
        binding.toggleMeasureMode.check(
            when (r.measureMode) {
                Record.MODE_WEIGHT_JIN -> binding.btnModeJin.id
                Record.MODE_QUANTITY -> binding.btnModeQty.id
                else -> binding.btnModeKg.id
            }
        )
        applyMeasureMode(r.measureMode)
        binding.etGrossWeight.setText(Record.formatNumber(r.grossWeight))
        binding.etTareWeight.setText(Record.formatNumber(r.vehicleWeight))
        binding.etQuantity.setText(Record.formatNumber(r.quantity))
        binding.etUnitName.setText(r.unitName)
        binding.etUnitPrice.setText(Record.formatNumber(r.unitPrice))
        recalcNetAndTotal()
    }

    // ==================== 保存 ====================

    private fun saveRecord() {
        val gross = binding.etGrossWeight.text?.toString().orEmpty().toDoubleOrNull() ?: 0.0
        val tare = binding.etTareWeight.text?.toString().orEmpty().toDoubleOrNull() ?: 0.0
        val qty = binding.etQuantity.text?.toString().orEmpty().toDoubleOrNull() ?: 0.0
        val price = binding.etUnitPrice.text?.toString().orEmpty().toDoubleOrNull() ?: 0.0

        if (selectedType.isBlank()) {
            toast("请选择类型")
            return
        }
        if (price <= 0) {
            toast("请输入单价")
            return
        }
        if (measureMode == Record.MODE_QUANTITY) {
            if (qty <= 0) {
                toast("请输入数量")
                return
            }
        } else {
            if (gross <= 0) {
                toast("请输入总重")
                return
            }
            if (tare > gross) {
                toast("车重不能大于总重，请修改")
                return
            }
        }

        val unitName = when (measureMode) {
            Record.MODE_WEIGHT_KG -> "公斤"
            Record.MODE_WEIGHT_JIN -> "斤"
            else -> binding.etUnitName.text?.toString().orEmpty().trim().ifBlank { "个" }
        }

        val record = (editingRecord?.copy() ?: Record().also { it.source = Record.SOURCE_MANUAL }).apply {
            dateTime = binding.tvDateTime.text.toString()
            direction = currentDirection
            type = selectedType
            measureMode = this@AddRecordActivity.measureMode
            grossWeight = gross
            vehicleWeight = tare
            quantity = qty
            this.unitName = unitName
            unitPrice = price
            netWeight = calculateNetWeight()
            totalAmount = calculateTotalAmount()
        }

        if (record.id > 0) {
            dbHelper.updateRecord(record)
            toast("修改成功")
        } else {
            record.id = dbHelper.insertRecord(record)
            toast("保存成功")
        }
        setResult(RESULT_OK)
        finish()
    }

    // ==================== utils ====================

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** Parcelable Record，编辑或由快速记录"全部修改"跳转时携带 */
        const val EXTRA_RECORD = "extra_record"
        /** 编辑现有记录的 id */
        const val EXTRA_RECORD_ID = "extra_record_id"
    }
}
