package com.farmtrade.app.util

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频录制工具：录制 16kHz 单声道 16bit PCM WAV 格式，适配 Whisper 识别。
 *
 * 使用方式：
 * - [startRecording] 开始录音
 * - [stopRecording] 停止录音，返回 WAV 文件
 * - [release] 释放资源
 */
class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    /**
     * 开始录音。
     * @param outputFile 输出 WAV 文件路径
     */
    fun startRecording(outputFile: File) {
        if (isRecording) return

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuf * 2
        )

        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            writeAudioToFile(outputFile)
        }.apply { start() }
    }

    /**
     * 停止录音并返回文件。
     */
    suspend fun stopRecording(): File? = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext null
        isRecording = false
        recordingThread?.join(2000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        return@withContext lastOutputFile
    }

    private var lastOutputFile: File? = null

    private fun writeAudioToFile(outputFile: File) {
        lastOutputFile = outputFile
        val data = ByteArray(bufferSize)
        val pcmData = mutableListOf<Byte>()

        while (isRecording) {
            val read = audioRecord?.read(data, 0, bufferSize) ?: 0
            if (read > 0) {
                pcmData.addAll(data.sliceArray(0 until read).toList())
            }
        }

        // 写入 WAV 文件（加 WAV 头）
        FileOutputStream(outputFile).use { fos ->
            writeWavHeader(fos, pcmData.size, sampleRate, 1, 16)
            fos.write(pcmData.toByteArray())
        }
    }

    /**
     * 写入 WAV 文件头。
     */
    private fun writeWavHeader(
        fos: FileOutputStream,
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = dataSize + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // Subchunk1Size
            putShort(1) // AudioFormat (1 = PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataSize)
        }
        fos.write(header.array())
    }

    fun release() {
        isRecording = false
        audioRecord?.release()
        audioRecord = null
    }
}
