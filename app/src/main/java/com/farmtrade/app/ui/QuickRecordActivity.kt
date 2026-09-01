package com.farmtrade.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.ActivityQuickRecordBinding
import com.farmtrade.app.databinding.DialogCustomTypeBinding
import com.farmtrade.app.databinding.DialogInlineEditBinding
import com.farmtrade.app.util.OcrHelper
import com.farmtrade.app.util.SpeechInputController
import com.farmtrade.app.util.VoiceParser
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快速记录界面：通过拍照或语音自动生成一条记录，并沿用今日上一条记录的车重/单价等。
 *
 * - 接收 [EXTRA_RECORD_MODE] = "PHOTO" 或 "VOICE"
 * - PHOTO：立即拍照 -> OCR 读取数字作为总重
 * - VOICE：立即语音识别 -> [VoiceParser.parse] 解析各字段
 * - 读取 [DatabaseHelper.getTodayLastRecord] 进行沿用
 * - 展示自动生成记录卡片，每个字段带来源徽标，可点击行内编辑
 * - ✏️全部修改：跳转 [AddRecordActivity]；✅确认保存：入库并结束
 */
class QuickRecordActivity : AppCompatActivity(), QuickRecordEditDialogs.Host {

    private lateinit var binding: ActivityQuickRecordBinding
    private val dbHelper by lazy { DatabaseHelper(this) }

    /**
     * 录入模式：PHOTO（拍照）或 VOICE（语音）。
     * 注意：通过键 [EXTRA_RECORD_MODE] 传入。
     */
    private val mode: String by lazy {
        intent.getStringExtra(EXTRA_RECORD_MODE) ?: EXTRA_MODE_PHOTO
    }

    /** 自动生成的记录，初始流程（拍照/语音）完成后赋值 */
    private lateinit var pendingRecord: Record

    /** 今日上一条记录，用于沿用 */
    private var carryOverRecord: Record? = null

    /** 初始拍照识别的总重（用于"恢复沿用"时还原总重） */
    private var originalGrossWeight = 0.0

    private var cameraImageUri: Uri? = null

    /** true = 初始拍照（PHOTO 模式首张），false = 行内重新拍照 */
    private var cameraForInitial = false
    /** 行内重新拍照时，OCR 结果要回填的字段 */
    private var cameraTarget: EditField = EditField.GROSS

    /** 行内语音修改时，解析结果要回填的字段；为空表示处于初始语音流程 */
    private var pendingVoiceField: EditField? = null

    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // 徽标颜色
    private val blueBg = 0xFFE3F2FD.toInt()
    private val blueFg = 0xFF1565C0.toInt()
    private val orangeBg = 0xFFFFF3E0.toInt()
    private val orangeFg = 0xFFE65100.toInt()
    private val grayBg = 0xFFEEEEEE.toInt()
    private val grayFg = 0xFF888888.toInt()

    // 买入(绿) / 卖出(红) 标签背景色
    private val buyBg = 0xFF2E7D32.toInt()
    private val buyFg = 0xFFFFFFFF.toInt()
    private val sellBg = 0xFFC62828.toInt()
    private val sellFg = 0xFFFFFFFF.toInt()

    // ==================== Activity Result ====================

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                toast("需要相机权限")
                if (cameraForInitial && !this::pendingRecord.isInitialized) finish()
            }
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                speechInput.startFlow()
            } else {
                toast("需要麦克风权限")
                if (!this::pendingRecord.isInitialized && mode == EXTRA_MODE_VOICE) finish()
            }
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraImageUri
            if (success && uri != null) {
                runOcr(uri)
            } else {
                toast("未拍摄照片")
                if (cameraForInitial && !this::pendingRecord.isInitialized) finish()
            }
        }

    // ===== 本地语音识别 =====
    private lateinit var speechInput: SpeechInputController

    /** 语音流程回调：录音对话框、行内修改/初始录入的结果分发 */
    private val speechCallbacks = object : SpeechInputController.Callbacks {
        override fun onRecordingStarted(outputFile: File) {
            val label = fieldLabel(pendingVoiceField ?: EditField.GROSS)
            MaterialAlertDialogBuilder(this@QuickRecordActivity)
                .setTitle("🎤 正在录音")
                .setMessage("请说出${label}\n\n说完后点击「完成」按钮")
                .setCancelable(false)
                .setPositiveButton("完成") { _, _ -> speechInput.stopAndRecognize() }
                .show()
        }

        override fun onRecognized(text: String?) {
            if (text.isNullOrEmpty()) {
                toast("未识别到语音内容")
                handleRecognitionFailure()
                return
            }
            // 行内修改按目标字段解析（用户可能只说数值不带关键词）；
            // 初始语音流程提示的是"请说出总重"，目标也按总重处理
            val parsed = VoiceParser.parseForField(text, pendingVoiceField?.name ?: "GROSS")
            toast("语音识别：${parsed.convertedText}")
            val field = pendingVoiceField
            if (field != null) {
                // 行内语音修改：只回填目标字段
                applyVoiceField(field, parsed)
                pendingVoiceField = null
                recalcAndRender()
            } else if (!this@QuickRecordActivity::pendingRecord.isInitialized) {
                // 初始语音流程
                assembleRecord(grossFromInput = 0.0, voiceResult = parsed)
            } else {
                // 兜底：合并到已有记录
                pendingRecord = VoiceParser.applyToRecord(pendingRecord, parsed)
                recalcAndRender()
            }
        }

        override fun onError(message: String) {
            toast(message)
            handleRecognitionFailure()
        }

        override fun onDownloadCancelled() = handleRecognitionFailure()
    }

    /** 跳转 AddRecordActivity "全部修改"，并透传保存结果给 RecordListFragment */
    private val editRecordLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
            }
            finish()
        }

    // ==================== Lifecycle ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        speechInput = SpeechInputController(this, speechCallbacks)

        binding.ivBack.setOnClickListener { finish() }

        // 卡片行点击 -> 专属编辑方式（方向/计量/类型 用抽取的辅助类，数值字段继续用通用文本框）
        binding.rowDirection.setOnClickListener { QuickRecordEditDialogs.openDirectionDialog(this) }
        binding.rowType.setOnClickListener { QuickRecordEditDialogs.openTypeDialog(this) }
        binding.rowMeasureMode.setOnClickListener { QuickRecordEditDialogs.openMeasureDialog(this) }
        binding.rowDateTime.setOnClickListener { openInlineEdit(EditField.DATETIME) }
        binding.rowGrossWeight.setOnClickListener { openInlineEdit(EditField.GROSS) }
        binding.rowTareWeight.setOnClickListener { openInlineEdit(EditField.TARE) }
        binding.rowUnitPrice.setOnClickListener { openInlineEdit(EditField.PRICE) }

        binding.btnEdit.setOnClickListener { goToFullEdit() }
        binding.btnConfirmSave.setOnClickListener { confirmSave() }

        // 占位提示，待生成后由 renderRecord 覆盖
        binding.tvCarryOverInfo.text = "正在生成记录..."

        when (mode) {
            EXTRA_MODE_VOICE -> startVoiceFlow()
            else -> startPhotoFlow()
        }
    }

    // ==================== 初始流程 ====================

    private fun startPhotoFlow() {
        cameraForInitial = true
        if (hasCameraPermission()) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startVoiceFlow() {
        pendingVoiceField = null
        if (hasAudioPermission()) speechInput.startFlow() else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun assembleRecord(grossFromInput: Double, voiceResult: VoiceParser.ParseResult?) {
        val today = dateFormat.format(Date())
        carryOverRecord = dbHelper.getTodayLastRecord(today)

        val r = Record()
        r.dateTime = dateTimeFormat.format(Date())
        r.source = if (mode == EXTRA_MODE_VOICE) Record.SOURCE_VOICE else Record.SOURCE_PHOTO
        r.isCarryOver = carryOverRecord != null

        // 沿用今日上一条记录（direction / type / measure / tare / price / unitName）
        carryOverRecord?.let {
            r.direction = it.direction
            r.type = it.type
            r.vehicleWeight = it.vehicleWeight
            r.unitPrice = it.unitPrice
            r.measureMode = it.measureMode
            r.unitName = it.unitName
        }

        // 兜底：没有可沿用的记录时，填入合理默认值（方向=买入、按重量公斤）
        if (r.direction.isBlank()) r.direction = "买入"
        if (r.type.isBlank()) r.type = dbHelper.getAllTypes().firstOrNull().orEmpty()
        if (r.measureMode.isBlank()) { r.measureMode = Record.MODE_WEIGHT_KG; r.unitName = "公斤" }

        // 语音识别结果覆盖
        if (voiceResult != null) {
            voiceResult.direction?.let { r.direction = it }
            voiceResult.type?.let { r.type = it }
            voiceResult.measureMode?.let { r.measureMode = it }
            voiceResult.vehicleWeight?.let { r.vehicleWeight = it }
            voiceResult.unitPrice?.let { r.unitPrice = it }
            voiceResult.quantity?.let { r.quantity = it }
            voiceResult.unitName?.let { r.unitName = it }
            voiceResult.grossWeight?.let { r.grossWeight = it }
        }

        // 拍照识别的总重
        if (r.grossWeight <= 0 && grossFromInput > 0) {
            r.grossWeight = grossFromInput
        }

        r.netWeight = r.calculateNetWeight()
        r.totalAmount = r.calculateTotalAmount()

        originalGrossWeight = r.grossWeight
        pendingRecord = r

        renderRecord()
        showCarryOverBanner()
    }

    private fun handleOcrResult(value: Double) {
        if (cameraForInitial) {
            cameraForInitial = false
            assembleRecord(grossFromInput = value, voiceResult = null)
            toast("识别到总重：${Record.formatNumber(value)}")
        } else {
            when (cameraTarget) {
                EditField.GROSS -> pendingRecord.grossWeight = value
                EditField.TARE -> pendingRecord.vehicleWeight = value
                EditField.PRICE -> pendingRecord.unitPrice = value
                else -> {}
            }
            recalcAndRender()
            toast("识别到${fieldLabel(cameraTarget)}：${Record.formatNumber(value)}")
        }
    }

    // ==================== 渲染 ====================

    private fun recalcAndRender() {
        if (!this::pendingRecord.isInitialized) return
        pendingRecord.netWeight = pendingRecord.calculateNetWeight()
        pendingRecord.totalAmount = pendingRecord.calculateTotalAmount()
        renderRecord()
    }

    private fun renderRecord() {
        if (!this::pendingRecord.isInitialized) return
        val r = pendingRecord
        val unit = r.unitName
        val priceUnit = when (r.measureMode) {
            Record.MODE_WEIGHT_KG -> "斤"
            Record.MODE_WEIGHT_JIN -> "斤"
            else -> r.unitName
        }

        // 方向：买入=绿 / 卖出=红
        binding.tvTag.text = r.direction
        if (r.direction == "卖出") {
            binding.tvTag.setBackgroundColor(sellBg)
            binding.tvTag.setTextColor(sellFg)
        } else {
            binding.tvTag.setBackgroundColor(buyBg)
            binding.tvTag.setTextColor(buyFg)
        }

        // 计量方式文字
        binding.tvMeasureMode.text = when (r.measureMode) {
            Record.MODE_WEIGHT_KG -> "按重量(公斤)"
            Record.MODE_WEIGHT_JIN -> "按重量(斤)"
            else -> "按数量(${unit})"
        }

        binding.tvTypeName.text = r.type
        binding.tvDateTime.text = r.dateTime
        binding.tvGrossWeight.text = "${Record.formatNumber(r.grossWeight)} $unit"
        binding.tvTareWeight.text = "${Record.formatNumber(r.vehicleWeight)} $unit"
        binding.tvNetWeight.text = "${Record.formatNumber(r.netWeight)} $unit"
        binding.tvUnitPrice.text = "${Record.formatNumber(r.unitPrice)} 元/$priceUnit"
        binding.tvTotalAmount.text = "￥${Record.formatMoney(r.totalAmount)}"

        // 总重来源：拍照 / 语音（不变）
        setBadge(
            binding.badgeGrossSource,
            if (r.source == Record.SOURCE_VOICE) "🎤 语音" else "📷 拍照",
            blueBg, blueFg
        )

        val co = carryOverRecord
        // 方向 沿用/已改
        setBadge(
            binding.badgeDirectionSource,
            if (co != null && r.direction == co.direction) "沿用" else "已改",
            if (co != null && r.direction == co.direction) orangeBg else blueBg,
            if (co != null && r.direction == co.direction) orangeFg else blueFg
        )
        // 类型 沿用/已改
        setBadge(
            binding.badgeTypeSource,
            if (co != null && r.type == co.type) "沿用" else "已改",
            if (co != null && r.type == co.type) orangeBg else blueBg,
            if (co != null && r.type == co.type) orangeFg else blueFg
        )
        // 计量方式 沿用/已改
        setBadge(
            binding.badgeMeasureSource,
            if (co != null && r.measureMode == co.measureMode) "沿用" else "已改",
            if (co != null && r.measureMode == co.measureMode) orangeBg else blueBg,
            if (co != null && r.measureMode == co.measureMode) orangeFg else blueFg
        )
        // 车重 沿用/已改
        setBadge(
            binding.badgeTareSource,
            if (isCarryOverValue(r.vehicleWeight, co?.vehicleWeight)) "沿用" else "已改",
            if (isCarryOverValue(r.vehicleWeight, co?.vehicleWeight)) orangeBg else blueBg,
            if (isCarryOverValue(r.vehicleWeight, co?.vehicleWeight)) orangeFg else blueFg
        )
        // 单价 沿用/已改
        setBadge(
            binding.badgePriceSource,
            if (isCarryOverValue(r.unitPrice, co?.unitPrice)) "沿用" else "已改",
            if (isCarryOverValue(r.unitPrice, co?.unitPrice)) orangeBg else blueBg,
            if (isCarryOverValue(r.unitPrice, co?.unitPrice)) orangeFg else blueFg
        )
        setBadge(binding.badgeDateSource, "✏️", grayBg, grayFg)
    }

    private fun isCarryOverValue(current: Double, carried: Double?): Boolean =
        carried != null && current == carried

    private fun showCarryOverBanner() {
        val co = carryOverRecord
        binding.tvCarryOverInfo.text = if (co != null) {
            "已沿用今日上一条记录：车重 ${Record.formatNumber(co.vehicleWeight)}${co.unitName} | 单价 ${Record.formatNumber(co.unitPrice)}元"
        } else {
            "暂无今日上一条记录，请核对后修改"
        }
    }

    private fun setBadge(tv: TextView, text: String, bg: Int, fg: Int) {
        tv.text = text
        tv.setBackgroundColor(bg)
        tv.setTextColor(fg)
        val h = (8 * resources.displayMetrics.density).toInt()
        val v = (3 * resources.displayMetrics.density).toInt()
        tv.setPadding(h, v, h, v)
    }

    // ==================== QuickRecordEditDialogs.Host 实现 ====================

    override val pendingRecord: Record
        get() = this@QuickRecordActivity.pendingRecord
    override val carryOverRecord: Record?
        get() = this@QuickRecordActivity.carryOverRecord
    override fun getAllTypes() = dbHelper.getAllTypes()
    override fun recalcAndRender() = this@QuickRecordActivity.recalcAndRender()
    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    override fun startVoiceEditForType() { voiceEditFor(EditField.TYPE) }
    override fun activity(): AppCompatActivity = this

    // ==================== 通用文本编辑对话框：日期时间 / 总重 / 车重 / 单价 ====================

    /** 通用文本编辑对话框：用于日期时间 / 总重 / 车重 / 单价 */
    private fun openInlineEdit(field: EditField) {
        if (!this::pendingRecord.isInitialized) {
            toast("记录尚未生成")
            return
        }
        val dv = DialogInlineEditBinding.inflate(layoutInflater)
        dv.tvFieldLabel.text = "编辑：${fieldLabel(field)}"
        dv.etInput.setText(fieldCurrentValue(field))
        dv.etInput.inputType = fieldInputType(field)
        dv.tvHint.text = fieldHint(field)

        // 日期时间没有拍照/语音辅助按钮
        if (field == EditField.DATETIME) {
            dv.btnRetakePhoto.visibility = View.GONE
            dv.btnVoiceEdit.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(this).setView(dv.root).create()

        dv.btnRetakePhoto.setOnClickListener {
            dialog.dismiss()
            retakePhotoFor(field)
        }
        dv.btnVoiceEdit.setOnClickListener {
            dialog.dismiss()
            voiceEditFor(field)
        }
        dv.btnRestore.setOnClickListener {
            restoreField(field)
            recalcAndRender()
            dialog.dismiss()
            toast("已恢复沿用")
        }
        dv.btnCancel.setOnClickListener { dialog.dismiss() }
        dv.btnConfirm.setOnClickListener {
            val v = dv.etInput.text.toString().trim()
            applyFieldInput(field, v)
            recalcAndRender()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun fieldLabel(field: EditField): String = when (field) {
        EditField.GROSS -> "总重"
        EditField.TARE -> "车重"
        EditField.PRICE -> "单价"
        EditField.DATETIME -> "日期时间"
        EditField.TYPE -> "类型"
    }

    private fun fieldCurrentValue(field: EditField): String = when (field) {
        EditField.GROSS -> Record.formatNumber(pendingRecord.grossWeight)
        EditField.TARE -> Record.formatNumber(pendingRecord.vehicleWeight)
        EditField.PRICE -> Record.formatNumber(pendingRecord.unitPrice)
        EditField.DATETIME -> pendingRecord.dateTime
        EditField.TYPE -> pendingRecord.type
    }

    private fun fieldInputType(field: EditField): Int = when (field) {
        EditField.TYPE, EditField.DATETIME -> InputType.TYPE_CLASS_TEXT
        else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun fieldHint(field: EditField): String = when (field) {
        EditField.GROSS -> "输入总重（${pendingRecord.unitName}），可拍照或语音识别"
        EditField.TARE -> "输入车重（${pendingRecord.unitName}），可拍照或语音识别"
        EditField.PRICE -> "输入单价（元），可语音说"
        EditField.DATETIME -> "格式：yyyy-MM-dd HH:mm（如 2026-09-01 10:30）"
        EditField.TYPE -> "输入类型名称"
    }

    private fun applyFieldInput(field: EditField, value: String) {
        when (field) {
            EditField.GROSS -> pendingRecord.grossWeight = value.toDoubleOrNull() ?: 0.0
            EditField.TARE -> pendingRecord.vehicleWeight = value.toDoubleOrNull() ?: 0.0
            EditField.PRICE -> pendingRecord.unitPrice = value.toDoubleOrNull() ?: 0.0
            EditField.DATETIME -> if (value.isNotBlank()) pendingRecord.dateTime = value
            EditField.TYPE -> if (value.isNotBlank()) pendingRecord.type = value
        }
    }

    private fun restoreField(field: EditField) {
        val co = carryOverRecord
        when (field) {
            EditField.GROSS -> pendingRecord.grossWeight = originalGrossWeight
            EditField.TARE -> pendingRecord.vehicleWeight = co?.vehicleWeight ?: 0.0
            EditField.PRICE -> pendingRecord.unitPrice = co?.unitPrice ?: 0.0
            EditField.DATETIME -> pendingRecord.dateTime = dateTimeFormat.format(Date())
            EditField.TYPE -> co?.type?.let { pendingRecord.type = it }
        }
    }

    // ----- 行内重新拍照 / 语音修改（仅数值字段可用） -----

    private fun retakePhotoFor(field: EditField) {
        when (field) {
            EditField.GROSS, EditField.TARE, EditField.PRICE -> {
                cameraForInitial = false
                cameraTarget = field
                if (hasCameraPermission()) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> toast("该字段不支持拍照识别")
        }
    }

    private fun voiceEditFor(field: EditField) {
        pendingVoiceField = field
        if (hasAudioPermission()) speechInput.startFlow() else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun applyVoiceField(field: EditField, r: VoiceParser.ParseResult) {
        when (field) {
            EditField.GROSS -> r.grossWeight?.let { pendingRecord.grossWeight = it }
            EditField.TARE -> r.vehicleWeight?.let { pendingRecord.vehicleWeight = it }
            EditField.PRICE -> r.unitPrice?.let { pendingRecord.unitPrice = it }
            EditField.TYPE -> r.type?.let { pendingRecord.type = it }
            EditField.DATETIME -> {}
        }
    }

    // ==================== 相机 / OCR ====================

    private fun launchCamera() {
        cameraImageUri = OcrHelper.createImageUri(this)
        if (cameraImageUri == null) {
            toast("无法创建图片文件")
            if (cameraForInitial) finish()
            return
        }
        try {
            takePictureLauncher.launch(cameraImageUri!!)
        } catch (e: Exception) {
            toast("无法启动相机")
            if (cameraForInitial) finish()
        }
    }

    private fun runOcr(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = OcrHelper.loadBitmap(this@QuickRecordActivity, uri)
            val number = if (bitmap != null) OcrHelper.recognizeFromBitmap(bitmap) else null
            if (number != null) {
                val value = number.toDoubleOrNull()
                if (value != null && value > 0) {
                    handleOcrResult(value)
                } else {
                    toast("未识别到有效数字")
                    if (cameraForInitial && !this@QuickRecordActivity::pendingRecord.isInitialized) finish()
                }
            } else {
                toast("OCR 识别失败")
                if (cameraForInitial && !this@QuickRecordActivity::pendingRecord.isInitialized) finish()
            }
        }
    }

    /**
     * 识别失败时的处理：如果是初始流程就结束页面。
     */
    private fun handleRecognitionFailure() {
        if (!this::pendingRecord.isInitialized && mode == EXTRA_MODE_VOICE) {
            finish()
        }
    }

    // ==================== 保存 / 跳转 ====================

    private fun goToFullEdit() {
        if (!this::pendingRecord.isInitialized) {
            toast("记录尚未生成")
            return
        }
        val intent = Intent(this, AddRecordActivity::class.java).apply {
            putExtra(AddRecordActivity.EXTRA_RECORD, pendingRecord)
        }
        editRecordLauncher.launch(intent)
    }

    private fun confirmSave() {
        if (!this::pendingRecord.isInitialized) {
            toast("记录尚未生成")
            return
        }
        pendingRecord.netWeight = pendingRecord.calculateNetWeight()
        pendingRecord.totalAmount = pendingRecord.calculateTotalAmount()
        dbHelper.insertRecord(pendingRecord)
        toast("保存成功")
        setResult(RESULT_OK)
        finish()
    }

    // ==================== 权限 / utils ====================

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        speechInput.release()
    }

    /** 可行内编辑的字段 */
    private enum class EditField { GROSS, TARE, PRICE, TYPE, DATETIME }

    companion object {
        // 录入模式值，与 Record.SOURCE_PHOTO / Record.SOURCE_VOICE 一致
        const val EXTRA_RECORD_MODE = "extra_record_mode"
        const val EXTRA_MODE_PHOTO = "PHOTO"
        const val EXTRA_MODE_VOICE = "VOICE"
    }
}
