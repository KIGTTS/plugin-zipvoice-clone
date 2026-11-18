package com.kigtts.clone.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

data class ReferenceAudio(val samples: FloatArray, val sampleRate: Int)

object Wav {
    fun wrapPcm(pcm: File, destination: File, sampleRate: Int) {
        require(pcm.length() % 2 == 0L && pcm.length() <= Int.MAX_VALUE - 44 && sampleRate in 8000..192000)
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.length().toInt()).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.length().toInt()).array()
        destination.outputStream().use { output -> output.write(header); pcm.inputStream().use { it.copyTo(output) } }
    }
    /** Deliberately bounded: PCM16 mono WAV, 8–48 kHz, 1–30 seconds. */
    fun read(file: File): ReferenceAudio = RandomAccessFile(file, "r").use { input ->
        require(input.length() in 44..(3L * 1024 * 1024)) { throw CloneMessageException("wav.file_size", "参考音频过大或不完整") }
        fun tag(): String = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        fun u16(): Int = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(input.readShort()))
        fun u32(): Long = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
        require(tag() == "RIFF") { throw CloneMessageException("wav.pcm_only", "仅支持 PCM16 WAV，请先转换音频") }
        val riffEnd = u32() + 8
        require(riffEnd <= input.length() && tag() == "WAVE") { throw CloneMessageException("wav.header", "WAV 头部损坏") }
        var sampleRate = 0
        var dataOffset = -1L
        var dataSize = 0L
        while (input.filePointer + 8 <= riffEnd) {
            val id = tag()
            val size = u32()
            val start = input.filePointer
            require(start + size <= riffEnd) { throw CloneMessageException("wav.incomplete", "WAV 数据不完整") }
            when (id) {
                "fmt " -> {
                    require(size >= 16 && u16() == 1 && u16() == 1) { throw CloneMessageException("wav.mono", "参考音频必须是 PCM16 单声道 WAV") }
                    sampleRate = u32().toInt()
                    val byteRate = u32()
                    require(u16() == 2 && u16() == 16 && byteRate == sampleRate * 2L) { throw CloneMessageException("wav.pcm", "参考音频必须是 PCM16") }
                }
                "data" -> { require(dataOffset < 0) { throw CloneMessageException("wav.chunks", "WAV 含多个数据区") }; dataOffset = start; dataSize = size }
            }
            input.seek(start + size + (size and 1))
        }
        require(sampleRate in 8_000..48_000 && dataOffset >= 0 && dataSize % 2 == 0L) { throw CloneMessageException("wav.invalid", "WAV 格式无效") }
        require(dataSize / 2 in sampleRate.toLong()..(sampleRate * 30L)) { throw CloneMessageException("wav.duration", "参考音频需要 1–30 秒，建议 3–10 秒") }
        input.seek(dataOffset)
        val samples = FloatArray((dataSize / 2).toInt()) { java.lang.Short.reverseBytes(input.readShort()) / 32768f }
        require(samples.any { kotlin.math.abs(it) > 0.0005f }) { throw CloneMessageException("wav.silent", "参考音频只有静音") }
        ReferenceAudio(samples, sampleRate)
    }

    fun pcm16(samples: FloatArray): ByteArray = ByteArray(samples.size * 2).also { output ->
        samples.forEachIndexed { i, sample ->
            require(sample.isFinite()) { "引擎输出非有限音频" }
            val value = (sample.coerceIn(-1f, 1f) * 32768).roundToInt().coerceIn(-32768, 32767)
            output[i * 2] = value.toByte(); output[i * 2 + 1] = (value shr 8).toByte()
        }
    }
}

/** Carries fractional position across native chunks. Rate change also changes pitch. */
class PcmRate(private val rate: Float) {
    private var previous: Float? = null
    private var position = 0.0
    init { require(rate.isFinite() && rate in 0.25f..4f) }
    fun convert(input: FloatArray): FloatArray {
        if (rate == 1f || input.isEmpty()) return input
        val data = previous?.let { floatArrayOf(it) + input } ?: input
        val output = ArrayList<Float>()
        while (position < data.size - 1) {
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            output += data[index] * (1 - fraction) + data[index + 1] * fraction
            position += rate
        }
        position -= data.size - 1
        previous = data.last()
        return output.toFloatArray()
    }
}
