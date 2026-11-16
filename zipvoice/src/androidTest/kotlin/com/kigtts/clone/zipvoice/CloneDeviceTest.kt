package com.kigtts.clone.zipvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kigtts.clone.core.*
import com.kigtts.plugin.api.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dalvik.system.DexClassLoader
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class CloneDeviceTest {
    @Test fun realOfflineSynthesisAndLifecycle(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null))
        val input = File(external, "imports")
        val root = File(context.filesDir, "clone-smoke")
        val storage = PluginStorage(root)
        val store = CloneStore(storage, CloneKind.ZIPVOICE)
        if (!store.modelReady()) store.importModel(File(input, "model.zip"))
        if (store.voices().isEmpty()) store.addVoice(File(input, "reference.wav"), "官方测试样本", File(input, "reference.txt").readText())
        // Exercise the signed .kigplugin DEX/native payload, with SDK classes supplied by the parent.
        val installed = File(context.filesDir, "isolated-runtime-${System.nanoTime()}").also { it.mkdirs() }
        val manifest = ZipFile(File(input, "plugin.kigplugin")).use { zip ->
            zip.entries().toList().filter { !it.isDirectory }.forEach { entry ->
                val file = File(installed, entry.name).canonicalFile
                require(file.toPath().startsWith(installed.canonicalFile.toPath()))
                file.parentFile.mkdirs(); zip.getInputStream(entry).use { source -> file.outputStream().use { source.copyTo(it) } }
                if (entry.name.endsWith(".dex")) file.setReadOnly()
            }
            JSONObject(zip.getInputStream(zip.getEntry("plugin.json")).reader().readText())
        }
        val dexFiles = File(installed, "code").listFiles()!!.filter { it.extension == "dex" }.sorted().joinToString(File.pathSeparator) { it.absolutePath }
        val loader = object : DexClassLoader(dexFiles, File(installed, "optimized").also { it.mkdirs() }.absolutePath,
            File(installed, "lib/arm64-v8a").absolutePath, KigPlugin::class.java.classLoader) {
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
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("Offline synthesis must not access the network")
        })
        try {
            val engine = requireNotNull(plugin.tts)
            assertEquals(EngineAvailability.READY, engine.info.availability)
            val pcm = File(external, "synthesis.pcm")
            val started = android.os.SystemClock.elapsedRealtime()
            var first = -1L
            var chunks = 0
            var sampleRate = 0
            var finished = false
            pcm.outputStream().use { output ->
                withTimeout(600_000) {
                    engine.synthesize(TtsRequest("你好，欢迎使用离线语音克隆。")).collect { event -> when (event) {
                        is SynthesisEvent.Audio -> {
                            if (first < 0) first = android.os.SystemClock.elapsedRealtime() - started
                            sampleRate = event.audio.sampleRate
                            assertEquals(1, event.audio.channels)
                            assertEquals(0, event.audio.bytes.size % 2)
                            output.write(event.audio.bytes); chunks++
                        }
                        is SynthesisEvent.Finished -> finished = true
                        else -> Unit
                    } }
                }
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            assertTrue(finished); assertTrue(chunks > 0); assertEquals(24000, sampleRate)
            assertTrue(pcm.length() > 4800)
            val bytes = pcm.readBytes()
            assertTrue(bytes.any { it != 0.toByte() })
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + bytes.size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(bytes.size).array()
            File(external, "synthesis.wav").outputStream().use { it.write(header); it.write(bytes) }
            val duration = bytes.size / 2.0 / sampleRate
            val report = JSONObject().put("model", "ZIPVOICE").put("device", android.os.Build.MODEL)
                .put("api", android.os.Build.VERSION.SDK_INT).put("coldFirstAudioMs", first).put("coldTotalMs", elapsed)
                .put("durationSeconds", duration).put("rtf", elapsed / 1000.0 / duration).put("chunks", chunks)
            // Cancel after first audio; the channel closes and no Finished event is delivered.
            val cancelStart = android.os.SystemClock.elapsedRealtime()
            val cancelled = withTimeout(600_000) { engine.synthesize(TtsRequest("这是一段用于验证停止朗读的语音。")).take(2).toList() }
            assertTrue(cancelled[0] is SynthesisEvent.Started); assertTrue(cancelled[1] is SynthesisEvent.Audio)
            report.put("cancelAtFirstAudioTotalMs", android.os.SystemClock.elapsedRealtime() - cancelStart)
            engine.control(TtsControl(TtsControlOperation.PREPARE))
            engine.control(TtsControl(TtsControlOperation.RELEASE))
            File(external, "report.json").writeText(report.toString(2))
            android.util.Log.i("CloneSmoke", report.toString())
        } finally { plugin.close() }
    }
}
