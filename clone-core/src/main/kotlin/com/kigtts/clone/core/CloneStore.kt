package com.kigtts.clone.core

import com.kigtts.plugin.api.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*

enum class CloneKind(val key: String, val title: String, val modelFiles: List<String>) {
    ZIPVOICE("zipvoice", "ZipVoice", listOf("encoder.int8.onnx", "decoder.int8.onnx", "vocos_24khz.onnx", "tokens.txt", "lexicon.txt")),
    QWEN3("qwen3", "Qwen3-TTS", listOf("qwen-talker-0.6b-base-Q4_K_M.gguf", "qwen-tokenizer-12hz-Q4_K_M.gguf"))
}
data class CloneVoice(val id: String, val name: String, val language: String, val referenceText: String, val directory: File,
                      val remark: String = "", val pinned: Boolean = false, val order: Long = 0,
                      val referenceRevision: String = "", val referenceAudio: ReferenceAudio? = null) {
    val wav get() = File(directory, "reference.wav")
    val avatar get() = File(directory, AVATAR_FILE).takeIf { it.isFile }
}

class CloneStore(val storage: PluginStorage, val kind: CloneKind,
                 private val normalizeAvatar: (File, File) -> Unit = ::copyVoiceAvatar,
                 private val bundledModel: File? = null) {
    private val importedModel = File(storage.directory("models"), "active")
    internal val modelSource: CloneModelSource?
        get() {
            val bundled = bundledModel?.takeIf(::isCompleteModel)
            if (isCompleteModel(importedModel) && (bundled == null || !sameManifest(importedModel, bundled)))
                return CloneModelSource.IMPORTED
            return bundled?.let { CloneModelSource.BUNDLED }
        }
    val modelDirectory: File
        get() = if (modelSource == CloneModelSource.BUNDLED) checkNotNull(bundledModel) else importedModel
    private val voicesDirectory = storage.directory("voices")
    private val selection = storage.file("config/selected.txt")
    private var cachedReference: Pair<String, ReferenceAudio>? = null
    init {
        restoreDirectory(importedModel)
        voicesDirectory.listFiles()?.filter { it.name.endsWith(".previous") }?.forEach { restoreDirectory(File(voicesDirectory, it.name.removeSuffix(".previous"))) }
    }
    fun modelReady() = modelSource != null
    fun modelLicense() = runCatching { File(modelDirectory, "model.json").readObject().string("license") }.getOrDefault("")

    private fun isCompleteModel(directory: File) = File(directory, "model.json").isFile &&
        kind.modelFiles.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }

    private fun sameManifest(first: File, second: File): Boolean {
        val a = File(first, "model.json")
        val b = File(second, "model.json")
        return a.length() == b.length() && runCatching { a.readBytes().contentEquals(b.readBytes()) }.getOrDefault(false)
    }
    var selected: String?
        get() = selection.takeIf { it.isFile }?.readText()?.trim()?.takeIf { value -> voices().any { it.id == value } }
        set(value) { if (value == null) selection.delete() else { resolve(value); atomicText(selection, value) } }
    fun voices(): List<CloneVoice> = voicesDirectory.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.endsWith(".previous") }
        ?.mapNotNull { runCatching { readVoice(it) }.getOrNull() }?.sortedBy { it.name } ?: emptyList()
    fun voiceInfos() = voices().map { VoiceInfo(it.id, it.name, it.language, "离线参考音频克隆", true, it.name, it.id) }
    fun packs() = voices().map { v -> VoicePackInfo(v.id, VoicePackMetadata(v.name, v.remark, v.pinned, v.order),
        listOf(v.id), avatarPath = v.avatar?.let { "voices/${v.id}/$AVATAR_FILE" },
        editable = true, exportable = true, deletable = true) }
    fun referenceVersion(): String {
        val changes = voices().mapNotNull { voice -> voice.referenceRevision.takeIf { it.isNotBlank() } }
        if (changes.isEmpty()) return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(changes.sorted().joinToString(",").toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }

    /** ZIPVOICE consumes detached PCM so replacing or deleting a voice need not wait for native audio. */
    fun synthesisVoice(id: String?): CloneVoice {
        val voice = resolve(id)
        if (kind != CloneKind.ZIPVOICE) return voice
        val key = "${voice.id}/${voice.referenceRevision}"
        val audio = cachedReference?.takeIf { it.first == key }?.second
            ?: Wav.read(voice.wav).also { cachedReference = key to it }
        return voice.copy(referenceAudio = audio)
    }
    fun resolve(id: String?): CloneVoice {
        val target = id ?: selected ?: voices().firstOrNull()?.id
        return voices().firstOrNull { it.id == target } ?: throw EngineException(EngineError.NOT_CONFIGURED, "请先添加或选择参考声音")
    }
    private fun readVoice(directory: File): CloneVoice {
        val data = File(directory, "voice.json").readObject()
        val id = data.string("id")
        require(id == directory.name && id.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        val name = data.string("name").trim(); val transcript = data.string("referenceText").trim()
        require(name.length in 1..80 && transcript.length in 1..2000 && File(directory, "reference.wav").isFile)
        val language = data.string("language", "zh-CN")
        require(language.length in 2..32)
        return CloneVoice(id, name, language, transcript, directory, data.string("remark").take(500),
            data["pinned"]?.jsonPrimitive?.booleanOrNull ?: false, data["order"]?.jsonPrimitive?.longOrNull ?: 0L,
            data.string("referenceRevision"))
    }
    suspend fun importModel(source: File) {
        val stage = storage.directory("tmp/model-${UUID.randomUUID()}")
        try {
            extractZip(source, stage)
            val manifest = File(stage, "model.json").readObject()
            require(manifest.string("engine") == kind.key && manifest["formatVersion"]?.jsonPrimitive?.int == 1) { "模型包与当前引擎不匹配" }
            require(manifest.string("license").isNotBlank()) { "模型包必须标明权重许可" }
            val hashes = manifest["sha256"]?.jsonObject ?: error("模型包缺少 SHA-256 清单")
            for (path in kind.modelFiles) {
                val file = child(stage, path)
                require(file.isFile && file.length() > 0) { "模型包缺少 $path" }
                val expected = hashes[path]?.jsonPrimitive?.content ?: error("缺少 $path 的 SHA-256")
                require(expected.matches(Regex("[a-fA-F0-9]{64}")) && sha256(file).equals(expected, true)) { "$path 校验失败" }
            }
            replaceDirectory(stage, importedModel)
        } finally { stage.deleteRecursively() }
    }
    suspend fun importVoice(source: File, replaceId: String? = null): CloneVoice {
        val stage = storage.directory("tmp/voice-${UUID.randomUUID()}")
        try {
            extractZip(source, stage, 6L * 1024 * 1024)
            require(stage.listFiles().orEmpty().all { it.isFile && it.name in setOf("voice.json", "reference.wav", AVATAR_FILE) }) {
                throw CloneMessageException("voice.pack_contents", "语音包只能包含说明、参考音频和头像")
            }
            val metadata = File(stage, "voice.json")
            require(metadata.isFile && metadata.length() in 2..16*1024L) { throw CloneMessageException("voice.pack_metadata", "缺少有效的语音包说明") }
            val data = metadata.readObject()
            require("formatVersion" !in data || data["formatVersion"]?.jsonPrimitive?.intOrNull == 1) { throw CloneMessageException("voice.pack_version", "不支持此语音包版本") }
            val old = replaceId?.let(::resolve)
            return saveVoice(File(stage, "reference.wav"), old?.name ?: data.string("name"), data.string("referenceText"),
                data.string("language", "zh-CN"), old?.avatar ?: File(stage, AVATAR_FILE).takeIf { it.isFile },
                old?.remark ?: data.string("remark"), old)
        } finally { stage.deleteRecursively() }
    }
    fun addVoice(wav: File, name: String, text: String, language: String = "zh-CN"): CloneVoice =
        saveVoice(wav,name,text,language,null,"",null)

    fun replaceReference(id: String, wav: File, text: String) = resolve(id).let { voice ->
        saveVoice(wav, voice.name, text, voice.language, voice.avatar, voice.remark, voice)
    }

    fun editReference(id: String, text: String? = null, language: String? = null) {
        val voice = resolve(id)
        val updatedText = text?.trim() ?: voice.referenceText
        val updatedLanguage = language?.trim() ?: voice.language
        require(updatedText.length in 1..2000) { throw CloneMessageException("voice.transcript_limit", "参考文本需要 1–2000 个字符") }
        require(updatedLanguage in setOf("zh-CN", "en-US") || updatedLanguage == voice.language) { throw CloneMessageException("voice.language_limit", "请选择中文或英文") }
        if (updatedText == voice.referenceText && updatedLanguage == voice.language) return
        val file = File(voice.directory, "voice.json")
        atomicText(file, JsonObject(file.readObject().toMutableMap().apply {
            put("referenceText", JsonPrimitive(updatedText))
            put("language", JsonPrimitive(updatedLanguage))
            put("referenceRevision", JsonPrimitive(UUID.randomUUID().toString()))
        }).toString())
    }

    private fun saveVoice(wav: File, name: String, text: String, language: String,
                          avatar: File?, remark: String, old: CloneVoice?): CloneVoice {
        require(name.trim().length in 1..80 && text.trim().length in 1..2000 &&
            language.length in 2..32 && remark.length <= 500) { throw CloneMessageException("voice.pack_invalid", "语音包资料无效") }
        Wav.read(wav)
        val id = old?.id ?: UUID.randomUUID().toString()
        val stage = File(voicesDirectory, ".${UUID.randomUUID()}").also { check(it.mkdir()) }
        try {
            wav.copyTo(File(stage, "reference.wav"))
            avatar?.let {
                val destination=File(stage, AVATAR_FILE)
                normalizeAvatar(it,destination); validateVoiceAvatar(destination)
            }
            atomicText(File(stage, "voice.json"), buildJsonObject {
                put("formatVersion", 1); put("id", id); put("name", name.trim()); put("language", language)
                put("referenceText", text.trim()); put("remark",remark.trim())
                put("pinned",old?.pinned ?: false); put("order",old?.order ?: 0L)
                if (old != null) put("referenceRevision", UUID.randomUUID().toString())
            }.toString())
            val destination = File(voicesDirectory, id)
            replaceDirectory(stage,destination)
            if (selected == null) selected = id
            return readVoice(destination)
        } finally { stage.deleteRecursively() }
    }
    fun setAvatar(id: String, source: File?) = replaceVoiceAvatar(resolve(id),source,normalizeAvatar)

    fun updateMetadata(id: String, metadata: VoicePackMetadata) {
        require(metadata.name.trim().length in 1..80 && metadata.remark.length <= 500) { throw CloneMessageException("voice.metadata_limit", "名称或备注过长") }
        val file=File(resolve(id).directory,"voice.json")
        val fields=file.readObject().toMutableMap()
        fields["name"]=JsonPrimitive(metadata.name.trim()); fields["remark"]=JsonPrimitive(metadata.remark.trim())
        fields["pinned"]=JsonPrimitive(metadata.pinned); fields["order"]=JsonPrimitive(metadata.order)
        atomicText(file,JsonObject(fields).toString())
    }

    fun delete(id: String) {
        val voice = resolve(id)
        check(voice.directory.deleteRecursively())
        if (cachedReference?.first?.startsWith("$id/") == true) cachedReference = null
        if (selection.isFile && selection.readText().trim() == id) selection.delete()
    }
    fun rename(id: String, name: String) {
        require(name.trim().length in 1..80) { throw CloneMessageException("voice.name_limit", "名称需要 1–80 个字符") }
        val file = File(resolve(id).directory, "voice.json")
        atomicText(file, JsonObject(file.readObject().toMutableMap().also { it["name"] = JsonPrimitive(name.trim()) }).toString())
    }
}

internal enum class CloneModelSource(val id: String) {
    IMPORTED("imported"), BUNDLED("bundled")
}
