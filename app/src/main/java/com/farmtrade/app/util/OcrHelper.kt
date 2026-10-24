package com.farmtrade.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
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

    /**
     * 独立重量数字：3~7 位（地磅重量合理范围），且前后不能紧贴数字。
     * 防止电话(11位)/单号(13位)等长数字串被 \d{1,7} 切成两段误判为算式，
     * 也顺便拦掉 2 位碎片（如手写截断的 "50"、"8"）。
     */

    // ================== 公开入口 ==================

    /** 去重 key（四舍五入到个位后相同的算同一行） */

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
        val src = downscaleForOcr(bitmap)
        Log.d("OcrHelper", "过磅单识别开始: ${src.width}x${src.height}")
        try {
            // Pass 1: 原图直接识别
            runMlKit(src)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }

            // Pass 2: 纸张裁剪 + 放大
            val paperScaled = cropAndScalePaper(src)
            try {
                runMlKit(paperScaled)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }

                // Pass 3: 灰度对比度增强
                val enhanced = enhanceGrayscaleContrast(paperScaled)
                try {
                    runMlKit(enhanced)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }

                    // Pass 4: Otsu 二值化
                    val binary = binarizeOtsu(enhanced)
                    try {
                        runMlKit(binary)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }

                        // Pass 5: ±12° 旋转重试（在二值化图上）
                        for (angle in listOf(-12f, 12f)) {
                            val rotated = rotateBitmap(binary, angle)
                            try {
                                runMlKit(rotated)?.let { extractWeighingSlip(it) }?.let { if (it != null) return it }
                            } finally {
                                rotated.recycle()
                            }
                        }
                    } finally {
                        binary.recycle()
                    }
                } finally {
                    enhanced.recycle()
                }
            } finally {
                if (paperScaled !== src) paperScaled.recycle()
            }

            return null
        } finally {
            if (src !== bitmap) src.recycle()
        }
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
        val logicalLines = buildLogicalLines(visionText)

        // 整张单据的主导单位：过磅单基本都是公斤，个别行把"公斤"漏认成"斤"会导致误除以 2。
        // 只要"公斤/kg"出现的行数不少于纯"斤"的行数，就整单按公斤处理。
        val kgLines = logicalLines.count { it.contains("公斤") || it.lowercase().contains("kg") }
        val jinLines = logicalLines.count { it.contains("斤") && !it.contains("公斤") }
        val forceKg = kgLines >= jinLines

        for (norm in logicalLines) {
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
                    numAfter(norm, "毛重")?.let { gross = toKg(norm, it, forceKg) }
                }
                if (tare <= 0.0 && (norm.contains("皮重") || norm.contains("车重"))) {
                    numAfter(norm, "皮重")?.let { tare = toKg(norm, it, forceKg) }
                        ?: numAfter(norm, "车重")?.let { tare = toKg(norm, it, forceKg) }
                }
                if (norm.contains("净重") && net <= 0.0) {
                    numAfter(norm, "净重")?.let { net = toKg(norm, it, forceKg) }
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
        // 皮重与净重交叉校验：单据必然满足 毛重 - 皮重 = 净重。
        // 两者矛盾（常见原因：某行"公斤"漏认成"斤"被误除以 2）时，以净重反推的皮重为准。
        if (tare > 0.0 && net > 0.0 && gross > net) {
            val impliedTare = gross - net
            if (impliedTare > 0 && Math.abs(impliedTare - tare) > tare * 0.05) {
                Log.d("OcrHelper", "皮重与净重矛盾: tare=$tare net=$net → 修正为 $impliedTare")
                tare = impliedTare
            }
        }
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

    /**
     * 按行内单位换算为公斤：公斤/kg 原值；纯"斤"÷2；无单位默认公斤。
     * [forceKg] 为整单主导单位判断结果，避免个别行漏认"公"字被误当成"斤"除以 2。
     */
    private fun toKg(norm: String, value: Double, forceKg: Boolean = false): Double {
        if (forceKg) return value
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

    /** 日期/时间片段（先剔除，避免 "2026-09-03" 被误当成算式 "2026-09"） */

    /** 算式合理性：毛重>皮重；地磅场景毛重通常≥100kg、皮重≥50kg（挡手写截断碎片如 50-8）；皮重≥1 */

    // ================== 内部：灰度对比度增强 / 二值化 ==================

    /**
     * 灰度化 + 对比度拉伸：把暗/亮的灰度值线性拉伸到 0~255 全范围。
     * 对光线不均、阴影、偏暗的照片效果显著，ML Kit 在高对比图上识别率更高。
     */
    private fun enhanceGrayscaleContrast(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1) 转灰度 + 找 min/max（灰度值暂存，第二趟复用 pixels 数组输出，省一份大数组）
        val gray = IntArray(w * h)
        var minG = 255; var maxG = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            gray[i] = g
            if (g < minG) minG = g
            if (g > maxG) maxG = g
        }
        // 2) 线性拉伸，结果直接写回 pixels
        val range = (maxG - minG).coerceAtLeast(1)
        for (i in gray.indices) {
            val stretched = ((gray[i] - minG) * 255 / range).coerceIn(0, 255)
            pixels[i] = Color.rgb(stretched, stretched, stretched)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
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

        // 二值化（结果直接写回 pixels，省一份 w*h 大数组）
        val black = Color.BLACK; val white = Color.WHITE
        for (i in pixels.indices) {
            val g = (Color.red(pixels[i]) * 299 + Color.green(pixels[i]) * 587 + Color.blue(pixels[i]) * 114) / 1000
            pixels[i] = if (g < bestTh) black else white
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
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

        // 单趟扫描：minc>mincThr 且 (maxc-minc)<satThr 的浅色像素视为纸张
        // 返回 [minX,minY,maxX,maxY]，无效返回 null
        fun scanPaper(mincThr: Int, satThr: Int): IntArray? {
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            var count = 0
            for (y in 0 until h step 2) {
                for (x in 0 until w step 2) {
                    val p = pixels[y * w + x]
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    val maxc = maxOf(r, g, b); val minc = minOf(r, g, b)
                    val isPaper = minc > mincThr && (maxc - minc) < satThr
                    if (isPaper) {
                        count++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            val sampled = (w / 2) * (h / 2)
            if (count < sampled * 0.08) return null
            val bw = maxX - minX; val bh = maxY - minY
            if (bw < w * 0.15 || bh < h * 0.15) return null
            return intArrayOf(minX, minY, maxX, maxY)
        }

        // 第一趟：严格阈值（更亮更素）。真实手写本照片实测：普通阈值会把水泥地
        // 也当成纸（包围盒=全图，裁剪失效）；严格阈值能收紧到本子区域。
        // 但严格阈值只在"纸占画面中一小块"时采用（包围盒面积 < 75% 画面）；
        // 纸铺满画面的过磅单场景 bbox≈全图，此时回退普通阈值，保持原行为。
        var box: IntArray? = null
        val strict = scanPaper(160, 80)
        if (strict != null) {
            val sArea = (strict[2] - strict[0]).toLong() * (strict[3] - strict[1])
            if (sArea < 0.75 * w.toLong() * h) box = strict
        }
        // 第二趟：普通阈值（原逻辑）
        if (box == null) box = scanPaper(110, 80)
        if (box == null) return src

        val minX = box[0]; val minY = box[1]; val maxX = box[2]; val maxY = box[3]
        val bw = maxX - minX; val bh = maxY - minY

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
     * 纸张裁剪 + 限制原始最大边 1200px + 放大（目标输出最大边 2400px，倍率 1~3x 自适应）。
     * 限制尺寸防止放大后超过 ML Kit 图像大小限制 / 内存溢出（相机原图 3x 放大可达上百 MB）。
     */
    private fun cropAndScalePaper(src: Bitmap): Bitmap {
        val paper = cropPaperRegion(src)
        val cap = 1200
        val p = if (maxOf(paper.width, paper.height) > cap) {
            val ratio = cap.toFloat() / maxOf(paper.width, paper.height)
            val capped = Bitmap.createScaledBitmap(paper, (paper.width * ratio).toInt(), (paper.height * ratio).toInt(), true)
            if (paper !== src) paper.recycle()  // 释放裁剪中间图
            capped
        } else {
            paper
        }
        val factor = (2400f / maxOf(p.width, p.height)).coerceIn(1f, 3f)
        val out = Bitmap.createScaledBitmap(p, (p.width * factor).toInt(), (p.height * factor).toInt(), true)
        if (p !== src && out !== p) p.recycle()  // p 是裁剪图/限幅图时释放（===src 时不能动）
        return out
    }

    /**
     * OCR 入口统一降采样：相机原图常达 3000~4000px 边长（ARGB 约 48MB），
     * 后续预处理还要放大 2~3 倍，不先降采样极易 OOM 闪退。
     * OCR 识别在最大边 1600px 下精度足够。返回工作图（新图由各识别函数在 finally 中回收，
     * 原图由调用方负责回收——同一张图可能连续传给多个识别函数）。
     */
    private fun downscaleForOcr(src: Bitmap, maxEdge: Int = 1600): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= maxEdge) return src
        val ratio = maxEdge.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
    }

    // ================== 内部：LED 显示屏预处理 ==================

    /**
     * 地磅 LED 屏预处理：
     *  1) 只保留 (绿通道 - 红通道 - 蓝通道) > T 的像素（= 纯绿色发光像素）
     *  2) 自动裁剪到屏幕区域（绿像素的 min/max 包围盒）
     *  3) 放大 3x
     *  4) 反色 + 二值化 → 白底黑字（ML Kit 最喜欢的形态）
     */

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
     * 分割：按列投影找"有内容的连续列段"（密度≥0.5%），段间 <12px 的窄缝视为
     * 数字内部间隙合并——首尾数字位于第一条/最后一条缝隙之外，不会被切掉。
     * 识别：每个数字框内放 7 个矩形探针，统计黑像素密度判段亮灭，Hamming 距离查表。
     */

    /** 矩形探针密度：数字框内 (rx0,ry0)-(rx1,ry1) 相对区域中黑像素占比 */

    /** 对一个数字框用 7 个矩形探针采样，匹配 0-9 段码 */

    // ==================== 增强预处理（v2）====================

    /**
     * 光照归一化：用大窗口局部均值估计背景，前景 = 原图 / 背景。
     * 手机拍纸张常有阴影，全局阈值会把阴影区整片判成黑。
     */

    /**
     * Sauvola 自适应二值化：阈值按局部均值和标准差动态计算。
     * 比全局 Otsu 更适合光照不均、手写笔画粗细不一的场景。
     */

    /**
     * 擦除记录本上的横线：某一行黑色像素占比超过阈值，判定为横线并清空。
     * 手写记录本的格子线会被 OCR 当成笔画，严重干扰识别。
     */

    /**
     * 形态学闭运算（先膨胀后腐蚀）：把断开的笔画连起来。
     * 手写细笔画二值化后容易断，ML Kit 会把它当成两个字符。
     */

    // ==================== 数字切分逐字识别（v2）====================

    /**
     * 按垂直投影把一行内容切分成单字符小块。
     * 返回 (放大后的字符位图, 起始列, 结束列)，调用方负责 recycle。
     */

    /** 把单个字符的识别结果规整成数字 / 减号 */

    /**
     * 数字切分识别：把图像切成单字符，逐个送 ML Kit，再拼成算式解析。
     * 这是整行手写认不出时的最后手段。
     */

}
