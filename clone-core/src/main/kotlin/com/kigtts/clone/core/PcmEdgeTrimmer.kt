package com.kigtts.clone.core

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/** Trims the edges of a whole request, not individual engine callbacks. */
internal class PcmEdgeTrimmer(
    private val spillFile: () -> File,
    private val pendingMemoryBytes: Int = 64 * 1024,
) : AutoCloseable {
    private var rate = 0
    private var frame = ByteArray(0)
    private var filled = 0
    private var started = false
    private val edge = ArrayDeque<ByteArray>()
    private val pending = ByteArrayOutputStream()
    private var spill: PcmSpool? = null
    private var pendingBytes = 0L
    private val output = ByteArrayOutputStream(OUTPUT_BYTES)
    var leadingSamples = 0L; private set
    var trailingSamples = 0L; private set

    suspend fun accept(bytes: ByteArray, sampleRate: Int, emit: suspend (ByteArray, Int) -> Unit) {
        require(bytes.size % 2 == 0 && sampleRate in 8000..192000)
        if (rate == 0) {
            rate = sampleRate
            frame = ByteArray((rate / 100) * 2)
        }
        require(rate == sampleRate) { "同一请求的音频采样率不一致" }
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(frame.size - filled, bytes.size - offset)
            bytes.copyInto(frame, filled, offset, offset + count)
            offset += count
            filled += count
            if (filled == frame.size) {
                consumeFrame(filled, emit)
                filled = 0
            }
        }
        flush(emit)
    }

    suspend fun finish(emit: suspend (ByteArray, Int) -> Unit) {
        if (filled > 0) {
            consumeFrame(filled, emit)
            filled = 0
        }
        if (!started) {
            // If everything was too quiet to locate speech confidently, retain the original.
            // The caller separately rejects genuinely silent audio before finishing.
            replayPending(emit)
        } else {
            trailingSamples = (pendingBytes - edge.sumOf { it.size }) / 2
            for (chunk in edge) queue(chunk, chunk.size, emit)
            resetPending()
        }
        flush(emit)
    }

    private suspend fun consumeFrame(size: Int, emit: suspend (ByteArray, Int) -> Unit) {
        if (isQuiet(size)) {
            if (spill == null && pendingBytes + size > pendingMemoryBytes) {
                val spool = PcmSpool(spillFile())
                spill = spool
                spool.append(pending.toByteArray(), rate)
                pending.reset()
            }
            spill?.append(frame.copyOf(size), rate) ?: pending.write(frame, 0, size)
            pendingBytes += size
            if (!started) {
                if (edge.size == LEADING_FRAMES) edge.removeFirst()
                edge.addLast(frame.copyOf(size))
            } else if (edge.size < TRAILING_FRAMES) {
                edge.addLast(frame.copyOf(size))
            }
        } else {
            if (!started) {
                // Keep a little context for quiet consonants next to the detected onset.
                started = true
                leadingSamples = (pendingBytes - edge.sumOf { it.size }) / 2
                for (chunk in edge) queue(chunk, chunk.size, emit)
                resetPending()
            } else {
                // Now known to be an internal pause: restore it byte for byte.
                replayPending(emit)
            }
            queue(frame, size, emit)
        }
    }

    private fun isQuiet(size: Int): Boolean {
        var energy = 0L
        var differenceEnergy = 0L
        var previous = 0
        for (i in 0 until size step 2) {
            val value = ((frame[i].toInt() and 255) or (frame[i + 1].toInt() shl 8)).toShort().toInt()
            if (abs(value) >= PEAK_LIMIT) return false
            energy += value.toLong() * value
            if (i != 0) {
                val delta = (value - previous).toLong()
                differenceEnergy += delta * delta
            }
            previous = value
        }
        val samples = size / 2
        // A separate change-in-energy check protects low-amplitude fricatives and transients.
        return energy < samples * (RMS_LIMIT * RMS_LIMIT).toLong() &&
            differenceEnergy <= (samples - 1) * (DIFFERENCE_LIMIT * DIFFERENCE_LIMIT).toLong()
    }

    private suspend fun replayPending(emit: suspend (ByteArray, Int) -> Unit) {
        if (pendingBytes > 0) {
            val file = spill
            if (file != null) file.drain { pcm, _ -> queue(pcm, pcm.size, emit) }
            else pending.toByteArray().let { queue(it, it.size, emit) }
            resetPending()
        }
    }

    private fun resetPending() {
        spill?.close()
        spill = null
        pending.reset()
        pendingBytes = 0
        edge.clear()
    }

    private suspend fun queue(bytes: ByteArray, size: Int, emit: suspend (ByteArray, Int) -> Unit) {
        var offset = 0
        while (offset < size) {
            val count = minOf(OUTPUT_BYTES - output.size(), size - offset)
            output.write(bytes, offset, count)
            offset += count
            if (output.size() == OUTPUT_BYTES) flush(emit)
        }
    }

    private suspend fun flush(emit: suspend (ByteArray, Int) -> Unit) {
        if (output.size() > 0) {
            val bytes = output.toByteArray()
            output.reset()
            emit(bytes, rate)
        }
    }

    override fun close() = resetPending()

    companion object {
        private const val LEADING_FRAMES = 4
        private const val TRAILING_FRAMES = 6
        private const val RMS_LIMIT = 360
        private const val DIFFERENCE_LIMIT = 110
        private const val PEAK_LIMIT = 1100
        private const val OUTPUT_BYTES = 16 * 1024
    }
}
