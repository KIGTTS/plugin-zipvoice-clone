package com.kigtts.clone.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
internal fun File.readObject(): JsonObject = json.parseToJsonElement(readText()).jsonObject
internal fun JsonObject.string(key: String, default: String = "") = this[key]?.jsonPrimitive?.content ?: default
internal fun atomicText(file: File, text: String) {
    file.parentFile.mkdirs()
    val temp = File(file.parentFile, file.name + ".new")
    temp.writeText(text, Charsets.UTF_8)
    try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
}
internal fun child(root: File, path: String): File {
    require(path.isNotBlank() && path.length <= 240 && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000')) { throw CloneMessageException("resource.path_invalid", "资源路径无效") }
    require(path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { throw CloneMessageException("resource.path_invalid", "资源路径无效") }
    return File(root, path).canonicalFile.also { require(it.toPath().startsWith(root.canonicalFile.toPath())) { throw CloneMessageException("resource.outside", "资源路径越界") } }
}
internal suspend fun extractZip(source: File, target: File, limit: Long = 4L * 1024 * 1024 * 1024) {
    require(source.isFile) { throw CloneMessageException("zip.required", "请选择 ZIP 文件") }
    target.mkdirs()
    val names = mutableSetOf<String>()
    var total = 0L
    var entries = 0
    ZipInputStream(source.inputStream().buffered()).use { zip ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: break
            require(++entries <= 20_000) { throw CloneMessageException("zip.too_many", "ZIP 文件数量过多") }
            val name = entry.name.trimEnd('/')
            val file = child(target, name)
            require(names.add(name.lowercase(java.util.Locale.ROOT))) { throw CloneMessageException("zip.duplicate", "ZIP 包含重名文件") }
            if (entry.isDirectory) file.mkdirs() else {
                file.parentFile.mkdirs()
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= limit) { throw CloneMessageException("zip.size", "解压资源超出大小限制") }
                        output.write(buffer, 0, count)
                    }
                }
            }
            zip.closeEntry()
        }
    }
    require(entries > 0) { throw CloneMessageException("zip.empty", "ZIP 为空") }
}
internal fun replaceDirectory(staged: File, destination: File) {
    val backup = File(destination.parentFile, destination.name + ".previous")
    require(!backup.exists()) { throw CloneMessageException("resource.recover", "发现未完成的资源更新，请重新加载插件以恢复") }
    if (destination.exists()) check(destination.renameTo(backup)) { "无法保留旧资源" }
    if (!staged.renameTo(destination)) {
        if (backup.exists()) check(backup.renameTo(destination))
        throw CloneMessageException("resource.commit_failed", "无法提交资源，旧资源已保留")
    }
    backup.deleteRecursively()
}
internal fun restoreDirectory(destination: File) {
    val backup = File(destination.parentFile, destination.name + ".previous")
    if (!destination.exists() && backup.exists()) check(backup.renameTo(destination))
    if (destination.exists() && backup.exists()) backup.deleteRecursively()
}
internal suspend fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
