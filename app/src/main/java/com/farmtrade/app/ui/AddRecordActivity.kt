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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.Record
import com.farmtrade.app.databinding.ActivityAddRecordBinding
import com.farmtrade.app.databinding.DialogCustomTypeBinding
import com.farmtrade.app.util.AudioRecorder
import com.farmtrade.app.util.OcrHelper
import com.farmtrade.app.util.VoiceParser
import com.farmtrade.app.util.VoskSpeechHelper
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 添加 / 编辑 记录界面
 *
 * 功能：
 * - 日期时间自动填充当前时间（只读）
 * - 买卖方向 买入/卖出 切换
 * - 类型 chips（来自 [DatabaseHelper.getAllTypes]） + "+ 自定义"
 * - 计量方式：按重量(公斤) / 按重量(斤) / 按数量
 * - 重量区：总重 / 车重（可拍照 OCR 识别） / 净重自动计算
 * - 单价 + 合计金额自动预览
 * - 顶部紫色语音输入条：语音识别后自动填写
 * - 保存：新增或更新记录
 * - 编辑模式：通过 [EXTRA_RECORD] 或 [EXTRA_RECORD_ID] 传入记录并预填
 */
class AddRecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddRecordBinding
    private val dbHelper by lazy { DatabaseHelper(this) }

    /** 编辑中的记录（新增时为 null） */
    private var editingRecord: Record? = null
    private var currentDirection = "买入"
    private var selectedType = ""
    private var measureMode = Record.MODE_WEIGHT_KG

    /** 0 = 总重, 1 = 车重 —— 标记当前拍照识别的目标输入框 */
    private var currentPhotoTarget = 0
    private var cameraImageUri: Uri? = null

    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** 监听重量/数量/单价变化，实时刷新净重与合计 */
    private val recalcWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = recalcNetAndTotal()
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera() else toast("需要相机权限才能拍照识别")
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onVoiceInputClicked() else toast("需要麦克风权限才能语音输入")
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraImageUri
            if (success && uri != null) {
                runOcr(uri)
            } else {
                toast("未拍摄照片")
            }
        }

    // ===== 本地语音识别 =====
    private val voskHelper by lazy { VoskSpeechHelper(this) }
    private val audioRecorder by lazy { AudioRecorder() }
    private var isRecording = false
    private var voiceTempFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ivBack.setOnClickListener { finish() }

        // 1. 日期时间（自动填充当前时间，只读）
        binding.tvDateTime.text = dateTimeFormat.format(Date())

        // 2. 买卖方向
        binding.toggleDirection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentDirection = when (checkedId) {
                    binding.btnBuy.id -> "买入"
                    binding.btnSell.id -> "卖出"
                    else -> currentDirection
                }
            }
        }

        // 3. 类型 chips
        binding.chipGroupType.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = group.findViewById<Chip>(checkedIds[0])
                selectedType = chip.text.toString()
            }
        }
        binding.chipCustom.setOnClickListener { showCustomTypeDialog() }

        // 4. 计量方式
        binding.toggleMeasureMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    binding.btnModeKg.id -> Record.MODE_WEIGHT_KG
                    binding.btnModeJin.id -> Record.MODE_WEIGHT_JIN
                    binding.btnModeQty.id -> Record.MODE_QUANTITY
                    else -> measureMode
                }
                applyMeasureMode(mode)
            }
        }

        // 5. 重量 / 数量 / 单价 输入监听
        binding.etGrossWeight.addTextChangedListener(recalcWatcher)
        binding.etTareWeight.addTextChangedListener(recalcWatcher)
        binding.etUnitPrice.addTextChangedListener(recalcWatcher)
        binding.etQuantity.addTextChangedListener(recalcWatcher)
        // 数量模式下单位名称变化时更新单价标签
        binding.etUnitName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (measureMode == Record.MODE_QUANTITY) {
                    binding.tvUnitPriceLabel.text = "单价（元/${s?.ifBlank { "个" }}）"
                }
            }
        })

        // 6. 拍照识别（总重 / 车重）
        binding.btnPhotoGross.setOnClickListener { requestCameraFor(0) }
        binding.btnPhotoTare.setOnClickListener { requestCameraFor(1) }

        // 7. 顶部语音输入条（点击开始/停止录音）
        binding.layoutVoiceInput.setOnClickListener { requestAudioPermissionAndStart() }
        binding.btnMic.setOnClickListener { requestAudioPermissionAndStart() }

        // 8. 保存
        binding.btnSave.setOnClickListener { saveRecord() }

        // 9. 编辑预填 or 初始化默认
        @Suppress("DEPRECATION")
        val passedRecord = intent.getParcelableExtra<Record>(EXTRA_RECORD)
        val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        when {
            passedRecord != null -> prefillFromRecord(passedRecord)
            recordId > 0 -> dbHelper.getAllRecords().firstOrNull { it.id == recordId }
                ?.let { prefillFromRecord(it) }
            else -> {
                buildTypeChips()
                binding.toggleDirection.check(binding.btnBuy.id)
                binding.toggleMeasureMode.check(binding.btnModeKg.id)
                applyMeasureMode(Record.MODE_WEIGHT_KG)
            }
        }
        recalcNetAndTotal()
    }

    // ==================== 类型 chips ====================

    /**
     * 构建类型 chips，[selected] 为需要选中的类型（编辑预填时使用）；
     * 若 [selected] 不在数据库中，会先持久化再加入。
     */
    private fun buildTypeChips(selected: String? = null) {
        binding.chipGroupType.removeAllViews()
        var types = dbHelper.getAllTypes()
        if (selected != null && selected !in types) {
            dbHelper.addCustomType(selected)
            types = dbHelper.getAllTypes()
        }
        val target = selected ?: types.firstOrNull()
        var chipToSelect: Chip? = null
        for (type in types) {
            val chip = Chip(this).apply {
                text = type
                isCheckable = true
                isClickable = true
                textSize = 16f
                id = View.generateViewId()
            }
            binding.chipGroupType.addView(chip)
            if (type == target) chipToSelect = chip
        }
        chipToSelect?.let {
            it.isChecked = true
            selectedType = it.text.toString()
        } ?: run {
            selectedType = types.firstOrNull().orEmpty()
        }
    }

    private fun showCustomTypeDialog() {
        val dv = DialogCustomTypeBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this).setView(dv.root).create()
        dv.btnAdd.setOnClickListener {
            val name = dv.etTypeName.text.toString().trim()
            if (name.isBlank()) {
                toast("请输入类型名称")
                return@setOnClickListener
            }
            if (dbHelper.addCustomType(name)) {
                buildTypeChips(name)
                toast("已添加：$name")
                dialog.dismiss()
            } else {
                toast("该类型已存在")
            }
        }
        dialog.show()
    }

    // ==================== 计量方式 ====================

    private fun applyMeasureMode(mode: String) {
        measureMode = mode
        if (mode == Record.MODE_QUANTITY) {
            binding.layoutWeightSection.visibility = View.GONE
            binding.layoutQuantitySection.visibility = View.VISIBLE
            binding.tvUnitPriceLabel.text = "单价（元/${binding.etUnitName.text.ifBlank { "个" }}）"
        } else {
            binding.layoutWeightSection.visibility = View.VISIBLE
            binding.layoutQuantitySection.visibility = View.GONE
            // 公斤和斤模式下，单价单位都是"元/斤"
            binding.tvUnitPriceLabel.text = "单价（元/斤）"
        }
        recalcNetAndTotal()
    }

    // ==================== 净重 / 合计 自动计算 ====================

    private fun recalcNetAndTotal() {
        if (!this::binding.isInitialized) return
        val gross = binding.etGrossWeight.text.toString().toDoubleOrNull() ?: 0.0
        val tare = binding.etTareWeight.text.toString().toDoubleOrNull() ?: 0.0
        val qty = binding.etQuantity.text.toString().toDoubleOrNull() ?: 0.0
        val price = binding.etUnitPrice.text.toString().toDoubleOrNull() ?: 0.0
        if (measureMode == Record.MODE_QUANTITY) {
            binding.tvNetWeight.text = Record.formatNumber(qty)
            binding.tvTotalPreview.text = "￥${Record.formatMoney(qty * price)}"
        } else {
            val net = (gross - tare).coerceAtLeast(0.0)
            binding.tvNetWeight.text = Record.formatNumber(net)
            // 公斤和斤模式下，单价都是"元/斤"
            // 公斤模式：总额 = 净重(公斤) × 2 × 单价
            // 斤模式：总额 = 净重(斤) × 单价
            val total = if (measureMode == Record.MODE_WEIGHT_KG) {
                net * 2 * price
            } else {
                net * price
            }
            binding.tvTotalPreview.text = "￥${Record.formatMoney(total)}"
        }
    }

    // ==================== 拍照 OCR ====================

    private fun requestCameraFor(target: Int) {
        currentPhotoTarget = target
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        cameraImageUri = createImageUri()
        if (cameraImageUri == null) {
            toast("无法创建图片文件")
            return
        }
        try {
            takePictureLauncher.launch(cameraImageUri!!)
        } catch (e: Exception) {
            toast("无法启动相机")
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
                    if (currentPhotoTarget == 0) {
                        binding.etGrossWeight.setText(number)
                    } else {
                        binding.etTareWeight.setText(number)
                    }
                    toast("识别到：$number")
                } else {
                    toast("未识别到有效数字")
                }
            } else {
                toast("OCR 识别失败")
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

    // ==================== 语音输入（本地 Vosk 离线识别） ====================

    private fun requestAudioPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onVoiceInputClicked()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * 语音输入条被点击：
     * - 未在录音 → 检查模型 → 开始录音
     * - 正在录音 → 停止录音 → 识别 → 填充结果
     */
    private fun onVoiceInputClicked() {
        if (isRecording) {
            stopRecordingAndRecognize()
        } else {
            checkModelAndStartRecording()
        }
    }

    private fun checkModelAndStartRecording() {
        if (voskHelper.isModelReady()) {
            startRecording()
        } else if (voskHelper.hasAssetsModel()) {
            // APK 内置了模型，直接从 assets 复制（不耗流量，速度快）
            copyModelFromAssetsAndStart()
        } else {
            // 模型未下载，询问用户是否下载
            MaterialAlertDialogBuilder(this)
                .setTitle("下载语音模型")
                .setMessage("首次使用语音输入需要下载离线识别模型（约 40MB），下载后无需联网即可使用。是否现在下载？")
                .setPositiveButton("下载") { _, _ -> downloadModelAndStart() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun copyModelFromAssetsAndStart() {
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
                startRecording()
            } else {
                toast("模型初始化失败")
            }
        }
    }

    private fun downloadModelAndStart() {
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
                startRecording()
            } else {
                toast("模型下载失败，请检查网络后重试")
            }
        }
    }

    private fun startRecording() {
        val outputFile = File(cacheDir, "voice_${System.currentTimeMillis()}.wav")
        voiceTempFile = outputFile
        audioRecorder.startRecording(outputFile)
        isRecording = true
        // 更新 UI 提示正在录音
        binding.tvVoiceHint.text = "🎤 正在录音，点击停止..."
        binding.layoutVoiceInput.setBackgroundColor(0xFFFFEBEE.toInt())
    }

    private fun stopRecordingAndRecognize() {
        binding.tvVoiceHint.text = "点击说话，自动识别填写"
        binding.layoutVoiceInput.setBackgroundColor(0xFFF3E5F5.toInt())

        lifecycleScope.launch {
            val wavFile = audioRecorder.stopRecording()
            isRecording = false

            if (wavFile == null) {
                toast("录音失败")
                return@launch
            }

            // 显示识别中提示
            val progressDialog = ProgressDialog(this@AddRecordActivity).apply {
                setMessage("正在识别...")
                setCancelable(false)
                show()
            }

            // 加载模型（首次加载可能需要几秒）
            val modelLoaded = voskHelper.loadModel()
            if (!modelLoaded) {
                progressDialog.dismiss()
                toast("语音模型加载失败")
                return@launch
            }

            // 进行识别
            val text = voskHelper.transcribe(wavFile)
            progressDialog.dismiss()

            // 清理临时文件
            wavFile.delete()
            voiceTempFile = null

            if (!text.isNullOrEmpty()) {
                val parsed = VoiceParser.parse(text)
                applyVoiceResult(parsed)
                toast("语音识别：$text")
            } else {
                toast("未识别到语音内容，请说得清晰一些")
            }
        }
    }

    /** 将语音解析结果应用到对应输入项 */
    private fun applyVoiceResult(r: VoiceParser.ParseResult) {
        r.direction?.let { selectDirection(it) }
        r.type?.let { selectType(it) }
        r.measureMode?.let { selectMeasureMode(it) }
        r.grossWeight?.let { binding.etGrossWeight.setText(Record.formatNumber(it)) }
        r.vehicleWeight?.let { binding.etTareWeight.setText(Record.formatNumber(it)) }
        r.quantity?.let { binding.etQuantity.setText(Record.formatNumber(it)) }
        r.unitName?.let { binding.etUnitName.setText(it) }
        r.unitPrice?.let { binding.etUnitPrice.setText(Record.formatNumber(it)) }
        recalcNetAndTotal()
    }

    private fun selectDirection(dir: String) {
        currentDirection = dir
        binding.toggleDirection.check(if (dir == "卖出") binding.btnSell.id else binding.btnBuy.id)
    }

    private fun selectType(type: String) {
        selectedType = type
        buildTypeChips(type)
    }

    private fun selectMeasureMode(mode: String) {
        binding.toggleMeasureMode.check(
            when (mode) {
                Record.MODE_WEIGHT_JIN -> binding.btnModeJin.id
                Record.MODE_QUANTITY -> binding.btnModeQty.id
                else -> binding.btnModeKg.id
            }
        )
        applyMeasureMode(mode)
    }

    // ==================== 编辑预填 ====================

    private fun prefillFromRecord(r: Record) {
        editingRecord = r
        binding.tvDateTime.text = r.dateTime.ifBlank { dateTimeFormat.format(Date()) }
        currentDirection = r.direction
        binding.toggleDirection.check(if (r.direction == "卖出") binding.btnSell.id else binding.btnBuy.id)
        selectedType = r.type
        buildTypeChips(r.type)
        binding.toggleMeasureMode.check(
            when (r.measureMode) {
                Record.MODE_WEIGHT_JIN -> binding.btnModeJin.id
                Record.MODE_QUANTITY -> binding.btnModeQty.id
                else -> binding.btnModeKg.id
            }
        )
        applyMeasureMode(r.measureMode)
        binding.etGrossWeight.setText(Record.formatNumber(r.grossWeight))
        binding.etTareWeight.setText(Record.formatNumber(r.vehicleWeight))
        binding.etQuantity.setText(Record.formatNumber(r.quantity))
        binding.etUnitName.setText(r.unitName)
        binding.etUnitPrice.setText(Record.formatNumber(r.unitPrice))
        recalcNetAndTotal()
    }

    // ==================== 保存 ====================

    private fun saveRecord() {
        val gross = binding.etGrossWeight.text.toString().toDoubleOrNull() ?: 0.0
        val tare = binding.etTareWeight.text.toString().toDoubleOrNull() ?: 0.0
        val qty = binding.etQuantity.text.toString().toDoubleOrNull() ?: 0.0
        val price = binding.etUnitPrice.text.toString().toDoubleOrNull() ?: 0.0

        if (selectedType.isBlank()) {
            toast("请选择类型")
            return
        }
        if (price <= 0) {
            toast("请输入单价")
            return
        }
        if (measureMode == Record.MODE_QUANTITY) {
            if (qty <= 0) {
                toast("请输入数量")
                return
            }
        } else {
            if (gross <= 0) {
                toast("请输入总重")
                return
            }
        }

        val unitName = when (measureMode) {
            Record.MODE_WEIGHT_KG -> "公斤"
            Record.MODE_WEIGHT_JIN -> "斤"
            else -> binding.etUnitName.text.toString().trim().ifBlank { "个" }
        }

        val record = (editingRecord?.copy() ?: Record().also { it.source = Record.SOURCE_MANUAL }).apply {
            dateTime = binding.tvDateTime.text.toString()
            direction = currentDirection
            type = selectedType
            measureMode = this@AddRecordActivity.measureMode
            grossWeight = gross
            vehicleWeight = tare
            quantity = qty
            this.unitName = unitName
            unitPrice = price
            netWeight = calculateNetWeight()
            totalAmount = calculateTotalAmount()
        }

        if (record.id > 0) {
            dbHelper.updateRecord(record)
            toast("修改成功")
        } else {
            record.id = dbHelper.insertRecord(record)
            toast("保存成功")
        }
        setResult(RESULT_OK)
        finish()
    }

    // ==================== utils ====================

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.release()
        voskHelper.release()
    }

    companion object {
        /** Parcelable Record，编辑或由快速记录"全部修改"跳转时携带 */
        const val EXTRA_RECORD = "extra_record"
        /** 编辑现有记录的 id */
        const val EXTRA_RECORD_ID = "extra_record_id"
    }
}
