package com.farmtrade.app.util

import com.farmtrade.app.data.Record

/**
 * 语音解析工具 - 将自然语言解析为记录字段
 * 支持识别: 毛重、车重、单价、买卖方向、类型、数量
 * 示例: "毛重5000斤，车重4000斤，一斤1块4" → 各字段值
 * 示例: "买入小麦，毛重2500公斤，车重2000公斤，单价2块5"
 * 示例: "卖出玉米20袋，每袋45块"
 */
object VoiceParser {

    data class ParseResult(
        var direction: String? = null,
        var type: String? = null,
        var measureMode: String? = null,
        var grossWeight: Double? = null,
        var vehicleWeight: Double? = null,
        var quantity: Double? = null,
        var unitName: String? = null,
        var unitPrice: Double? = null,
        val parseDetails: MutableList<String> = mutableListOf()
    )

    /**
     * 解析语音文本
     */
    fun parse(text: String): ParseResult {
        val result = ParseResult()
        val lowerText = text.replace("，", ",").replace(" ", "")

        // 1. 识别买卖方向
        when {
            lowerText.contains("买入") || lowerText.contains("进货") || lowerText.contains("采购") -> {
                result.direction = "买入"
                result.parseDetails.add("买卖方向: 买入")
            }
            lowerText.contains("卖出") || lowerText.contains("销售") || lowerText.contains("出货") -> {
                result.direction = "卖出"
                result.parseDetails.add("买卖方向: 卖出")
            }
        }

        // 2. 识别类型
        val typeKeywords = Record.DEFAULT_TYPES + listOf("花生", "大豆油", "菜籽", "棉花", "高粱", "红薯")
        for (keyword in typeKeywords) {
            if (lowerText.contains(keyword)) {
                result.type = keyword
                result.parseDetails.add("类型: $keyword")
                break
            }
        }

        // 3. 识别计量方式 - 检测斤/公斤/数量
        val hasJin = lowerText.contains("斤") && !lowerText.contains("公斤")
        val hasKg = lowerText.contains("公斤") || lowerText.contains("kg")
        val hasQuantity = lowerText.contains("袋") || lowerText.contains("桶") || lowerText.contains("包") ||
                lowerText.contains("箱") || lowerText.contains("瓶") || lowerText.contains("升")

        when {
            hasQuantity -> {
                result.measureMode = Record.MODE_QUANTITY
                result.parseDetails.add("计量方式: 按数量")
            }
            hasJin -> {
                result.measureMode = Record.MODE_WEIGHT_JIN
                result.unitName = "斤"
                result.parseDetails.add("计量方式: 按重量(斤)")
            }
            hasKg -> {
                result.measureMode = Record.MODE_WEIGHT_KG
                result.unitName = "公斤"
                result.parseDetails.add("计量方式: 按重量(公斤)")
            }
        }

        // 4. 识别毛重
        val grossPattern = Regex("""(?:毛重|总重|总重量|重量)[为是]?\s*(\d+\.?\d*)""")
        grossPattern.find(lowerText)?.let {
            result.grossWeight = it.groupValues[1].toDouble()
            result.parseDetails.add("毛重: ${it.groupValues[1]}")
        }

        // 5. 识别车重
        val vehiclePattern = Regex("""(?:车重|皮重|空车|车皮)[为是]?\s*(\d+\.?\d*)""")
        vehiclePattern.find(lowerText)?.let {
            result.vehicleWeight = it.groupValues[1].toDouble()
            result.parseDetails.add("车重: ${it.groupValues[1]}")
        }

        // 6. 识别数量 (按数量模式)
        if (result.measureMode == Record.MODE_QUANTITY) {
            val qtyPattern = Regex("""(\d+\.?\d*)\s*(袋|桶|包|箱|瓶|升)""")
            qtyPattern.find(lowerText)?.let {
                result.quantity = it.groupValues[1].toDouble()
                result.unitName = it.groupValues[2]
                result.parseDetails.add("数量: ${it.groupValues[1]}${it.groupValues[2]}")
            }
        }

        // 7. 识别单价
        // 匹配 "单价2.5" / "一斤1块4" / "每袋45" / "2块5一斤" / "45元一袋"
        val pricePatterns = listOf(
            Regex("""(?:单价|价格)[为是]?\s*(\d+\.?\d*)"""),
            Regex("""(?:一斤|一公斤|一斤的?)?(?:单价)?(\d+)块(\d+)"""),
            Regex("""(\d+\.?\d*)\s*[元块](?:/?[每斤公斤袋桶包箱])?"""),
            Regex("""(?:每|一)(袋|桶|包|箱|瓶|升|公斤|斤)\s*(\d+\.?\d*)\s*[元块]?"""),
            Regex("""(\d+\.?\d*)\s*[元块]/?\s*(?:斤|公斤|袋|桶|包|箱|瓶|升)?""")
        )

        for (pattern in pricePatterns) {
            val match = pattern.find(lowerText)
            if (match != null) {
                val priceStr = if (match.groupValues.size >= 3 && match.groupValues[2].isNotEmpty()) {
                    // "1块4" → 1.4
                    "${match.groupValues[1]}.${match.groupValues[2]}".toDoubleOrNull()
                } else {
                    match.groupValues[1].toDoubleOrNull()
                }
                if (priceStr != null && priceStr > 0) {
                    result.unitPrice = priceStr
                    result.parseDetails.add("单价: ${Record.formatNumber(priceStr)}元")
                    break
                }
            }
        }

        return result
    }

    /**
     * 将解析结果应用到Record
     */
    fun applyToRecord(record: Record, result: ParseResult): Record {
        return record.copy(
            direction = result.direction ?: record.direction,
            type = result.type ?: record.type,
            measureMode = result.measureMode ?: record.measureMode,
            grossWeight = result.grossWeight ?: record.grossWeight,
            vehicleWeight = result.vehicleWeight ?: record.vehicleWeight,
            quantity = result.quantity ?: record.quantity,
            unitName = result.unitName ?: record.unitName,
            unitPrice = result.unitPrice ?: record.unitPrice
        )
    }
}
