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
import com.farmtrade.app.databinding.ItemRecordBinding

/**
 * 买卖记录列表适配器。
 *
 * 适配 [item_record.xml] 布局，逐条展示 [Record] 的：
 * 方向标签、日期时间、类型名称、重量/数量、单价、总金额、来源标识，
 * 以及"沿用"标记。点击/长按通过 [OnRecordClickListener] 回调给宿主页面。
 */
class RecordAdapter(
    private val records: MutableList<Record> = mutableListOf(),
    private val listener: OnRecordClickListener
) : RecyclerView.Adapter<RecordAdapter.RecordViewHolder>() {

    /** 多选模式 */
    private var multiSelectMode = false
    private val selectedIds = mutableSetOf<Long>()

    /**
     * 记录条目的点击与长按事件回调接口。
     */
    interface OnRecordClickListener {
        fun onItemClick(record: Record, position: Int)
        fun onItemLongClick(record: Record, position: Int)
        /** 多选模式下选中数量变化时回调 */
        fun onSelectionChanged(selectedCount: Int)
    }

    /**
     * ViewHolder，持有 [item_record.xml] 生成的 ViewBinding。
     */
    class RecordViewHolder(val binding: ItemRecordBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val binding = ItemRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = records[position]
        bindRecord(holder.binding, record)

        // 多选模式：显示 CheckBox，点击切换选中
        if (multiSelectMode) {
            holder.binding.cbSelect.visibility = View.VISIBLE
            holder.binding.cbSelect.isChecked = selectedIds.contains(record.id)
            holder.binding.cbSelect.setOnClickListener {
                toggleSelection(record.id)
            }
            holder.itemView.setOnClickListener {
                toggleSelection(record.id)
            }
            holder.itemView.setOnLongClickListener(null)
            // 选中状态背景高亮
            holder.itemView.alpha = if (selectedIds.contains(record.id)) 0.6f else 1.0f
        } else {
            holder.binding.cbSelect.visibility = View.GONE
            holder.itemView.alpha = 1.0f
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onItemClick(records[pos], pos)
                }
            }
            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onItemLongClick(records[pos], pos)
                }
                true
            }
        }
    }

    override fun getItemCount(): Int = records.size

    /**
     * 用新数据替换当前列表并整体刷新。
     */
    fun updateData(newRecords: List<Record>) {
        records.clear()
        records.addAll(newRecords)
        notifyDataSetChanged()
    }

    // ==================== 多选模式 ====================

    fun enterMultiSelect() {
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

    fun isSelected(): Boolean = multiSelectMode

    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        notifyDataSetChanged()
        listener.onSelectionChanged(selectedIds.size)
    }

    fun selectAll() {
        selectedIds.clear()
        records.forEach { selectedIds.add(it.id) }
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

        // 方向标签：买入绿色背景、卖出橙色背景
        b.tvTag.text = record.direction
        b.tvTag.setBackgroundColor(if (isBuy) green else orange)

        // 日期时间
        b.tvDateTime.text = record.dateTime

        // 类型名称（大号加粗）
        b.tvTypeName.text = record.type

        // 来源标识：拍照 / 语音 / 手动（不显示）
        when (record.source) {
            Record.SOURCE_PHOTO -> {
                b.tvSourceBadge.text = "📷拍照"
                b.tvSourceBadge.visibility = View.VISIBLE
            }
            Record.SOURCE_VOICE -> {
                b.tvSourceBadge.text = "🎤语音"
                b.tvSourceBadge.visibility = View.VISIBLE
            }
            else -> {
                b.tvSourceBadge.visibility = View.GONE
            }
        }

        // 重量 / 数量信息
        if (record.measureMode == Record.MODE_QUANTITY) {
            // 数量模式：数量:X unitName
            b.layoutWeight.visibility = View.GONE
            b.layoutQuantity.visibility = View.VISIBLE
            b.tvQuantity.text = Record.formatNumber(record.quantity)
            b.tvUnitName.text = " ${record.unitName}"
        } else {
            // 重量模式：总重:X | 车重:Y | 净重:Z unitName
            b.layoutWeight.visibility = View.VISIBLE
            b.layoutQuantity.visibility = View.GONE
            b.tvGrossWeight.text = "总重:${Record.formatNumber(record.grossWeight)}"
            b.tvTareWeight.text = withCarryOver(
                "车重:${Record.formatNumber(record.vehicleWeight)}",
                record.isCarryOver,
                orange
            )
            b.tvNetWeight.text =
                "净重:${Record.formatNumber(record.netWeight)} ${record.unitName}"
        }

        // 单价：单价:X元/单位（沿用时附橙色"(沿用)"）
        // 公斤模式下单价按"元/斤"计算，所以显示也用"元/斤"
        val priceUnit = when (record.measureMode) {
            Record.MODE_WEIGHT_KG -> "斤"
            Record.MODE_WEIGHT_JIN -> "斤"
            else -> record.unitName
        }
        b.tvUnitPrice.text = withCarryOver(
            "单价:${Record.formatNumber(record.unitPrice)}元/$priceUnit",
            record.isCarryOver,
            orange
        )

        // 总金额：买入绿色、卖出橙色，右对齐大号
        b.tvTotalAmount.text = "￥${Record.formatMoney(record.totalAmount)}"
        b.tvTotalAmount.setTextColor(if (isBuy) green else orange)
    }

    /**
     * 在 [base] 文本后追加橙色的"(沿用)"标记（仅在 [carryOver] 为真时）。
     */
    private fun withCarryOver(base: String, carryOver: Boolean, color: Int): CharSequence {
        if (!carryOver) return base
        val ssb = SpannableStringBuilder(base)
        val start = ssb.length
        ssb.append(" (沿用)")
        ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ssb
    }
}
