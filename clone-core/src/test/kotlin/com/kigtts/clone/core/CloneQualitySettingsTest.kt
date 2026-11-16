package com.kigtts.clone.core

import com.kigtts.plugin.api.PluginStorage
import com.kigtts.plugin.api.TtsProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloneQualitySettingsTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun preservesExistingRealtimeAndPersistsEachProfileIndependently() {
        val storage = PluginStorage(directory.newFolder())
        assertEquals(2, CloneQualitySettings(storage).realtimeSteps)
        assertEquals(4, CloneQualitySettings(storage).prebakeSteps)
        atomicText(storage.file("config/steps.txt"), "4")
        val quality = CloneQualitySettings(storage)
        val original = quality.versions()
        assertEquals(4, quality.realtimeSteps)
        quality.save(TtsProfile.REALTIME, 2)
        assertEquals(original.prebake, quality.versions().prebake)
        val fast = quality.versions().realtime
        quality.save(TtsProfile.PREBAKE, 2)
        assertEquals(fast, quality.versions().realtime)
        val restored = CloneQualitySettings(storage)
        assertEquals(2, restored.realtimeSteps)
        assertEquals(2, restored.prebakeSteps)
        assertNotEquals(original.prebake, restored.versions().prebake)
    }

    @Test fun appliesStepCountPerRequestWithoutSwitchingTheModelOptions() {
        val quality = CloneQualitySettings(PluginStorage(directory.newFolder()))
        val current = CloneOptions(threads = 4, steps = quality.realtimeSteps)
        assertSame(current, quality.forRequest(current, TtsProfile.REALTIME))
        assertEquals(current.copy(steps = 4), quality.forRequest(current, TtsProfile.PREBAKE))
        assertEquals(2, current.steps)
    }
    @Test fun persistsAllEightDiscreteStopsAndRejectsOtherValues() {
        val storage = PluginStorage(directory.newFolder())
        val quality = CloneQualitySettings(storage)
        for (n in 1..8) {
            quality.save(TtsProfile.REALTIME, n)
            quality.save(TtsProfile.PREBAKE, 9 - n)
            val restored = CloneQualitySettings(storage)
            assertEquals(n, restored.realtimeSteps)
            assertEquals(9 - n, restored.prebakeSteps)
            assertEquals(n, synthesisSliderValue("$n.0"))
        }
        for (value in listOf("0", "9", "NaN", "Infinity", "3.25", "", "5 步", "999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { synthesisSliderValue(value) }
        }
        assertEquals(8, synthesisSliderValue("7.9999995"))
        assertThrows(IllegalArgumentException::class.java) { quality.save(TtsProfile.REALTIME, 9) }
        assertEquals(8, CloneQualitySettings(storage).realtimeSteps)
        assertEquals(1, CloneQualitySettings(storage).prebakeSteps)
    }

}
