package com.farmtrade.app.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.DialogCustomTypeBinding
import com.farmtrade.app.databinding.DialogInlineEditBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * QuickRecordActivity 确认页的 3 个字段专属对话框（方向 / 计量 / 类型）。
 * 抽出来的目的：把 Activity 里 ~280 行的对话框构建代码移走，让 Activity 只保留数据流。
 *
 * Activity 通过实现 [Host] 提供所有对话框需要的数据与回调。
 */
object QuickRecordEditDialogs {

    interface Host {
        val pendingRecord: Record
        val carryOverRecord: Record?
        fun getAllTypes(): List<String>
        fun recalcAndRender()
        fun toast(msg: String)
        fun startVoiceEditForType()
        fun activity(): AppCompatActivity
    }

    fun openDirectionDialog(host: Host) {
        val ctx = host.activity()
        val options = arrayOf("买入", "卖出")
        val currentIdx = options.indexOf(host.pendingRecord.direction.takeIf { it.isNotBlank() } ?: "买入")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("选择买卖方向")
            .setPositiveButton("恢复沿用") { _, _ ->
                host.carryOverRecord?.direction?.let { host.pendingRecord.direction = it }
                host.recalcAndRender()
                host.toast("已恢复沿用")
            }
            .setSingleChoiceItems(options, currentIdx) { dlg, which ->
                host.pendingRecord.direction = options[which]
                host.recalcAndRender()
                dlg.dismiss()
                host.toast("方向：${options[which]}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun openMeasureDialog(host: Host) {
        val ctx = host.activity()
        val modes = arrayOf(Record.MODE_WEIGHT_KG, Record.MODE_WEIGHT_JIN, Record.MODE_QUANTITY)
        val labels = arrayOf("按重量（公斤）", "按重量（斤）", "按数量（件）")
        val currentIdx = modes.indexOf(host.pendingRecord.measureMode).let { if (it < 0) 0 else it }

        val dv = DialogInlineEditBinding.inflate(ctx.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(ctx).setView(dv.root).create()
        dv.tvFieldLabel.text = "选择计量方式"
        dv.tvHint.text = "改变计量方式后单位名称会同步调整，请核对数值"
        dv.etInput.visibility = View.GONE
        dv.btnRetakePhoto.visibility = View.GONE
        dv.btnVoiceEdit.visibility = View.GONE

        val modeButtons = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (8 * ctx.resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, margin)
            labels.forEachIndexed { idx, label ->
                val btn = MaterialButton(
                    ctx, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = label
                    setTextColor(0xFF1565C0.toInt())
                    strokeColor = ColorStateList.valueOf(0xFF1565C0.toInt())
                    strokeWidth = 2
                    if (idx == currentIdx) setBackgroundColor(0xFFE3F2FD.toInt())
                    setOnClickListener {
                        host.pendingRecord.measureMode = modes[idx]
                        host.pendingRecord.unitName = when (modes[idx]) {
                            Record.MODE_WEIGHT_KG -> "公斤"
                            Record.MODE_WEIGHT_JIN -> "斤"
                            else -> if (host.pendingRecord.unitName.isBlank()) "件" else host.pendingRecord.unitName
                        }
                        if (modes[idx] == Record.MODE_QUANTITY) {
                            host.pendingRecord.grossWeight = 0.0
                            host.pendingRecord.vehicleWeight = 0.0
                        }
                        host.recalcAndRender()
                        dialog.dismiss()
                        host.toast("计量方式：$label")
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 4 * ctx.resources.displayMetrics.density.toInt() }
                    layoutParams = lp
                }
                addView(btn)
            }
        }
        (dv.root as LinearLayout).addView(modeButtons, 1)

        dv.btnRestore.setOnClickListener {
            val co = host.carryOverRecord
            if (co != null) {
                host.pendingRecord.measureMode = co.measureMode
                host.pendingRecord.unitName = co.unitName
                host.recalcAndRender()
                host.toast("已恢复沿用")
            } else {
                host.toast("暂无可沿用的记录")
            }
            dialog.dismiss()
        }
        dv.btnCancel.setOnClickListener { dialog.dismiss() }
        dv.btnConfirm.setOnClickListener {
            host.recalcAndRender()
            dialog.dismiss()
        }
        dialog.show()
    }

    fun openTypeDialog(host: Host) {
        val ctx = host.activity()
        val allTypes = host.getAllTypes()
        val dv = DialogInlineEditBinding.inflate(ctx.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(ctx).setView(dv.root).create()
        dv.tvFieldLabel.text = "选择或编辑类型"
        dv.tvHint.text = "从已用类型中选择，或点击「自定义…」输入新的名称"
        dv.etInput.visibility = View.GONE
        dv.btnRetakePhoto.visibility = View.GONE

        val chipGroup = ChipGroup(ctx).apply {
            isSingleSelection = false
            setChipSpacingHorizontal((8 * ctx.resources.displayMetrics.density).toInt())
            setChipSpacingVertical((4 * ctx.resources.displayMetrics.density).toInt())
        }
        allTypes.forEach { t ->
            val chip = Chip(ctx).apply {
                text = t
                isCheckable = true
                if (t == host.pendingRecord.type) isChecked = true
                setOnClickListener {
                    host.pendingRecord.type = t
                    host.recalcAndRender()
                    dialog.dismiss()
                    host.toast("类型：$t")
                }
            }
            chipGroup.addView(chip)
        }
        (dv.root as LinearLayout).addView(chipGroup, 1)

        dv.btnVoiceEdit.setOnClickListener {
            dialog.dismiss()
            host.startVoiceEditForType()
        }
        dv.btnRestore.setOnClickListener {
            host.carryOverRecord?.type?.let { host.pendingRecord.type = it }
            host.recalcAndRender()
            dialog.dismiss()
            host.toast("已恢复沿用")
        }
        dv.btnCancel.setOnClickListener { dialog.dismiss() }
        dv.btnConfirm.text = "自定义…"
        dv.btnConfirm.setOnClickListener {
            val dv2 = DialogCustomTypeBinding.inflate(ctx.layoutInflater)
            MaterialAlertDialogBuilder(ctx)
                .setTitle("自定义类型")
                .setView(dv2.root)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _, _ ->
                    val v = dv2.etTypeName.text.toString().trim()
                    if (v.isNotBlank()) {
                        host.pendingRecord.type = v
                        host.recalcAndRender()
                        host.toast("类型：$v")
                    }
                }
                .show()
            dialog.dismiss()
        }
        dialog.show()
    }
}
