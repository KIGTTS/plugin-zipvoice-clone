package com.kigtts.clone.zipvoice

import com.kigtts.clone.core.*
import com.kigtts.plugin.api.*
import com.kigtts.plugin.android.AndroidPluginEnvironment
import com.k2fsa.sherpa.onnx.*
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

internal class RequestGuard(private val active: () -> Boolean) {
    @Suppress("unused") fun isActive(): Boolean = active()
}

internal object QualcommNative {
    init { System.loadLibrary("sherpa-onnx-jni") }
    external fun libraryDirectory(): String
    external fun begin(mode: String, libraries: String, skels: String, guard: RequestGuard, fault: String, profiles: String)
    external fun setProbeSeed(seed: Int, trace: Boolean)
    external fun end(): String
}

class ZipVoicePlugin : ClonePlugin(CloneKind.ZIPVOICE) {
    override fun openRuntime(directory: File, options: CloneOptions): CloneRuntime =
        ZipVoiceRuntime(directory,options.copy(acceleration="cpu",provider="cpu"),
            environment.storage.directory("tmp/native"), environment.storage.directory("tmp/native"))

    override fun normalizeAvatar(source: File, destination: File) =
        AndroidVoicePackFiles.normalizeAvatar(source,destination)

    override fun shareVoiceZip(path: String, name: String): PluginOutcome {
        val context=(environment as? AndroidPluginEnvironment)?.context ?: return super.shareVoiceZip(path,name)
        val archive=environment.storage.file(path)
        return if (AndroidVoicePackFiles.share(context,archive,name)) {
            archive.delete()
            PluginOutcome.NoAction
        } else super.shareVoiceZip(path,name)
    }

}

class ZipVoiceRuntime(directory: File, options: CloneOptions, private val skels: File, private val profiles: File) : CloneRuntime {
    private var cachedVoiceId: String? = null
    private var cachedRevision: String? = null
    private var cachedReference: ReferenceAudio? = null
    private var report = JSONObject()
    override val diagnostics get() = report.toString()
    private val engine = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
        zipvoice = OfflineTtsZipVoiceModelConfig(
            tokens = File(directory, "tokens.txt").absolutePath,
            encoder = File(directory, "encoder.int8.onnx").absolutePath,
            decoder = File(directory, "decoder.int8.onnx").absolutePath,
            vocoder = File(directory, "vocos_24khz.onnx").absolutePath,
            lexicon = File(directory, "lexicon.txt").absolutePath
        ), numThreads = options.threads, provider = "cpu", debug = false
    ), maxNumSentences = 1))
    override fun generate(request: TtsRequest, voice: CloneVoice, options: CloneOptions, active: () -> Boolean, emit: (FloatArray, Int) -> Boolean) =
        generateWithTailPolicy(request, voice, options.copy(acceleration = "cpu", provider = "cpu", seed = -1L), true, active, emit)

    /** Internal A/B uses both policies; production always keeps the short first segment. */
    fun generateWithTailPolicy(request: TtsRequest, voice: CloneVoice, options: CloneOptions, mergeTail: Boolean,
                               active: () -> Boolean, emit: (FloatArray, Int) -> Boolean) {
        require(!mergeTail || options.acceleration in setOf("cpu", "gpu"))
        require(request.language.startsWith("zh") || request.language.startsWith("en")) { "该 ZipVoice 模型支持中文和英文" }
        val started = System.nanoTime()
        val reused = voice.id == cachedVoiceId && voice.referenceRevision == cachedRevision &&
            cachedReference != null && (voice.referenceAudio == null || voice.referenceAudio === cachedReference)
        val reference = if (reused) requireNotNull(cachedReference) else
            (voice.referenceAudio ?: Wav.read(voice.wav)).also {
                cachedVoiceId = voice.id
                cachedRevision = voice.referenceRevision
                cachedReference = it
            }
        val referenceReadMs = (System.nanoTime() - started) / 1_000_000.0
        val nativeStarted = System.nanoTime()
        var firstCallbackMs: Double? = null
        var callbackMs = 0.0
        val sampleRate = engine.sampleRate()
        var received = false
        QualcommNative.begin(options.acceleration, QualcommNative.libraryDirectory(), skels.absolutePath, RequestGuard(active), options.fallbackFault, profiles.absolutePath)
        var accelerationReport = JSONObject()
        val audio = try {
            if (options.seed in 0..Int.MAX_VALUE.toLong()) {
                QualcommNative.setProbeSeed(options.seed.toInt(), options.collectCheck)
            }
            engine.generateWithConfigAndCallback(request.text, GenerationConfig(
            speed = request.rate, referenceAudio = reference.samples, referenceSampleRate = reference.sampleRate,
            referenceText = voice.referenceText, numSteps = options.steps,
            extra = if (mergeTail) mapOf("kig_merge_comma_tail" to "1") else null
        )) { samples ->
            if (firstCallbackMs == null && samples.isNotEmpty()) firstCallbackMs = (System.nanoTime() - nativeStarted) / 1_000_000.0
            val callbackStart = System.nanoTime()
            received = received || samples.isNotEmpty()
            val proceed = if (active() && emit(samples, sampleRate)) 1 else 0
            callbackMs += (System.nanoTime() - callbackStart) / 1_000_000.0
            proceed
        } } finally {
            accelerationReport = JSONObject(QualcommNative.end())
            report = JSONObject().put("acceleration", accelerationReport)
        }
        if (!active()) throw CancellationException("已取消")
        report = JSONObject().put("referencePcmCached", reused).put("referenceReadMs", referenceReadMs)
            .put("nativeWallMs", (System.nanoTime() - nativeStarted) / 1_000_000.0).put("callbackMs", callbackMs)
            .put("firstNativeCallbackMs", firstCallbackMs).put("steps", options.steps).put("threads", options.threads)
            .put("providerRequested", options.provider).put("providerAssignmentVerified", false)
            .put("acceleration", accelerationReport)
            .put("segmentation", if (mergeTail) "short-first-merge-comma-tail" else "stock")
            .put("referenceEncodingSeparable", false).put("referenceEncodingNote", "sherpa Kotlin API includes reference feature preparation inside native generation")
        // Current API calls back once per sentence; never resend the accumulated result.
        if (active() && !received && audio.samples.isNotEmpty()) emit(audio.samples, audio.sampleRate)
        if (active() && !received && audio.samples.isEmpty()) throw EngineException(EngineError.INFERENCE, "ZipVoice 未生成音频")
    }
    override fun close() = engine.release()
}
