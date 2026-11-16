package com.kigtts.clone.core

import com.kigtts.plugin.api.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class CloneOptions(val threads: Int = 2, val steps: Int = 4,
    val chunkSeconds: Float = 0.32f, val leftContextSeconds: Float = 2f,
    val seed: Long = -1, val batch: Boolean = false, val collectCheck: Boolean = false,
    val maxTokens: Int = 512, val forceGeneric: Boolean = false, val buffered: Boolean = false,
    val provider: String = "cpu", val acceleration: String = "cpu", val fallbackFault: String = "")

interface CloneRuntime : AutoCloseable {
    val diagnostics: String get() = "{}"
    /** Blocking native call. emit returns false when the request is cancelled. */
    fun generate(request: TtsRequest, voice: CloneVoice, options: CloneOptions, active: () -> Boolean, emit: (FloatArray, Int) -> Boolean)
}

abstract class ClonePlugin(private val kind: CloneKind) : KigPlugin {
    override val descriptor = PluginDescriptor("engine.${kind.key}-clone",
        if (kind == CloneKind.ZIPVOICE) "语音克隆引擎" else "${kind.title} 离线克隆",
        "离线合成与语音包管理", version = if (kind == CloneKind.ZIPVOICE) "0.3.6" else "0.1.4", license = "MIT")
    protected lateinit var environment: PluginEnvironment
    private lateinit var store: CloneStore
    private lateinit var voiceActions: CloneVoiceActions
    private var quality: CloneQualitySettings? = null
    private val access = Mutex()
    private val state = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var model: CloneRuntime? = null
    private var modelKey: Triple<File, Int, Long>? = null
    private var runtimeRevision = 0L
    private var idle: Job? = null
    private var resident = false
    private var active = false
    private var closed = false
    @Volatile private var options = CloneOptions()
    private var profile = false
    private var benchmarkJob: Job? = null
    private data class ModelPlan(val directory: File, val source: CloneModelSource?, val options: CloneOptions, val revision: Long) {
        val key get() = Triple(directory, options.threads, revision)
    }
    private data class RequestPlan(val voice: CloneVoice, val model: ModelPlan, val options: CloneOptions, val capture: Boolean)
    protected abstract fun openRuntime(directory: File, options: CloneOptions): CloneRuntime
    protected open fun normalizeAvatar(source: File, destination: File) = copyVoiceAvatar(source,destination)
    protected open fun shareVoiceZip(path: String, name: String): PluginOutcome =
        ExportedVoice(path,name).outcome(descriptor.id)

    private fun normalOptions(threads: Int): CloneOptions {
        val buffered = kind == CloneKind.QWEN3 && environment.storage.file("config/playback.txt").let { !it.isFile || it.readText().trim() != "stream" }
        val steps = quality?.realtimeSteps ?: environment.storage.file("config/steps.txt").let {
            if (it.isFile && it.readText().trim() == "2") 2 else 4
        }
        return CloneOptions(threads = threads, steps = steps, batch = buffered, buffered = buffered)
    }

    private fun benchmarkOptions(values: JsonObject): CloneOptions {
        fun number(key: String, fallback: Float) = values[key]?.jsonPrimitive?.float ?: fallback
        val candidate = options.copy(steps = values["steps"]?.jsonPrimitive?.int ?: 4,
            chunkSeconds = number("chunkSeconds", 0.32f), leftContextSeconds = number("leftContextSeconds", 2f),
            seed = values["seed"]?.jsonPrimitive?.long ?: -1,
            batch = values["batch"]?.jsonPrimitive?.boolean ?: false,
            collectCheck = values["collectCheck"]?.jsonPrimitive?.boolean ?: false,
            maxTokens = values["maxTokens"]?.jsonPrimitive?.int ?: 512,
            forceGeneric = values["forceGeneric"]?.jsonPrimitive?.boolean ?: false,
            buffered = values["buffered"]?.jsonPrimitive?.boolean ?: false,
            provider = "cpu", acceleration = "cpu", fallbackFault = "")
        require(candidate.steps in 1..16 && candidate.chunkSeconds in 0.16f..4f && candidate.leftContextSeconds in 0f..4f && candidate.maxTokens in 1..512)
        return candidate
    }

    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment
        val bundled = if (kind == CloneKind.ZIPVOICE) File(environment.assets, "default-model") else null
        store = CloneStore(environment.storage, kind, ::normalizeAvatar, bundledModel = bundled)
        voiceActions = CloneVoiceActions(store, ::shareVoiceZip)
        quality = if (kind == CloneKind.ZIPVOICE) CloneQualitySettings(environment.storage) else null
        val file = environment.storage.file("config/threads.txt")
        options = normalOptions(file.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()?.coerceIn(SYNTHESIS_SETTINGS) ?: 2)
        val benchmark = environment.storage.file("config/benchmark.json")
        if (benchmark.isFile) {
            val values = benchmark.readObject()
            options = benchmarkOptions(values)
            profile = true
        }
    }
    private fun modelPlan() = ModelPlan(store.modelDirectory, store.modelSource, options, runtimeRevision)

    private fun acquire(plan: ModelPlan): CloneRuntime {
        check(!closed) { "插件已关闭" }
        idle?.cancel(); idle = null
        if (plan.source == null) throw EngineException(EngineError.NOT_CONFIGURED, "模型资源缺失，请重新安装引擎")
        if (modelKey != plan.key) release()
        return model ?: try { openRuntime(plan.directory, plan.options).also { model = it; modelKey = plan.key } }
        catch (e: LinkageError) { throw EngineException(EngineError.INITIALIZATION, "本地运行库加载失败；需要 arm64-v8a 设备", e) }
        catch (e: Exception) { throw EngineException(EngineError.INITIALIZATION, "模型加载失败：${e.message}", e) }
    }
    private fun release() { idle?.cancel(); idle = null; model?.close(); model = null; modelKey = null }
    private fun scheduleRelease() {
        idle?.cancel()
        if (!resident && !active && !closed) idle = scope.launch { delay(30_000); access.withLock { if (!resident && !active) release() } }
    }
    // The legacy Qwen runtime still reads on-disk voice files. Only ZipVoice ships with detached snapshots.
    private suspend fun <T> edit(block: suspend () -> T): T =
        if (kind == CloneKind.ZIPVOICE) state.withLock { block() }
        else access.withLock { state.withLock { block() } }

    override val tts = object : TtsEngine {
        override val info get(): TtsInfo {
            if (!::store.isInitialized) return TtsInfo(availability = EngineAvailability.NEEDS_RESOURCES)
            val voices = store.voiceInfos()
            val versions = quality?.versions(options.steps)?.let { base ->
                val revision = store.referenceVersion()
                if (revision.isEmpty()) base else TtsProfileVersions("${base.realtime}:$revision", "${base.prebake}:$revision")
            }
            return TtsInfo(availability = if (store.modelReady() && voices.isNotEmpty()) EngineAvailability.READY else EngineAvailability.NEEDS_RESOURCES,
                voices = voices, maxTextLength = 500, selectedVoiceId = store.selected, importsVoicePacks = true, managesVoices = true,
                packs = store.packs(), supportsControl = true, profiles = versions)
        }
        override fun synthesize(request: TtsRequest): Flow<SynthesisEvent> = channelFlow {
            access.withLock {
                check(!closed)
                send(SynthesisEvent.Started)
                if (request.text.isBlank()) { send(SynthesisEvent.Finished()); return@withLock }
                if (request.text.length > 500 || request.text.contains('\u0000')) throw EngineException(EngineError.UNSUPPORTED_FORMAT, "单次文本最多 500 个字符，不能包含空字符")
                val startedNs = System.nanoTime()
                val startedAt = System.currentTimeMillis()
                val plan = state.withLock {
                    RequestPlan(store.synthesisVoice(request.voiceId), modelPlan(), quality?.forRequest(options, request.profile) ?: options, profile)
                }
                val requestOptions = plan.options
                val requestJob = currentCoroutineContext().job
                var completed = false
                var failure = ""
                val reused = model != null && modelKey == plan.model.key
                var loadMs = 0.0
                var inferenceMs = 0.0
                var firstPcmMs: Double? = null
                var firstValidMs: Double? = null
                var firstNativeMs: Double? = null
                var firstNativeValidMs: Double? = null
                val generated = PcmStats()
                val delivered = PcmStats()
                val spool = if (requestOptions.buffered) PcmSpool(environment.storage.file("tmp/synthesis-buffer.pcm")) else null
                val trim = if (kind == CloneKind.ZIPVOICE) PcmEdgeTrimmer({
                    File(environment.storage.directory("tmp"), "edge-trim-pending.pcm")
                }) else null
                var used: CloneRuntime? = null
                val pcmCapture = if (plan.capture) environment.storage.file("tmp/last-audio.pcm") else null
                val capture = pcmCapture?.outputStream()?.buffered()
                var capturedRate = 24000
                suspend fun deliver(bytes: ByteArray, rate: Int) {
                    requestJob.ensureActive()
                    val elapsed = (System.nanoTime() - startedNs) / 1_000_000.0
                    send(SynthesisEvent.Audio(PcmAudio(bytes, rate)))
                    delivered.accept(bytes)
                    if (firstPcmMs == null) firstPcmMs = elapsed
                    if (firstValidMs == null && delivered.effectiveSamples > 0) firstValidMs = elapsed
                    capturedRate = rate
                    capture?.write(bytes)
                }
                suspend fun forward(bytes: ByteArray, rate: Int) {
                    requestJob.ensureActive()
                    if (spool != null) spool.append(bytes, rate) else deliver(bytes, rate)
                }
                try {
                    val runtime = acquire(plan.model)
                    used = runtime
                    loadMs = (System.nanoTime() - startedNs) / 1_000_000.0
                    requestJob.ensureActive()
                    // Small bounded chunks keep IPC/backpressure bounded. Native callbacks never drop audio.
                    runtime.generate(request, plan.voice, requestOptions, { requestJob.isActive }) { samples, rate ->
                        if (samples.isNotEmpty()) {
                            val elapsed = (System.nanoTime() - startedNs) / 1_000_000.0
                            if (firstNativeMs == null) firstNativeMs = elapsed
                            if (firstNativeValidMs == null && samples.any { kotlin.math.abs(it) > 0.0005f }) firstNativeValidMs = elapsed
                        }
                        if (!requestJob.isActive) false else try {
                            runBlocking {
                                for (offset in samples.indices step 8192) {
                                    requestJob.ensureActive()
                                    val bytes = Wav.pcm16(samples.copyOfRange(offset, minOf(offset + 8192, samples.size)))
                                    generated.accept(bytes)
                                    if (trim != null) trim.accept(bytes, rate, ::forward) else forward(bytes, rate)
                                }
                            }
                            true
                        } catch (_: CancellationException) { false }
                    }
                    inferenceMs = (System.nanoTime() - startedNs) / 1_000_000.0 - loadMs
                    requestJob.ensureActive()
                    generated.requireSpeech()
                    trim?.finish(::forward)
                    spool?.drain { bytes, rate -> deliver(bytes, rate) }
                    delivered.requireSpeech()
                    send(SynthesisEvent.Finished())
                    completed = true
                } catch (e: CancellationException) { failure = "已取消"; release(); throw e }
                catch (e: EngineException) { failure = e.message.orEmpty(); release(); throw e }
                catch (e: LinkageError) { failure = "原生推理库不可用"; release(); throw EngineException(EngineError.INITIALIZATION, failure, e) }
                catch (e: Exception) {
                    failure = e.message.orEmpty()
                    release(); requestJob.ensureActive()
                    throw EngineException(EngineError.INFERENCE, "${kind.title} 合成失败：${e.message}", e)
                } finally {
                    trim?.close()
                    spool?.close()
                    if (used != null) inferenceMs = (System.nanoTime() - startedNs) / 1_000_000.0 - loadMs
                    capture?.close()
                    // No raw text, reference or generated audio is retained by this lightweight record.
                    runCatching {
                        atomicText(environment.storage.file("tmp/last-result.json"), buildJsonObject {
                            put("version", descriptor.version); put("startedAt", startedAt); put("completedAt", System.currentTimeMillis())
                            put("synthesisProfile", request.profile.name); put("steps", requestOptions.steps)
                            if (used != null) plan.model.source?.let { put("modelSource", it.id) }
                            put("threads", requestOptions.threads)
                            put("finished", completed); put("cancelled", !completed && !requestJob.isActive)
                            put("error", failure.take(300)); put("wallMs", (System.nanoTime() - startedNs) / 1_000_000.0)
                            put("generatedSamples", generated.samples); put("generatedEffectiveSamples", generated.effectiveSamples)
                            if (trim != null) {
                                put("trimmedLeadingSamples", trim.leadingSamples); put("trimmedTrailingSamples", trim.trailingSamples)
                            }
                            put("deliveredSamples", delivered.samples); put("deliveredEffectiveSamples", delivered.effectiveSamples)
                            put("peakPcm16", delivered.peak); put("sampleRate", capturedRate)
                            put("runtime", used?.diagnostics?.let { json.parseToJsonElement(it) } ?: JsonNull)
                            put("note", "交付样本数表示插件已向宿主音频流发送，不代表扬声器实际播放确认")
                        }.toString())
                    }
                    if (plan.capture) runCatching {
                        val detail = used?.diagnostics?.let { json.parseToJsonElement(it) } ?: JsonNull
                        val wav = environment.storage.file("tmp/last-audio.wav")
                        if (pcmCapture != null && pcmCapture.length() > 0) Wav.wrapPcm(pcmCapture, wav, capturedRate)
                        atomicText(environment.storage.file("tmp/last-profile.json"), buildJsonObject {
                            put("modelReused", reused); put("modelAcquireMs", loadMs); put("inferenceWallMs", inferenceMs)
                            firstPcmMs?.let { put("firstPcmMs", it) }; firstValidMs?.let { put("firstValidPcmMs", it) }
                            firstNativeMs?.let { put("firstNativePcmMs", it) }; firstNativeValidMs?.let { put("firstNativeValidPcmMs", it) }
                            put("bufferedDelivery", requestOptions.buffered); put("generatedSamples", generated.samples)
                            put("deliveredSamples", delivered.samples); put("effectivePcmSamples", delivered.effectiveSamples); put("peakPcm16", delivered.peak)
                            if (pcmCapture != null && pcmCapture.length() > 0) put("audioPath", wav.absolutePath) else put("audioPath", JsonNull)
                            put("audioSamples", (pcmCapture?.length() ?: 0) / 2)
                            put("sampleRate", capturedRate)
                            put("runtime", detail)
                        }.toString())
                    }
                    if (!requestJob.isActive) release() else scheduleRelease()
                }
            }
        }.buffer(2, BufferOverflow.SUSPEND).flowOn(Dispatchers.IO)
        override suspend fun control(command: TtsControl) = withContext(Dispatchers.IO) {
            access.withLock {
                check(!closed)
                when (command.operation) {
                    TtsControlOperation.PREPARE -> { acquire(state.withLock { modelPlan() }); scheduleRelease() }
                    TtsControlOperation.RELEASE -> release()
                    TtsControlOperation.SET_RESIDENT -> { resident = command.resident; if (resident) idle?.cancel() else scheduleRelease() }
                    TtsControlOperation.ACTIVITY -> { active = command.active; if (active) idle?.cancel() else scheduleRelease() }
                }
                Unit
            }
        }
        override suspend fun close() = this@ClonePlugin.close()
    }
    override suspend fun close() {
        benchmarkJob?.cancelAndJoin()
        withContext(Dispatchers.IO) { access.withLock { closed = true; release() } }
        scope.cancel()
    }
    override val voiceManager = object : VoiceManager {
        override suspend fun command(command: VoiceCommand): PluginOutcome = withContext(Dispatchers.IO) {
            try { edit {
                val target=command.packId ?: command.voiceId
                when (command.operation) {
                    VoiceOperation.IMPORT -> {
                        store.importVoice(environment.storage.file(requireNotNull(command.resourcePath)), command.packId)
                        if (command.packId != null && kind != CloneKind.ZIPVOICE) release()
                    }
                    VoiceOperation.SELECT -> store.selected = requireNotNull(command.voiceId ?: target)
                    VoiceOperation.DELETE -> store.delete(requireNotNull(target))
                    VoiceOperation.RENAME -> store.rename(requireNotNull(target), requireNotNull(command.value))
                    VoiceOperation.UPDATE_METADATA -> store.updateMetadata(requireNotNull(target),requireNotNull(command.metadata))
                    VoiceOperation.SET_AVATAR -> store.setAvatar(requireNotNull(target),command.resourcePath?.let(environment.storage::file))
                    VoiceOperation.EXPORT -> return@edit exportVoicePack(environment.storage,store.resolve(target)).outcome(descriptor.id)
                    VoiceOperation.REFRESH -> Unit
                    else -> return@edit PluginOutcome.Message("此语音包不支持该操作")
                }
                PluginOutcome.RefreshUi
            } } catch (e: CancellationException) { throw e }
            catch (e: Exception) { PluginOutcome.Message(e.message ?: "语音包操作失败") }
        }
    }
    override val contributions = PluginContributions(drawerEntries = listOf(
        DrawerEntry("settings", descriptor.name, "settings", PluginIcon.SPEAKER, settingsPage = true)))
    override fun page(pageId: String, context: PluginContext): PluginPage? {
        if (!::store.isInitialized) return null
        if (pageId == "settings") return cloneVoicePage(descriptor.name, store, options, quality?.prebakeSteps)
        if (pageId.startsWith("voice/")) return runCatching {
            cloneVoiceDetail(store, store.resolve(pageId.substringAfter('/')))
        }.getOrNull()
        return null
    }

    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome = withContext(Dispatchers.IO) {
        if (actionId == "benchmark-status") return@withContext PluginOutcome.Dialog("异步自检", environment.storage.file("tmp/benchmark-result.json").let { if (it.isFile) it.readText().take(48_000) else "{}" })
        if (actionId == "benchmark-cancel") { benchmarkJob?.cancel(); return@withContext PluginOutcome.Message("已请求停止自检，原生状态将在下一回调安全释放") }
        try {
            if (actionId == "release" || actionId == "prepare") return@withContext access.withLock {
                if (actionId == "release") {
                    release()
                    PluginOutcome.Message("模型内存已释放")
                } else {
                    val started = System.nanoTime()
                    acquire(state.withLock { modelPlan() }); scheduleRelease()
                    PluginOutcome.Dialog("预热计时", buildJsonObject { put("modelAcquireMs", (System.nanoTime() - started) / 1_000_000.0) }.toString())
                }
            }
            edit {
                voiceActions.execute(actionId, context)?.let { return@edit it }
                val value = context.inputs["value"].orEmpty()
                when {
                    kind == CloneKind.QWEN3 && actionId == "import-model" -> PluginOutcome.ImportResource("导入 ${kind.title} 模型 ZIP", "imports/voice-clone-inbox", "model-imported", mimeTypes = listOf("application/zip", "application/octet-stream"))
                    kind == CloneKind.QWEN3 && actionId == "model-imported" -> {
                        voiceActions.fromInbox(context) { store.importModel(it) }; release(); PluginOutcome.RefreshUi
                    }
                    actionId == "threads" -> PluginOutcome.TextInput("CPU 线程数（1–8）", "threads-saved", options.threads.toString())
                    actionId == "threads-saved" -> {
                        val count = synthesisSliderValue(value)
                        if (count != options.threads) {
                            atomicText(environment.storage.file("config/threads.txt"), count.toString())
                            options = options.copy(threads = count)
                        }
                        PluginOutcome.RefreshUi
                    }
                    actionId == "playback/buffered" || actionId == "playback/stream" -> {
                        require(kind == CloneKind.QWEN3)
                        release(); atomicText(environment.storage.file("config/playback.txt"), actionId.substringAfter('/'))
                        options = normalOptions(options.threads)
                        PluginOutcome.RefreshUi
                    }
                    actionId == "steps-saved" || actionId.startsWith("steps/") -> {
                        val steps = synthesisSliderValue(if (actionId == "steps-saved") value else actionId.substringAfter('/'))
                        requireNotNull(quality).save(TtsProfile.REALTIME, steps)
                        options = options.copy(steps = steps)
                        PluginOutcome.RefreshUi
                    }
                    actionId == "prebake-steps-saved" || actionId.startsWith("prebake-steps/") -> {
                        val steps = synthesisSliderValue(if (actionId == "prebake-steps-saved") value else actionId.substringAfter('/'))
                        requireNotNull(quality).save(TtsProfile.PREBAKE, steps)
                        PluginOutcome.RefreshUi
                    }
                    actionId == "diagnostics" -> PluginOutcome.Dialog("性能诊断", environment.storage.file("tmp/last-profile.json").let {
                        if (it.isFile) it.readText().take(48_000) else "{}"
                    })
                    actionId == "last-result" -> PluginOutcome.Dialog("上次合成诊断", environment.storage.file("tmp/last-result.json").let {
                        if (it.isFile) it.readText() else "暂无记录，请先朗读一段文本。"
                    })
                    actionId == "model-status" -> PluginOutcome.Dialog("资源状态", buildJsonObject {
                        put("modelReady", store.modelReady())
                        store.modelSource?.let { put("modelSource", it.id) }
                        if (store.modelReady()) put("modelManifestSha256", sha256(File(store.modelDirectory, "model.json")))
                        put("selectedVoiceId", store.selected ?: "")
                        put("voices", JsonArray(store.voices().map { voice -> buildJsonObject {
                            put("id", voice.id); put("name", voice.name); put("referenceSha256", sha256(voice.wav))
                        } }))
                    }.toString())
                    actionId == "benchmark-clear-reference" -> {
                        require(profile) { "请先显式启用诊断配置" }
                        val voice = store.resolve(context.inputs["voiceId"])
                        voice.directory.listFiles()?.filter { it.name.matches(Regex("speaker-[a-f0-9]{24}\\.json")) }?.forEach { check(it.delete()) }
                        PluginOutcome.Message("已清理所选声音的派生特征缓存，参考音频和文本保留")
                    }
                    actionId == "benchmark-config" -> {
                        require(benchmarkJob?.isActive != true) { "请先停止自检" }
                        val path = requireNotNull(context.inputs["resourcePath"])
                        require(path.startsWith("imports/")) { "诊断配置需通过本插件 imports 导入" }
                        val values = environment.storage.file(path).readObject()
                        val candidate = benchmarkOptions(values)
                        runtimeRevision++; atomicText(environment.storage.file("config/benchmark.json"), values.toString())
                        options = candidate; profile = true
                        PluginOutcome.RefreshUi
                    }
                    actionId == "benchmark-disable" -> {
                        require(benchmarkJob?.isActive != true) { "请先停止自检" }
                        runtimeRevision++; profile = false; options = normalOptions(options.threads)
                        environment.storage.file("config/benchmark.json").delete()
                        PluginOutcome.Message("诊断捕获已关闭")
                    }
                    actionId == "benchmark" -> {
                        require(profile) { "请先显式启用诊断配置" }
                        require(benchmarkJob?.isActive != true) { "自检仍在运行，请查询 benchmark-status" }
                        val text = context.inputs["text"] ?: "你好，欢迎使用离线语音克隆。"
                        require(text.isNotBlank() && text.length <= 500)
                        val runId = UUID.randomUUID().toString()
                        val state = environment.storage.file("tmp/benchmark-result.json")
                        atomicText(state, buildJsonObject { put("runId", runId); put("status", "running") }.toString())
                        benchmarkJob = scope.launch {
                            val started = System.nanoTime()
                            var bytes = 0L; var sampleRate = 24000; var firstValid: Double? = null; var finished = false
                            val chunks = mutableListOf<JsonElement>()
                            try {
                                tts.synthesize(TtsRequest(text, voiceId = context.inputs["voiceId"])).collect { event -> when(event) {
                                    is SynthesisEvent.Audio -> {
                                        val arrival = (System.nanoTime() - started) / 1_000_000.0
                                        val audio = event.audio
                                        val valid = audio.bytes.indices.step(2).any { i -> kotlin.math.abs(((audio.bytes[i].toInt() and 255) or (audio.bytes[i+1].toInt() shl 8)).toShort().toInt()) > 16 }
                                        if (firstValid == null && valid) firstValid = arrival
                                        chunks += buildJsonObject { put("arrivalMs", arrival); put("startSample", bytes / 2); put("endSample", (bytes + audio.bytes.size) / 2); put("nonSilent", valid) }
                                        bytes += audio.bytes.size; sampleRate = audio.sampleRate
                                    }
                                    is SynthesisEvent.Finished -> finished = true
                                    else -> Unit
                                } }
                                check(finished && bytes > 0 && firstValid != null) { "自检未完成或没有有效 PCM" }
                                atomicText(state, buildJsonObject {
                                    put("runId", runId); put("status", "complete"); put("finished", finished); put("pcmBytes", bytes)
                                    put("sampleRate", sampleRate); put("durationSeconds", bytes / 2.0 / sampleRate)
                                    put("firstValidPcmMs", firstValid!!); put("totalMs", (System.nanoTime() - started) / 1_000_000.0)
                                    put("chunks", JsonArray(chunks)); put("profile", json.parseToJsonElement(environment.storage.file("tmp/last-profile.json").readText()))
                                }.toString())
                            } catch (error: Exception) {
                                atomicText(state, buildJsonObject {
                                    put("runId", runId); put("status", "failed"); put("finished", finished); put("pcmBytes", bytes)
                                    put("error", error.message.orEmpty()); put("totalMs", (System.nanoTime() - started) / 1_000_000.0)
                                    environment.storage.file("tmp/last-profile.json").takeIf { it.isFile }?.let { put("profile", json.parseToJsonElement(it.readText())) }
                                }.toString())
                            }
                        }
                        benchmarkJob!!.invokeOnCompletion { cause ->
                            if (cause != null) runCatching {
                                val last = state.readObject()
                                if (last.string("runId") == runId && last.string("status") == "running") atomicText(state, buildJsonObject {
                                    put("runId", runId); put("status", "failed"); put("finished", false)
                                    put("error", if (cause is CancellationException) "Benchmark cancelled" else cause.javaClass.simpleName)
                                }.toString())
                            }
                        }
                        PluginOutcome.Dialog("异步自检已启动", buildJsonObject { put("runId", runId); put("status", "running") }.toString())
                    }
                    else -> PluginOutcome.Message("请打开插件设置")
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { PluginOutcome.Message(e.message ?: "操作失败，请检查导入文件") }
    }
}
