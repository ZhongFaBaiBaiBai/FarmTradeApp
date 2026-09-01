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
import kotlin.math.ln

/**
 * OCR文字识别工具 - 用于从地磅照片中提取数字
 */
object OcrHelper {

    /**
     * 从图片中识别数字
     * @param context 上下文
     * @param imageUri 图片URI
     * @return 识别到的数字，失败返回null
     */
    suspend fun recognizeNumber(context: Context, imageUri: Uri): String? {
        return suspendCancellableCoroutine { cont ->
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
                recognizeFromBitmap(bitmap) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * 从Bitmap识别数字
     */
    suspend fun recognizeFromBitmap(bitmap: Bitmap): String? {
        return suspendCancellableCoroutine { cont ->
            recognizeFromBitmap(bitmap) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
    }

    /**
     * 为拍照创建临时图片 Uri（经 FileProvider 共享）
     */
    fun createImageUri(context: Context): Uri? {
        return try {
            val file = File.createTempFile("weigh_${System.currentTimeMillis()}", ".jpg", context.cacheDir)
            file.deleteOnExit()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 Uri 加载 Bitmap（兼容 Android 9 以下）
     */
    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setMutableRequired(true)
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun recognizeFromBitmap(bitmap: Bitmap, callback: (String?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                callback(pickBestNumber(visionText))
            }
            .addOnFailureListener {
                callback(null)
            }
    }

    /** 强关键词（明确指向总重/毛重类数值） */
    private val strongKeywordRegex = Regex("""总重|毛重|净重|重量""")
    /** 计量单位（出现在同一文本块内，或紧邻的文本块中） */
    private val unitRegex = Regex("""(?i)kg|千克|公斤|斤|吨|\dt""")
    /** 干扰项：单号/日期等（含 - / 或年月日时分秒字样） */
    private val noiseRegex = Regex("""[-/年月日时分秒]""")
    /** 纯时间样式（如 12:30、9:05:30），与"毛重:2500"区分开 */
    private val timeRegex = Regex("""^\d{1,2}[:：]\d{2}([:：]\d{2})?$""")
    /** 纯数字（含小数）；整数最多 7 位，防止把单号/日期串当重量 */
    private val numberRegex = Regex("""(\d{1,7})(?:\.(\d{1,3}))?""")

    private data class Candidate(
        val display: String,
        val value: Double,
        val hasUnit: Boolean,
        val hasStrongKeyword: Boolean,
        val height: Int
    )

    /**
     * 从 OCR 结果中挑选最可能是重量的数字。
     * 优先级：总重/毛重关键词 > 计量单位(kg/斤/吨等) > 数值更大 > 字号更大（主显示屏数值）。
     * 日期、时间、单号等干扰数字会被排除。
     */
    private fun pickBestNumber(visionText: Text): String? {
        val candidates = mutableListOf<Candidate>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val elements = line.elements
                elements.forEachIndexed { index, element ->
                    val raw = element.text
                        .replace(" ", "").replace(",", "").replace("，", "")
                    if (noiseRegex.containsMatchIn(raw) || timeRegex.containsMatchIn(raw)) return@forEachIndexed
                    val m = numberRegex.find(raw) ?: return@forEachIndexed
                    val display = m.value
                    val value = display.toDoubleOrNull() ?: return@forEachIndexed
                    if (value <= 0.0) return@forEachIndexed

                    // 单位：本元素内，或同一行左右相邻元素
                    val hasUnit = unitRegex.containsMatchIn(raw) ||
                            elements.getOrNull(index - 1)?.let { unitRegex.containsMatchIn(it.text) } == true ||
                            elements.getOrNull(index + 1)?.let { unitRegex.containsMatchIn(it.text) } == true
                    // 关键词：本元素或所在行
                    val hasKeyword = strongKeywordRegex.containsMatchIn(raw) ||
                            strongKeywordRegex.containsMatchIn(line.text)

                    candidates.add(
                        Candidate(
                            display = display,
                            value = value,
                            hasUnit = hasUnit,
                            hasStrongKeyword = hasKeyword,
                            height = element.boundingBox?.height() ?: 0
                        )
                    )
                }
            }
        }

        if (candidates.isEmpty()) return null

        return candidates.maxByOrNull { c ->
            var score = 0.0
            if (c.hasStrongKeyword) score += 5000.0
            if (c.hasUnit) score += 1000.0
            score += ln(c.value + 1.0) * 8.0   // 数值更大略优先（毛重通常是屏上最大的数）
            score += c.height * 0.5            // 字号越大越可能是主显示屏数值
            score
        }?.display
    }

    /**
     * 检查文本是否包含重量相关关键词
     */
    fun containsWeightKeywords(text: String): Boolean {
        val keywords = listOf("kg", "公斤", "斤", "吨", "总重", "毛重", "净重", "皮重", "车重")
        return keywords.any { text.contains(it, ignoreCase = true) }
    }
}
