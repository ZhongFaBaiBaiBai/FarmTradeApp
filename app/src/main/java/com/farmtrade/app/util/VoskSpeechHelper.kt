package com.farmtrade.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Vosk 本地离线语音识别工具。
 *
 * 功能：
 * - 管理 Vosk 模型文件（首次使用需下载）
 * - 加载模型到内存
 * - 将 WAV 音频文件转写为文字
 *
 * 模型说明：
 * - 使用 vosk-model-small-cn-0.22（约 40MB，轻量快速）
 * - 完全离线，无需联网
 */
class VoskSpeechHelper(private val context: Context) {

    companion object {
        private const val TAG = "VoskSpeechHelper"

        // 中文小模型下载地址（约 40MB）
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

        // 模型目录名
        private const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"

        // 采样率
        private const val SAMPLE_RATE = 16000f
    }

    private var model: Model? = null
    private var isModelLoaded = false

    init {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
        } catch (e: Exception) {
            // 忽略日志级别设置失败
        }
    }

    /**
     * 获取模型目录。
     */
    fun getModelDir(): File {
        return File(context.filesDir, MODEL_DIR_NAME)
    }

    /**
     * 检查模型是否已下载并解压。
     */
    fun isModelReady(): Boolean {
        val modelDir = getModelDir()
        // 检查 am 文件夹是否存在（Vosk 模型的标志）
        val amDir = File(modelDir, "am")
        return modelDir.exists() && amDir.exists() && amDir.isDirectory
    }

    /**
     * 检查 assets 中是否有内置模型。
     */
    fun hasAssetsModel(): Boolean {
        return try {
            val assets = context.assets.list("vosk-model")
            !assets.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 assets 中复制模型到本地文件目录（首次安装时使用）。
     * @param onProgress 进度回调 0~100
     * @return 是否成功
     */
    suspend fun copyModelFromAssets(onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val modelDir = getModelDir()
                if (!modelDir.exists()) modelDir.mkdirs()

                val assetManager = context.assets
                val basePath = "vosk-model"
                val mainHandler = Handler(Looper.getMainLooper())

                // 递归复制 assets 中的模型文件
                val totalFiles = countAssetsFiles(basePath)
                var copied = 0

                copyAssetsDir(basePath, modelDir.absolutePath) {
                    copied++
                    if (totalFiles > 0) {
                        val progress = (copied * 100 / totalFiles).toInt()
                        mainHandler.post { onProgress(progress.coerceAtMost(99)) }
                    }
                }

                mainHandler.post { onProgress(100) }
                Log.d(TAG, "从 assets 复制模型完成: ${modelDir.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "从 assets 复制模型失败", e)
                false
            }
        }

    /**
     * 统计 assets 目录下的文件总数。
     */
    private fun countAssetsFiles(path: String): Int {
        val assetManager = context.assets
        var count = 0
        try {
            val list = assetManager.list(path) ?: return 0
            for (item in list) {
                val fullPath = if (path.isEmpty()) item else "$path/$item"
                // 判断是文件还是目录（尝试 list 一下，空的就是文件）
                val subList = assetManager.list(fullPath)
                if (subList.isNullOrEmpty()) {
                    count++
                } else {
                    count += countAssetsFiles(fullPath)
                }
            }
        } catch (e: Exception) {
            // 忽略
        }
        return count
    }

    /**
     * 递归复制 assets 目录到目标路径。
     */
    private fun copyAssetsDir(
        srcPath: String,
        destPath: String,
        onFileCopied: () -> Unit
    ) {
        val assetManager = context.assets
        val files = assetManager.list(srcPath) ?: return

        for (filename in files) {
            val src = if (srcPath.isEmpty()) filename else "$srcPath/$filename"
            val dest = "$destPath/$filename"

            val subList = try {
                assetManager.list(src)
            } catch (e: Exception) {
                null
            }

            if (subList.isNullOrEmpty()) {
                // 文件
                val destFile = File(dest)
                destFile.parentFile?.mkdirs()
                assetManager.open(src).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                onFileCopied()
            } else {
                // 目录
                val destDir = File(dest)
                if (!destDir.exists()) destDir.mkdirs()
                copyAssetsDir(src, dest, onFileCopied)
            }
        }
    }

    /**
     * 下载并解压模型文件。
     * @param onProgress 进度回调 0~100
     * @return 是否成功
     */
    suspend fun downloadModel(onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val modelDir = getModelDir()
                val zipFile = File(context.cacheDir, "vosk-model.zip")

                // 下载 ZIP
                Log.d(TAG, "开始下载模型: $MODEL_URL")
                val url = java.net.URL(MODEL_URL)
                val connection = url.openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 120000
                val contentLength = connection.contentLength

                connection.getInputStream().use { input ->
                    FileOutputStream(zipFile).use { output ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            totalRead += read
                            if (contentLength > 0) {
                                // 下载进度占 70%
                                val progress = (totalRead * 70 / contentLength).toInt()
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                        }
                    }
                }

                Log.d(TAG, "下载完成，开始解压")

                // 解压 ZIP
                if (!modelDir.exists()) modelDir.mkdirs()
                ZipInputStream(FileInputStream(zipFile)).use { zis ->
                    var entry = zis.nextEntry
                    var entryCount = 0
                    while (entry != null) {
                        val entryName = entry.name
                        // 去掉顶层目录名，直接解压到 modelDir
                        val relativeName = entryName.substringAfter('/')
                        if (relativeName.isNotEmpty()) {
                            val outFile = File(modelDir, relativeName)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                        }
                        entryCount++
                        // 解压进度占 70%~100%
                        val unzipProgress = 70 + (entryCount % 100) * 30 / 100
                        withContext(Dispatchers.Main) { onProgress(unzipProgress.coerceAtMost(99)) }
                        entry = zis.nextEntry
                    }
                }

                // 删除临时 zip 文件
                zipFile.delete()
                withContext(Dispatchers.Main) { onProgress(100) }
                Log.d(TAG, "模型下载并解压完成: ${modelDir.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "模型下载失败", e)
                false
            }
        }

    /**
     * 加载模型（首次调用较慢，之后可复用）。
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.Default) {
        if (isModelLoaded && model != null) return@withContext true
        if (!isModelReady()) {
            Log.e(TAG, "模型不存在，请先下载")
            return@withContext false
        }

        try {
            val modelPath = getModelDir().absolutePath
            Log.d(TAG, "正在加载模型: $modelPath")
            model = Model(modelPath)
            isModelLoaded = true
            Log.d(TAG, "模型加载成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败", e)
            false
        }
    }

    /**
     * 转写 WAV 音频文件为文字。
     * @param wavFile WAV 格式音频（16kHz, mono, 16bit PCM）
     * @return 识别结果文本，失败返回 null
     */
    suspend fun transcribe(wavFile: File): String? = withContext(Dispatchers.Default) {
        if (!isModelLoaded) {
            val loaded = loadModel()
            if (!loaded) return@withContext null
        }

        val currentModel = model ?: return@withContext null

        try {
            val recognizer = Recognizer(currentModel, SAMPLE_RATE)

            FileInputStream(wavFile).use { fis ->
                // 跳过 WAV 头（44 字节）
                fis.skip(44)

                // Vosk acceptWaveForm 接收 byte[] (16bit PCM 小端序)
                val buffer = ByteArray(4096)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    recognizer.acceptWaveForm(buffer, read)
                }
            }

            // 获取最终结果
            val resultJson = recognizer.finalResult
            val jsonObject = JSONObject(resultJson)
            val text = jsonObject.optString("text", "")

            try {
                recognizer.close()
            } catch (e: Exception) {
                // 忽略关闭异常
            }

            Log.d(TAG, "识别结果: $text")
            text.trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "转写失败", e)
            null
        }
    }

    /**
     * 释放资源。
     */
    fun release() {
        try {
            model?.close()
        } catch (e: Exception) {
            // 忽略
        }
        model = null
        isModelLoaded = false
    }
}
