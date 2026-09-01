package com.farmtrade.app.util

import android.app.ProgressDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

/**
 * 语音输入控制器：封装"模型准备（assets 复制 / 网络下载）→ 录音 → Vosk 识别"完整流程，
 * 供 AddRecordActivity / QuickRecordActivity 复用。
 * 权限申请、录音提示与识别结果的字段填充由页面通过 [Callbacks] 自行处理。
 */
class SpeechInputController(
    private val activity: AppCompatActivity,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        /** 开始录音，[outputFile] 为本次录音文件 */
        fun onRecordingStarted(outputFile: File)

        /** 识别完成；[text] 为识别原文，可能为 null 或空 */
        fun onRecognized(text: String?)

        /** 模型准备 / 识别失败 */
        fun onError(message: String)

        /** 用户在"下载语音模型"弹窗点了取消 */
        fun onDownloadCancelled() {}
    }

    private val vosk = VoskSpeechHelper(activity)
    private val recorder = AudioRecorder()
    private var recording = false

    /** 当前是否正在录音 */
    fun isRecording(): Boolean = recording

    /** 语音入口：模型就绪则直接录音，否则先准备模型 */
    fun startFlow() {
        when {
            vosk.isModelReady() -> startRecording()
            vosk.hasAssetsModel() -> prepare("正在初始化语音模型...", "模型初始化失败") { vosk.copyModelFromAssets(it) }
            else -> askDownload()
        }
    }

    private fun askDownload() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("下载语音模型")
            .setMessage("首次使用语音输入需要下载离线识别模型（约 40MB），下载后无需联网即可使用。是否现在下载？")
            .setPositiveButton("下载") { _, _ ->
                prepare("正在下载语音模型...", "模型下载失败，请检查网络后重试") { vosk.downloadModel(it) }
            }
            .setNegativeButton("取消") { _, _ -> callbacks.onDownloadCancelled() }
            .show()
    }

    /** 带 ProgressDialog 的模型准备，完成后自动开始录音 */
    private fun prepare(progressMessage: String, errorMessage: String, action: suspend ((Int) -> Unit) -> Boolean) {
        val progressDialog = ProgressDialog(activity).apply {
            setMessage(progressMessage)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            show()
        }
        activity.lifecycleScope.launch {
            val success = action { progressDialog.progress = it }
            progressDialog.dismiss()
            if (success) startRecording() else callbacks.onError(errorMessage)
        }
    }

    private fun startRecording() {
        val output = File(activity.cacheDir, "voice_${System.currentTimeMillis()}.wav")
        recorder.startRecording(output)
        recording = true
        callbacks.onRecordingStarted(output)
    }

    /** 停止录音并识别，结果通过 [Callbacks.onRecognized] 返回 */
    fun stopAndRecognize() {
        activity.lifecycleScope.launch {
            val file = recorder.stopRecording()
            recording = false
            if (file == null) {
                callbacks.onError("录音失败")
                return@launch
            }
            val progressDialog = ProgressDialog(activity).apply {
                setMessage("正在识别...")
                setCancelable(false)
                show()
            }
            if (!vosk.loadModel()) {
                progressDialog.dismiss()
                callbacks.onError("语音模型加载失败")
                return@launch
            }
            val text = vosk.transcribe(file)
            progressDialog.dismiss()
            file.delete()
            callbacks.onRecognized(text)
        }
    }

    fun release() {
        recorder.release()
        vosk.release()
    }
}
