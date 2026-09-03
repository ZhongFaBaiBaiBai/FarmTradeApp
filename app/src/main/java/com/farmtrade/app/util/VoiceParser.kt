package com.farmtrade.app.util

import com.farmtrade.app.data.Record

/**
 * 语音解析工具 - 将自然语言解析为记录字段
 * 支持识别: 总重、车重、单价、买卖方向、类型、数量
 * 支持中文数字识别: 两千五 → 2500, 一块四 → 1.4, 一块二毛八 → 1.28, 三毛五 → 0.35
 * 示例: "总重五千斤，车重四百斤，一斤一块四" → 各字段值
 * 示例: "买入小麦，总重两千五百公斤，车重两千公斤，单价两块五"
 * 示例: "卖出玉米二十袋，每袋四十五块"
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
        val parseDetails: MutableList<String> = mutableListOf(),
        var rawText: String = "",
        /** 中文数字转换后的文本（含阿拉伯数字），用于界面展示 */
        var convertedText: String = ""
    )

    /**
     * 解析语音文本
     */
    fun parse(text: String): ParseResult {
        val result = ParseResult()
        result.rawText = text

        // 预处理：统一标点、去除空格
        var normalizedText = text
            .replace("，", ",")
            .replace("。", ".")
            .replace(" ", "")
            .replace("　", "")

        // 归一化"阿拉伯元+中文角分"的价格表达，防止"单价1块2毛5"被"单价(\d+)"截断成整数：
        // "1块零5"→1.05、"1块2毛5"→1.25、"1块25"→1.25、"1块2"→1.2（顺序不可调换）
        normalizedText = Regex("""(\d+)块零(\d)""")
            .replace(normalizedText) { "${it.groupValues[1]}.0${it.groupValues[2]}" }
        normalizedText = Regex("""(\d+)块(\d)毛(\d)""")
            .replace(normalizedText) { "${it.groupValues[1]}.${it.groupValues[2]}${it.groupValues[3]}" }
        normalizedText = Regex("""(\d+)块(\d)(\d)(?!\d)""")
            .replace(normalizedText) { "${it.groupValues[1]}.${it.groupValues[2]}${it.groupValues[3]}" }
        normalizedText = Regex("""(\d+)块(\d)(?!\d)""")
            .replace(normalizedText) { "${it.groupValues[1]}.${it.groupValues[2]}" }

        // 先把中文数字转换成阿拉伯数字
        normalizedText = convertChineseNumbers(normalizedText)
        result.convertedText = normalizedText

        val lowerText = normalizedText

        // 1. 识别买卖方向
        when {
            lowerText.contains("买入") || lowerText.contains("进货") || lowerText.contains("采购") ||
                    lowerText.contains("收") && lowerText.contains("买") -> {
                result.direction = "买入"
                result.parseDetails.add("买卖方向: 买入")
            }
            lowerText.contains("卖出") || lowerText.contains("销售") || lowerText.contains("出货") ||
                    lowerText.contains("卖") -> {
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
        val hasKg = lowerText.contains("公斤") || lowerText.contains("kg") || lowerText.contains("千克")
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

        // 4. 识别总重（支持多种说法）
        val grossPatterns = listOf(
            Regex("""(?:总重|总重量|毛重|重量|总质量)[为是等于]?\s*(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*(?:公斤|斤|kg|千克)\s*(?:总重|总重量|毛重|重量)""")
        )
        for (pattern in grossPatterns) {
            val match = pattern.find(lowerText)
            if (match != null) {
                result.grossWeight = match.groupValues[1].toDoubleOrNull()
                if (result.grossWeight != null) {
                    result.parseDetails.add("总重: ${match.groupValues[1]}")
                    break
                }
            }
        }

        // 5. 识别车重
        val vehiclePatterns = listOf(
            Regex("""(?:车重|皮重|空车|车皮|自重)[为是等于]?\s*(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*(?:公斤|斤|kg|千克)\s*(?:车重|皮重|空车)""")
        )
        for (pattern in vehiclePatterns) {
            val match = pattern.find(lowerText)
            if (match != null) {
                result.vehicleWeight = match.groupValues[1].toDoubleOrNull()
                if (result.vehicleWeight != null) {
                    result.parseDetails.add("车重: ${match.groupValues[1]}")
                    break
                }
            }
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
        // 匹配 "单价2.5" / "一斤1块4" / "每袋45" / "2块5一斤" / "45元一袋" / "一块四一斤"
        // 中文数字转换后："一块二毛八一斤" → "1.28一斤"，"一斤一块二毛八" → "1斤1.28"
        val pricePatterns = listOf(
            Regex("""(?:单价|价格)[为是等于]?\s*(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*块\s*(\d+)\s*(?:一|每)?(?:斤|公斤|袋|桶|包|箱)?"""),
            Regex("""(?:一|每)(?:斤|公斤|袋|桶|包|箱|瓶|升)\s*(\d+\.?\d*)\s*[元块]?"""),
            Regex("""(\d+\.?\d*)\s*一(?:斤|公斤|袋|桶|包|箱|瓶|升)"""),
            Regex("""(?:斤|公斤|袋|桶|包|箱|瓶|升)\s*(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*[元块]/?\s*(?:斤|公斤|袋|桶|包|箱|瓶|升)"""),
            Regex("""(\d+\.?\d*)\s*[元块](?:一|每)?(?:斤|公斤|袋|桶|包|箱|瓶|升)?""")
        )

        for (pattern in pricePatterns) {
            val match = pattern.find(lowerText)
            if (match != null) {
                val priceStr = if (match.groupValues.size >= 3 && match.groupValues[2].isNotEmpty()) {
                    // "1块4" → 1.4
                    val yuan = match.groupValues[1].toDoubleOrNull() ?: 0.0
                    val jiao = match.groupValues[2].toDoubleOrNull() ?: 0.0
                    yuan + jiao / 10
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

        // 8. 如果没有识别到单位但有重量数值，尝试推断单位
        if (result.measureMode == null && result.grossWeight != null) {
            // 默认按斤
            result.measureMode = Record.MODE_WEIGHT_JIN
            result.unitName = "斤"
        }

        return result
    }

    /**
     * 提取文本中的第一个阿拉伯数字。
     * 如 "总重2500斤" → 2500.0，"单价1.4" → 1.4
     */
    fun firstNumber(text: String): Double? =
        Regex("""\d+(?:\.\d+)?""").find(text)?.value?.toDoubleOrNull()

    /**
     * 针对"行内语音修改"的解析。
     * 用户点某个字段后通常只说数值本身（如"两千五百"或"二五零零"），不带"总重/单价"关键词，
     * 普通 [parse] 会因缺关键词而填不上。本方法在关键词解析失败时，
     * 把文本中的第一个数字直接填入目标字段。
     * @param target 目标字段名（GROSS/TARE/PRICE/TYPE/DATETIME），null 表示完整句子解析
     */
    fun parseForField(text: String, target: String?): ParseResult {
        val result = parse(text)
        if (target == null || target == "DATETIME") return result

        val filled = when (target) {
            "GROSS" -> result.grossWeight != null
            "TARE" -> result.vehicleWeight != null
            "PRICE" -> result.unitPrice != null
            "TYPE" -> result.type != null
            else -> true
        }
        if (filled) return result

        val num = firstNumber(result.convertedText)
        if (num != null && num > 0) {
            when (target) {
                "GROSS" -> { result.grossWeight = num; result.parseDetails.add("总重(直接取值): $num") }
                "TARE" -> { result.vehicleWeight = num; result.parseDetails.add("车重(直接取值): $num") }
                "PRICE" -> { result.unitPrice = num; result.parseDetails.add("单价(直接取值): $num") }
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

    // ===== 中文数字转阿拉伯数字 =====

    private val chineseDigitMap = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '俩' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5,
        '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9
    )

    private val unitMap = mapOf(
        '十' to 10L, '拾' to 10L,
        '百' to 100L, '佰' to 100L,
        '千' to 1000L, '仟' to 1000L,
        '万' to 10000L, '亿' to 100000000L
    )

    /**
     * 将文本中的中文数字替换为阿拉伯数字。
     * 支持: 两千五 → 2500, 三百 → 300, 一万二 → 12000, 二十 → 20
     * 支持: 一块四 → 1.4 (价格表达), 三点一四 → 3.14 (小数)
     */
    private fun convertChineseNumbers(text: String): String {
        val result = StringBuilder()
        var i = 0
        val len = text.length

        while (i < len) {
            val c = text[i]
            // 相邻独立数字拼接防护：上一段转换结果以数字结尾时，
            // 紧跟的"一+单位量词"（如"零点八一斤"、"一百二十块五一斤"）保留汉字不转换，
            // 避免拼成 0.81 / 120.51
            if (result.isNotEmpty() && result.last().isDigit() && isMeasureWordOne(text, i)) {
                result.append('一')
                i++
                continue
            }
            // 检查当前字符是否是中文数字或单位的开始
            if (isChineseNumberStart(c, text, i)) {
                // 找到中文数字的起止位置
                val numRange = findChineseNumberRange(text, i)
                if (numRange != null) {
                    val numStr = text.substring(numRange.first, numRange.second)
                    val number = parseChineseNumber(numStr)
                    if (number != null) {
                        // 处理小数情况
                        if (number == number.toLong().toDouble()) {
                            result.append(number.toLong())
                        } else {
                            result.append(String.format("%.2f", number).trimEnd('0').trimEnd('.'))
                        }
                        i = numRange.second
                        continue
                    }
                }
            }
            result.append(c)
            i++
        }
        return result.toString()
    }

    /**
     * 判断当前位置是否是中文数字的开头
     */
    private fun isChineseNumberStart(c: Char, text: String, index: Int): Boolean {
        if (chineseDigitMap.containsKey(c)) return true
        if (unitMap.containsKey(c)) {
            // "十"、"百"等单位开头的数字（如"十五"=15）
            return true
        }
        if (c == '第' && index + 1 < text.length && chineseDigitMap.containsKey(text[index + 1])) {
            return true
        }
        return false
    }

    /**
     * text[i] 是否是"一"且后一位是计量单位词（如"一斤/一公斤/一袋"）。
     * 此时"一"是量词而非数字：如"零点八一斤"="0.8一斤"、"五毛一斤"的分位不吃"一"。
     */
    private fun isMeasureWordOne(text: String, i: Int): Boolean {
        return text[i] == '一' && i + 1 < text.length && text[i + 1] in "斤公袋桶包箱瓶升"
    }

    /**
     * 找到中文数字的范围 [start, end)
     */
    private fun findChineseNumberRange(text: String, start: Int): Pair<Int, Int>? {
        var i = start
        val len = text.length
        var hasDigit = false
        var hasUnit = false

        while (i < len) {
            val c = text[i]
            if (chineseDigitMap.containsKey(c)) {
                hasDigit = true
                i++
            } else if (unitMap.containsKey(c)) {
                hasUnit = true
                i++
            } else if (c == '点' || c == '.') {
                // 小数点，后面继续找数字
                i++
                // 小数部分必须都是数字
                val decStart = i
                while (i < len && chineseDigitMap.containsKey(text[i])) {
                    i++
                }
                // "零点八一斤"：两位小数且末位是"一"、紧跟单位词时，
                // 末位"一"通常是"一斤/一公斤"的"一"，回退不吃
                if (i - decStart == 2 && text[i - 1] == '一' && i < len && text[i] in "斤公袋桶包箱瓶升") {
                    i--
                }
                break
            } else if (c == '块' && hasDigit) {
                // "一块四" / "一块二毛八" 这种价格表达，"块"作为特殊单位
                // 检查后面是否有数字（角）
                if (i + 1 < len && chineseDigitMap.containsKey(text[i + 1])) {
                    val jiaoDigit = text[i + 1]
                    i += 2
                    if (jiaoDigit == '零') {
                        // "一块零五"：零占角位，后续数字是分位（不吃紧跟单位的"一"）
                        while (i < len && chineseDigitMap.containsKey(text[i]) &&
                            !isMeasureWordOne(text, i)
                        ) {
                            i++
                        }
                    } else if (i < len && (text[i] == '毛' || text[i] == '角')) {
                        // 角位后跟"毛/角+数字"作分位（一块二毛八 → 1.28），
                        // 或孤立的"毛/角"（一块五毛 → 1.5）；分位"一"若紧跟单位词是量词，不吃
                        i++
                        if (i < len && chineseDigitMap.containsKey(text[i]) && !isMeasureWordOne(text, i)) {
                            i++
                            if (i < len && text[i] == '分') i++
                        }
                    }
                    // 其余情况不再贪吃后续数字，防止"一百二十块五一斤"把"一斤"的"一"吃进角位
                }
                break
            } else if ((c == '毛' || c == '角') && hasDigit) {
                // "三毛五" / "五毛" / "八角" 价格表达：把"毛/角"一并纳入数字串
                i++
                // 分位"一"若紧跟单位词（"三毛五一斤"）是量词，不吃
                if (i < len && chineseDigitMap.containsKey(text[i]) && !isMeasureWordOne(text, i)) {
                    i++
                    if (i < len && text[i] == '分') i++
                }
                break
            } else {
                break
            }
        }

        // 至少要有一个数字，或者是"十"开头的
        if (!hasDigit && !hasUnit) return null
        if (i <= start) return null

        return Pair(start, i)
    }

    /**
     * 解析中文数字字符串为 Double。
     * 支持整数、小数、价格表达（一块四 → 1.4、一块二毛八 → 1.28、三毛五 → 0.35、八角 → 0.8）
     */
    private fun parseChineseNumber(str: String): Double? {
        if (str.isEmpty()) return null

        // 处理价格表达 "一块四"、"两块五"、"一块二毛八"
        val kuaiIndex = str.indexOf('块')
        if (kuaiIndex > 0) {
            val yuanPart = parseIntegerChinese(str.substring(0, kuaiIndex))
            if (yuanPart != null) {
                val rest = if (kuaiIndex + 1 < str.length) str.substring(kuaiIndex + 1) else ""
                return yuanPart.toDouble() + parseJiaoFen(rest)
            }
        }

        // 处理 "三毛五" → 0.35, "五毛" → 0.5, "八角" → 0.8
        val maoIndex = str.indexOfFirst { it == '毛' || it == '角' }
        if (maoIndex > 0) {
            val jiaoPart = parseIntegerChinese(str.substring(0, maoIndex))
            if (jiaoPart != null) {
                val fenStr = str.substring(maoIndex + 1).removeSuffix("分")
                return jiaoPart / 10.0 + (parseIntegerChinese(fenStr) ?: 0L) / 100.0
            }
        }

        // 处理 "点" 小数，如 "三点一四" → 3.14
        val dotIndex = str.indexOf('点')
        if (dotIndex >= 0) {
            val intPart = parseIntegerChinese(str.substring(0, dotIndex))
            val decPart = parseDecimalChinese(str.substring(dotIndex + 1))
            if (intPart != null && decPart != null) {
                return intPart + decPart
            }
            if (intPart != null) {
                return intPart.toDouble()
            }
            if (decPart != null) {
                return decPart
            }
            return null
        }

        return parseIntegerChinese(str)?.toDouble()
    }

    /**
     * 解析整数部分的中文数字。
     * 支持: 两千五 → 2500, 一万二 → 12000, 三百 → 300, 二十 → 20
     * 支持: 十 → 10, 十五 → 15
     */
    private fun parseIntegerChinese(str: String): Long? {
        if (str.isEmpty()) return null

        // 纯数字串（不含十百千万单位）：按位拼接。
        // Vosk 常把数字逐位识别成汉字（如 "总重二五零零斤"），
        // 必须支持: "二五零零" → 2500、"三五" → 35、"三" → 3
        if (str.all { chineseDigitMap.containsKey(it) }) {
            var value = 0L
            for (c in str) {
                value = value * 10 + (chineseDigitMap[c] ?: 0).toLong()
            }
            return value.takeIf { it > 0 }
        }

        // 处理含单位（十百千万）的中文数字
        var total = 0L
        var wanSection = 0L  // 万以下的部分
        var qianSection = 0L // 千以下的部分
        var current = 0L     // 当前数字
        var lastUnit = 1L    // 上一个单位（用于末尾省略的位权推断）
        var hasAnyUnit = false
        var hasZero = false  // 是否出现过"零"（影响末尾位权推断）

        for (c in str) {
            val digit = chineseDigitMap[c]
            val unit = unitMap[c]

            if (digit != null) {
                if (digit == 0) hasZero = true
                current = digit.toLong()
            } else if (unit != null) {
                hasAnyUnit = true
                when {
                    unit >= 100000000L -> { // 亿
                        total += (wanSection + qianSection + current) * unit
                        wanSection = 0
                        qianSection = 0
                        current = 0
                        lastUnit = unit
                    }
                    unit >= 10000L -> { // 万
                        // "一万" → 10000, "十万" → 100000, "两万五" → 25000（万 + 末尾推断）
                        wanSection += (qianSection + current) * unit
                        qianSection = 0
                        current = 0
                        lastUnit = unit
                    }
                    else -> { // 十、百、千
                        if (current == 0L && unit == 10L && qianSection == 0L && wanSection == 0L && total == 0L) {
                            // "十" 开头 = 一十 = 10
                            qianSection += unit
                        } else {
                            qianSection += current * unit
                        }
                        current = 0
                        lastUnit = unit
                    }
                }
            }
        }

        // 处理末尾省略的数字（如 "两千五" = 2500, "一万二" = 12000）
        // 若中间出现过"零"，末尾数字是个位（如 "两千零五" = 2005, "一百零一" = 101）
        if (current > 0 && hasAnyUnit) {
            val inferredUnit = when (lastUnit) {
                1000L -> if (hasZero) 1L else 100L   // 千后面的尾数 → 百（有零则个位）
                100L -> if (hasZero) 1L else 10L     // 百后面的尾数 → 十（有零则个位）
                10L -> 1L                            // 十后面的尾数 → 个
                10000L -> if (hasZero) 1L else 1000L // 万后面的尾数 → 千（有零则个位）
                100000000L -> if (hasZero) 1L else 10000000L
                else -> 1L
            }
            qianSection += current * inferredUnit
        }

        total += wanSection + qianSection

        return total.takeIf { it > 0 }
    }

    /**
     * 解析"块"后面的角分部分。
     * "二毛八" → 0.28, "二毛" → 0.2, "四" → 0.4（老式"一块四"）, "" → 0.0
     */
    private fun parseJiaoFen(rest: String): Double {
        if (rest.isEmpty()) return 0.0
        val maoIndex = rest.indexOfFirst { it == '毛' || it == '角' }
        if (maoIndex >= 0) {
            val jiao = (parseIntegerChinese(rest.substring(0, maoIndex)) ?: 0L) / 10.0
            val fenStr = rest.substring(maoIndex + 1).removeSuffix("分")
            return jiao + (parseIntegerChinese(fenStr) ?: 0L) / 100.0
        }
        // "一块四" 的 "四" 直接是角位；"一块零五" 的 "零五" 是分位（1.05）
        val v = parseIntegerChinese(rest) ?: 0L
        return if (rest.startsWith("零")) v / 100.0 else v / 10.0
    }

    /**
     * 解析小数部分的中文数字（简单逐位转换）。
     * 如 "一四" → 0.14
     */
    private fun parseDecimalChinese(str: String): Double? {
        if (str.isEmpty()) return null
        var result = 0.0
        var divisor = 10.0
        for (c in str) {
            val digit = chineseDigitMap[c] ?: return result
            result += digit / divisor
            divisor *= 10
        }
        return result
    }
}
