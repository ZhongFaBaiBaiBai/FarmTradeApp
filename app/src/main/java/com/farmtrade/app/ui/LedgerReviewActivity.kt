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
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 批量录入确认页（拍照 OCR + 手动批量共用）：
 * - 拍照模式：接收 [EXTRA_ROWS_JSON]（OCR 出的"总重-车重"算式列表）预填各行
 * - 手动模式：不传 JSON，页面初始空，用户点"+ 添加一行"逐条录入
 * - 整批统一设置 方向 / 类型 / 单位 / 单价
 * - 每行可修改总重、车重（净重自动重算），可删除
 * - 一键批量保存，日期时间按行序每条递增 1 分钟
 */
class LedgerReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLedgerReviewBinding
    private val dbHelper by lazy { DatabaseHelper(this) }

    private var currentDirection = "买入"
    private var measureMode = Record.MODE_WEIGHT_KG
    private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var batchDateTime: String = dateTimeFmt.format(Date())

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

        // 日期时间显示当前值，点击弹双入口弹窗（文本输入 + 滚轮选）
        binding.tvDateTime.text = batchDateTime
        binding.rowDateTime.setOnClickListener {
            DatePickerDialogs.open(this, batchDateTime) { picked ->
                batchDateTime = picked
                binding.tvDateTime.text = picked
                toast("日期已设为 $picked")
            }
        }

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
        // 手动模式：初始没数据时自动加一行空行，方便用户直接开始填
        if (rows.isEmpty()) addRowView(OcrHelper.LedgerRow(0.0, 0.0))
        // "+ 添加一行"按钮
        binding.btnAddRow.setOnClickListener { addRowView(OcrHelper.LedgerRow(0.0, 0.0)) }
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
        if (row.gross > 0) item.etRowGross.setText(Record.formatNumber(row.gross))
        if (row.tare > 0) item.etRowTare.setText(Record.formatNumber(row.tare))
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

        // TextWatcher 在 setText 之后才注册，初始净重需要主动计算一次，否则显示 0
        holder.tvNet.text = Record.formatNumber((row.gross - row.tare).coerceAtLeast(0.0))

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
            "共 $n 条记录，请核对后保存"
        } else {
            "点击「+ 添加一行」开始录入"
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
        // 解析用户选的起始日期时间，每条递增 1 分钟
        val baseCal = Calendar.getInstance().apply {
            try {
                time = dateTimeFmt.parse(batchDateTime) ?: Date()
            } catch (_: Exception) { /* 用当前时间兜底 */ }
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        checks.forEachIndexed { i, row ->
            val r = Record().apply {
                val cal = baseCal.clone() as Calendar
                cal.add(Calendar.MINUTE, i)
                dateTime = fmt.format(cal.time)
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
