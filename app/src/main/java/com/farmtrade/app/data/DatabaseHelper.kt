package com.farmtrade.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 数据库帮助类 - 本地SQLite存储
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "farm_trade.db"
        private const val DB_VERSION = 2

        private const val TABLE_RECORDS = "records"
        private const val TABLE_CUSTOM_TYPES = "custom_types"
        private const val TABLE_PHOTO_NOTES = "photo_notes"

        // photo_notes 表字段（图片注释 / 纯文字笔记）
        private const val COL_PN_ID = "id"
        private const val COL_PN_FILE = "file_name"
        private const val COL_PN_NOTE = "note"
        private const val COL_PN_CREATED = "created_at"

        // records表字段
        private const val COL_ID = "id"
        private const val COL_DATE_TIME = "date_time"
        private const val COL_DIRECTION = "direction"
        private const val COL_TYPE = "type"
        private const val COL_MEASURE_MODE = "measure_mode"
        private const val COL_GROSS_WEIGHT = "gross_weight"
        private const val COL_VEHICLE_WEIGHT = "vehicle_weight"
        private const val COL_NET_WEIGHT = "net_weight"
        private const val COL_QUANTITY = "quantity"
        private const val COL_UNIT_NAME = "unit_name"
        private const val COL_UNIT_PRICE = "unit_price"
        private const val COL_TOTAL_AMOUNT = "total_amount"
        private const val COL_PHOTO_PATH = "photo_path"
        private const val COL_SOURCE = "source"
        private const val COL_IS_CARRY_OVER = "is_carry_over"

        // custom_types表字段
        private const val COL_TYPE_NAME = "type_name"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 创建记录表
        val createRecords = """
            CREATE TABLE $TABLE_RECORDS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DATE_TIME TEXT NOT NULL,
                $COL_DIRECTION TEXT NOT NULL,
                $COL_TYPE TEXT NOT NULL,
                $COL_MEASURE_MODE TEXT NOT NULL,
                $COL_GROSS_WEIGHT REAL DEFAULT 0,
                $COL_VEHICLE_WEIGHT REAL DEFAULT 0,
                $COL_NET_WEIGHT REAL DEFAULT 0,
                $COL_QUANTITY REAL DEFAULT 0,
                $COL_UNIT_NAME TEXT DEFAULT '',
                $COL_UNIT_PRICE REAL DEFAULT 0,
                $COL_TOTAL_AMOUNT REAL DEFAULT 0,
                $COL_PHOTO_PATH TEXT,
                $COL_SOURCE TEXT DEFAULT 'MANUAL',
                $COL_IS_CARRY_OVER INTEGER DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createRecords)

        // 创建自定义类型表
        db.execSQL("""
            CREATE TABLE $TABLE_CUSTOM_TYPES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TYPE_NAME TEXT UNIQUE NOT NULL
            )
        """.trimIndent())

        // 插入默认类型
        Record.DEFAULT_TYPES.forEach { type ->
            val cv = ContentValues().apply { put(COL_TYPE_NAME, type) }
            db.insert(TABLE_CUSTOM_TYPES, null, cv)
        }

        createPhotoNotesTable(db)
    }

    /** 图片注释 / 纯文字笔记表 */
    private fun createPhotoNotesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_PHOTO_NOTES (
                $COL_PN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PN_FILE TEXT,
                $COL_PN_NOTE TEXT NOT NULL,
                $COL_PN_CREATED TEXT NOT NULL
            )
        """.trimIndent())
    }

    /**
     * 增量升级：只补建新表，**绝不 DROP 已有表**。
     * （旧实现是 DROP + onCreate，升级一次就会把用户全部记录清空）
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createPhotoNotesTable(db)
        }
    }

    // ==================== 记录操作 ====================

    fun insertRecord(record: Record): Long {
        val db = writableDatabase
        val cv = recordToContentValues(record)
        return db.insert(TABLE_RECORDS, null, cv)
    }

    fun updateRecord(record: Record): Int {
        val db = writableDatabase
        return db.update(TABLE_RECORDS, recordToContentValues(record), "$COL_ID = ?", arrayOf(record.id.toString()))
    }

    fun deleteRecord(id: Long): Int {
        return writableDatabase.delete(TABLE_RECORDS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun getAllRecords(): List<Record> {
        return queryRecords("1=1 ORDER BY $COL_DATE_TIME DESC")
    }

    fun getRecordsByDate(date: String): List<Record> {
        return queryRecords("$COL_DATE_TIME LIKE '$date%' ORDER BY $COL_DATE_TIME DESC")
    }

    fun getRecordsByMonth(year: Int, month: Int): List<Record> {
        val monthStr = String.format("%04d-%02d", year, month)
        return queryRecords("$COL_DATE_TIME LIKE '$monthStr%' ORDER BY $COL_DATE_TIME ASC")
    }

    fun getRecordsByQuarter(year: Int, quarter: Int): List<Record> {
        val startMonth = (quarter - 1) * 3 + 1
        val endMonth = startMonth + 2
        val start = String.format("%04d-%02d", year, startMonth)
        val end = String.format("%04d-%02d", year, endMonth)
        return queryRecords("$COL_DATE_TIME >= '$start-01 00:00' AND $COL_DATE_TIME <= '$end-31 23:59' ORDER BY $COL_DATE_TIME ASC")
    }

    fun getRecordsByYear(year: Int): List<Record> {
        return queryRecords("$COL_DATE_TIME LIKE '${year}%' ORDER BY $COL_DATE_TIME ASC")
    }

    fun getTodayLastRecord(today: String): Record? {
        val records = queryRecords("$COL_DATE_TIME LIKE '$today%' ORDER BY $COL_DATE_TIME DESC LIMIT 1")
        return records.firstOrNull()
    }

    private fun queryRecords(queryClause: String): List<Record> {
        val records = mutableListOf<Record>()
        val sql = "SELECT * FROM $TABLE_RECORDS WHERE $queryClause"
        val cursor = readableDatabase.rawQuery(sql, null)
        cursor.use {
            while (it.moveToNext()) {
                records.add(cursorToRecord(it))
            }
        }
        return records
    }

    private fun recordToContentValues(record: Record): ContentValues {
        return ContentValues().apply {
            put(COL_DATE_TIME, record.dateTime)
            put(COL_DIRECTION, record.direction)
            put(COL_TYPE, record.type)
            put(COL_MEASURE_MODE, record.measureMode)
            put(COL_GROSS_WEIGHT, record.grossWeight)
            put(COL_VEHICLE_WEIGHT, record.vehicleWeight)
            put(COL_NET_WEIGHT, record.calculateNetWeight())
            put(COL_QUANTITY, record.quantity)
            put(COL_UNIT_NAME, record.unitName)
            put(COL_UNIT_PRICE, record.unitPrice)
            put(COL_TOTAL_AMOUNT, record.calculateTotalAmount())
            put(COL_PHOTO_PATH, record.photoPath)
            put(COL_SOURCE, record.source)
            put(COL_IS_CARRY_OVER, if (record.isCarryOver) 1 else 0)
        }
    }

    private fun cursorToRecord(cursor: android.database.Cursor): Record {
        return Record(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            dateTime = cursor.getString(cursor.getColumnIndexOrThrow(COL_DATE_TIME)),
            direction = cursor.getString(cursor.getColumnIndexOrThrow(COL_DIRECTION)),
            type = cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)),
            measureMode = cursor.getString(cursor.getColumnIndexOrThrow(COL_MEASURE_MODE)),
            grossWeight = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_GROSS_WEIGHT)),
            vehicleWeight = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_VEHICLE_WEIGHT)),
            netWeight = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_NET_WEIGHT)),
            quantity = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_QUANTITY)),
            unitName = cursor.getString(cursor.getColumnIndexOrThrow(COL_UNIT_NAME)),
            unitPrice = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_UNIT_PRICE)),
            totalAmount = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_TOTAL_AMOUNT)),
            photoPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_PHOTO_PATH)),
            source = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE)),
            isCarryOver = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_CARRY_OVER)) == 1
        )
    }

    // ==================== 自定义类型操作 ====================

    fun getAllTypes(): List<String> {
        val types = mutableListOf<String>()
        val cursor = readableDatabase.rawQuery("SELECT $COL_TYPE_NAME FROM $TABLE_CUSTOM_TYPES ORDER BY $COL_ID", null)
        cursor.use {
            while (it.moveToNext()) {
                types.add(it.getString(0))
            }
        }
        return types
    }

    fun addCustomType(typeName: String): Boolean {
        val cv = ContentValues().apply { put(COL_TYPE_NAME, typeName) }
        val result = writableDatabase.insert(TABLE_CUSTOM_TYPES, null, cv)
        return result > 0
    }

    /**
     * 重命名货物类型：同时把历史记录里的类型名一起改掉，保证数据一致。
     * @return true 表示成功；false 表示重命名失败（通常是新名字已存在）
     */
    fun renameType(oldName: String, newName: String): Boolean {
        val old = oldName.trim()
        val name = newName.trim()
        if (old.isEmpty() || name.isEmpty() || old == name) return false

        val database = writableDatabase
        // 新名字已存在就不让改
        database.query(
            TABLE_CUSTOM_TYPES, arrayOf(COL_TYPE_NAME),
            "$COL_TYPE_NAME = ?", arrayOf(name), null, null, null
        ).use { if (it.moveToFirst()) return false }

        val cv = ContentValues().apply { put(COL_TYPE_NAME, name) }
        val updated = database.update(
            TABLE_CUSTOM_TYPES, cv, "$COL_TYPE_NAME = ?", arrayOf(old)
        )
        if (updated <= 0) return false

        // 历史记录里的类型名同步更新
        val rcv = ContentValues().apply { put(COL_TYPE, name) }
        database.update(TABLE_RECORDS, rcv, "$COL_TYPE = ?", arrayOf(old))
        return true
    }

    /** 该类型下有多少条记录（删除前提示用） */
    fun countRecordsOfType(typeName: String): Int {
        readableDatabase.query(
            TABLE_RECORDS, arrayOf(COL_ID), "$COL_TYPE = ?", arrayOf(typeName), null, null, null
        ).use { return it.count }
    }

    fun deleteCustomType(typeName: String) {
        writableDatabase.delete(TABLE_CUSTOM_TYPES, "$COL_TYPE_NAME = ?", arrayOf(typeName))
    }

    // ==================== 图片注释 / 文字笔记 ====================

    /** 新增或更新某张图片的注释；fileName 为 null 表示一条纯文字笔记 */
    fun savePhotoNote(fileName: String?, note: String, createdAt: String): Long {
        val db = writableDatabase
        val trimmed = note.trim()
        val existing = if (fileName != null) {
            db.query(
                TABLE_PHOTO_NOTES, arrayOf(COL_PN_ID),
                "$COL_PN_FILE = ?", arrayOf(fileName), null, null, null
            ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        } else -1L

        return if (existing > 0) {
            val cv = ContentValues().apply {
                put(COL_PN_NOTE, trimmed)
                put(COL_PN_CREATED, createdAt)
            }
            db.update(TABLE_PHOTO_NOTES, cv, "$COL_PN_ID = ?", arrayOf(existing.toString()))
            existing
        } else {
            val cv = ContentValues().apply {
                put(COL_PN_FILE, fileName)
                put(COL_PN_NOTE, trimmed)
                put(COL_PN_CREATED, createdAt)
            }
            db.insert(TABLE_PHOTO_NOTES, null, cv)
        }
    }

    /** 取某张图片的注释，没有返回 null */
    fun getNoteForFile(fileName: String): String? {
        readableDatabase.query(
            TABLE_PHOTO_NOTES, arrayOf(COL_PN_NOTE),
            "$COL_PN_FILE = ?", arrayOf(fileName), null, null, null
        ).use { cur ->
            return if (cur.moveToFirst()) cur.getString(0) else null
        }
    }

    /** 全部注释与文字笔记，按时间倒序 */
    fun getAllPhotoNotes(): List<PhotoNote> {
        val list = mutableListOf<PhotoNote>()
        readableDatabase.query(
            TABLE_PHOTO_NOTES, null, null, null, null, null, "$COL_PN_CREATED DESC"
        ).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    PhotoNote(
                        id = cur.getLong(cur.getColumnIndexOrThrow(COL_PN_ID)),
                        fileName = cur.getString(cur.getColumnIndexOrThrow(COL_PN_FILE)),
                        note = cur.getString(cur.getColumnIndexOrThrow(COL_PN_NOTE)),
                        createdAt = cur.getString(cur.getColumnIndexOrThrow(COL_PN_CREATED))
                    )
                )
            }
        }
        return list
    }

    fun deletePhotoNote(id: Long): Int =
        writableDatabase.delete(TABLE_PHOTO_NOTES, "$COL_PN_ID = ?", arrayOf(id.toString()))

    fun deleteNoteByFile(fileName: String): Int =
        writableDatabase.delete(TABLE_PHOTO_NOTES, "$COL_PN_FILE = ?", arrayOf(fileName))
}
