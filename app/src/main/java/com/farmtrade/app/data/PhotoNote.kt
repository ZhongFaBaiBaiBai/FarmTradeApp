package com.farmtrade.app.data

/**
 * 图片注释 / 纯文字笔记。
 *
 * @param fileName 关联的图片文件名；为 null 表示这是一条独立的文字笔记
 * @param note 注释内容（例如「麦种：济麦 22」）
 * @param createdAt 创建/更新时间，格式 yyyy-MM-dd HH:mm
 */
data class PhotoNote(
    val id: Long = 0,
    val fileName: String? = null,
    val note: String = "",
    val createdAt: String = ""
)
