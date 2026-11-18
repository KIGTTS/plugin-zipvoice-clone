package com.kigtts.clone.core

import com.kigtts.plugin.api.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal const val AVATAR_FILE = "avatar.png"

/** Persisted, portable avatar format. Android callers additionally decode and re-encode images. */
internal fun copyVoiceAvatar(input: File, output: File) {
    validateVoiceAvatar(input)
    input.copyTo(output, overwrite = true)
}

internal fun validateVoiceAvatar(file: File) {
    require(file.isFile && file.length() in 45..2L * 1024 * 1024) { throw CloneMessageException("voice.avatar_invalid", "头像图片无效或过大") }
    RandomAccessFile(file, "r").use {
        require(it.readLong() == 0x89504e470d0a1a0aUL.toLong() && it.readInt() == 13 &&
            it.readInt() == 0x49484452) { throw CloneMessageException("voice.avatar_png", "语音包头像必须是 PNG") }
        require(it.readInt() in 1..2048 && it.readInt() in 1..2048) { throw CloneMessageException("voice.avatar_dimensions", "头像尺寸无效") }
    }
}

internal data class ExportedVoice(val path: String, val name: String) {
    fun outcome(owner: String, copy: CloneCopy = CloneCopy()) = PluginOutcome.ExportResource(copy("voice.export", "导出语音包"), PluginResource(path, owner),
        name, "application/zip", temporary = true)
}

internal suspend fun exportVoicePack(storage: PluginStorage, voice: CloneVoice): ExportedVoice {
    val path = "tmp/exports/${UUID.randomUUID()}.zip"
    val output=storage.file(path)
    output.parentFile.mkdirs()
    // The host normally deletes the staging ZIP after export. Bound abandoned staging files too.
    val olderThan=System.currentTimeMillis()-24L*60*60*1000
    output.parentFile.listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9a-f-]{36}\\.zip")) && it.lastModified()<olderThan }
        ?.forEach { it.delete() }
    try {
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            val description=buildJsonObject {
                put("formatVersion",1); put("name",voice.name); put("language",voice.language)
                put("referenceText",voice.referenceText); put("remark",voice.remark)
            }.toString().toByteArray(Charsets.UTF_8)
            entry("voice.json",description)
            for (file in listOfNotNull(voice.wav, voice.avatar)) {
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { source -> source.copyTo(zip) }
                zip.closeEntry()
            }
        }
    } catch (error: Exception) {
        output.delete()
        throw error
    }
    val base=voice.name.map { if(it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }
        .joinToString("").trim(' ','.').take(64).ifBlank { "语音包" }
    return ExportedVoice(path,"$base.zip")
}

internal fun replaceVoiceAvatar(voice: CloneVoice, source: File?, normalize: (File, File) -> Unit) {
    val output=File(voice.directory, AVATAR_FILE)
    if (source==null) { if(output.exists()) check(output.delete()); return }
    val temp=File(voice.directory, ".avatar-${UUID.randomUUID()}.tmp")
    try {
        normalize(source,temp); validateVoiceAvatar(temp)
        try { Files.move(temp.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { temp.delete() }
}
