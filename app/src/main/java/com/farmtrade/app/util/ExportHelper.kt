package com.farmtrade.app.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import com.farmtrade.app.data.Record
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets

/**
 * 导出帮助工具：将交易记录导出为 CSV 或 PDF 文件。
 *
 * CSV 使用标准库（UTF-8 BOM，Excel 可直接打开）；PDF 使用 Android 自带的 [PdfDocument]。
 * 文件统一保存到应用专属外部文件目录（无需存储权限）。
 */
object ExportHelper {

    /** 导出表格的列标题，顺序固定 */
    private val HEADERS = arrayOf(
        "日期时间", "买卖方向", "类型", "计量方式", "毛重", "车重",
        "净重", "数量", "单位", "单价", "总额", "来源"
    )

    /** PDF 表格列定义 */
    private class PdfColumn(
        val title: String,
        val width: Float,
        val value: (Record) -> String
    )

    /**
     * 将记录导出为 CSV 文件（Excel/WPS 可直接打开）。
     *
     * @param context 上下文
     * @param records 要导出的记录列表
     * @param fileName 文件名（不含扩展名）
     * @return 是否导出成功
     */
    fun exportToExcel(context: Context, records: List<Record>, fileName: String): Boolean {
        if (records.isEmpty()) {
            Toast.makeText(context, "暂无数据可导出", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            val dir = context.getExternalFilesDir(null)
            if (dir == null) {
                Toast.makeText(context, "无法访问存储目录", Toast.LENGTH_SHORT).show()
                return false
            }
            val file = File(dir, "$fileName.csv")

            FileOutputStream(file).use { fos ->
                // 写入 UTF-8 BOM，确保 Excel 正确识别中文编码
                fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                val writer: Writer = OutputStreamWriter(fos, StandardCharsets.UTF_8)

                // 写表头
                writer.write(HEADERS.joinToString(",") { escapeCsv(it) })
                writer.write("\n")

                // 写数据行
                for (record in records) {
                    val row = recordToRow(record)
                    writer.write(row.joinToString(",") { escapeCsv(it) })
                    writer.write("\n")
                }
                writer.flush()
            }

            Toast.makeText(context, "已导出到：${file.absolutePath}", Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * CSV 字段转义：包含逗号、引号、换行时用双引号包裹，内部引号翻倍。
     */
    private fun escapeCsv(field: String): String {
        if (field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r')) {
            return "\"${field.replace("\"", "\"\"")}\""
        }
        return field
    }

    /**
     * 将记录导出为 PDF。
     *
     * 使用 Android 自带的 [PdfDocument]，A4 横向，简单表格排版，自动分页。
     *
     * @param context 上下文
     * @param records 要导出的记录列表
     * @param fileName 文件名（不含扩展名）
     * @return 是否导出成功
     */
    fun exportToPdf(context: Context, records: List<Record>, fileName: String): Boolean {
        if (records.isEmpty()) {
            Toast.makeText(context, "暂无数据可导出", Toast.LENGTH_SHORT).show()
            return false
        }

        val pdf = PdfDocument()
        return try {
            // A4 横向：宽 842，高 595（单位：PostScript point）
            val pageW = 842
            val pageH = 595
            val margin = 24f
            val contentW = pageW - margin * 2

            // 列定义（相对宽度，会按比例缩放铺满页宽）
            val cols = listOf(
                PdfColumn("日期时间", 90f) { it.dateTime },
                PdfColumn("方向", 30f) { it.direction },
                PdfColumn("类型", 50f) { it.type },
                PdfColumn("计量", 46f) { measureModeText(it.measureMode) },
                PdfColumn("毛重", 38f) { Record.formatNumber(it.grossWeight) },
                PdfColumn("车重", 38f) { Record.formatNumber(it.vehicleWeight) },
                PdfColumn("净重", 42f) { Record.formatNumber(it.calculateNetWeight()) },
                PdfColumn("数量", 38f) { Record.formatNumber(it.quantity) },
                PdfColumn("单位", 32f) { it.unitName },
                PdfColumn("单价", 44f) { Record.formatNumber(it.unitPrice) },
                PdfColumn("总额", 58f) { Record.formatMoney(it.totalAmount) },
                PdfColumn("来源", 38f) { sourceText(it.source) }
            )

            val totalColWidth = cols.sumOf { it.width.toDouble() }.toFloat()
            val scale = contentW / totalColWidth
            val colWidths = cols.map { it.width * scale }
            val colX = mutableListOf<Float>()
            var xCursor = margin
            colWidths.forEach { w ->
                colX.add(xCursor)
                xCursor += w
            }

            // 画笔
            val titlePaint = Paint().apply {
                color = Color.parseColor("#2e7d32")
                textSize = 20f
                isFakeBoldText = true
            }
            val headerPaint = Paint().apply {
                color = Color.WHITE
                textSize = 9f
                isFakeBoldText = true
            }
            val cellPaint = Paint().apply {
                color = Color.BLACK
                textSize = 8f
            }
            val linePaint = Paint().apply {
                color = Color.parseColor("#cccccc")
            }
            val headerBgPaint = Paint().apply {
                color = Color.parseColor("#2e7d32")
            }

            val rowHeight = 22f
            var pageNum = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
            var page = pdf.startPage(pageInfo)
            var canvas: Canvas = page.canvas

            // 标题
            canvas.drawText("农产品买卖记录", margin, margin + 8f, titlePaint)
            var y = margin + 34f

            // 绘制表头（局部函数，可修改外层 y）
            fun drawHeader(c: Canvas) {
                c.drawRect(margin, y, margin + contentW, y + rowHeight, headerBgPaint)
                cols.forEachIndexed { i, col ->
                    val text = col.title
                    val textW = headerPaint.measureText(text)
                    val cellX = colX[i] + (colWidths[i] - textW) / 2f
                    c.drawText(text, cellX, y + 14f, headerPaint)
                }
                y += rowHeight
            }

            drawHeader(canvas)

            records.forEach { record ->
                // 超出页面高度则换页
                if (y + rowHeight > pageH - margin) {
                    pdf.finishPage(page)
                    pageNum++
                    pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
                    page = pdf.startPage(pageInfo)
                    canvas = page.canvas
                    y = margin + 34f
                    drawHeader(canvas)
                }
                // 行底分隔线
                canvas.drawLine(margin, y + rowHeight, margin + contentW, y + rowHeight, linePaint)
                cols.forEachIndexed { i, col ->
                    val text = col.value(record)
                    val maxW = colWidths[i] - 6f
                    val display = truncateText(text, maxW, cellPaint)
                    canvas.drawText(display, colX[i] + 3f, y + 14f, cellPaint)
                }
                y += rowHeight
            }
            pdf.finishPage(page)

            // 写入文件
            val dir = context.getExternalFilesDir(null)
            if (dir == null) {
                Toast.makeText(context, "无法访问存储目录", Toast.LENGTH_SHORT).show()
                return false
            }
            val file = File(dir, "$fileName.pdf")
            FileOutputStream(file).use { pdf.writeTo(it) }

            Toast.makeText(context, "已导出到：${file.absolutePath}", Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            false
        } finally {
            pdf.close()
        }
    }

    /** 将一条记录转换为一行字符串数据 */
    private fun recordToRow(r: Record): Array<String> = arrayOf(
        r.dateTime,
        r.direction,
        r.type,
        measureModeText(r.measureMode),
        Record.formatNumber(r.grossWeight),
        Record.formatNumber(r.vehicleWeight),
        Record.formatNumber(r.calculateNetWeight()),
        Record.formatNumber(r.quantity),
        r.unitName,
        Record.formatNumber(r.unitPrice),
        Record.formatMoney(r.totalAmount),
        sourceText(r.source)
    )

    /** 计量方式转中文 */
    fun measureModeText(mode: String): String = when (mode) {
        Record.MODE_WEIGHT_KG -> "按重量(公斤)"
        Record.MODE_WEIGHT_JIN -> "按重量(斤)"
        Record.MODE_QUANTITY -> "按数量"
        else -> mode
    }

    /** 来源转中文 */
    fun sourceText(source: String): String = when (source) {
        Record.SOURCE_PHOTO -> "拍照"
        Record.SOURCE_VOICE -> "语音"
        Record.SOURCE_MANUAL -> "手动"
        else -> source
    }

    /** 文本超出宽度时截断并加省略号 */
    private fun truncateText(text: String, maxWidth: Float, paint: Paint): String {
        if (text.isEmpty()) return text
        if (paint.measureText(text) <= maxWidth) return text
        var len = text.length
        while (len > 0 && paint.measureText(text.substring(0, len) + "..") > maxWidth) {
            len--
        }
        return if (len > 0) text.substring(0, len) + ".." else ""
    }
}
