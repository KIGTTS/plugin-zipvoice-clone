package com.kigtts.clone.core

import com.kigtts.plugin.api.PluginStorage
import com.kigtts.plugin.api.TtsProfile
import com.kigtts.plugin.api.TtsProfileVersions
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val MIN_SYNTHESIS_SETTING = 1
internal const val MAX_SYNTHESIS_SETTING = 8
internal val SYNTHESIS_SETTINGS = MIN_SYNTHESIS_SETTING..MAX_SYNTHESIS_SETTING

/** Native SDK sliders send decimal strings (e.g. 4.0); only discrete stops are accepted. */
internal fun synthesisSliderValue(value: String): Int {
    val number = requireNotNull(value.trim().toDoubleOrNull()) { "请选 1–8 的整数" }
    require(number.isFinite()) { "请选 1–8 的整数" }
    val nearest = number.roundToInt()
    require(nearest in SYNTHESIS_SETTINGS && abs(nearest - number) < 1e-4) { "请选 1–8 的整数" }
    return nearest
}

/** Changing either profile never writes the other profile's setting. */
internal class CloneQualitySettings(storage: PluginStorage) {
    private val realtime = storage.file("config/steps.txt")
    private val prebake = storage.file("config/prebake-steps.txt")
    @Volatile var realtimeSteps = read(realtime, 2)
        private set
    @Volatile var prebakeSteps = read(prebake, 4)
        private set

    fun save(profile: TtsProfile, steps: Int) {
        require(steps in SYNTHESIS_SETTINGS)
        if (profile == TtsProfile.PREBAKE) {
            atomicText(prebake, steps.toString())
            prebakeSteps = steps
        } else {
            atomicText(realtime, steps.toString())
            realtimeSteps = steps
        }
    }

    fun versions(actualRealtimeSteps: Int = realtimeSteps) =
        TtsProfileVersions("zipvoice-steps:$actualRealtimeSteps:edges-v1", "zipvoice-steps:$prebakeSteps:edges-v1")

    fun forRequest(base: CloneOptions, profile: TtsProfile): CloneOptions =
        if (profile == TtsProfile.PREBAKE) base.copy(steps = prebakeSteps) else base

    private fun read(file: java.io.File, default: Int) =
        file.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.takeIf { it in SYNTHESIS_SETTINGS } ?: default
}
