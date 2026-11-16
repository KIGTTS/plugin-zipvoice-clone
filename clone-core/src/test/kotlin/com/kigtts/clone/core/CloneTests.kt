package com.kigtts.clone.core

import com.kigtts.plugin.api.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloneTests {
    @Test fun longTextSplitsLosslesslyWithoutBrokenSurrogates() {
        val text = ("你好，欢迎使用离线语音克隆。😀 English text; " + "长文本".repeat(170)).take(500)
        val chunks = cloneTextSegments(text)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 80 })
        assertTrue(chunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
        assertEquals(emptyList<String>(), cloneTextSegments(""))
        assertEquals(500, text.length)
        val emoji = "😀".repeat(250)
        val emojiChunks = cloneTextSegments(emoji)
        assertEquals(emoji, emojiChunks.joinToString(""))
        assertTrue(emojiChunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }
    private fun root() = Files.createTempDirectory("clone-test").toFile().also { it.deleteOnExit() }
    private fun wav(directory: File, silent: Boolean = false, channels: Int = 1): File {
        val count = 16000
        val buffer = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        buffer.putShort(1).putShort(channels.toShort()).putInt(16000).putInt(32000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(count * 2)
        repeat(count) { buffer.putShort(if (silent) 0 else (sin(it * 0.04) * 12000).toInt().toShort()) }
        return File(directory, "source.wav").also { it.writeBytes(buffer.array()) }
    }
    private fun zip(directory: File, entries: Map<String, ByteArray>): File = File(directory, "pack-${System.nanoTime()}.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (name, bytes) -> output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() } }
    }
    @Test fun wavRejectsStereoSilenceAndTruncation() {
        val directory = root()
        assertThrows(IllegalArgumentException::class.java) { Wav.read(wav(directory, channels = 2)) }
        assertThrows(IllegalArgumentException::class.java) { Wav.read(wav(directory, silent = true)) }
        val valid = wav(directory)
        assertEquals(16000, Wav.read(valid).sampleRate)
        valid.writeBytes(valid.readBytes().dropLast(10).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { Wav.read(valid) }
    }
    @Test fun zipCannotEscapeOrExceedLimit() = runBlocking {
        val directory = root()
        try { extractZip(zip(directory, mapOf("../outside" to byteArrayOf(1))), File(directory, "stage")); fail() } catch (_: IllegalArgumentException) {}
        assertFalse(File(directory, "outside").exists())
        try { extractZip(zip(directory, mapOf("large" to ByteArray(200))), File(directory, "stage2"), 100); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun zipRejectsCaseCollisionsAndWindowsPaths() = runBlocking {
        val directory = root()
        for (entries in listOf(mapOf("a" to byteArrayOf(1), "A" to byteArrayOf(2)), mapOf("C:/escape" to byteArrayOf(1)), mapOf("a\\b" to byteArrayOf(1)))) {
            try { extractZip(zip(directory, entries), File(directory, "s-${System.nanoTime()}")); fail() } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun voicesPersistAndRequireMatchingText() {
        val directory = root(); val storage = PluginStorage(directory); val store = CloneStore(storage, CloneKind.ZIPVOICE)
        val source = wav(directory)
        assertThrows(IllegalArgumentException::class.java) { store.addVoice(source, "Voice", "") }
        val voice = store.addVoice(source, "Voice", "参考文本")
        store.rename(voice.id, "New name")
        val reloaded = CloneStore(storage, CloneKind.ZIPVOICE)
        assertEquals(voice.id, reloaded.selected)
        assertEquals("New name", reloaded.resolve(null).name)
        reloaded.delete(voice.id)
        assertNull(reloaded.selected); assertTrue(source.isFile)
    }
    private suspend fun modelZip(directory: File, kind: CloneKind, badHash: Boolean = false, variant: String = ""): File {
        val entries = kind.modelFiles.associateWith { it.toByteArray() }.toMutableMap()
        val hashes = buildJsonObject { entries.forEach { (name, bytes) ->
            val file = File(directory, "hash.tmp").also { it.writeBytes(bytes) }
            put(name, if (badHash) "0".repeat(64) else sha256(file))
        } }
        entries["model.json"] = buildJsonObject { put("formatVersion", 1); put("engine", kind.key); put("license", "test-only"); put("sha256", hashes); put("variant", variant) }.toString().toByteArray()
        return zip(directory, entries)
    }
    @Test fun failedModelImportPreservesPreviousModel() = runBlocking {
        val directory = root(); val store = CloneStore(PluginStorage(directory), CloneKind.ZIPVOICE)
        store.importModel(modelZip(directory, CloneKind.ZIPVOICE))
        assertTrue(store.modelReady())
        val before = File(store.modelDirectory, "encoder.int8.onnx").readBytes()
        try { store.importModel(modelZip(directory, CloneKind.ZIPVOICE, badHash = true)); fail() } catch (_: IllegalArgumentException) {}
        assertArrayEquals(before, File(store.modelDirectory, "encoder.int8.onnx").readBytes())
        try { store.importModel(modelZip(directory, CloneKind.QWEN3)); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(store.modelReady())
    }
    @Test fun interruptedDirectorySwapRestoresOldData() {
        val directory = root(); val previous = File(directory, "models/active.previous").also { it.mkdirs() }
        File(previous, "sentinel").writeText("old")
        CloneStore(PluginStorage(directory), CloneKind.QWEN3)
        assertEquals("old", File(directory, "models/active/sentinel").readText())
    }
    @Test fun builtInWeightsWorkWithoutImportAndNeverOverwriteExistingWeights() = runBlocking {
        val directory = root()
        val builtin = File(directory, "assets/default-model")
        val stock = modelZip(directory, CloneKind.ZIPVOICE)
        extractZip(stock, builtin)
        val manifest = File(builtin, "model.json").readBytes()
        val storage = PluginStorage(File(directory, "data"))
        val store = CloneStore(storage, CloneKind.ZIPVOICE, bundledModel = builtin)
        assertTrue(store.modelReady())
        assertEquals(CloneModelSource.BUNDLED, store.modelSource)
        assertFalse(storage.file("models/active").exists())
        store.importModel(stock)
        assertEquals(builtin, store.modelDirectory)
        assertTrue(storage.file("models/active/model.json").isFile)
        store.importModel(modelZip(directory, CloneKind.ZIPVOICE, variant = "previous custom weights"))
        assertEquals(CloneModelSource.IMPORTED, store.modelSource)
        assertArrayEquals(manifest, File(builtin, "model.json").readBytes())
    }
    @Test fun pcmClipsWithoutOverflowAndRatePreservesChunkContinuity() {
        assertArrayEquals(byteArrayOf(0, -128, -1, 127), Wav.pcm16(floatArrayOf(-2f, 2f)))
        assertThrows(IllegalArgumentException::class.java) { Wav.pcm16(floatArrayOf(Float.NaN)) }
        val input = FloatArray(100) { it / 100f }
        val one = PcmRate(1.3f).convert(input)
        val converter = PcmRate(1.3f)
        val many = input.asList().chunked(7).flatMap { converter.convert(it.toFloatArray()).asList() }.toFloatArray()
        assertArrayEquals(one, many, 0.00001f)
    }
    private class TestPlugin(kind: CloneKind = CloneKind.ZIPVOICE) : ClonePlugin(kind) {
        var opens = 0; var closes = 0
        var generationCompleted = false
        var lastOptions: CloneOptions? = null
        var lastModelDirectory: File? = null
        var atGeneration: ((CloneVoice, CloneOptions) -> Unit)? = null
        fun lastResult() = environment.storage.file("tmp/last-result.json").readObject()
        fun file(path: String) = environment.storage.file(path)
        override fun openRuntime(directory: File, options: CloneOptions): CloneRuntime {
            opens++; lastModelDirectory = directory
            return object : CloneRuntime {
                override fun generate(request: TtsRequest, voice: CloneVoice, options: CloneOptions, active: () -> Boolean, emit: (FloatArray, Int) -> Boolean) {
                    lastOptions = options
                    atGeneration?.invoke(voice, options)
                    if (request.text == "EDGE_TEST") {
                        emit(FloatArray(7200), 24000)
                        emit(FloatArray(2400) { 0.1f }, 24000)
                        emit(FloatArray(4800), 24000)
                        emit(FloatArray(2400) { -0.1f }, 24000)
                        emit(FloatArray(9600), 24000)
                        return
                    }
                    repeat(100) {
                        if (!active() || !emit(FloatArray(200) { if (request.text == "SILENT_TEST") 0f else 0.1f }, 24000)) return
                        if (request.text == "TOKEN_LIMIT_TEST") throw EngineException(EngineError.INFERENCE, "TOKEN_LIMIT: exhausted before EOS")
                        if (request.text == "slow benchmark") Thread.sleep(5)
                    }
                    generationCompleted = true
                }
                override fun close() { closes++ }
            }
        }
    }
    private suspend fun fixture(diagnostics: Boolean = false, kind: CloneKind = CloneKind.ZIPVOICE, bundled: Boolean = false): TestPlugin {
        val directory = root()
        val storage = PluginStorage(directory)
        if (diagnostics) storage.file("config/benchmark.json").also { it.parentFile.mkdirs(); it.writeText("{\"collectCheck\":true}") }
        val store = CloneStore(storage, kind)
        val models = modelZip(directory, kind)
        if (bundled) extractZip(models, File(directory, "default-model")) else store.importModel(models)
        store.addVoice(wav(directory), "test", "test")
        return TestPlugin(kind).also { it.attach(object : PluginEnvironment {
            override val storage = storage
            override val assets = directory
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("Offline test must not download")
        }) }
    }
    @Test fun profileSlidersUseSdkDecimalValuesAndKeepOnlyOneSettingsCard() = runBlocking {
        val plugin = fixture()
        val context = PluginContext("", "")
        suspend fun change(action: String, value: String) =
            plugin.execute(action, context.copy(inputs = mapOf("value" to value)))
        try {
            plugin.tts.control(TtsControl(TtsControlOperation.PREPARE))
            val first = requireNotNull(plugin.tts.info.profiles)
            assertEquals(PluginOutcome.RefreshUi, change("steps-saved", "1.0"))
            val second = requireNotNull(plugin.tts.info.profiles)
            assertNotEquals(first.realtime, second.realtime)
            assertEquals(first.prebake, second.prebake)
            assertEquals(PluginOutcome.RefreshUi, change("prebake-steps-saved", "8.0"))
            val third = requireNotNull(plugin.tts.info.profiles)
            assertEquals(second.realtime, third.realtime)
            assertNotEquals(second.prebake, third.prebake)
            assertEquals(0, plugin.closes)

            assertTrue(change("prebake-steps-saved", "2.5") is PluginOutcome.Message)
            assertTrue(change("threads-saved", "9.0") is PluginOutcome.Message)
            assertEquals(third, plugin.tts.info.profiles)
            assertEquals(PluginOutcome.RefreshUi, change("threads-saved", "5.0"))
            assertEquals(0, plugin.closes) // Never close native state from the slider callback.
            assertEquals(third, plugin.tts.info.profiles)

            val page = requireNotNull(plugin.page("settings", context))
            assertEquals(listOf("import-pack"), page.blocks.filterIsInstance<PageBlock.Actions>().single().actions.map { it.id })
            val group = page.blocks.filterIsInstance<PageBlock.Group>().single()
            val sliders = group.children.filterIsInstance<PageBlock.Slider>().map { it.control }
            assertEquals(listOf("threads-saved", "steps-saved", "prebake-steps-saved"), sliders.map { it.actionId })
            assertEquals(listOf(5f, 1f, 8f), sliders.map { it.value })
            assertTrue(sliders.all { it.min == 1f && it.max == 8f && it.steps == 6 })
            assertEquals(listOf("CPU 线程", "实时合成质量", "缓存合成质量"), sliders.map { it.title })
            assertEquals(2, page.management?.items?.size)
            assertEquals("add-voice", page.management?.items?.last()?.primaryAction)

            for ((profile, expected) in listOf(TtsProfile.REALTIME to 1, TtsProfile.PREBAKE to 8)) {
                val events = plugin.tts.synthesize(TtsRequest("测试", profile = profile)).toList()
                assertTrue(events.last() is SynthesisEvent.Finished)
                assertEquals(expected, plugin.lastOptions?.steps)
                assertEquals(5, plugin.lastOptions?.threads)
            }
            assertEquals(2, plugin.opens)
            assertEquals(1, plugin.closes)
        } finally { plugin.close() }
    }

    @Test fun allSliderStopsPersistIndependently() = runBlocking {
        val plugin = fixture()
        val context = PluginContext("", "")
        val settings = listOf("threads-saved" to "threads", "steps-saved" to "steps", "prebake-steps-saved" to "prebake-steps")
        try {
            for ((action, filename) in settings) {
                // Start from the upper endpoint, so every ascending stop differs from the current one.
                assertEquals(PluginOutcome.RefreshUi, plugin.execute(action, context.copy(inputs = mapOf("value" to "8.0"))))
                for (stop in 1..8) {
                    assertEquals(PluginOutcome.RefreshUi, plugin.execute(action, context.copy(inputs = mapOf("value" to "$stop.0"))))
                    assertEquals(stop.toString(), plugin.file("config/$filename.txt").readText())
                }
            }
        } finally { plugin.close() }
    }

    @Test fun voiceEditingCreationAndSettingsProceedDuringActiveSynthesis() = runBlocking {
        val plugin = fixture(bundled = true)
        val running = CompletableDeferred<Unit>()
        val unblock = CountDownLatch(1)
        val snapshots = mutableListOf<Pair<CloneVoice, CloneOptions>>()
        plugin.atGeneration = { voice, settings ->
            snapshots += voice to settings
            running.complete(Unit)
            check(unblock.await(10, TimeUnit.SECONDS))
        }
        val context = PluginContext("", "")
        suspend fun edit(action: String, vararg inputs: Pair<String, String>) =
            plugin.execute(action, context.copy(inputs = inputs.toMap()))
        val original = requireNotNull(plugin.tts.info.selectedVoiceId)
        val preset = requireNotNull(plugin.tts.info.profiles)
        val generation = async { plugin.tts.synthesize(TtsRequest("正在合成")).toList() }
        try {
            withTimeout(4_000) {
                running.await()
                assertEquals(PluginOutcome.OpenPage("voice/$original"), edit("edit/$original"))
                val detail = requireNotNull(plugin.page("voice/$original", context))
                assertEquals("test", detail.header?.title)
                assertEquals(PluginOutcome.RefreshUi, edit("renamed/$original", "value" to "新名称"))
                assertEquals(PluginOutcome.RefreshUi, edit("remark-saved/$original", "value" to "新备注"))
                assertEquals(PluginOutcome.RefreshUi, edit("transcript-saved/$original", "value" to "新参考文本"))
                assertNotEquals(preset.prebake, plugin.tts.info.profiles?.prebake)
                val replacePath = "imports/voice-clone-inbox/replace.wav"
                val replacement = plugin.file(replacePath).also { it.parentFile.mkdirs() }
                val raw = plugin.file("tmp/replacement.pcm").also { it.parentFile.mkdirs() }
                raw.writeBytes(Wav.pcm16(FloatArray(16000) { if (it % 20 < 10) 0.25f else -0.25f }))
                Wav.wrapPcm(raw, replacement, 16000)
                val nextReference = edit("reference-picked/$original", "resourcePath" to replacePath) as PluginOutcome.TextInput
                assertEquals(PluginOutcome.RefreshUi, edit(nextReference.actionId, "value" to "新参考文本"))
                val importPath = "imports/voice-clone-inbox/create.wav"
                val wavFile = plugin.file(importPath).also { it.parentFile.mkdirs() }
                plugin.file("voices/$original/reference.wav").copyTo(wavFile)
                val name = edit("wav-imported", "resourcePath" to importPath) as PluginOutcome.TextInput
                val text = edit(name.actionId, "value" to "制作音色") as PluginOutcome.TextInput
                val created = edit(text.actionId, "value" to "参考文字") as PluginOutcome.OpenPage
                assertEquals("制作音色", plugin.page(created.pageId, context)?.title)
                assertEquals(PluginOutcome.RefreshUi, edit("threads-saved", "value" to "3.0"))
                assertEquals(PluginOutcome.RefreshUi, edit("steps-saved", "value" to "6.0"))
                assertEquals(PluginOutcome.RefreshUi, edit("prebake-steps-saved", "value" to "8.0"))
                assertFalse(generation.isCompleted)
                assertEquals(0, plugin.closes)
            }
            unblock.countDown()
            assertTrue(generation.await().last() is SynthesisEvent.Finished)
            assertEquals("bundled", plugin.lastResult().string("modelSource"))
            assertEquals("default-model", plugin.lastModelDirectory?.name)
            assertNotNull(snapshots[0].first.referenceAudio)
            assertEquals(0f, snapshots[0].first.referenceAudio!!.samples.first(), 0f)
            assertEquals("test", snapshots[0].first.referenceText)
            assertEquals(2, snapshots[0].second.steps)
            assertEquals(2, snapshots[0].second.threads)
            for (profile in listOf(TtsProfile.REALTIME, TtsProfile.PREBAKE)) {
                assertTrue(plugin.tts.synthesize(TtsRequest("新请求", profile = profile)).toList().last() is SynthesisEvent.Finished)
            }
            assertTrue(snapshots.drop(1).all { (voice, config) ->
                voice.referenceText == "新参考文本" && voice.referenceAudio?.samples?.first() == 0.25f && config.threads == 3
            })
            assertEquals(listOf(2, 6, 8), snapshots.map { it.second.steps })
            assertEquals(2, plugin.opens)
            assertEquals(1, plugin.closes)
        } finally {
            unblock.countDown()
            generation.cancelAndJoin()
            plugin.close()
        }
    }

    @Test fun prepareReusesAndReleaseClosesExactlyOnce() = runBlocking {
        val plugin = fixture()
        try {
            plugin.tts.control(TtsControl(TtsControlOperation.PREPARE))
            plugin.tts.control(TtsControl(TtsControlOperation.PREPARE))
            assertEquals(1, plugin.opens)
            plugin.tts.control(TtsControl(TtsControlOperation.RELEASE))
            assertEquals(1, plugin.closes)
        } finally { plugin.close() }
        assertEquals(1, plugin.closes)
    }
    @Test fun cancellationClosesNativeStateAndDoesNotSendFinished() = runBlocking {
        val plugin = fixture()
        try {
            val events = withTimeout(5000) { plugin.tts.synthesize(TtsRequest("你好")).take(2).toList() }
            assertTrue(events[0] is SynthesisEvent.Started)
            assertTrue(events[1] is SynthesisEvent.Audio)
            assertEquals(1, plugin.closes)
        } finally { plugin.close() }
    }
    @Test fun partialNativeFailureNeverReportsFinished() = runBlocking {
        val plugin = fixture()
        val events = mutableListOf<SynthesisEvent>()
        try {
            try { plugin.tts.synthesize(TtsRequest("TOKEN_LIMIT_TEST")).collect { events += it }; fail() }
            catch (error: EngineException) { assertEquals(EngineError.INFERENCE, error.code); assertTrue(error.message!!.contains("TOKEN_LIMIT")) }
            assertTrue(events.none { it is SynthesisEvent.Finished })
            assertEquals(1, plugin.closes)
        } finally { plugin.close() }
    }
    @Test fun silentEngineFailsInsteadOfReportingSuccessfulPlayback() = runBlocking {
        val plugin = fixture()
        val events = mutableListOf<SynthesisEvent>()
        try {
            try { plugin.tts.synthesize(TtsRequest("SILENT_TEST")).collect { events += it }; fail() }
            catch (error: EngineException) { assertEquals(EngineError.INFERENCE, error.code) }
            assertTrue(events.none { it is SynthesisEvent.Finished })
            assertEquals(1, plugin.closes)
            val status = plugin.lastResult()
            assertFalse(status["finished"]!!.jsonPrimitive.boolean)
            assertEquals(0, status["deliveredEffectiveSamples"]!!.jsonPrimitive.int)
            assertFalse(status.containsKey("text")); assertFalse(status.containsKey("audioPath"))
        } finally { plugin.close() }
    }
    @Test fun realtimeAndPrebakeBothDeliverTrimmedPcmAndKeepTheMiddlePause() = runBlocking {
        val plugin = fixture()
        try {
            for (profile in listOf(TtsProfile.REALTIME, TtsProfile.PREBAKE)) {
                val events = plugin.tts.synthesize(TtsRequest("EDGE_TEST", profile = profile)).toList()
                val original = FloatArray(7200) + FloatArray(2400) { 0.1f } +
                    FloatArray(4800) + FloatArray(2400) { -0.1f } + FloatArray(9600)
                val expected = Wav.pcm16(original.copyOfRange(6240, original.size-8160))
                val delivered = events.filterIsInstance<SynthesisEvent.Audio>().fold(ByteArray(0)) { out, event -> out + event.audio.bytes }
                assertArrayEquals(expected, delivered)
                assertTrue(events.last() is SynthesisEvent.Finished)
                val status = plugin.lastResult()
                assertEquals(6240, status["trimmedLeadingSamples"]!!.jsonPrimitive.int)
                assertEquals(8160, status["trimmedTrailingSamples"]!!.jsonPrimitive.int)
                assertEquals(delivered.size / 2, status["deliveredSamples"]!!.jsonPrimitive.int)
            }
        } finally { plugin.close() }
    }
    @Test fun bufferedQwenDeliversCompletePcmOnlyAfterAllGenerationSucceeds() = runBlocking {
        val plugin = fixture(kind = CloneKind.QWEN3)
        var bytes = 0; var finished = false
        try {
            plugin.tts.synthesize(TtsRequest("测试连续播放")).collect { event ->
                if (event is SynthesisEvent.Audio) { assertTrue(plugin.generationCompleted); bytes += event.audio.bytes.size }
                if (event is SynthesisEvent.Finished) finished = true
            }
            assertTrue(finished); assertEquals(40000, bytes)
            assertTrue(plugin.lastResult()["finished"]!!.jsonPrimitive.boolean)
            assertEquals(20000, plugin.lastResult()["deliveredSamples"]!!.jsonPrimitive.int)
        } finally { plugin.close() }
    }
    @Test fun bufferedPartialFailureDeliversNoAudioAndNoFinished() = runBlocking {
        val plugin = fixture(kind = CloneKind.QWEN3)
        val events = mutableListOf<SynthesisEvent>()
        try {
            try { plugin.tts.synthesize(TtsRequest("TOKEN_LIMIT_TEST")).collect { events += it }; fail() }
            catch (error: EngineException) { assertTrue(error.message!!.contains("TOKEN_LIMIT")) }
            assertTrue(events.none { it is SynthesisEvent.Audio || it is SynthesisEvent.Finished })
        } finally { plugin.close() }
    }
    @Test fun pcmSpoolBoundsStorageReplaysExactlyAndRemovesTemporaryFile() = runBlocking {
        val file = File(root(), "spool.pcm")
        val expected = ByteArray(30000) { it.toByte() }
        val actual = java.io.ByteArrayOutputStream()
        PcmSpool(file, maximumBytes = 30000).use { spool ->
            spool.append(expected, 24000)
            assertThrows(IllegalArgumentException::class.java) { spool.append(byteArrayOf(0, 0), 24000) }
            spool.drain { bytes, rate -> assertEquals(24000, rate); actual.write(bytes) }
        }
        assertArrayEquals(expected, actual.toByteArray()); assertFalse(file.exists())
    }
    @Test fun asyncBenchmarkCanBePolledAndCancelledWithoutLockingStatus() = runBlocking {
        val plugin = fixture(diagnostics = true)
        try {
            val start = plugin.execute("benchmark", PluginContext("", "", inputs = mapOf("text" to "slow benchmark"))) as PluginOutcome.Dialog
            val id = json.parseToJsonElement(start.text).jsonObject.string("runId")
            val state = withTimeout(1000) { plugin.execute("benchmark-status", PluginContext("", "")) } as PluginOutcome.Dialog
            assertEquals(id, json.parseToJsonElement(state.text).jsonObject.string("runId"))
            plugin.execute("benchmark-cancel", PluginContext("", ""))
            withTimeout(5000) {
                while (true) {
                    val status = plugin.execute("benchmark-status", PluginContext("", "")) as PluginOutcome.Dialog
                    val data = json.parseToJsonElement(status.text).jsonObject
                    if (data.string("status") == "failed") { assertEquals(false, data["finished"]!!.jsonPrimitive.boolean); break }
                    delay(10)
                }
            }
        } finally { plugin.close() }
    }
}
