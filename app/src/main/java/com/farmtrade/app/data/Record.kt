package com.farmtrade.app.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 买卖记录实体类
 * @param id 记录ID
 * @param dateTime 日期时间 (格式: yyyy-MM-dd HH:mm)
 * @param direction 买卖方向: "买入" 或 "卖出"
 * @param type 类型: 小麦/玉米/化肥/农药/柴油/大豆/水稻/自定义...
 * @param measureMode 计量方式: "WEIGHT_KG"(按重量-公斤) / "WEIGHT_JIN"(按重量-斤) / "QUANTITY"(按数量)
 * @param grossWeight 毛重 (重量模式时使用)
 * @param vehicleWeight 车重/皮重 (重量模式时使用)
 * @param netWeight 净重 (自动计算: 毛重 - 车重)
 * @param quantity 数量 (数量模式时使用)
 * @param unitName 单位名称 (如: 公斤/斤/桶/袋/升)
 * @param unitPrice 单价
 * @param totalAmount 总金额 (自动计算: 净重×单价 或 数量×单价)
 * @param photoPath 拍照图片路径 (可选)
 * @param source 来源: "PHOTO"(拍照) / "VOICE"(语音) / "MANUAL"(手动)
 * @param isCarryOver 车重和单价是否沿用上一条记录
 */
@Parcelize
data class Record(
    var id: Long = 0,
    var dateTime: String = "",
    var direction: String = "买入",
    var type: String = "小麦",
    var measureMode: String = "WEIGHT_KG",
    var grossWeight: Double = 0.0,
    var vehicleWeight: Double = 0.0,
    var netWeight: Double = 0.0,
    var quantity: Double = 0.0,
    var unitName: String = "公斤",
    var unitPrice: Double = 0.0,
    var totalAmount: Double = 0.0,
    var photoPath: String? = null,
    var source: String = "MANUAL",
    var isCarryOver: Boolean = false
) : Parcelable {

    /**
     * 计算净重 = 毛重 - 车重
     */
    fun calculateNetWeight(): Double {
        return if (measureMode == "QUANTITY") {
            quantity
        } else {
            grossWeight - vehicleWeight
        }
    }

    /**
     * 计算总金额
     * - 公斤模式：单价按"元/斤"计算，总额 = 净重(公斤) × 2 × 单价
     * - 斤模式：总额 = 净重(斤) × 单价
     * - 数量模式：总额 = 数量 × 单价
     */
    fun calculateTotalAmount(): Double {
        val net = calculateNetWeight()
        return if (measureMode == MODE_WEIGHT_KG) {
            net * 2 * unitPrice
        } else {
            net * unitPrice
        }
    }

    /**
     * 获取显示用的重量/数量文本
     */
    fun getQuantityDisplayText(): String {
        return if (measureMode == "QUANTITY") {
            "${formatNumber(quantity)} ${unitName}"
        } else {
            val net = calculateNetWeight()
            "毛重${formatNumber(grossWeight)}${unitName} - 车重${formatNumber(vehicleWeight)}${unitName} = 净重${formatNumber(net)}${unitName}"
        }
    }

    /**
     * 获取简短的净重/数量显示
     */
    fun getShortQuantityText(): String {
        return if (measureMode == "QUANTITY") {
            "${formatNumber(quantity)}${unitName}"
        } else {
            "净重${formatNumber(calculateNetWeight())}${unitName}"
        }
    }

    companion object {
        // 预设类型
        val DEFAULT_TYPES = listOf("小麦", "玉米", "化肥", "农药", "柴油", "大豆", "水稻")

        // 计量方式常量
        const val MODE_WEIGHT_KG = "WEIGHT_KG"
        const val MODE_WEIGHT_JIN = "WEIGHT_JIN"
        const val MODE_QUANTITY = "QUANTITY"

        // 来源常量
        const val SOURCE_PHOTO = "PHOTO"
        const val SOURCE_VOICE = "VOICE"
        const val SOURCE_MANUAL = "MANUAL"

        fun formatNumber(value: Double): String {
            return if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                String.format("%.2f", value).trimEnd('0').trimEnd('.')
            }
        }

        fun formatMoney(value: Double): String {
            return String.format("%,.2f", value)
        }
    }
}
