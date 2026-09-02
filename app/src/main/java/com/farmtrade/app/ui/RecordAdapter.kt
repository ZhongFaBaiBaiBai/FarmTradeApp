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
 * 支持三种显示模式：
 * 1. 默认平铺模式 — 直接显示 [Record] 列表，按时间排序
 * 2. 类型→单价 分组模式 — 类型不可折叠，单价头可折叠
 * 3. 日期→类型→单价 三级分组 — 日期/类型不可折叠，单价头可折叠
 */
class RecordAdapter(
    private val listener: OnRecordClickListener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // ==================== 数据模型 ====================

    sealed class ListItem {
        /** 日期分组头（最外层，不可折叠） */
        data class DateHeader(val date: String, val count: Int, val subtotal: Double) : ListItem()
        /** 类型分组头（不可折叠，永远展开） */
        data class TypeHeader(val type: String, val count: Int, val subtotal: Double) : ListItem()
        /** 单价分组头（可折叠） */
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

    private var flatRecords: MutableList<Record> = mutableListOf()
    private var flatList: MutableList<ListItem> = mutableListOf()
    private var groupedMode = false

    /** 折叠状态：仅 PriceHeader 可折叠，key = "PRICE:类型|单价" */
    private val collapsedKeys = mutableSetOf<String>()

    /** 当前单价排序方向 */
    private var currentPriceDesc = true

    private var multiSelectMode = false
    private val selectedIds = mutableSetOf<Long>()

    companion object {
        private const val DATE_HEADER = 0
        private const val TYPE_HEADER = 1
        private const val PRICE_HEADER = 2
        private const val RECORD_ITEM = 3
    }

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
        return when (flatList[position]) {
            is ListItem.DateHeader -> DATE_HEADER
            is ListItem.TypeHeader -> TYPE_HEADER
            is ListItem.PriceHeader -> PRICE_HEADER
            is ListItem.RecordItem -> RECORD_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == RECORD_ITEM) {
            RecordViewHolder(ItemRecordBinding.inflate(inflater, parent, false))
        } else {
            GroupHeaderViewHolder(ItemGroupHeaderBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = flatList[position]
        when (holder) {
            is GroupHeaderViewHolder -> bindHeader(holder, item)
            is RecordViewHolder -> bindRecordItem(holder, item as ListItem.RecordItem)
        }
    }

    override fun getItemCount(): Int = flatList.size

    // ==================== 绑定逻辑 ====================

    private fun bindHeader(holder: GroupHeaderViewHolder, item: ListItem) {
        val b = holder.binding
        when (item) {
            is ListItem.DateHeader -> {
                // ===== 日期头：最外层，最大最醒目 =====
                b.tvGroupName.text = item.date
                b.tvGroupName.textSize = 22f
                b.tvGroupName.setTextColor(0xFF1B5E20.toInt()) // 深绿
                b.tvCount.text = "${item.count}条"
                b.tvCount.textSize = 15f
                b.tvSubtotal.text = "￥${Record.formatMoney(item.subtotal)}"
                b.tvSubtotal.textSize = 20f
                b.tvSubtotal.setTextColor(0xFF1B5E20.toInt())
                b.root.setBackgroundColor(0xFFFFFFFF.toInt())
                b.ivArrow.visibility = View.GONE
                b.root.setPadding(16, 20, 16, 14)
                b.root.setOnClickListener(null)
            }
            is ListItem.TypeHeader -> {
                // ===== 类型头：次级醒目，浅绿背景区分 =====
                b.tvGroupName.text = item.type
                b.tvGroupName.textSize = 20f
                b.tvGroupName.setTextColor(0xFF2E7D32.toInt())
                b.tvCount.text = "${item.count}条"
                b.tvCount.textSize = 14f
                b.tvSubtotal.text = "￥${Record.formatMoney(item.subtotal)}"
                b.tvSubtotal.textSize = 18f
                b.tvSubtotal.setTextColor(0xFF2E7D32.toInt())
                b.root.setBackgroundColor(0xFFF1F8E9.toInt())
                b.ivArrow.visibility = View.GONE
                b.root.setPadding(28, 16, 16, 12)
                b.root.setOnClickListener(null)
            }
            is ListItem.PriceHeader -> {
                // ===== 单价头：最细层级，不显示折叠图标 =====
                b.tvGroupName.text = "${Record.formatNumber(item.unitPrice)}元/${item.priceUnit}"
                b.tvGroupName.textSize = 17f
                b.tvGroupName.setTextColor(0xFF558B2F.toInt())
                b.tvCount.text = "${item.count}条"
                b.tvCount.textSize = 14f
                b.tvSubtotal.text = "￥${Record.formatMoney(item.subtotal)}"
                b.tvSubtotal.textSize = 17f
                b.tvSubtotal.setTextColor(0xFF558B2F.toInt())
                b.root.setBackgroundColor(0xFFFFFFFE.toInt())
                b.ivArrow.visibility = View.GONE
                b.root.setPadding(48, 14, 16, 14)
                // 保留点击折叠功能，但视觉上不显示箭头
                b.root.setOnClickListener {
                    toggleCollapse("PRICE:${item.type}|${item.unitPrice}")
                }
            }
            is ListItem.RecordItem -> Unit
        }
    }

    private fun bindRecordItem(holder: RecordViewHolder, item: ListItem.RecordItem) {
        val record = item.record
        bindRecord(holder.binding, record)
        val pos = holder.bindingAdapterPosition

        val indentLeft = if (groupedMode) 64 else 0

        if (multiSelectMode) {
            // 多选模式：无论平铺还是分组，都显示 cbSelect
            holder.itemView.setPadding(indentLeft, 0, 0, 0)
            holder.binding.cbSelect.visibility = View.VISIBLE
            holder.binding.cbSelect.setOnCheckedChangeListener(null)
            holder.binding.cbSelect.isChecked = selectedIds.contains(record.id)
            holder.binding.cbSelect.setOnCheckedChangeListener { _, _ -> toggleSelection(record.id) }
            holder.itemView.setOnClickListener { toggleSelection(record.id) }
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.alpha = if (selectedIds.contains(record.id)) 0.6f else 1.0f
        } else {
            // 普通模式
            holder.itemView.setPadding(indentLeft, 0, 0, 0)
            holder.binding.cbSelect.visibility = View.GONE
            holder.itemView.alpha = 1.0f
            holder.itemView.setOnClickListener { listener.onItemClick(record, pos) }
            holder.itemView.setOnLongClickListener {
                listener.onItemLongClick(record, pos); true
            }
        }
    }

    // ==================== 更新数据 ====================

    fun updateFlat(newRecords: List<Record>) {
        if (multiSelectMode) { multiSelectMode = false; selectedIds.clear() }
        groupedMode = false
        flatRecords.clear(); flatRecords.addAll(newRecords)
        flatList.clear()
        flatRecords.forEach { flatList.add(ListItem.RecordItem(it)) }
        notifyDataSetChanged()
    }

    /** 两级分组：类型 → 单价 */
    fun updateGroupedByType(newRecords: List<Record>, priceDesc: Boolean = true) {
        if (multiSelectMode) { multiSelectMode = false; selectedIds.clear() }
        groupedMode = true
        collapsedKeys.clear()
        currentPriceDesc = priceDesc
        flatRecords.clear(); flatRecords.addAll(newRecords)
        rebuildTwoLevel(priceDesc)
        notifyDataSetChanged()
    }

    /** 三级分组：日期 → 类型 → 单价 */
    fun updateGroupedByDate(newRecords: List<Record>, priceDesc: Boolean = true) {
        if (multiSelectMode) { multiSelectMode = false; selectedIds.clear() }
        groupedMode = true
        collapsedKeys.clear()
        currentPriceDesc = priceDesc
        flatRecords.clear(); flatRecords.addAll(newRecords)
        rebuildThreeLevel(priceDesc)
        notifyDataSetChanged()
    }

    // ==================== 分组构建 ====================

    /** 两级：类型 → 单价 */
    private fun rebuildTwoLevel(priceDesc: Boolean) {
        flatList.clear()
        val typeGroups = flatRecords.groupBy { it.type }.toList().sortedBy { it.first }

        for ((type, typeRecords) in typeGroups) {
            flatList.add(ListItem.TypeHeader(type, typeRecords.size, typeRecords.sumOf { it.totalAmount }))
            addPriceGroups(type, typeRecords, priceDesc, indentType = true)
        }
    }

    /** 三级：日期 → 类型 → 单价 */
    private fun rebuildThreeLevel(priceDesc: Boolean) {
        flatList.clear()
        // 日期提取：取 "yyyy-MM-dd" 部分
        val dateGroups = flatRecords.groupBy { it.dateTime.substringBefore(' ') }.toList()
            .sortedByDescending { it.first } // 日期倒序（最新在前）

        for ((date, dateRecords) in dateGroups) {
            flatList.add(ListItem.DateHeader(date, dateRecords.size, dateRecords.sumOf { it.totalAmount }))

            val typeGroups = dateRecords.groupBy { it.type }.toList().sortedBy { it.first }
            for ((type, typeRecords) in typeGroups) {
                flatList.add(ListItem.TypeHeader(type, typeRecords.size, typeRecords.sumOf { it.totalAmount }))
                addPriceGroups(type, typeRecords, priceDesc, indentType = true)
            }
        }
    }

    /** 往 flatList 追加单价头 + 记录（如果该单价组未折叠） */
    private fun addPriceGroups(type: String, records: List<Record>, priceDesc: Boolean, indentType: Boolean) {
        val priceGroups = records.groupBy { "%.2f".format(it.unitPrice) }.toList()
            .let { pairs ->
                if (priceDesc) pairs.sortedByDescending { it.second.first().unitPrice }
                else pairs.sortedBy { it.second.first().unitPrice }
            }

        for ((_, priceRecords) in priceGroups) {
            val price = priceRecords.first().unitPrice
            val priceSubtotal = priceRecords.sumOf { it.totalAmount }
            val priceUnit = priceRecords.first().let { r ->
                when (r.measureMode) {
                    Record.MODE_WEIGHT_KG, Record.MODE_WEIGHT_JIN -> "斤"
                    else -> r.unitName
                }
            }
            val key = "PRICE:$type|$price"
            flatList.add(ListItem.PriceHeader(type, price, priceUnit, priceRecords.size, priceSubtotal))

            if (!isCollapsed(key)) {
                priceRecords.sortedByDescending { it.dateTime }
                    .forEach { flatList.add(ListItem.RecordItem(it)) }
            }
        }
    }

    // ==================== 折叠控制 ====================

    private fun isCollapsed(key: String): Boolean = collapsedKeys.contains(key)

    private fun toggleCollapse(key: String) {
        if (collapsedKeys.contains(key)) collapsedKeys.remove(key) else collapsedKeys.add(key)
        // 重新判断当前是两级还是三级分组，用重建方法保持一致
        rebuildFromState(currentPriceDesc)
        notifyDataSetChanged()
    }

    /** 从 flatList 的第一个元素判断用哪种重建方法 */
    private fun rebuildFromState(priceDesc: Boolean) {
        if (flatList.isEmpty()) return
        when (flatList[0]) {
            is ListItem.DateHeader -> rebuildThreeLevel(priceDesc)
            is ListItem.TypeHeader -> rebuildTwoLevel(priceDesc)
            else -> {} // 平铺模式不会触发折叠
        }
    }

    // ==================== 多选 ====================

    fun enterMultiSelect() { multiSelectMode = true; selectedIds.clear(); notifyDataSetChanged(); listener.onSelectionChanged(0) }
    fun exitMultiSelect() { multiSelectMode = false; selectedIds.clear(); notifyDataSetChanged(); listener.onSelectionChanged(0) }
    fun isMultiSelect(): Boolean = multiSelectMode
    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        notifyDataSetChanged()
        listener.onSelectionChanged(selectedIds.size)
    }
    /** 全选 / 取消全选 toggle — 如果当前已全选则取消，否则全选 */
    fun selectAllOrNone() {
        val allIds = flatRecords.map { it.id }.toSet()
        if (selectedIds.size >= allIds.size) {
            // 已全选 → 取消全选
            selectedIds.clear()
        } else {
            selectedIds.clear()
            selectedIds.addAll(allIds)
        }
        notifyDataSetChanged()
        listener.onSelectionChanged(selectedIds.size)
    }
    fun isAllSelected(): Boolean = flatRecords.isNotEmpty() && selectedIds.size >= flatRecords.size
    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    // ==================== 绑定记录 ====================

    private fun bindRecord(b: ItemRecordBinding, record: Record) {
        val ctx = b.root.context
        val isBuy = record.direction == "买入"
        val green = ContextCompat.getColor(ctx, R.color.green_primary)
        val orange = ContextCompat.getColor(ctx, R.color.orange_primary)

        b.tvTag.text = record.direction
        b.tvTag.setBackgroundColor(if (isBuy) green else orange)
        b.tvDateTime.text = record.dateTime
        b.tvTypeName.text = record.type

        when (record.source) {
            Record.SOURCE_PHOTO -> { b.tvSourceBadge.text = "📷拍照"; b.tvSourceBadge.visibility = View.VISIBLE }
            Record.SOURCE_VOICE -> { b.tvSourceBadge.text = "🎤语音"; b.tvSourceBadge.visibility = View.VISIBLE }
            else -> b.tvSourceBadge.visibility = View.GONE
        }

        if (record.measureMode == Record.MODE_QUANTITY) {
            b.layoutWeight.visibility = View.GONE; b.layoutQuantity.visibility = View.VISIBLE
            b.tvQuantity.text = Record.formatNumber(record.quantity)
            b.tvUnitName.text = " ${record.unitName}"
        } else {
            b.layoutWeight.visibility = View.VISIBLE; b.layoutQuantity.visibility = View.GONE
            b.tvGrossWeight.text = "总重:${Record.formatNumber(record.grossWeight)}"
            b.tvTareWeight.text = withCarryOver("车重:${Record.formatNumber(record.vehicleWeight)}", record.isCarryOver, orange)
            b.tvNetWeight.text = "净重:${Record.formatNumber(record.netWeight)} ${record.unitName}"
        }

        val priceUnit = when (record.measureMode) {
            Record.MODE_WEIGHT_KG, Record.MODE_WEIGHT_JIN -> "斤"
            else -> record.unitName
        }
        b.tvUnitPrice.text = withCarryOver("单价:${Record.formatNumber(record.unitPrice)}元/$priceUnit", record.isCarryOver, orange)
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
