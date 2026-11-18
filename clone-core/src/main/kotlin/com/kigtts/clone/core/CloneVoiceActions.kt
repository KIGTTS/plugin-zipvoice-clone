package com.kigtts.clone.core

import com.kigtts.plugin.api.*
import java.io.File
import java.util.UUID

/** Editor operations run under the short storage lock, not the native model lock. */
internal class CloneVoiceActions(
    private val store: CloneStore,
    private val share: (String, String) -> PluginOutcome,
) {
    private val inbox = "imports/voice-clone-inbox"
    private val wavTypes = listOf("audio/wav", "audio/x-wav", "application/octet-stream")

    suspend fun execute(action: String, context: PluginContext, copy: CloneCopy = CloneCopy()): PluginOutcome? {
        val value = context.inputs["value"].orEmpty()
        val id = action.substringAfter('/', "")
        val refresh = PluginOutcome.RefreshUi
        return when (action.substringBefore('/')) {
            "new-pack", "add-voice" -> PluginOutcome.ImportResource(copy("voice.pick_wav", "选择参考 WAV"), inbox, "wav-imported", mimeTypes = wavTypes)
            "import-pack" -> PluginOutcome.ImportResource(copy("voice.import_zip", "导入语音包 ZIP"), inbox, "pack-imported",
                mimeTypes = listOf("application/zip", "application/octet-stream"))
            "pack-imported" -> { fromInbox(context) { store.importVoice(it) }; refresh }
            "wav-imported" -> fromInbox(context) { source ->
                val (key, _) = stageWav(source)
                PluginOutcome.TextInput(copy("voice.pack_name", "语音包名称"), "voice-name/$key", copy("voice.default_name", "我的声音"))
            }
            "voice-name" -> {
                require(value.trim().length in 1..80) { throw CloneMessageException("voice.name_limit", "名称需要 1–80 个字符") }
                atomicText(File(draft(id), "name.txt"), value.trim())
                PluginOutcome.TextInput(copy("voice.enter_transcript", "请输入与参考音频逐字匹配的文本"), "voice-text/$id", multiline = true)
            }
            "voice-text" -> {
                val pending = draft(id)
                val voice = store.addVoice(File(pending, "reference.wav"), File(pending, "name.txt").readText(), value)
                pending.deleteRecursively()
                PluginOutcome.OpenPage("voice/${voice.id}")
            }
            "edit" -> PluginOutcome.OpenPage("voice/${store.resolve(id).id}")
            "select" -> { store.selected = id; refresh }
            "rename" -> editText(id, copy("voice.name", "名称"), "renamed", CloneVoice::name)
            "renamed" -> { store.rename(id, value); refresh }
            "remark" -> editText(id, copy("voice.remark", "备注"), "remark-saved", CloneVoice::remark, multiline = true)
            "remark-saved" -> { metadata(id) { it.copy(remark = value) }; refresh }
            "pin" -> {
                val checked = context.inputs["checked"]?.toBooleanStrictOrNull()
                metadata(id) { it.copy(pinned = checked ?: !it.pinned) }
                refresh
            }
            "language" -> {
                store.resolve(id)
                PluginOutcome.Menu(copy("voice.reference_language", "参考声音语言"), listOf(
                    PageAction("language-zh/$id", copy("voice.zh", "中文"), PluginIcon.TEXT),
                    PageAction("language-en/$id", copy("voice.en", "英文"), PluginIcon.TEXT)))
            }
            "language-zh", "language-en" -> {
                store.editReference(id, language = if (action.startsWith("language-zh/")) "zh-CN" else "en-US")
                refresh
            }
            "transcript" -> editText(id, copy("voice.transcript", "参考文本"), "transcript-saved", CloneVoice::referenceText, multiline = true)
            "transcript-saved" -> { store.editReference(id, text = value); refresh }
            "reference" -> {
                store.resolve(id)
                PluginOutcome.ImportResource(copy("voice.new_wav", "选择新的参考 WAV"), inbox, "reference-picked/$id", mimeTypes = wavTypes)
            }
            "reference-picked" -> fromInbox(context) { source ->
                val voice = store.resolve(id)
                val (key, directory) = stageWav(source)
                atomicText(File(directory, "target.txt"), voice.id)
                PluginOutcome.TextInput(copy("voice.confirm_transcript", "确认新录音对应的参考文本"), "reference-saved/$key", voice.referenceText, multiline = true)
            }
            "reference-saved" -> {
                val pending = draft(id)
                store.replaceReference(File(pending, "target.txt").readText(), File(pending, "reference.wav"), value)
                pending.deleteRecursively()
                refresh
            }
            "avatar" -> {
                store.resolve(id)
                PluginOutcome.ImportResource(copy("voice.avatar_select", "设置语音包头像"), inbox, "avatar-saved/$id",
                    mimeTypes = listOf("image/png", "image/jpeg", "image/webp"))
            }
            "avatar-saved" -> { fromInbox(context) { store.setAvatar(id, it) }; refresh }
            "avatar-remove" -> { store.setAvatar(id, null); refresh }
            "share" -> {
                store.resolve(id)
                PluginOutcome.Confirm(copy("voice.share", "分享语音包"), copy("voice.share_body", "包含参考录音、文字和已设置的头像。只分享你有权分享的声音。"),
                    PageAction("share-confirmed/$id", copy("voice.share_confirm", "继续分享"), PluginIcon.EXPORT), cancelLabel = copy("voice.cancel", "取消"))
            }
            "share-confirmed" -> exportVoicePack(store.storage, store.resolve(id)).let { share(it.path, it.name) }
            "delete" -> {
                val voice = store.resolve(id)
                PluginOutcome.Confirm(copy("voice.delete", "删除语音包"), copy("voice.delete_body", "删除 {name} 及包内的参考录音和头像？", "name" to voice.name),
                    PageAction("deleted/$id", copy("voice.remove", "删除"), PluginIcon.CLOSE), cancelLabel = copy("voice.cancel", "取消"))
            }
            "deleted" -> { store.delete(id); PluginOutcome.OpenPage("settings") }
            else -> null
        }
    }

    private fun editText(id: String, title: String, action: String, initial: (CloneVoice) -> String, multiline: Boolean = false) =
        PluginOutcome.TextInput(title, "$action/$id", initial(store.resolve(id)), multiline = multiline)

    private fun metadata(id: String, update: (VoicePackMetadata) -> VoicePackMetadata) {
        val v = store.resolve(id)
        store.updateMetadata(v.id, update(VoicePackMetadata(v.name, v.remark, v.pinned, v.order)))
    }

    suspend fun <T> fromInbox(context: PluginContext, read: suspend (File) -> T): T {
        val path = requireNotNull(context.inputs["resourcePath"])
        val source = store.storage.file(path)
        try { return read(source) } finally { if (path.startsWith("$inbox/")) source.delete() }
    }

    private fun stageWav(source: File): Pair<String, File> {
        Wav.read(source)
        val key = UUID.randomUUID().toString()
        val directory = store.storage.directory("tmp/draft-$key")
        source.copyTo(File(directory, "reference.wav"))
        return key to directory
    }

    private fun draft(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return store.storage.file("tmp/draft-$id").also { require(it.isDirectory) { throw CloneMessageException("voice.draft_expired", "参考声音草稿已失效，请重新添加") } }
    }
}
