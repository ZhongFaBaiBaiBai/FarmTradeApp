package com.farmtrade.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.ActivityLedgerReviewBinding
import com.farmtrade.app.databinding.ItemLedgerRowBinding
import com.farmtrade.app.util.OcrHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录本批量录入确认页：
 * - 接收 [EXTRA_ROWS_JSON]（QuickRecordActivity 拍照 OCR 出的"总重-车重"算式列表）
 * - 整批统一设置 方向 / 类型 / 单位 / 单价
 * - 每行可修改总重、车重（净重自动重算），可删除识别错误的行
 * - 一键批量保存，日期时间按行序每条递增 1 分钟，保证列表排序稳定
 */
class LedgerReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLedgerReviewBinding
    private val dbHelper by lazy { DatabaseHelper(this) }

    private var currentDirection = "买入"
    private var measureMode = Record.MODE_WEIGHT_KG

    /** 行视图持有者，保存时按顺序读取输入框内容 */
    private class RowHolder(
        val root: android.view.View,
        val etGross: EditText,
        val etTare: EditText,
        val tvNet: TextView
    )

    private val rowHolders = mutableListOf<RowHolder>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLedgerReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ivBack.setOnClickListener { finish() }

        val rows = parseRows(intent.getStringExtra(EXTRA_ROWS_JSON).orEmpty())

        // 统一设置预填：沿用今日上一条记录的类型 / 单价（没有就用默认）
        val last = dbHelper.getTodayLastRecord(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
        binding.etLedgerType.setText(last?.type?.takeIf { it.isNotBlank() } ?: "小麦")
        if ((last?.unitPrice ?: 0.0) > 0) {
            binding.etLedgerPrice.setText(Record.formatNumber(last!!.unitPrice))
        }
        binding.toggleDirection.check(binding.btnBuy.id)
        binding.toggleUnit.check(binding.btnUnitKg.id)

        binding.toggleDirection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentDirection = when (checkedId) {
                    binding.btnSell.id -> "卖出"
                    else -> "买入"
                }
            }
        }
        binding.toggleUnit.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                measureMode = when (checkedId) {
                    binding.btnUnitJin.id -> Record.MODE_WEIGHT_JIN
                    else -> Record.MODE_WEIGHT_KG
                }
            }
        }

        rows.forEach { addRowView(it) }
        updateSummary()

        binding.btnSaveAll.setOnClickListener { saveAll() }
    }

    private fun parseRows(json: String): List<OcrHelper.LedgerRow> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o: JSONObject = arr.getJSONObject(i)
            val g = o.optDouble("g", 0.0)
            val t = o.optDouble("t", 0.0)
            if (g > 0) OcrHelper.LedgerRow(g, t) else null
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 添加一行可编辑的算式（总重 − 车重 = 净重），净重随输入自动重算 */
    private fun addRowView(row: OcrHelper.LedgerRow) {
        val item = ItemLedgerRowBinding.inflate(LayoutInflater.from(this))
        item.etRowGross.setText(Record.formatNumber(row.gross))
        item.etRowTare.setText(Record.formatNumber(row.tare))
        val holder = RowHolder(item.root, item.etRowGross, item.etRowTare, item.tvRowNet)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val g = holder.etGross.text.toString().toDoubleOrNull() ?: 0.0
                val t = holder.etTare.text.toString().toDoubleOrNull() ?: 0.0
                holder.tvNet.text = Record.formatNumber((g - t).coerceAtLeast(0.0))
            }
        }
        holder.etGross.addTextChangedListener(watcher)
        holder.etTare.addTextChangedListener(watcher)

        item.btnRowDelete.setOnClickListener {
            binding.llRows.removeView(holder.root)
            rowHolders.remove(holder)
            updateSummary()
        }

        binding.llRows.addView(holder.root)
        rowHolders.add(holder)
    }

    private fun updateSummary() {
        val n = rowHolders.size
        binding.tvSummary.text = if (n > 0) {
            "共识别 $n 条算式，请核对修改后保存（单价未识别，请统一填写）"
        } else {
            "没有识别到算式，请返回重拍或手动录入"
        }
        binding.btnSaveAll.text = if (n > 0) "保存全部 $n 条记录" else "保存全部记录"
        binding.btnSaveAll.isEnabled = n > 0
    }

    private fun saveAll() {
        val type = binding.etLedgerType.text.toString().trim()
        val price = binding.etLedgerPrice.text.toString().toDoubleOrNull() ?: 0.0
        if (type.isBlank()) {
            toast("请填写类型")
            return
        }
        if (price <= 0) {
            toast("请填写单价")
            return
        }
        // 先整体校验，任何一行无效就不保存，避免错误数据混入
        data class RowCheck(val lineNo: Int, val gross: Double, val tare: Double)
        val checks = rowHolders.mapIndexed { idx, h ->
            RowCheck(
                idx + 1,
                h.etGross.text.toString().toDoubleOrNull() ?: 0.0,
                h.etTare.text.toString().toDoubleOrNull() ?: 0.0
            )
        }
        val bad = checks.firstOrNull { it.gross <= 0 || it.gross <= it.tare }
        if (bad != null) {
            toast("第 ${bad.lineNo} 行数值无效（总重必须大于车重），请修改或删除该行")
            return
        }
        if (checks.isEmpty()) {
            toast("没有可保存的记录")
            return
        }
        if (dbHelper.getAllTypes().firstOrNull { it == type } == null) {
            dbHelper.addCustomType(type)
        }

        val unitName = if (measureMode == Record.MODE_WEIGHT_KG) "公斤" else "斤"
        val base = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        checks.forEachIndexed { i, row ->
            val r = Record().apply {
                dateTime = fmt.format(Date(base + i * 60_000L))
                direction = currentDirection
                this.type = type
                this.measureMode = this@LedgerReviewActivity.measureMode
                this.unitName = unitName
                grossWeight = row.gross
                vehicleWeight = row.tare
                unitPrice = price
                source = Record.SOURCE_PHOTO
                netWeight = calculateNetWeight()
                totalAmount = calculateTotalAmount()
            }
            dbHelper.insertRecord(r)
        }
        toast("已保存 ${checks.size} 条记录")
        setResult(RESULT_OK)
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** 拍照识别出的算式列表（JSON 数组，元素 {"g":总重,"t":车重}） */
        const val EXTRA_ROWS_JSON = "extra_rows_json"
    }
}
