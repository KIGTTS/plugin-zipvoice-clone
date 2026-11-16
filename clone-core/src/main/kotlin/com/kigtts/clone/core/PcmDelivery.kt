package com.kigtts.clone.core

import java.io.File
import kotlin.math.abs

/** Statistics on the actual PCM16 payload, independent of optional audio recording. */
class PcmStats {
    var samples = 0L; private set
    var effectiveSamples = 0L; private set
    var peak = 0; private set
    fun accept(bytes: ByteArray) {
        require(bytes.size % 2 == 0)
        for (i in bytes.indices step 2) {
            val value = abs(((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt())
            peak = maxOf(peak, value)
            if (value > 16) effectiveSamples++
        }
        samples += bytes.size / 2
    }
    fun requireSpeech() {
        if (samples == 0L || effectiveSamples == 0L) throw com.kigtts.plugin.api.EngineException(
            com.kigtts.plugin.api.EngineError.INFERENCE, "引擎未生成有效语音（空音频或全静音），请重试或检查文本与参考声音")
    }
}

/** Disk-backed prebuffer. A failed/cancelled generation is never drained to the host. */
class PcmSpool(private val file: File, private val maximumBytes: Long = 128L * 1024 * 1024) : AutoCloseable {
    private val output = file.outputStream().buffered()
    private var rate = 0
    private var bytes = 0L
    fun append(pcm: ByteArray, sampleRate: Int) {
        require(sampleRate in 8000..192000 && pcm.size % 2 == 0)
        require(rate == 0 || rate == sampleRate) { "同一请求的音频采样率不一致" }
        require(bytes + pcm.size <= maximumBytes) { "连续播放缓冲超过限制，请缩短文本" }
        rate = sampleRate; output.write(pcm); bytes += pcm.size
    }
    suspend fun drain(emit: suspend (ByteArray, Int) -> Unit) {
        output.close()
        java.io.DataInputStream(file.inputStream().buffered()).use { input ->
            val block = ByteArray(16384)
            var remaining = bytes
            while (remaining > 0) {
                val count = minOf(block.size.toLong(), remaining).toInt()
                input.readFully(block, 0, count)
                emit(block.copyOf(count), rate)
                remaining -= count
            }
        }
    }
    override fun close() { try { output.close() } finally { file.delete() } }
}
