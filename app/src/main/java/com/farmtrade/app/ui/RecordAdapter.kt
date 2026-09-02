package com.farmtrade.app.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.farmtrade.app.R
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.ItemGroupHeaderBinding
import com.farmtrade.app.databinding.ItemRecordBinding

/**
 * 买卖记录列表适配器。
 *
 * 支持两种显示模式：
 * 1. 默认平铺模式 — 直接显示 [Record] 列表，按时间排序
 * 2. 分组折叠模式 — 先按类型分组，再按单价分组，组内按时间倒序，可折叠
 *
 * 多选模式仅在平铺模式下可用。
 */
class RecordAdapter(
    private val listener: OnRecordClickListener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // ==================== 数据模型 ====================

    sealed class ListItem {
        data class TypeHeader(val type: String, val count: Int, val subtotal: Double) : ListItem()
        data class PriceHeader(
            val type: String,
            val unitPrice: Double,
            val priceUnit: String,
            val count: Int,
            val subtotal: Double
        ) : ListItem()
        data class RecordItem(val record: Record) : ListItem()
    }

    // ==================== 状态 ====================

    /** 平铺模式的原始记录 */
    private var flatRecords: MutableList<Record> = mutableListOf()

    /** 分组模式的平铺化列表（头+子项混合） */
    private var flatList: MutableList<ListItem> = mutableListOf()

    /** 是否启用分组折叠模式 */
    private var groupedMode = false

    /** 折叠状态：key = "TYPE:类型名" 或 "PRICE:类型名|单价" */
    private val collapsedKeys = mutableSetOf<String>()

    /** 多选模式（仅平铺模式） */
    private var multiSelectMode = false
    private val selectedIds = mutableSetOf<Long>()

    // ==================== ViewType 常量 ====================

    companion object {
        private const val TYPE_HEADER = 0
        private const val PRICE_HEADER = 1
        private const val RECORD_ITEM = 2
    }

    // ==================== 回调接口 ====================

    interface OnRecordClickListener {
        fun onItemClick(record: Record, position: Int)
        fun onItemLongClick(record: Record, position: Int)
        fun onSelectionChanged(selectedCount: Int)
    }

    // ==================== ViewHolder ====================

    class RecordViewHolder(val binding: ItemRecordBinding) :
        RecyclerView.ViewHolder(binding.root)

    class GroupHeaderViewHolder(val binding: ItemGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    // ==================== 基础方法 ====================

    override fun getItemViewType(position: Int): Int {
        val item = flatList[position]
        return when (item) {
            is ListItem.TypeHeader -> TYPE_HEADER
            is ListItem.PriceHeader -> PRICE_HEADER
            is ListItem.RecordItem -> RECORD_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER, PRICE_HEADER -> GroupHeaderViewHolder(
                ItemGroupHeaderBinding.inflate(inflater, parent, false)
            )
            else -> RecordViewHolder(
                ItemRecordBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = flatList[position]
        when (holder) {
            is GroupHeaderViewHolder -> bindGroupHeader(holder, item)
            is RecordViewHolder -> bindRecordItem(holder, item as ListItem.RecordItem)
        }
    }

    override fun getItemCount(): Int = flatList.size

    // ==================== 绑定逻辑 ====================

    private fun bindGroupHeader(holder: GroupHeaderViewHolder, item: ListItem) {
        val b = holder.binding
        when (item) {
            is ListItem.TypeHeader -> {
                b.tvGroupName.text = item.type
                b.tvGroupName.textSize = 17f
                b.tvGroupName.setTextColor(holder.itemView.context.getColor(R.color.green_primary))
                b.tvCount.text = "${item.count}条"
                b.tvSubtotal.text = "￥${Record.formatMoney(item.subtotal)}"
                b.root.setBackgroundColor(0xFFFFFFFF.toInt())
                // 折叠箭头方向 + 缩进
                b.ivArrow.text = if (isCollapsed("TYPE:${item.type}")) "▶" else "▼"
                b.root.setPadding(14, 14, 14, 14)
                b.root.setOnClickListener {
                    toggleCollapse("TYPE:${item.type}")
                }
            }
            is ListItem.PriceHeader -> {
                b.tvGroupName.text = "${Record.formatNumber(item.unitPrice)}元/${item.priceUnit}"
                b.tvGroupName.textSize = 15f
                b.tvGroupName.setTextColor(0xFF558B2F.toInt())
                b.tvCount.text = "${item.count}条"
                b.tvSubtotal.text = "￥${Record.formatMoney(item.subtotal)}"
                b.root.setBackgroundColor(0xFFF1F8E9.toInt())
                b.ivArrow.text = if (isCollapsed("PRICE:${item.type}|${item.unitPrice}")) "▶" else "▼"
                // 缩进显示
                b.root.setPadding(36, 10, 14, 10)
                b.root.setOnClickListener {
                    toggleCollapse("PRICE:${item.type}|${item.unitPrice}")
                }
            }
            is ListItem.RecordItem -> Unit // 不会到这里
        }
    }

    private fun bindRecordItem(holder: RecordViewHolder, item: ListItem.RecordItem) {
        val record = item.record
        bindRecord(holder.binding, record)

        // 分组模式：隐藏 CheckBox，左侧缩进
        if (groupedMode) {
            holder.binding.cbSelect.visibility = View.GONE
            holder.itemView.setPadding(48, 0, 0, 0)
            holder.itemView.setOnClickListener {
                listener.onItemClick(record, holder.bindingAdapterPosition)
            }
            holder.itemView.setOnLongClickListener {
                listener.onItemLongClick(record, holder.bindingAdapterPosition)
                true
            }
        } else if (multiSelectMode) {
            holder.itemView.setPadding(0, 0, 0, 0)
            holder.binding.cbSelect.visibility = View.VISIBLE
            holder.binding.cbSelect.isChecked = selectedIds.contains(record.id)
            holder.binding.cbSelect.setOnClickListener { toggleSelection(record.id) }
            holder.itemView.setOnClickListener { toggleSelection(record.id) }
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.alpha = if (selectedIds.contains(record.id)) 0.6f else 1.0f
        } else {
            holder.itemView.setPadding(0, 0, 0, 0)
            holder.binding.cbSelect.visibility = View.GONE
            holder.itemView.alpha = 1.0f
            holder.itemView.setOnClickListener {
                listener.onItemClick(record, holder.bindingAdapterPosition)
            }
            holder.itemView.setOnLongClickListener {
                listener.onItemLongClick(record, holder.bindingAdapterPosition)
                true
            }
        }
    }

    // ==================== 更新数据 ====================

    /**
     * 平铺模式：直接设置记录列表。
     */
    fun updateFlat(newRecords: List<Record>) {
        groupedMode = false
        flatRecords.clear()
        flatRecords.addAll(newRecords)
        flatList.clear()
        flatRecords.forEach { flatList.add(ListItem.RecordItem(it)) }
        notifyDataSetChanged()
    }

    /**
     * 分组模式：按 type → unitPrice 两级分组，组内按时间倒序。
     * @param priceDesc true=单价降序，false=单价升序
     */
    fun updateGrouped(newRecords: List<Record>, priceDesc: Boolean = true) {
        groupedMode = true
        collapsedKeys.clear()
        flatRecords.clear()
        flatRecords.addAll(newRecords)
        rebuildFlatList(priceDesc)
        notifyDataSetChanged()
    }

    /** 平铺和分组之间切换时重建 flatList */
    private fun rebuildFlatList(priceDesc: Boolean) {
        flatList.clear()
        // 按 type 分组
        val typeGroups = flatRecords.groupBy { it.type }
            .toList()
            .sortedBy { it.first } // 类型按名称排序

        for ((type, typeRecords) in typeGroups) {
            val typeSubtotal = typeRecords.sumOf { it.totalAmount }
            val typeCount = typeRecords.size
            flatList.add(ListItem.TypeHeader(type, typeCount, typeSubtotal))

            // 按 unitPrice 分组（保留两位小数作为 key）
            val priceGroups = typeRecords.groupBy { "%.2f".format(it.unitPrice) }
                .toList()
                .let { pairs ->
                    if (priceDesc) pairs.sortedByDescending { it.second.first().unitPrice }
                    else pairs.sortedBy { it.second.first().unitPrice }
                }

            for ((priceKey, priceRecords) in priceGroups) {
                val price = priceRecords.first().unitPrice
                val priceSubtotal = priceRecords.sumOf { it.totalAmount }
                val priceUnit = priceRecords.first().let { r ->
                    when (r.measureMode) {
                        Record.MODE_WEIGHT_KG, Record.MODE_WEIGHT_JIN -> "斤"
                        else -> r.unitName
                    }
                }
                flatList.add(ListItem.PriceHeader(type, price, priceUnit, priceRecords.size, priceSubtotal))

                // 只有当该类型未折叠时才显示单价头和记录
                if (!isCollapsed("TYPE:$type")) {
                    if (!isCollapsed("PRICE:$type|$price")) {
                        // 组内按时间倒序
                        priceRecords.sortedByDescending { it.dateTime }
                            .forEach { flatList.add(ListItem.RecordItem(it)) }
                    }
                }
            }
        }
    }

    // ==================== 折叠控制 ====================

    private fun isCollapsed(key: String): Boolean = collapsedKeys.contains(key)

    private fun toggleCollapse(key: String) {
        if (collapsedKeys.contains(key)) collapsedKeys.remove(key) else collapsedKeys.add(key)
        // 找到排序方向
        rebuildFlatList(isPriceDesc())
        notifyDataSetChanged()
    }

    /** 从当前 flatList 推断单价排序方向（简化：默认降序） */
    private fun isPriceDesc(): Boolean = true // 分组时重建会按当前方向，简化处理

    // ==================== 多选模式（仅平铺） ====================

    fun enterMultiSelect() {
        if (groupedMode) return
        multiSelectMode = true
        selectedIds.clear()
        notifyDataSetChanged()
        listener.onSelectionChanged(0)
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        listener.onSelectionChanged(0)
    }

    fun isSelected(): Boolean = multiSelectMode && !groupedMode

    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        notifyDataSetChanged()
        listener.onSelectionChanged(selectedIds.size)
    }

    fun selectAll() {
        selectedIds.clear()
        flatRecords.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
        listener.onSelectionChanged(selectedIds.size)
    }

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    // ==================== 绑定逻辑 ====================

    private fun bindRecord(b: ItemRecordBinding, record: Record) {
        val context = b.root.context
        val isBuy = record.direction == "买入"
        val green = ContextCompat.getColor(context, R.color.green_primary)
        val orange = ContextCompat.getColor(context, R.color.orange_primary)

        b.tvTag.text = record.direction
        b.tvTag.setBackgroundColor(if (isBuy) green else orange)
        b.tvDateTime.text = record.dateTime
        b.tvTypeName.text = record.type

        when (record.source) {
            Record.SOURCE_PHOTO -> {
                b.tvSourceBadge.text = "📷拍照"
                b.tvSourceBadge.visibility = View.VISIBLE
            }
            Record.SOURCE_VOICE -> {
                b.tvSourceBadge.text = "🎤语音"
                b.tvSourceBadge.visibility = View.VISIBLE
            }
            else -> b.tvSourceBadge.visibility = View.GONE
        }

        if (record.measureMode == Record.MODE_QUANTITY) {
            b.layoutWeight.visibility = View.GONE
            b.layoutQuantity.visibility = View.VISIBLE
            b.tvQuantity.text = Record.formatNumber(record.quantity)
            b.tvUnitName.text = " ${record.unitName}"
        } else {
            b.layoutWeight.visibility = View.VISIBLE
            b.layoutQuantity.visibility = View.GONE
            b.tvGrossWeight.text = "总重:${Record.formatNumber(record.grossWeight)}"
            b.tvTareWeight.text = withCarryOver(
                "车重:${Record.formatNumber(record.vehicleWeight)}",
                record.isCarryOver, orange
            )
            b.tvNetWeight.text = "净重:${Record.formatNumber(record.netWeight)} ${record.unitName}"
        }

        val priceUnit = when (record.measureMode) {
            Record.MODE_WEIGHT_KG, Record.MODE_WEIGHT_JIN -> "斤"
            else -> record.unitName
        }
        b.tvUnitPrice.text = withCarryOver(
            "单价:${Record.formatNumber(record.unitPrice)}元/$priceUnit",
            record.isCarryOver, orange
        )
        b.tvTotalAmount.text = "￥${Record.formatMoney(record.totalAmount)}"
        b.tvTotalAmount.setTextColor(if (isBuy) green else orange)
    }

    private fun withCarryOver(base: String, carryOver: Boolean, color: Int): CharSequence {
        if (!carryOver) return base
        val ssb = SpannableStringBuilder(base)
        val start = ssb.length
        ssb.append(" (沿用)")
        ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ssb
    }
}
