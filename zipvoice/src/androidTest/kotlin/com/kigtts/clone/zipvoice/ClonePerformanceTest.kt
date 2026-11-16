package com.kigtts.clone.zipvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kigtts.clone.core.*
import com.kigtts.plugin.api.*
import dalvik.system.DexClassLoader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClonePerformanceTest {
    @Test fun coldAndWarm(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        arguments.getString("expectedDevice")?.let { assertEquals(it, android.os.Build.DEVICE) }
        val external = requireNotNull(context.getExternalFilesDir(null))
        val input = File(external, "imports")
        val storage = PluginStorage(File(context.filesDir, "clone-perf"))
        val store = CloneStore(storage, CloneKind.ZIPVOICE)
        if (!store.modelReady()) store.importModel(File(input, "model.zip"))
        if (store.voices().isEmpty()) store.addVoice(File(input, "reference.wav"), "公开测试样本", File(input, "reference.txt").readText())
        val threads = arguments.getString("threads", "2")!!.toInt()
        storage.file("config/threads.txt").also { it.parentFile.mkdirs(); it.writeText(threads.toString()) }
        val installed = File(context.filesDir, "runtime-${System.nanoTime()}").also { it.mkdirs() }
        val manifest = ZipFile(File(input, "plugin.kigplugin")).use { zip ->
            zip.entries().toList().filter { !it.isDirectory }.forEach { entry ->
                val file = File(installed, entry.name).canonicalFile
                require(file.toPath().startsWith(installed.canonicalFile.toPath()))
                requireNotNull(file.parentFile).mkdirs()
                zip.getInputStream(entry).use { source -> file.outputStream().use { source.copyTo(it) } }
                if (entry.name.endsWith(".dex")) file.setReadOnly()
            }
            JSONObject(zip.getInputStream(zip.getEntry("plugin.json")).reader().readText())
        }
        val dexFiles = File(installed, "code").listFiles()!!.filter { it.extension == "dex" }.sorted().joinToString(File.pathSeparator) { it.absolutePath }
        val loader = object : DexClassLoader(dexFiles, File(installed, "optimized").also { it.mkdirs() }.absolutePath, File(installed, "lib/arm64-v8a").absolutePath, KigPlugin::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
                val type = findLoadedClass(name) ?: if (name.startsWith("com.kigtts.clone.") || name.startsWith("com.k2fsa.sherpa.")) findClass(name) else super.loadClass(name, false)
                if (resolve) resolveClass(type)
                type
            }
        }
        val plugin = loader.loadClass(manifest.getString("entryClass")).getDeclaredConstructor().newInstance() as KigPlugin
        plugin.attach(object : PluginEnvironment {
            override val storage = storage
            override val assets = external
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("No network allowed")
        })
        val label = arguments.getString("label", "baseline")!!
        val output = File(external, "performance/$label").also { it.mkdirs() }
        val report = JSONObject().put("model", "ZIPVOICE").put("label", label).put("threads", threads)
            .put("device", android.os.Build.DEVICE).put("modelNumber", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
            .put("text", "你好，欢迎使用离线语音克隆。").put("pluginVersion", manifest.getString("version"))
        val runs = JSONArray()
        report.put("runs", runs)
        try {
            val engine = requireNotNull(plugin.tts)
            engine.control(TtsControl(TtsControlOperation.SET_RESIDENT, resident = true))
            for (name in listOf("cold", "warm")) {
                val samples = JSONArray()
                val timeline = JSONArray()
                var peakPssKiB = 0
                var peakNativeBytes = 0L
                val monitor = launch(Dispatchers.IO) {
                    while (isActive) {
                        val memory = android.os.Debug.MemoryInfo()
                        android.os.Debug.getMemoryInfo(memory)
                        peakPssKiB = maxOf(peakPssKiB, memory.totalPss)
                        peakNativeBytes = maxOf(peakNativeBytes, android.os.Debug.getNativeHeapAllocatedSize())
                        delay(500)
                    }
                }
                var totalSamples = 0L
                var firstAny = -1L
                var firstValid = -1L
                var finished = false
                var startedCount = 0
                var sampleRate = 0
                var coveredUntil = 0.0
                var underrunMs = 0.0
                var underruns = 0
                val started = android.os.SystemClock.elapsedRealtime()
                val pcm = File(output, "$name.pcm")
                try { pcm.outputStream().use { stream ->
                    withTimeout(600_000) { engine.synthesize(TtsRequest(report.getString("text"))).collect { event -> when(event) {
                        SynthesisEvent.Started -> startedCount++
                        is SynthesisEvent.Audio -> {
                            val arrived = android.os.SystemClock.elapsedRealtime() - started
                            val data = event.audio.bytes
                            if (firstAny < 0) firstAny = arrived
                            val valid = data.indices.step(2).any { index -> kotlin.math.abs(((data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)).toShort().toInt()) > 16 }
                            if (valid && firstValid < 0) firstValid = arrived
                            assertEquals(1, event.audio.channels); assertEquals(0, data.size % 2)
                            sampleRate = event.audio.sampleRate
                            val frames = data.size / 2
                            timeline.put(JSONObject().put("arrivalMs", arrived).put("startSample", totalSamples).put("endSample", totalSamples + frames).put("nonSilent", valid))
                            if (totalSamples == 0L) coveredUntil = arrived.toDouble()
                            if (arrived > coveredUntil + 2 && totalSamples > 0) { underruns++; underrunMs += arrived - coveredUntil; coveredUntil = arrived.toDouble() }
                            coveredUntil += frames * 1000.0 / sampleRate
                            totalSamples += frames
                            stream.write(data)
                        }
                        is SynthesisEvent.Finished -> finished = true
                    } } }
                } } finally { monitor.cancelAndJoin() }
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                assertEquals(1, startedCount); assertTrue(finished); assertTrue(firstValid >= 0); assertEquals(24000, sampleRate)
                assertEquals(totalSamples * 2, pcm.length())
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + pcm.length().toInt())
                    .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(pcm.length().toInt()).array()
                File(output, "$name.wav").outputStream().use { out -> out.write(header); pcm.inputStream().use { it.copyTo(out) } }
                val nativeProfile = storage.file("tmp/last-profile.json").takeIf { it.isFile }?.readText()
                val duration = totalSamples.toDouble() / sampleRate
                val round = JSONObject().put("name", name).put("firstAnyPcmMs", firstAny).put("firstValidPcmMs", firstValid).put("totalMs", elapsed)
                    .put("samples", totalSamples).put("durationSeconds", duration).put("rtf", elapsed / 1000.0 / duration)
                    .put("sampledPeakPssKiB", peakPssKiB).put("sampledPeakNativeAllocBytes", peakNativeBytes).put("memorySampleIntervalMs", 500)
                    .put("zeroPrebufferUnderruns", underruns).put("zeroPrebufferUnderrunMs", underrunMs).put("chunks", timeline)
                if (nativeProfile != null) round.put("profile", JSONObject(nativeProfile))
                runs.put(round)
                File(output, "report.json").writeText(report.toString(2))
                android.util.Log.i("ClonePerf", round.toString())
            }
            engine.control(TtsControl(TtsControlOperation.RELEASE))
            val prepare = android.os.SystemClock.elapsedRealtime()
            engine.control(TtsControl(TtsControlOperation.PREPARE))
            report.put("reloadPrepareMs", android.os.SystemClock.elapsedRealtime() - prepare)
            engine.control(TtsControl(TtsControlOperation.RELEASE))
            File(output, "report.json").writeText(report.toString(2))
        } finally { plugin.close() }
    }
}
