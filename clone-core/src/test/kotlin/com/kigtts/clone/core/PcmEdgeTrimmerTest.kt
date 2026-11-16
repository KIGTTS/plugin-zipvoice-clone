package com.kigtts.clone.core

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmEdgeTrimmerTest {
    @get:Rule val dir = TemporaryFolder()
    private val rate = 24_000
    private val window = rate / 100
    private fun quiet(frames: Int) = pcm(frames * window) { ((it % 37) - 18) * 12 }
    private fun speech(frames: Int) = pcm(frames * window) { (3000 * sin(2 * PI * 200 * it / rate)).toInt() }
    private fun pcm(samples: Int, value: (Int) -> Int) = Wav.pcm16(FloatArray(samples) { value(it) / 32768f })

    @Test fun trimsOnlyRequestEdgesWithoutWaitingForLastChunk() = runBlocking {
        val temp = dir.root.resolve("queue.pcm")
        val output = ByteArrayOutputStream()
        val emit: suspend (ByteArray, Int) -> Unit = { bytes, hz ->
            assertEquals(rate, hz)
            assertTrue(bytes.size <= 16 * 1024)
            output.write(bytes)
        }
        val intro = quiet(32)
        val body = speech(18)
        val pause = quiet(120)
        val next = speech(9)
        val ending = quiet(43)
        PcmEdgeTrimmer({ temp }, pendingMemoryBytes = 4096).use { trim ->
            val first = intro + body
            for (i in first.indices step 514) trim.accept(first.copyOfRange(i, minOf(i+514, first.size)), rate, emit)
            val initialOutput = output.size()
            assertTrue(initialOutput > 0)
            trim.accept(pause, rate, emit)
            // A small partial speech frame may complete now, but no internal pause has been released yet.
            assertTrue(output.size() - initialOutput < window * 2)
            assertTrue(temp.isFile)
            trim.accept(next + ending, rate, emit)
            trim.finish(emit)
            val lead = 4 * window * 2
            val tail = 6 * window * 2
            val expected = intro.copyOfRange(intro.size - lead, intro.size) + body + pause + next + ending.copyOf(tail)
            assertArrayEquals(expected, output.toByteArray())
            assertEquals(28L * window, trim.leadingSamples)
            assertEquals(37L * window, trim.trailingSamples)
        }
        assertFalse(temp.exists())
    }

    @Test fun quietOnlyFallsBackAndShortWeakConsonantIsNotLost() = runBlocking {
        val temp = dir.root.resolve("queue.pcm")
        val out = ByteArrayOutputStream()
        val emit: suspend (ByteArray, Int) -> Unit = { data, _ -> out.write(data) }
        // Ambiguous low-level material stays intact; do not erase an unusually quiet voice.
        val uncertain = quiet(60)
        PcmEdgeTrimmer({ temp }, pendingMemoryBytes = 2048).use { trim ->
            trim.accept(uncertain, rate, emit)
            trim.finish(emit)
            assertArrayEquals(uncertain, out.toByteArray())
        }
        assertFalse(temp.exists())
        out.reset()
        // This signal is below the main RMS/peak threshold but has fricative-like variation.
        val consonant = pcm(80) { if (it % 2 == 0) 175 else -175 }
        PcmEdgeTrimmer({ temp }).use { trim ->
            trim.accept(quiet(12) + consonant, rate, emit)
            trim.finish(emit)
            assertTrue(out.toByteArray().takeLast(consonant.size).toByteArray().contentEquals(consonant))
            assertEquals(8L * window, trim.leadingSamples)
        }
    }

    @Test fun discardedStreamCleansTemporaryPause() = runBlocking {
        val temp = dir.root.resolve("queue.pcm")
        PcmEdgeTrimmer({ temp }, pendingMemoryBytes = 1024).use { trim ->
            trim.accept(speech(2) + quiet(40), rate) { _, _ -> }
            assertTrue(temp.isFile)
        }
        assertFalse(temp.exists())
    }
}
