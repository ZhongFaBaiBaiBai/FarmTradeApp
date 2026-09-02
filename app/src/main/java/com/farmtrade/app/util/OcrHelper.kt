package com.farmtrade.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * OCR 文字识别工具。
 * - 单字段：[recognizeFromBitmap] 返回最可能是重量的数字（取最大值）。
 * - 记录本批量：[recognizeLedgerRows] 逐行扫描"总重-车重"算式，一条算式一条记录。
 */
object OcrHelper {

    /** 记录本批量识别：一行算式 = 一条记录（gross=总重，tare=车重）。 */
    data class LedgerRow(val gross: Double, val tare: Double)

    /** 减法算式 "2500-800"，允许减号是 - – — 或全角 － */
    private val subtractionRegex = Regex("""(\d{1,7}(?:\.\d{1,3})?)\s*[-–—－]\s*(\d{1,7}(?:\.\d{1,3})?)""")
    /** 普通数字 */
    private val numberRegex = Regex("""(\d{1,7})(?:\.(\d{1,3}))?""")
    /** 重量相关关键词：数字所在行含这些词时优先采纳 */
    private val weightKeywords = Regex("""总重|毛重|车重|皮重|车皮重|净重|毛|皮|kg|公斤|斤|吨|tare|gross""", RegexOption.IGNORE_CASE)

    // ================== 公开入口 ==================

    /** 单字段：从 Bitmap 识别一个最像重量的数字（关键词优先，其次取最大值）。 */
    suspend fun recognizeFromBitmap(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                .process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(pickLargestNumber(visionText))
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

    /**
     * 记录本批量：逐行扫描"总重-车重"算式（如 1880-810），一条算式 = 一条记录。
     * 复杂行（带 ×2、×1.1 等乘法）只取减号前后两个数；识别不准的行由用户在确认列表里删除。
     */
    suspend fun recognizeLedgerRows(bitmap: Bitmap): List<LedgerRow> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                .process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(extractLedgerRows(visionText))
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    /** 为拍照创建临时图片 Uri（经 FileProvider 共享）。 */
    fun createImageUri(context: Context): Uri? {
        return try {
            val file = File.createTempFile("weigh_${System.currentTimeMillis()}", ".jpg", context.cacheDir)
            file.deleteOnExit()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) { null }
    }

    /** 从 Uri 加载 Bitmap（兼容 Android 9 以下）。 */
    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.setMutableRequired(true) }
            } else {
                @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (_: Exception) { null }
    }

    // ================== 内部实现 ==================

    /**
     * 从 OCR 文本中取最可能是重量的数字。
     * 策略：有关键词标注（总重/毛重/kg/斤等）的数字优先；没有关键词才 fallback 取最大值。
     */
    private fun pickLargestNumber(visionText: Text): String? {
        data class Num(val display: String, val value: Double, val hasKeyword: Boolean)
        val nums = mutableListOf<Num>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val norm = line.text.replace(" ", "").replace(",", "").replace("，", "")
                // 跳过时间格式
                if (norm.matches(Regex("""\d{1,2}[:：]\d{2}([:：]\d{2})?"""))) continue
                val hasKeyword = weightKeywords.containsMatchIn(norm)
                for (m in numberRegex.findAll(norm)) {
                    val display = m.value
                    val value = display.toDoubleOrNull() ?: continue
                    if (value <= 0.0) continue
                    nums.add(Num(display, value, hasKeyword))
                }
            }
        }
        if (nums.isEmpty()) return null
        // 有关键词标注的数字优先；都在同一行时取最大值
        val keywordNums = nums.filter { it.hasKeyword }
        val pool = if (keywordNums.isNotEmpty()) keywordNums else nums
        return pool.maxByOrNull { it.value }?.display
    }

    private fun extractLedgerRows(visionText: Text): List<LedgerRow> {
        val rows = mutableListOf<LedgerRow>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val norm = line.text
                    .replace(" ", "").replace(",", "").replace("，", "")
                    .replace('–', '-').replace('—', '-').replace('－', '-')
                val m = subtractionRegex.find(norm) ?: continue
                val gross = m.groupValues[1].toDoubleOrNull() ?: continue
                val tare = m.groupValues[2].toDoubleOrNull() ?: continue
                if (gross <= tare) continue
                if (gross < 10 || gross > 999999 || tare < 1) continue
                rows.add(LedgerRow(gross, tare))
            }
        }
        return rows
    }
}
