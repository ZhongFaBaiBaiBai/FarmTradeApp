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
 * OCR 文字识别工具。
 * - 单字段：[recognizeFromBitmap] 返回一个最可能是重量的数字字符串（兼容旧调用方）。
 * - 多字段：[recognizeMultiFields] 返回 [OcrResult]，按"关键词→减法算式→兜底"三级策略同时识别
 *   总重 / 车重 / 单价，适合整张记录本照片一次识别三个字段。
 * - 记录本批量：[recognizeLedgerRows] 逐行扫描"总重-车重"算式，一条算式生成一条记录。
 */
object OcrHelper {

    /** 多字段识别结果（总重 / 车重 / 单价 分别为 null 表示未识别到）。 */
    data class OcrResult(
        val gross: String? = null,    // 总重 / 毛重
        val tare: String? = null,     // 车重 / 皮重
        val price: String? = null,    // 单价
        val rawText: String = ""      // OCR 原始文本，方便用户核对
    )

    /** 记录本批量识别：一行算式 = 一条记录（gross=总重，tare=车重）。 */
    data class LedgerRow(val gross: Double, val tare: Double)

    // ================== 公开入口 ==================

    /** 单字段：从 Uri 识别"最像重量的数字"。兼容旧调用方。 */
    suspend fun recognizeNumber(context: Context, imageUri: Uri): String? {
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, imageUri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.setMutableRequired(true) }
            } else {
                @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
            }
        } catch (_: Exception) { null }
        return if (bitmap != null) recognizeFromBitmap(bitmap) else null
    }

    /** 单字段：从 Bitmap 识别一个最像重量的数字字符串。 */
    suspend fun recognizeFromBitmap(bitmap: Bitmap): String? =
        recognizeMultiFields(bitmap).gross

    /** 多字段：一次识别总重 + 车重 + 单价。 */
    suspend fun recognizeMultiFields(bitmap: Bitmap): OcrResult =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(extractMultiFields(visionText))
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(OcrResult())
                }
        }

    /**
     * 记录本批量：逐行扫描"总重-车重"算式（如 1880-810），一条算式 = 一条记录。
     * 复杂行（带 ×2、×1.1 等乘法）只取减号前后两个数；识别不准的行由用户在确认列表里删除。
     */
    suspend fun recognizeLedgerRows(bitmap: Bitmap): List<LedgerRow> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(extractLedgerRows(visionText))
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
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
                // 合理性过滤：总重必须大于车重，且数值在记录本常见范围内
                if (gross <= tare) continue
                if (gross < 10 || gross > 999999 || tare < 1) continue
                rows.add(LedgerRow(gross, tare))
            }
        }
        return rows
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

    // ================== 关键词与正则 ==================

    /** 标签 → 字段分类的强关键词。同一 element 或同一 line 出现关键词时，相邻数字归属到对应字段。 */
    private val grossKeywords = Regex("""总重|毛重|毛|total.*?(weight|gross)""", RegexOption.IGNORE_CASE)
    private val tareKeywords = Regex("""车重|皮重|车皮重|皮|tare""", RegexOption.IGNORE_CASE)
    private val priceKeywords = Regex("""单价|价格|元|元/斤|元/公斤|元/kg|每斤|每公斤|price""", RegexOption.IGNORE_CASE)

    private val unitRegex = Regex("""(?i)(kg|千克|公斤|斤|吨|t)""")
    private val noiseRegex = Regex("""[-/年月日时分秒]""")
    private val timeRegex = Regex("""^\d{1,2}[:：]\d{2}([:：]\d{2})?$""")
    private val numberRegex = Regex("""(\d{1,7})(?:\.(\d{1,3}))?""")
    /** 减法算式 "2500-800" → 前 gross 后 tare，允许 减号 是 - – — 或全角 － */
    private val subtractionRegex = Regex("""(\d{1,7}(?:\.\d{1,3})?)\s*[-\–—－]\s*(\d{1,7}(?:\.\d{1,3})?)""")
    /** "3.5元/斤" 这类 数字 + 单位+价格 的紧凑写法 */
    private val priceCompactRegex = Regex("""(\d+\.\d{1,2})\s*(?:元/?斤|元/?公斤|元/?kg|元)""")

    // ================== 多字段分配核心 ==================

    private data class Candidate(
        val display: String,
        val value: Double,
        val elementIdx: Int,    // 在所在 line.elements 里的索引
        val line: String,        // 所在行文本（用于关键词判定）
        val elementText: String, // 当前 element 原始文本
        val height: Int
    )

    private enum class Tag { GROSS, TARE, PRICE, NONE }

    private fun extractMultiFields(visionText: Text): OcrResult {
        val rawText = visionText.text
        // 1. 收集所有数字候选 + 它们的标签分类
        val candidates = collectCandidates(visionText)

        // 2. 先扫减法算式：整张 rawText 里找 "2500-800"
        val subtraction = subtractionRegex.find(rawText)
        var grossBySub: String? = null
        var tareBySub: String? = null
        if (subtraction != null) {
            grossBySub = subtraction.groupValues[1]
            tareBySub = subtraction.groupValues[2]
        }

        // 3. price 额外启发：找 "3.5元/斤" 紧凑写法
        val priceCompact = priceCompactRegex.find(rawText)?.groupValues?.get(1)

        // 4. 按"有关键词优先，减法其次，兜底最后"分配
        val assigned = mutableMapOf<Tag, Candidate?>()
        val usedValues = mutableSetOf<String>()

        // 4a. 优先关键词分配
        assignByKeyword(candidates, assigned, usedValues, Tag.GROSS)
        assignByKeyword(candidates, assigned, usedValues, Tag.TARE)
        assignByKeyword(candidates, assigned, usedValues, Tag.PRICE)

        // 4b. 减法算式填补 gross / tare（关键词没找到时）
        if (assigned[Tag.GROSS] == null && grossBySub != null) {
            candidates.firstOrNull { it.display == grossBySub }?.let {
                assigned[Tag.GROSS] = it; usedValues.add(it.display)
            }
        }
        if (assigned[Tag.TARE] == null && tareBySub != null) {
            candidates.firstOrNull { it.display == tareBySub }?.let {
                assigned[Tag.TARE] = it; usedValues.add(it.display)
            }
        }

        // 4c. price 填补（priceCompact 优先）
        if (assigned[Tag.PRICE] == null && priceCompact != null) {
            candidates.firstOrNull { it.display == priceCompact }?.let {
                assigned[Tag.PRICE] = it; usedValues.add(it.display)
            }
        }

        // 4d. 兜底：关键词和减法都没识别到的字段 → 在剩余候选里按数值/小数位分配
        val remaining = candidates.filter { it.display !in usedValues }
        if (assigned[Tag.GROSS] == null) assigned[Tag.GROSS] = remaining.maxByOrNull { it.value }
        val remAfterGross = remaining.filter { assigned[Tag.GROSS]?.display != it.display }
        if (assigned[Tag.TARE] == null) assigned[Tag.TARE] = remAfterGross.maxByOrNull { it.value }
        val remAfterTare = remAfterGross.filter { assigned[Tag.TARE]?.display != it.display }
        if (assigned[Tag.PRICE] == null) {
            // 优先选小数点位数多的（单价多为 3.5 / 0.95 这种）；没有小数就选最小的正数
            assigned[Tag.PRICE] = remAfterTare
                .filter { it.value in 0.01..500.0 }
                .maxByOrNull { if (it.display.contains('.')) 1.0 + it.value else it.value }
        }

        return OcrResult(
            gross = assigned[Tag.GROSS]?.display ?: grossBySub,
            tare = assigned[Tag.TARE]?.display ?: tareBySub,
            price = assigned[Tag.PRICE]?.display ?: priceCompact,
            rawText = rawText
        )
    }

    private fun collectCandidates(visionText: Text): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val elements = line.elements
                elements.forEachIndexed { idx, el ->
                    val raw = el.text.replace(" ", "").replace(",", "").replace("，", "")
                    if (noiseRegex.containsMatchIn(raw) || timeRegex.containsMatchIn(raw)) return@forEachIndexed
                    val m = numberRegex.find(raw) ?: return@forEachIndexed
                    val display = m.value
                    val value = display.toDoubleOrNull() ?: return@forEachIndexed
                    if (value <= 0.0) return@forEachIndexed
                    out.add(Candidate(
                        display = display,
                        value = value,
                        elementIdx = idx,
                        line = line.text,
                        elementText = raw,
                        height = el.boundingBox?.height() ?: 0
                    ))
                }
            }
        }
        return out
    }

    private fun assignByKeyword(
        candidates: List<Candidate>,
        assigned: MutableMap<Tag, Candidate?>,
        usedValues: MutableSet<String>,
        tag: Tag
    ) {
        val re = when (tag) {
            Tag.GROSS -> grossKeywords
            Tag.TARE -> tareKeywords
            Tag.PRICE -> priceKeywords
            Tag.NONE -> return
        }
        val best = candidates
            .filter { c ->
                if (c.display in usedValues) return@filter false
                // 关键词出现在 element 文本 或 所在行文本
                re.containsMatchIn(c.elementText) || re.containsMatchIn(c.line)
            }
            .maxByOrNull { c ->
                var score = 0.0
                if (unitRegex.containsMatchIn(c.line)) score += 100.0
                score += ln(c.value + 1.0) * 8.0
                score += c.height * 0.2
                score
            }
        if (best != null) {
            assigned[tag] = best
            usedValues.add(best.display)
        }
    }

    // ================== 兼容旧 API ==================

    /** 旧版调用方仍然可以用 [pickBestNumber] 的等价行为。 */
    fun pickBestNumberCompat(visionText: Text): String? = extractMultiFields(visionText).gross

    fun containsWeightKeywords(text: String): Boolean {
        val keywords = listOf("kg", "公斤", "斤", "吨", "总重", "毛重", "净重", "皮重", "车重")
        return keywords.any { text.contains(it, ignoreCase = true) }
    }
}
