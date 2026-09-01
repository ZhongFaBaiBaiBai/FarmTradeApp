package com.farmtrade.app.ui

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.ActivityQuickRecordBinding
import com.farmtrade.app.databinding.DialogInlineEditBinding
import com.farmtrade.app.util.AudioRecorder
import com.farmtrade.app.util.OcrHelper
import com.farmtrade.app.util.VoiceParser
import com.farmtrade.app.util.VoskSpeechHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快速记录界面：通过拍照或语音自动生成一条记录，并沿用今日上一条记录的车重/单价等。
 *
 * - 接收 [RecordListActivity.EXTRA_RECORD_MODE] = "PHOTO" 或 "VOICE"
 * - PHOTO：立即拍照 -> OCR 读取数字作为总重
 * - VOICE：立即语音识别 -> [VoiceParser.parse] 解析各字段
 * - 读取 [DatabaseHelper.getTodayLastRecord] 进行沿用
 * - 展示自动生成记录卡片，每个字段带来源徽标，可点击行内编辑
 * - ✏️全部修改：跳转 [AddRecordActivity]；✅确认保存：入库并结束
 */
class QuickRecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickRecordBinding
    private val dbHelper by lazy { DatabaseHelper(this) }

    /**
     * 录入模式：PHOTO（拍照）或 VOICE（语音）。
     * 注意：与 [RecordListActivity] 约定，通过键 [RecordListActivity.EXTRA_RECORD_MODE] 传入。
     */
    private val mode: String by lazy {
        intent.getStringExtra(RecordListActivity.EXTRA_RECORD_MODE) ?: EXTRA_MODE_PHOTO
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
                onVoicePermissionGranted()
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
    private val voskHelper by lazy { VoskSpeechHelper(this) }
    private val audioRecorder by lazy { AudioRecorder() }
    private var isRecording = false

    /** 跳转 AddRecordActivity "全部修改"，并透传保存结果给 RecordListActivity */
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

        binding.ivBack.setOnClickListener { finish() }

        // 卡片行点击 -> 行内编辑
        binding.rowType.setOnClickListener { openInlineEdit(EditField.TYPE) }
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
        if (hasAudioPermission()) onVoicePermissionGranted() else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun assembleRecord(grossFromInput: Double, voiceResult: VoiceParser.ParseResult?) {
        val today = dateFormat.format(Date())
        carryOverRecord = dbHelper.getTodayLastRecord(today)

        val r = Record()
        r.dateTime = dateTimeFormat.format(Date())
        r.source = if (mode == EXTRA_MODE_VOICE) Record.SOURCE_VOICE else Record.SOURCE_PHOTO
        r.isCarryOver = carryOverRecord != null

        // 沿用今日上一条记录
        carryOverRecord?.let {
            r.direction = it.direction
            r.type = it.type
            r.vehicleWeight = it.vehicleWeight
            r.unitPrice = it.unitPrice
            r.measureMode = it.measureMode
            r.unitName = it.unitName
        }

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
        // 单价单位：重量模式下统一用"斤"，数量模式用自定义单位
        val priceUnit = when (r.measureMode) {
            Record.MODE_WEIGHT_KG -> "斤"
            Record.MODE_WEIGHT_JIN -> "斤"
            else -> r.unitName
        }

        binding.tvTag.text = r.direction
        binding.tvTypeName.text = r.type
        binding.tvDateTime.text = r.dateTime
        binding.tvGrossWeight.text = "${Record.formatNumber(r.grossWeight)} $unit"
        binding.tvTareWeight.text = "${Record.formatNumber(r.vehicleWeight)} $unit"
        binding.tvNetWeight.text = "${Record.formatNumber(r.netWeight)} $unit"
        binding.tvUnitPrice.text = "${Record.formatNumber(r.unitPrice)} 元/$priceUnit"
        binding.tvTotalAmount.text = "￥${Record.formatMoney(r.totalAmount)}"

        // 总重来源：拍照 / 语音
        setBadge(
            binding.badgeGrossSource,
            if (r.source == Record.SOURCE_VOICE) "🎤 语音" else "📷 拍照",
            blueBg, blueFg
        )
        // 车重 / 单价 / 类型：沿用 or 已改
        setBadge(
            binding.badgeTareSource,
            if (isCarryOverValue(r.vehicleWeight, carryOverRecord?.vehicleWeight)) "沿用" else "已改",
            orangeBg, orangeFg
        )
        setBadge(
            binding.badgePriceSource,
            if (isCarryOverValue(r.unitPrice, carryOverRecord?.unitPrice)) "沿用" else "已改",
            orangeBg, orangeFg
        )
        setBadge(
            binding.badgeTypeSource,
            if (carryOverRecord != null && r.type == carryOverRecord!!.type) "沿用" else "已改",
            orangeBg, orangeFg
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

    // ==================== 行内编辑 ====================

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
        EditField.TYPE -> "类型"
        EditField.DATETIME -> "日期时间"
    }

    private fun fieldCurrentValue(field: EditField): String = when (field) {
        EditField.GROSS -> Record.formatNumber(pendingRecord.grossWeight)
        EditField.TARE -> Record.formatNumber(pendingRecord.vehicleWeight)
        EditField.PRICE -> Record.formatNumber(pendingRecord.unitPrice)
        EditField.TYPE -> pendingRecord.type
        EditField.DATETIME -> pendingRecord.dateTime
    }

    private fun fieldInputType(field: EditField): Int = when (field) {
        EditField.TYPE, EditField.DATETIME -> InputType.TYPE_CLASS_TEXT
        else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun fieldHint(field: EditField): String = when (field) {
        EditField.GROSS -> "输入总重（${pendingRecord.unitName}），可拍照识别"
        EditField.TARE -> "输入车重（${pendingRecord.unitName}），可拍照识别"
        EditField.PRICE -> "输入单价（元）"
        EditField.TYPE -> "输入类型名称"
        EditField.DATETIME -> "格式：yyyy-MM-dd HH:mm"
    }

    private fun applyFieldInput(field: EditField, value: String) {
        when (field) {
            EditField.GROSS -> pendingRecord.grossWeight = value.toDoubleOrNull() ?: 0.0
            EditField.TARE -> pendingRecord.vehicleWeight = value.toDoubleOrNull() ?: 0.0
            EditField.PRICE -> pendingRecord.unitPrice = value.toDoubleOrNull() ?: 0.0
            EditField.TYPE -> if (value.isNotBlank()) pendingRecord.type = value
            EditField.DATETIME -> if (value.isNotBlank()) pendingRecord.dateTime = value
        }
    }

    private fun restoreField(field: EditField) {
        val co = carryOverRecord
        when (field) {
            EditField.GROSS -> pendingRecord.grossWeight = originalGrossWeight
            EditField.TARE -> pendingRecord.vehicleWeight = co?.vehicleWeight ?: 0.0
            EditField.PRICE -> pendingRecord.unitPrice = co?.unitPrice ?: 0.0
            EditField.TYPE -> co?.type?.let { pendingRecord.type = it }
            EditField.DATETIME -> pendingRecord.dateTime = dateTimeFormat.format(Date())
        }
    }

    // ----- 行内重新拍照 / 语音修改 -----

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
        if (hasAudioPermission()) startLocalVoiceRecognition() else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
        cameraImageUri = createImageUri()
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

    private fun createImageUri(): Uri? {
        return try {
            val file = File.createTempFile("weigh_${System.currentTimeMillis()}", ".jpg", cacheDir)
            file.deleteOnExit()
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    private fun runOcr(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = loadBitmap(uri)
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

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setMutableRequired(true)
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 语音（本地 Vosk 离线识别） ====================

    /**
     * 麦克风权限获得后调用，根据当前场景开始语音识别。
     */
    private fun onVoicePermissionGranted() {
        startLocalVoiceRecognition()
    }

    /**
     * 开始本地语音识别流程：检查模型 → 下载或开始录音。
     * 录音完成后根据 [pendingVoiceField] 判断是初始录入还是行内修改。
     */
    private fun startLocalVoiceRecognition() {
        if (voskHelper.isModelReady()) {
            startRecordingAndRecognize()
        } else if (voskHelper.hasAssetsModel()) {
            // APK 内置了模型，直接从 assets 复制（不耗流量，速度快）
            copyModelFromAssetsAndRecord()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("下载语音模型")
                .setMessage("首次使用语音输入需要下载离线识别模型（约 40MB），下载后无需联网即可使用。是否现在下载？")
                .setPositiveButton("下载") { _, _ -> downloadModelAndRecord() }
                .setNegativeButton("取消") { _, _ ->
                    if (!this::pendingRecord.isInitialized && mode == EXTRA_MODE_VOICE) finish()
                }
                .show()
        }
    }

    private fun copyModelFromAssetsAndRecord() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("正在初始化语音模型...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            show()
        }

        lifecycleScope.launch {
            val success = voskHelper.copyModelFromAssets { progress ->
                progressDialog.progress = progress
            }
            progressDialog.dismiss()
            if (success) {
                toast("语音模型初始化完成")
                startRecordingAndRecognize()
            } else {
                toast("模型初始化失败")
                if (!this@QuickRecordActivity::pendingRecord.isInitialized && mode == EXTRA_MODE_VOICE) finish()
            }
        }
    }

    private fun downloadModelAndRecord() {
        val progressDialog = ProgressDialog(this).apply {
            setMessage("正在下载语音模型...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            show()
        }

        lifecycleScope.launch {
            val success = voskHelper.downloadModel { progress ->
                progressDialog.progress = progress
            }
            progressDialog.dismiss()
            if (success) {
                toast("模型下载完成")
                startRecordingAndRecognize()
            } else {
                toast("模型下载失败")
                if (!this@QuickRecordActivity::pendingRecord.isInitialized && mode == EXTRA_MODE_VOICE) finish()
            }
        }
    }

    private fun startRecordingAndRecognize() {
        val outputFile = File(cacheDir, "quick_voice_${System.currentTimeMillis()}.wav")
        audioRecorder.startRecording(outputFile)
        isRecording = true

        // 显示录音中对话框
        val label = fieldLabel(pendingVoiceField ?: EditField.GROSS)
        MaterialAlertDialogBuilder(this)
            .setTitle("🎤 正在录音")
            .setMessage("请说出${label}\n\n说完后点击「完成」按钮")
            .setCancelable(false)
            .setPositiveButton("完成") { _, _ ->
                stopAndRecognize(outputFile)
            }
            .show()
    }

    private fun stopAndRecognize(wavFile: File) {
        lifecycleScope.launch {
            audioRecorder.stopRecording()
            isRecording = false

            val progressDialog = ProgressDialog(this@QuickRecordActivity).apply {
                setMessage("正在识别...")
                setCancelable(false)
                show()
            }

            // 加载模型
            val modelLoaded = voskHelper.loadModel()
            if (!modelLoaded) {
                progressDialog.dismiss()
                toast("语音模型加载失败")
                handleRecognitionFailure()
                return@launch
            }

            // 识别
            val text = voskHelper.transcribe(wavFile)
            progressDialog.dismiss()
            wavFile.delete()

            if (text.isNullOrEmpty()) {
                toast("未识别到语音内容")
                handleRecognitionFailure()
                return@launch
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
        audioRecorder.release()
        voskHelper.release()
    }

    /** 可行内编辑的字段 */
    private enum class EditField { GROSS, TARE, PRICE, TYPE, DATETIME }

    companion object {
        // 录入模式值，与 Record.SOURCE_PHOTO / Record.SOURCE_VOICE 一致
        const val EXTRA_MODE_PHOTO = "PHOTO"
        const val EXTRA_MODE_VOICE = "VOICE"
    }
}
