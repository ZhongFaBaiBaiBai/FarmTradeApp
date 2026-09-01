package com.farmtrade.app.util

import android.content.Context
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跨手机数据互通：完整数据 <-> JSON 文件。
 *
 * JSON 结构：
 * ```
 * {
 *   "version": 1,
 *   "exported_at": "2026-09-02 10:00",
 *   "app_version": 1,
 *   "custom_types": ["小麦", "玉米", ...],
 *   "records": [
 *     {
 *       "date_time": "2026-09-01 10:30",
 *       "direction": "买入",
 *       "type": "小麦",
 *       "measure_mode": "WEIGHT_KG",
 *       "gross_weight": 2500.0,
 *       "vehicle_weight": 400.0,
 *       "net_weight": 2100.0,
 *       "quantity": 0.0,
 *       "unit_name": "公斤",
 *       "unit_price": 1.4,
 *       "total_amount": 5880.0,
 *       "photo_path": null,
 *       "source": "PHOTO",
 *       "is_carry_over": true
 *     },
 *     ...
 *   ]
 * }
 * ```
 */
object DataExchangeHelper {

    data class ImportResult(
        val totalRecords: Int,
        val insertedRecords: Int,
        val skippedDuplicates: Int,
        val typesAdded: Int,
        val malformedRecords: Int
    ) {
        val summary: String
            get() = "共 $totalRecords 条记录 / 新增 $insertedRecords 条 / 重复跳过 $skippedDuplicates 条 / 格式异常 $malformedRecords 条 / 类型补齐 $typesAdded 个"
    }

    private const val VERSION = 1
    private const val FMT_EXPORTED = "yyyy-MM-dd HH:mm"

    // ---- 字段名（与 Record 及 JSON 保持一致） ----
    private const val K_DATE_TIME = "date_time"
    private const val K_DIRECTION = "direction"
    private const val K_TYPE = "type"
    private const val K_MEASURE_MODE = "measure_mode"
    private const val K_GROSS_WEIGHT = "gross_weight"
    private const val K_VEHICLE_WEIGHT = "vehicle_weight"
    private const val K_NET_WEIGHT = "net_weight"
    private const val K_QUANTITY = "quantity"
    private const val K_UNIT_NAME = "unit_name"
    private const val K_UNIT_PRICE = "unit_price"
    private const val K_TOTAL_AMOUNT = "total_amount"
    private const val K_PHOTO_PATH = "photo_path"
    private const val K_SOURCE = "source"
    private const val K_IS_CARRY_OVER = "is_carry_over"

    /**
     * 生成完整 JSON 字符串，包含所有记录 + 自定义类型 + 元数据。
     * ID 不导出（跨设备 id 不具可比性，导入时会重新生成）。
     */
    fun toJsonBundle(context: Context, db: DatabaseHelper): String {
        val dateFmt = SimpleDateFormat(FMT_EXPORTED, Locale.getDefault())
        val packageVersionCode = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) { 1 }

        val types = JSONArray().also { arr -> db.getAllTypes().forEach { arr.put(it) } }
        val records = JSONArray().also { arr ->
            for (r in db.getAllRecords()) arr.put(recordToJson(r))
        }
        return JSONObject()
            .put("version", VERSION)
            .put("exported_at", dateFmt.format(Date()))
            .put("app_version", packageVersionCode)
            .put("custom_types", types)
            .put("records", records)
            .toString(2)
    }

    /**
     * 解析 JSON 并导入数据库：
     * - 自定义类型：不存在的都补齐（不会覆盖已存在的）
     * - 记录：按"内容指纹"去重，完全相同的跳过；返回各计数
     */
    fun importFromJson(context: Context, db: DatabaseHelper, json: String): ImportResult {
        val root = JSONObject(json)
        val typesArr = root.optJSONArray("custom_types") ?: JSONArray()
        val recordsArr = root.optJSONArray("records") ?: JSONArray()

        // ===== 导入类型 =====
        var typesAdded = 0
        for (i in 0 until typesArr.length()) {
            val name = typesArr.optString(i)?.trim().orEmpty()
            if (name.isNotBlank()) {
                val existing = db.getAllTypes()
                if (name !in existing && db.addCustomType(name)) typesAdded++
            }
        }

        // ===== 构建已存在记录的指纹集合 =====
        val existingFingerprints = db.getAllRecords().mapTo(HashSet()) { recordFingerprint(it) }

        var inserted = 0
        var skipped = 0
        var malformed = 0
        val total = recordsArr.length()

        for (i in 0 until total) {
            val obj = recordsArr.optJSONObject(i)
            if (obj == null) { malformed++; continue }
            val record = try { jsonToRecord(obj) } catch (t: Exception) { malformed++; continue }

            val fp = recordFingerprint(record)
            if (fp in existingFingerprints) { skipped++; continue }

            if (db.insertRecord(record) > 0) {
                inserted++
                existingFingerprints.add(fp)
                // 顺便把记录里出现但 custom_types 漏掉的类型也补齐
                if (record.type.isNotBlank()) {
                    val existing = db.getAllTypes()
                    if (record.type !in existing && db.addCustomType(record.type)) typesAdded++
                }
            } else {
                malformed++
            }
        }
        return ImportResult(total, inserted, skipped, typesAdded, malformed)
    }

    // ---------- 序列化 ----------

    private fun recordToJson(r: Record): JSONObject = JSONObject()
        .put(K_DATE_TIME, r.dateTime)
        .put(K_DIRECTION, r.direction)
        .put(K_TYPE, r.type)
        .put(K_MEASURE_MODE, r.measureMode)
        .put(K_GROSS_WEIGHT, r.grossWeight)
        .put(K_VEHICLE_WEIGHT, r.vehicleWeight)
        .put(K_NET_WEIGHT, r.netWeight)
        .put(K_QUANTITY, r.quantity)
        .put(K_UNIT_NAME, r.unitName)
        .put(K_UNIT_PRICE, r.unitPrice)
        .put(K_TOTAL_AMOUNT, r.totalAmount)
        .put(K_PHOTO_PATH, r.photoPath ?: JSONObject.NULL)
        .put(K_SOURCE, r.source)
        .put(K_IS_CARRY_OVER, if (r.isCarryOver) 1 else 0)

    private fun jsonToRecord(obj: JSONObject): Record = Record(
        dateTime = obj.optString(K_DATE_TIME).ifBlank {
            throw IllegalArgumentException("missing date_time")
        },
        direction = obj.optString(K_DIRECTION).ifBlank { "买入" },
        type = obj.optString(K_TYPE).ifBlank { "自定义" },
        measureMode = obj.optString(K_MEASURE_MODE).ifBlank { Record.MODE_WEIGHT_KG },
        grossWeight = obj.optDouble(K_GROSS_WEIGHT, 0.0),
        vehicleWeight = obj.optDouble(K_VEHICLE_WEIGHT, 0.0),
        netWeight = obj.optDouble(K_NET_WEIGHT, 0.0),
        quantity = obj.optDouble(K_QUANTITY, 0.0),
        unitName = obj.optString(K_UNIT_NAME).ifBlank { "公斤" },
        unitPrice = obj.optDouble(K_UNIT_PRICE, 0.0),
        totalAmount = obj.optDouble(K_TOTAL_AMOUNT, 0.0),
        photoPath = if (obj.isNull(K_PHOTO_PATH)) null else obj.optString(K_PHOTO_PATH),
        source = obj.optString(K_SOURCE).ifBlank { Record.SOURCE_MANUAL },
        isCarryOver = obj.optInt(K_IS_CARRY_OVER, 0) == 1
    )

    /**
     * "内容指纹"：忽略 id / photoPath（跨手机这两个无意义），
     * 比较 时间+方向+类型+计量+毛+车+净+数+单位+单价+金额，完全一致视为同一条。
     */
    private fun recordFingerprint(r: Record): String = buildString {
        append(r.dateTime).append('|')
        append(r.direction).append('|')
        append(r.type).append('|')
        append(r.measureMode).append('|')
        append("%.5f".format(r.grossWeight)).append('|')
        append("%.5f".format(r.vehicleWeight)).append('|')
        append("%.5f".format(r.netWeight)).append('|')
        append("%.5f".format(r.quantity)).append('|')
        append(r.unitName).append('|')
        append("%.5f".format(r.unitPrice)).append('|')
        append("%.5f".format(r.totalAmount))
    }
}
