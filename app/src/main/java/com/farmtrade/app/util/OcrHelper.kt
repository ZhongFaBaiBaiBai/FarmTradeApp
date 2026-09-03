package com.farmtrade.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
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
 *  - 单字段：[recognizeFromBitmap] 返回最可能是重量的数字。
 *  - 记录本批量：[recognizeLedgerRows] 逐行扫描"总重-车重"算式。
 *
 *  针对【地磅LED七段数码管】做了专门优化：
 *  1) 前置图像预处理：只保留绿色高亮度发光像素 → 自动裁剪屏幕区域 → 放大3x → 反色为白底黑字 → 二值化。
 *  2) 双引擎：ML Kit 先识别；若失败，则走【七段数码管像素模板匹配】fallback。
 */
object OcrHelper {

    data class LedgerRow(val gross: Double, val tare: Double)

    private val subtractionRegex = Regex("""(\d{1,7}(?:\.\d{1,3})?)\s*[-–—－]\s*(\d{1,7}(?:\.\d{1,3})?)""")
    private val numberRegex = Regex("""(\d{1,7})(?:\.(\d{1,3}))?""")
    private val weightKeywords = Regex("""总重|毛重|车重|皮重|车皮重|净重|毛|皮|kg|公斤|斤|吨|tare|gross""", RegexOption.IGNORE_CASE)

    // ================== 公开入口 ==================

    suspend fun recognizeFromBitmap(bitmap: Bitmap): String? {
        // —— 策略 1：先直接交给 ML Kit（正常文本/记录本场景快速成功）
        val r1 = runMlKit(bitmap)
        val n1 = pickLargestNumber(r1)
        if (n1 != null) return n1

        // —— 策略 2：LED 预处理管线 → ML Kit
        val preprocessed = preprocessLedScreen(bitmap)
        val r2 = runMlKit(preprocessed)
        val n2 = pickLargestNumber(r2)
        if (n2 != null) return n2

        // —— 策略 3：七段数码管像素识别 fallback
        val n3 = recognizeSevenSegmentDigits(preprocessed)
        if (n3 != null) return n3

        // —— 策略 4：纸张裁剪 + 3x 放大 + 灰度增强 + 二值化 + ±12° 旋转
        // 所有变体都跑一遍，取识别到的"最大数字"——手写截断碎片一定比完整数字小
        val paperScaled = cropAndScalePaper(bitmap)
        val paperCandidates = mutableListOf<Double>()
        runMlKit(paperScaled).let { pickLargestNumber(it) }?.toDoubleOrNull()?.let { paperCandidates.add(it) }
        val enhanced = enhanceGrayscaleContrast(paperScaled)
        runMlKit(enhanced).let { pickLargestNumber(it) }?.toDoubleOrNull()?.let { paperCandidates.add(it) }
        val binary = binarizeOtsu(enhanced)
        runMlKit(binary).let { pickLargestNumber(it) }?.toDoubleOrNull()?.let { paperCandidates.add(it) }
        for (angle in listOf(-12f, 12f)) {
            runMlKit(rotateBitmap(enhanced, angle)).let { pickLargestNumber(it) }?.toDoubleOrNull()?.let { paperCandidates.add(it) }
            runMlKit(rotateBitmap(binary, angle)).let { pickLargestNumber(it) }?.toDoubleOrNull()?.let { paperCandidates.add(it) }
        }
        if (paperCandidates.isNotEmpty()) {
            // 优先 3 位以上的数（重量场景），没有再退回最大
            val best = paperCandidates.filter { it >= 100 }.maxOrNull() ?: paperCandidates.maxOrNull()
            if (best != null) {
                return if (best == best.toLong().toDouble()) best.toLong().toString() else best.toString()
            }
        }

        // —— 策略 5：原图 + 2x 放大兜底
        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
        val n4 = runMlKit(scaled).let { pickLargestNumber(it) }
        scaled.recycle()
        return n4
    }

    suspend fun recognizeLedgerRows(bitmap: Bitmap): List<LedgerRow> {
        // 不再"第一个 pass 出结果就返回"——手写识别可能把 2150 截断成 50，
        // 而是把所有 pass 的候选算式都收集起来，最后做去重/去碎片，选最可信的结果。
        val candidates = LinkedHashMap<String, LedgerRow>()

        // Pass 1: 原图直接识别
        runMlKit(bitmap).let { extractLedgerRows(it) }.forEach { candidates[keyOf(it)] = it }

        // Pass 2: 纸张裁剪 + 3x 放大
        val scaled = cropAndScalePaper(bitmap)
        runMlKit(scaled).let { extractLedgerRows(it) }.forEach { candidates[keyOf(it)] = it }

        // Pass 3: 灰度对比度增强
        val enhanced = enhanceGrayscaleContrast(scaled)
        runMlKit(enhanced).let { extractLedgerRows(it) }.forEach { candidates[keyOf(it)] = it }

        // Pass 4: Otsu 二值化
        val binary = binarizeOtsu(enhanced)
        runMlKit(binary).let { extractLedgerRows(it) }.forEach { candidates[keyOf(it)] = it }

        // Pass 5: ±12° 旋转重试（灰度图 + 二值化图都试）
        for (angle in listOf(-12f, 12f)) {
            runMlKit(rotateBitmap(enhanced, angle)).let { extractLedgerRows(it) }.forEach { candidates[keyOf(it)] = it }
            runMlKit(rotateBitmap(binary, angle)).let { extractLedgerRows(it) }.forEach { candidates[keyOf(it)] = it }
        }

        // Pass 6: 原图 2x 放大兜底
        val scaled2 = Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
        runMlKit(scaled2).let { extractLedgerRows(it) }.forEach { candidates[keyOf(it)] = it }
        scaled2.recycle()

        return dropFragmentRows(candidates.values.toList())
    }

    /** 去重 key（四舍五入到个位后相同的算同一行） */
    private fun keyOf(r: LedgerRow): String = "${Math.round(r.gross)}_${Math.round(r.tare)}"

    /**
     * 去碎片：手写识别可能把 "2150-812" 截断成 "50-8"。
     * 若小行的毛重/车重数字串都是大行数字串的后缀、且差 10 倍以上，判定小行为碎片并丢弃。
     */
    private fun dropFragmentRows(rows: List<LedgerRow>): List<LedgerRow> {
        if (rows.size <= 1) return rows
        val sorted = rows.sortedByDescending { it.gross }
        val kept = mutableListOf<LedgerRow>()
        for (r in sorted) {
            val gs = r.gross.toLong().toString()
            val ts = r.tare.toLong().toString()
            val isFragment = kept.any { big ->
                val bgs = big.gross.toLong().toString()
                val bts = big.tare.toLong().toString()
                big.gross >= r.gross * 10 &&
                    bgs.endsWith(gs) &&
                    (bts.endsWith(ts) || r.tare < 10)
            }
            if (!isFragment) kept.add(r)
        }
        return kept
    }

    // ================== 过磅单多字段识别 ==================

    /**
     * 过磅单识别结果（重量统一换算为公斤）。
     * @param type 粮食品类（如"小麦"），识别不到为 null
     * @param grossKg 毛重（公斤）
     * @param tareKg 皮重/车重（公斤）
     * @param netKg 净重（公斤，识别值；可能为 0 表示未识别到）
     * @param unitPrice 单价（元/斤）
     * @param dateTime 过磅时间（yyyy-MM-dd HH:mm），识别不到为 null
     */
    data class WeighingSlip(
        val type: String?,
        val grossKg: Double,
        val tareKg: Double,
        val netKg: Double,
        val unitPrice: Double?,
        val dateTime: String?
    )

    /** 粮食品类关键词（用于从"粮食：小麦"行提取类型） */
    private val grainTypeRegex = Regex(
        """小麦|玉米|水稻|大米|大豆|黄豆|花生|棉粕|豆粕|麸皮|饲料|高粱|谷子|绿豆|豌豆|杂粮|化肥|农药|柴油|尿素|复合肥"""
    )
    /** 过磅单日期时间（兼容 2026-09-03 11:01:55 / 2026年9月3日 11:01） */
    private val slipDateTimeRegex = Regex(
        """(\d{4})[-年/.](\d{1,2})[-月/.](\d{1,2})日?\s*(\d{1,2}):(\d{2})"""
    )
    /** 通用数字 */
    private val plainNumberRegex = Regex("""\d+(?:\.\d+)?""")

    /**
     * 过磅单识别：逐行按关键词提取 毛重/皮重/净重/单价/类型/时间。
     * 判定成功条件：毛重>0 且（皮重>0 或 净重>0）。普通文字/地磅屏/记录本图片不会误判。
     */
    suspend fun recognizeWeighingSlip(bitmap: Bitmap): WeighingSlip? {
        // Pass 1: 原图直接识别
        runMlKit(bitmap)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }

        // Pass 2: 纸张裁剪 + 3x 放大
        val paperScaled = cropAndScalePaper(bitmap)
        runMlKit(paperScaled)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }

        // Pass 3: 灰度对比度增强
        val enhanced = enhanceGrayscaleContrast(paperScaled)
        runMlKit(enhanced)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }

        // Pass 4: Otsu 二值化
        val binary = binarizeOtsu(enhanced)
        runMlKit(binary)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }

        // Pass 5: ±12° 旋转重试（在二值化图上）
        for (angle in listOf(-12f, 12f)) {
            val rotated = rotateBitmap(binary, angle)
            runMlKit(rotated)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }
        }

        return null
    }

    private fun extractWeighingSlip(visionText: Text): WeighingSlip? {
        var gross = 0.0
        var tare = 0.0
        var net = 0.0
        var price: Double? = null
        var type: String? = null
        var dateTime: String? = null

        // 关键：过磅单是"标签列 | 数值列 | 单位列"三列布局，ML Kit 常把三列拆成不同
        // 文本块（如"皮重："一行、"814"另一行），必须先按 Y 坐标合并成视觉行再匹配
        for (norm in buildLogicalLines(visionText)) {
            if (norm.isBlank()) continue

                // 1) 过磅时间
                if (dateTime == null) {
                    val dm = slipDateTimeRegex.find(norm)
                    if (dm != null) {
                        dateTime = "%04d-%02d-%02d %02d:%02d".format(
                            dm.groupValues[1].toInt(),
                            dm.groupValues[2].toInt(),
                            dm.groupValues[3].toInt(),
                            dm.groupValues[4].toInt(),
                            dm.groupValues[5].toInt()
                        )
                    }
                }

                // 2) 粮食类型（"粮食：小麦"）
                if (type == null && norm.contains("粮食")) {
                    type = extractGrainType(norm)
                }

                // 3) 重量字段（从各自关键词后面取第一个数字，支持一行多字段合并）
                if (norm.contains("毛重") && gross <= 0.0) {
                    numAfter(norm, "毛重")?.let { gross = toKg(norm, it) }
                }
                if (tare <= 0.0 && (norm.contains("皮重") || norm.contains("车重"))) {
                    numAfter(norm, "皮重")?.let { tare = toKg(norm, it) }
                        ?: numAfter(norm, "车重")?.let { tare = toKg(norm, it) }
                }
                if (norm.contains("净重") && net <= 0.0) {
                    numAfter(norm, "净重")?.let { net = toKg(norm, it) }
                }

            // 4) 单价（元/斤，不换算）
            if (price == null && norm.contains("单价")) {
                price = numAfter(norm, "单价")
            }
        }

        // 没有毛重就不是过磅单
        if (gross <= 0.0) return null
        // 皮重缺失时用净重兜底推算
        if (tare <= 0.0 && net > 0.0) tare = gross - net
        if (tare <= 0.0 || gross <= tare) return null
        // 合理性：地磅毛重≥100kg、皮重≥50kg，毛重 < 100 吨
        if (gross < 100.0 || tare < 50.0 || gross > 100000) return null

        return WeighingSlip(type, gross, tare, net, price?.takeIf { it > 0 }, dateTime)
    }

    /** 取 label 之后的第一个数字（支持"毛重2154皮重814"合并行） */
    private fun numAfter(norm: String, label: String): Double? {
        val idx = norm.indexOf(label)
        if (idx < 0) return null
        return plainNumberRegex.find(norm, idx + label.length)?.value?.toDoubleOrNull()
    }

    /** 按行内单位换算为公斤：公斤/kg 原值；纯"斤"÷2；无单位默认公斤 */
    private fun toKg(norm: String, value: Double): Double {
        val lower = norm.lowercase()
        return when {
            lower.contains("公斤") || lower.contains("kg") -> value
            lower.contains("斤") -> value / 2.0
            else -> value
        }
    }

    /** 从"粮食：小麦"行提取品类；无冒号视为标题行（如"XX粮食收购点"）不提取 */
    private fun extractGrainType(norm: String): String? {
        grainTypeRegex.find(norm)?.value?.let { return it }
        val labelIdx = norm.indexOf("粮食")
        val colonIdx = norm.indexOfFirst { it == '：' || it == ':' }
        if (labelIdx >= 0 && colonIdx > labelIdx) {
            var rest = norm.substring(colonIdx + 1)
            // 截到第一个单位/干扰词之前
            val stopIdx = rest.indexOfFirst { it == '斤' || it == '公' || it == 'k' || it == 'K' }
            if (stopIdx > 0) rest = rest.substring(0, stopIdx)
            val cleaned = rest.filter { it.code > 0x2E00 }  // 只保留 CJK 等全角字符
            if (cleaned.isNotBlank() && cleaned.length <= 8) return cleaned
        }
        return null
    }

    /** ML Kit 文本行（带位置，用于按 Y 坐标合并同一视觉行） */
    private data class LogicLine(val text: String, val centerY: Int, val centerX: Int, val height: Int)

    /**
     * 把 OCR 结果按"视觉行"合并：
     * 过磅单是"标签列 | 数值列 | 单位列"三列布局，ML Kit 经常把三列拆成不同 block
     * （如"皮重："一行、"814"一行、"公斤"一行），导致关键词和数字匹配失败。
     * 这里按行 boundingBox 的 Y 坐标聚类：Y 接近的多个片段视为同一视觉行，
     * 行内再按 X 坐标从左到右拼接。
     */
    private fun buildLogicalLines(visionText: Text): List<String> {
        val raw = mutableListOf<LogicLine>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val t = line.text
                    ?.replace(" ", "")?.replace(",", "")?.replace("，", "")
                    ?.replace('–', '-')?.replace('—', '-')?.replace('－', '-')
                    .orEmpty()
                if (t.isBlank()) continue
                val box = line.boundingBox
                raw.add(
                    LogicLine(
                        t,
                        box?.let { it.top + it.height() / 2 } ?: 0,
                        box?.let { it.left + it.width() / 2 } ?: 0,
                        (box?.height() ?: 30).coerceAtLeast(10)
                    )
                )
            }
        }
        if (raw.isEmpty()) return emptyList()
        raw.sortBy { it.centerY }

        // Y 坐标聚类：与当前行任一片段中心 Y 差距 < 行高 60% 视为同一视觉行
        val rows = mutableListOf<MutableList<LogicLine>>()
        for (tl in raw) {
            val last = rows.lastOrNull()
            val sameRow = last != null && last.any {
                kotlin.math.abs(it.centerY - tl.centerY) < maxOf(it.height, tl.height) * 0.6
            }
            if (sameRow) last!!.add(tl) else rows.add(mutableListOf(tl))
        }
        return rows.map { row -> row.sortedBy { it.centerX }.joinToString("") { it.text } }
    }

    fun createImageUri(context: Context): Uri? {
        return try {
            val file = File.createTempFile("weigh_${System.currentTimeMillis()}", ".jpg", context.cacheDir)
            file.deleteOnExit()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) { null }
    }

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

    // ================== 内部：ML Kit ==================

    private suspend fun runMlKit(bitmap: Bitmap): Text? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                .process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }

    private fun pickLargestNumber(visionText: Text?): String? {
        if (visionText == null) return null
        data class Num(val display: String, val value: Double, val hasKeyword: Boolean)
        val nums = mutableListOf<Num>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val norm = line.text.replace(" ", "").replace(",", "").replace("，", "")
                if (norm.matches(Regex("""\d{1,2}[:：]\d{2}([:：]\d{2})?"""))) continue
                val hasKeyword = weightKeywords.containsMatchIn(norm)
                for (m in numberRegex.findAll(norm)) {
                    val display = m.value
                    val value = display.toDoubleOrNull() ?: continue
                    if (value <= 0.0) continue
                    if (value > 9_999_999) continue
                    nums.add(Num(display, value, hasKeyword))
                }
            }
        }
        if (nums.isEmpty()) return null
        val keywordNums = nums.filter { it.hasKeyword }
        val pool = if (keywordNums.isNotEmpty()) keywordNums else nums
        return pool.maxByOrNull { it.value }?.display
    }

    /** 日期/时间片段（先剔除，避免 "2026-09-03" 被误当成算式 "2026-09"） */
    private val dateTimeStripRegex = Regex("""\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}日?|\d{1,2}:\d{2}(?::\d{2})?""")

    private fun extractLedgerRows(visionText: Text?): List<LedgerRow> {
        if (visionText == null) return emptyList()
        val rows = mutableListOf<LedgerRow>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                // 原始文本：保留空格（减号丢失时两个数字靠空格分隔），仅统一标点
                val rawNorm = line.text
                    .replace(",", "").replace("，", "")
                    .replace('–', '-').replace('—', '-').replace('－', '-')
                // 主分支文本：去空格 + 剔除日期/时间片段
                val cleaned = dateTimeStripRegex.replace(rawNorm.replace(" ", ""), "")

                val m = subtractionRegex.find(cleaned)
                if (m != null) {
                    val gross = m.groupValues[1].toDoubleOrNull() ?: continue
                    val tare = m.groupValues[2].toDoubleOrNull() ?: continue
                    if (isValidLedgerRow(gross, tare)) rows.add(LedgerRow(gross, tare))
                } else {
                    // 放宽：手写减号可能被 OCR 吞掉/变形。
                    // 用保留空格的原始文本提取，一行恰好两个数字且前大后小也算算式
                    val nums = dateTimeStripRegex.replace(rawNorm, "")
                        .let { numberRegex.findAll(it) }
                        .mapNotNull { mm -> mm.value.toDoubleOrNull() }
                        .toList()
                    if (nums.size == 2 && isValidLedgerRow(nums[0], nums[1])) {
                        rows.add(LedgerRow(nums[0], nums[1]))
                    }
                }
            }
        }
        return rows
    }

    /** 算式合理性：毛重>皮重；地磅场景毛重通常≥100kg、皮重≥50kg（挡手写截断碎片如 50-8）；皮重≥1 */
    private fun isValidLedgerRow(gross: Double, tare: Double): Boolean {
        if (gross <= tare) return false
        if (gross < 100 || gross > 999999) return false
        if (tare < 50) return false
        return true
    }

    // ================== 内部：灰度对比度增强 / 二值化 ==================

    /**
     * 灰度化 + 对比度拉伸：把暗/亮的灰度值线性拉伸到 0~255 全范围。
     * 对光线不均、阴影、偏暗的照片效果显著，ML Kit 在高对比图上识别率更高。
     */
    private fun enhanceGrayscaleContrast(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1) 转灰度 + 找 min/max
        val gray = IntArray(w * h)
        var minG = 255; var maxG = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            gray[i] = g
            if (g < minG) minG = g
            if (g > maxG) maxG = g
        }
        // 2) 线性拉伸
        val range = (maxG - minG).coerceAtLeast(1)
        val out = IntArray(w * h)
        for (i in gray.indices) {
            val stretched = ((gray[i] - minG) * 255 / range).coerceIn(0, 255)
            out[i] = Color.rgb(stretched, stretched, stretched)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Otsu 自适应二值化：自动计算最佳阈值，比固定阈值更适合光线不均的照片。
     * 输出白底黑字，适合 ML Kit。
     */
    private fun binarizeOtsu(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 灰度直方图
        val hist = IntArray(256)
        for (p in pixels) {
            val g = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            hist[g]++
        }
        // Otsu 找最佳阈值
        val total = w * h
        var sumAll = 0
        for (i in 0..255) sumAll += i * hist[i]
        var sumBg = 0; var countBg = 0
        var maxVar = 0.0; var bestTh = 128
        for (th in 0..255) {
            countBg += hist[th]
            if (countBg == 0) continue
            val countFg = total - countBg
            if (countFg == 0) break
            sumBg += th * hist[th]
            val meanBg = sumBg.toDouble() / countBg
            val meanFg = (sumAll - sumBg).toDouble() / countFg
            val variance = countBg.toDouble() * countFg * (meanBg - meanFg) * (meanBg - meanFg)
            if (variance > maxVar) { maxVar = variance; bestTh = th }
        }

        // 二值化
        val black = Color.BLACK; val white = Color.WHITE
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val g = (Color.red(pixels[i]) * 299 + Color.green(pixels[i]) * 587 + Color.blue(pixels[i]) * 114) / 1000
            out[i] = if (g < bestTh) black else white
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    // ================== 内部：纸张区域检测 / 旋转 ==================

    /**
     * 检测纸张（白/浅色低饱和度像素）包围盒并裁剪，用于手写记录本等
     * "本子只占画面一小部分"的场景，裁掉大部分背景干扰。
     * 纸张占比过小或包围盒异常时返回原图。
     */
    private fun cropPaperRegion(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        var count = 0
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val p = pixels[y * w + x]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                val maxc = maxOf(r, g, b); val minc = minOf(r, g, b)
                // 浅色纸张（白/浅蓝/浅黄/暗光纸）：亮度较高、饱和度低
                val isPaper = minc > 110 && (maxc - minc) < 80
                if (isPaper) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // 纸张像素占比 < 8% → 判定失败返回原图
        val sampled = (w / 2) * (h / 2)
        if (count < sampled * 0.08) return src
        val bw = maxX - minX; val bh = maxY - minY
        if (bw < w * 0.15 || bh < h * 0.15) return src

        // 留 5% 余量
        val pad = (maxOf(bw, bh) * 0.05).toInt()
        val x0 = (minX - pad).coerceAtLeast(0)
        val y0 = (minY - pad).coerceAtLeast(0)
        val x1 = (maxX + pad).coerceAtMost(w - 1)
        val y1 = (maxY + pad).coerceAtMost(h - 1)
        return Bitmap.createBitmap(src, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val m = android.graphics.Matrix()
        m.postRotate(degrees)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * 纸张裁剪 + 限制最大边 1200px + 3x 放大。
     * 限制原始尺寸防止 3x 后超过 ML Kit 图像大小限制 / 内存溢出。
     */
    private fun cropAndScalePaper(src: Bitmap, factor: Int = 3): Bitmap {
        val paper = cropPaperRegion(src)
        val cap = 1200
        val p = if (maxOf(paper.width, paper.height) > cap) {
            val ratio = cap.toFloat() / maxOf(paper.width, paper.height)
            Bitmap.createScaledBitmap(paper, (paper.width * ratio).toInt(), (paper.height * ratio).toInt(), true)
        } else {
            paper
        }
        return Bitmap.createScaledBitmap(p, p.width * factor, p.height * factor, true)
    }

    // ================== 内部：LED 显示屏预处理 ==================

    /**
     * 地磅 LED 屏预处理：
     *  1) 只保留 (绿通道 - 红通道 - 蓝通道) > T 的像素（= 纯绿色发光像素）
     *  2) 自动裁剪到屏幕区域（绿像素的 min/max 包围盒）
     *  3) 放大 3x
     *  4) 反色 + 二值化 → 白底黑字（ML Kit 最喜欢的形态）
     */
    private fun preprocessLedScreen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1) 颜色过滤：找出"偏绿+高亮"像素
        val greenMask = BooleanArray(w * h)
        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                // LED 绿屏：G 明显高于 R/B，且绝对值亮度足够（发光）
                val isGreenLed = (g - r > 30) && (g - b > 30) && (g > 80)
                greenMask[y * w + x] = isGreenLed
                if (isGreenLed) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // 2) 裁剪包围盒（+ 小余量；无效则回退全图）
        val boxValid = (maxX - minX > 20) && (maxY - minY > 8)
        val cx = if (boxValid) minX else 0
        val cy = if (boxValid) minY else 0
        val cw = if (boxValid) (maxX - minX + 1) else w
        val ch = if (boxValid) (maxY - minY + 1) else h

        // 3) 二值化到白底黑字：绿像素=黑（保留字体），其他=白
        val bwPixels = IntArray(cw * ch)
        val white = Color.WHITE
        val black = Color.BLACK
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val origIdx = (cy + y) * w + (cx + x)
                bwPixels[y * cw + x] = if (greenMask[origIdx]) black else white
            }
        }

        var out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        out.setPixels(bwPixels, 0, cw, 0, 0, cw, ch)

        // 4) 放大 3x（让 ML Kit 能看清数码管笔划）
        val scale = 3
        out = Bitmap.createScaledBitmap(out, cw * scale, ch * scale, true)

        // 5) 再一次二值化（放大后的插值会产生灰色过渡，纯黑白更清晰）
        val sw = out.width; val sh = out.height
        val sp = IntArray(sw * sh)
        out.getPixels(sp, 0, sw, 0, 0, sw, sh)
        for (i in sp.indices) {
            val gray = (Color.red(sp[i]) + Color.green(sp[i]) + Color.blue(sp[i])) / 3
            sp[i] = if (gray < 160) black else white
        }
        val out2 = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        out2.setPixels(sp, 0, sw, 0, 0, sw, sh)
        out.recycle()
        return out2
    }

    // ================== 内部：七段数码管像素识别 fallback ==================

    /**
     * 七段数码管（a~g）：
     *   aaa
     *  f   b
     *  f   b
     *   ggg
     *  e   c
     *  e   c
     *   ddd
     *
     * 每个数字 = 7 段亮灭的固定组合。
     * 我们把单个数码框划分为 3 行 × 3 列 = 9 个格子，
     * 统计每个格子内"黑像素密度"，超过阈值判为"亮段"，然后查表得数字。
     */
    private fun recognizeSevenSegmentDigits(binarized: Bitmap): String? {
        val w = binarized.width; val h = binarized.height
        if (w < 20 || h < 10) return null
        val pixels = IntArray(w * h)
        binarized.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1) 列投影找数字分隔：统计每列黑色像素占比，<0.5% 就是分隔缝
        val colDensity = FloatArray(w)
        for (x in 0 until w) {
            var count = 0
            for (y in 0 until h) {
                if (pixels[y * w + x] == Color.BLACK) count++
            }
            colDensity[x] = count.toFloat() / h
        }
        val separators = mutableListOf<Int>()
        val threshold = 0.005f
        var inGap = false
        for (x in 0 until w) {
            if (colDensity[x] < threshold) {
                if (!inGap) { separators.add(x); inGap = true }
            } else { inGap = false }
        }
        if (separators.size < 2) {
            // 没找到分隔就全图当一个数字识别（太罕见，直接返回 null）
            return null
        }
        // 2) 切出每个数字的 x 区间
        val digitRanges = mutableListOf<Rect>()
        for (i in 0 until separators.size - 1) {
            val start = separators[i]; val end = separators[i + 1]
            // 找到该区间内 y 方向的实际黑像素范围（去掉上下空白）
            var yMin = h; var yMax = 0
            var hasAny = false
            for (x in start until end) {
                for (y in 0 until h) {
                    if (pixels[y * w + x] == Color.BLACK) {
                        hasAny = true
                        if (y < yMin) yMin = y
                        if (y > yMax) yMax = y
                    }
                }
            }
            if (!hasAny) continue
            val dw = end - start; val dh = yMax - yMin
            // 过窄的不是数字（小数点 / 单位文字等）
            if (dw < h * 0.18 || dh < h * 0.3) continue
            digitRanges.add(Rect(start, yMin, end, yMax))
        }
        if (digitRanges.isEmpty()) return null

        val sb = StringBuilder()
        for (rect in digitRanges) {
            val digit = matchSevenSegment(pixels, w, h, rect)
            if (digit != null) sb.append(digit)
        }

        val raw = sb.toString()
        if (raw.isBlank()) return null
        // 只取数字序列里的合理数字（地磅一般 3~6 位数）
        val m = numberRegex.find(raw) ?: return null
        val value = m.value.toDoubleOrNull() ?: return null
        if (value <= 0) return null
        return m.value
    }

    /** 对一个数字的包围盒内 9 宫格采样，匹配 0-9 段码 */
    private fun matchSevenSegment(pixels: IntArray, picW: Int, picH: Int, r: Rect): Char? {
        val dw = r.width(); val dh = r.height()
        if (dw < 3 || dh < 3) return null
        // 9 宫格：3 行 × 3 列
        val w3 = dw / 3.0; val h3 = dh / 3.0
        val densities = FloatArray(9)
        for (gi in 0..8) {
            val row = gi / 3; val col = gi % 3
            val x0 = (r.left + col * w3).toInt().coerceAtLeast(0)
            val x1 = (r.left + (col + 1) * w3).toInt().coerceAtMost(picW)
            val y0 = (r.top + row * h3).toInt().coerceAtLeast(0)
            val y1 = (r.top + (row + 1) * h3).toInt().coerceAtMost(picH)
            var total = 0; var black = 0
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    total++
                    if (pixels[y * picW + x] == Color.BLACK) black++
                }
            }
            densities[gi] = if (total == 0) 0f else black.toFloat() / total
        }
        // 7 段对应的宫格：
        //   a = row0 col1     b = row1 col2     c = row2 col2
        //   d = row2 col1     e = row2 col0     f = row1 col0
        //   g = row1 col1
        val T = 0.22f  // 段亮阈值（七段数码管每段是细条，密度不用太高）
        val a = densities[1] > T
        val b = densities[5] > T
        val c = densities[8] > T
        val d = densities[7] > T
        val e = densities[6] > T
        val f = densities[3] > T
        val g = densities[4] > T

        // 查表（顺序 a,b,c,d,e,f,g）
        return when {
            a && b && c && d && e && f && !g -> '8'
            a && b && c && d && e && f &&  g -> '8'  // 残影容忍
            a && b && c && d && f && !e &&  g -> '9'
            a && b && c && d && !e && !f && !g -> '0'.takeIf { false } ?: '0'
            !a && b && c && !d && !e && !f && !g -> '1'
            a && b && !c && d && e && !f &&  g -> '2'
            a && b && c && d && !e && !f &&  g -> '3'
            !a && b && c && !d && !e && f &&  g -> '4'
            a && !b && c && d && !e && f &&  g -> '5'
            a && !b && c && d && e && f &&  g -> '6'
            a && b && c && !d && !e && !f && !g -> '7'
            a && b && c && d && e && !f &&  g -> '9'  // 近似 9
            a && !b && !c && d && e && f &&  g -> '6'  // 近似 6
            // 兜底：如果段数匹配不上，选最接近的数字（Hamming 距离）
            else -> closestDigit(a, b, c, d, e, f, g)
        }
    }

    private fun closestDigit(a: Boolean, b: Boolean, c: Boolean,
                             d: Boolean, e: Boolean, f: Boolean, g: Boolean): Char? {
        // 每个数字的 7 段标准：下标 [a,b,c,d,e,f,g]
        val pattern = intArrayOf(
            if (a) 1 else 0, if (b) 1 else 0, if (c) 1 else 0,
            if (d) 1 else 0, if (e) 1 else 0, if (f) 1 else 0, if (g) 1 else 0
        )
        val digits = listOf(
            Triple('0', intArrayOf(1,1,1,1,1,1,0), 6),
            Triple('1', intArrayOf(0,1,1,0,0,0,0), 2),
            Triple('2', intArrayOf(1,1,0,1,1,0,1), 5),
            Triple('3', intArrayOf(1,1,1,1,0,0,1), 5),
            Triple('4', intArrayOf(0,1,1,0,0,1,1), 4),
            Triple('5', intArrayOf(1,0,1,1,0,1,1), 5),
            Triple('6', intArrayOf(1,0,1,1,1,1,1), 6),
            Triple('7', intArrayOf(1,1,1,0,0,0,0), 3),
            Triple('8', intArrayOf(1,1,1,1,1,1,1), 7),
            Triple('9', intArrayOf(1,1,1,1,0,1,1), 6),
        )
        var best: Char? = null; var minDist = 99
        for ((digit, std, _) in digits) {
            var dist = 0
            for (i in 0..6) if (pattern[i] != std[i]) dist++
            if (dist < minDist) { minDist = dist; best = digit }
        }
        // Hamming 距离 ≤3 才接受（太差就放弃，避免误读）
        return if (minDist <= 3) best else null
    }
}
