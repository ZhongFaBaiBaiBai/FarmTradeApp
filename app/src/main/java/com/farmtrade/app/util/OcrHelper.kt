package com.farmtrade.app.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

    private fun recognizeFromBitmap(bitmap: Bitmap, callback: (String?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // 提取所有数字
                val numbers = extractNumbers(visionText.text)
                callback(numbers.firstOrNull())
            }
            .addOnFailureListener {
                callback(null)
            }
    }

    /**
     * 从文本中提取数字
     * 优先匹配类似 "2500" 或 "2500.5" 的纯数字
     */
    private fun extractNumbers(text: String): List<String> {
        // 匹配纯数字（包括小数）
        val numberPattern = Regex("""\d+\.?\d*""")
        val matches = numberPattern.findAll(text).map { it.value }.toList()

        // 按长度降序排序，优先返回较长的数字（通常是重量值）
        return matches.sortedByDescending { it.length }
    }

    /**
     * 检查文本是否包含重量相关关键词
     */
    fun containsWeightKeywords(text: String): Boolean {
        val keywords = listOf("kg", "公斤", "斤", "吨", "总重", "毛重", "净重", "皮重", "车重")
        return keywords.any { text.contains(it, ignoreCase = true) }
    }
}
