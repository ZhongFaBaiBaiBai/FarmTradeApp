package com.farmtrade.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.farmtrade.app.databinding.DialogDatetimeEditBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日期时间双入口编辑弹窗工具。
 * - 入口一：文本框直接输入（yyyy-MM-dd 或 yyyy-MM-dd HH:mm）
 * - 入口二：「📅 用滚轮选」按钮 → DatePickerDialog（默认只选年月日）
 *   选完日期后可选点「⏰ 选具体时间」按钮展开 TimePicker，不点则时间默认 00:00
 *
 * QuickRecordActivity / AddRecordActivity / LedgerReviewActivity 共享同一个弹窗。
 */
object DatePickerDialogs {

    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 合法日期时间格式：yyyy-MM-dd 或 yyyy-MM-dd HH:mm（宽松匹配） */
    private val dateOnlyRegex = Regex("""^\d{4}[/.-]\d{1,2}[/.-]\d{1,2}$""")
    private val datetimeRegex = Regex("""^\d{4}[/.-]\d{1,2}[/.-]\d{1,2}\s+\d{1,2}[:：]?\d{2}$""")

    /**
     * 弹出日期时间编辑弹窗。
     *
     * @param activity 宿主 Activity（用于创建 Dialog 和 Toast）
     * @param initial  初始显示的日期时间文本（null 或空则用当前时间）
     * @param onConfirm 用户点确认时回调参数（已经过格式校验）
     */
    fun open(
        activity: AppCompatActivity,
        initial: String?,
        onConfirm: (formattedDateTime: String) -> Unit
    ) {
        val dv = DialogDatetimeEditBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dv.root).create()

        val startValue = initial?.takeIf { it.isNotBlank() }
            ?: dateTimeFormat.format(Date())
        dv.etDatetimeText.setText(startValue)

        // 文本框聚焦后全选方便用户直接改
        dv.etDatetimeText.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (v as? android.widget.EditText)?.selectAll()
            }
        }

        dv.btnPickFromWheel.setOnClickListener {
            val cal = Calendar.getInstance()
            try {
                startValue.let { dateTimeFormat.parse(it) }?.let { cal.time = it }
            } catch (_: Exception) {
                try {
                    dateFormat.parse(startValue)?.let { cal.time = it }
                } catch (_: Exception) {}
            }

            DatePickerDialog(
                activity,
                { _, year, month, dayOfMonth ->
                    val picked = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, dayOfMonth)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                    }
                    dv.etDatetimeText.setText(dateFormat.format(picked.time))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // ⏰ 选具体时间按钮（可选，点了才弹 TimePicker）
        dv.btnPickTime.setOnClickListener {
            val cal = Calendar.getInstance()
            try {
                startValue.let { dateTimeFormat.parse(it) }?.let { cal.time = it }
            } catch (_: Exception) {}

            TimePickerDialog(
                activity,
                { _, hour, minute ->
                    // 在当前文本框的日期基础上加上时分
                    val currentText = dv.etDatetimeText.text.toString().trim()
                    val datePart = dateOnlyRegex.find(currentText)?.value
                        ?: dateFormat.format(cal.time)
                    dv.etDatetimeText.setText("$datePart ${String.format("%02d:%02d", hour, minute)}")
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        }

        dv.btnCancelDatetime.setOnClickListener { dialog.dismiss() }

        dv.btnConfirmDatetime.setOnClickListener {
            val raw = dv.etDatetimeText.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(activity, "请输入日期", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val formatted = normalizeOrNull(raw)
            if (formatted == null) {
                Toast.makeText(activity, "格式不对，请输入 yyyy-MM-dd 或 yyyy-MM-dd HH:mm", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            onConfirm(formatted)
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * 把松散格式的日期/日期时间字符串标准化。
     * 支持：
     * - yyyy-MM-dd / yyyy/MM/dd / yyyy.MM.dd（纯日期，时间默认 00:00）
     * - yyyy-MM-dd HH:mm / yyyy/MM/dd HH:mm / yyyy-MM-dd HHmm（带时间）
     * 解析失败返回 null。
     */
    private fun normalizeOrNull(raw: String): String? {
        // 先尝试纯日期格式（无时分）
        val dateOnlyRe = Regex("""(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})$""")
        val dateMatch = dateOnlyRe.find(raw.trim())
        if (dateMatch != null) {
            val y = dateMatch.groupValues[1].toIntOrNull() ?: return null
            val mo = dateMatch.groupValues[2].toIntOrNull() ?: return null
            val d = dateMatch.groupValues[3].toIntOrNull() ?: return null
            if (y in 2000..2100 && mo in 1..12 && d in 1..31) {
                val cal = Calendar.getInstance()
                cal.set(y, mo - 1, d, 0, 0)
                if (cal.get(Calendar.YEAR) == y &&
                    cal.get(Calendar.MONTH) == mo - 1 &&
                    cal.get(Calendar.DAY_OF_MONTH) == d
                ) {
                    return String.format("%04d-%02d-%02d 00:00", y, mo, d)
                }
            }
        }

        // 再尝试日期+时间格式
        val slashRe = Regex("""(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})\s+(\d{1,2})[:：]?(\d{2})""")
        val m = slashRe.find(raw) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        val h = m.groupValues[4].toIntOrNull() ?: return null
        val mi = m.groupValues[5].toIntOrNull() ?: return null
        if (y !in 2000..2100) return null
        if (mo !in 1..12 || d !in 1..31) return null
        if (h !in 0..23 || mi !in 0..59) return null

        val cal = Calendar.getInstance()
        cal.set(y, mo - 1, d, h, mi)
        if (cal.get(Calendar.YEAR) != y ||
            cal.get(Calendar.MONTH) != mo - 1 ||
            cal.get(Calendar.DAY_OF_MONTH) != d
        ) return null

        return String.format("%04d-%02d-%02d %02d:%02d", y, mo, d, h, mi)
    }
}
