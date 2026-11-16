package com.kigtts.clone.core

import com.kigtts.plugin.api.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoicePackTests {
    @Test fun exportImportRoundTripWithAvatarAndNoPrivateFiles() = runBlocking {
        val home=Files.createTempDirectory("voice-pack-roundtrip").toFile()
        try {
            val store=CloneStore(PluginStorage(home),CloneKind.ZIPVOICE)
            val raw=File(home,"tone.pcm")
            raw.writeBytes(Wav.pcm16(FloatArray(16000) { if (it%25<12) 0.1f else -0.1f }))
            val wav=File(home,"fixture.wav")
            Wav.wrapPcm(raw,wav,16000)
            val first=store.addVoice(wav,"青石/晚风","这是语音包测试。")
            val avatar=File(home,"icon.png")
            ImageIO.write(BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",avatar)
            store.setAvatar(first.id,avatar)
            store.updateMetadata(first.id,VoicePackMetadata(first.name,"便携备注",pinned=true,order=1))
            File(first.directory,"private-cache.bin").writeText("excluded")
            val exposed=store.packs().single()
            assertEquals("voices/${first.id}/avatar.png",exposed.avatarPath)
            assertTrue(exposed.editable && exposed.exportable && exposed.deletable)

            val export=exportVoicePack(store.storage,store.resolve(first.id))
            assertEquals("青石_晚风.zip",export.name)
            val source=store.storage.file(export.path)
            ZipFile(source).use { zip ->
                assertEquals(setOf("voice.json","reference.wav","avatar.png"),zip.entries().asSequence().map { it.name }.toSet())
                assertFalse(zip.getInputStream(zip.getEntry("voice.json")).bufferedReader().readText().contains(first.id))
            }
            val imported=store.importVoice(source)
            assertNotEquals(first.id,imported.id)
            assertEquals(first.referenceText,imported.referenceText)
            assertEquals("便携备注",imported.remark)
            assertArrayEquals(avatar.readBytes(),requireNotNull(imported.avatar).readBytes())
            assertArrayEquals(wav.readBytes(),imported.wav.readBytes())
            assertEquals(first.id,store.selected)

            val invalid=File(home,"bad.png").apply { writeText("no") }
            assertThrows(IllegalArgumentException::class.java) { store.setAvatar(first.id,invalid) }
            assertArrayEquals(avatar.readBytes(),requireNotNull(store.resolve(first.id).avatar).readBytes())

            val page=cloneVoicePage("语音克隆引擎",store,CloneOptions(4,4))
            assertEquals(1,page.blocks.filterIsInstance<PageBlock.Group>().size)
            assertEquals("import-pack",page.blocks.filterIsInstance<PageBlock.Actions>().single().actions.single().id)
            assertFalse(PluginJson.encodeToString(PluginPage.serializer(),page).contains("NPU"))
            val rows = requireNotNull(page.management).items
            assertEquals(3, rows.size)
            assertTrue(rows.dropLast(1).all { it.image != null && it.primaryAction == "edit/${it.id}" && it.selection == null })
            val add = rows.last()
            assertEquals("添加语音包", add.title)
            assertEquals("add-voice", add.primaryAction)
            assertEquals(rows.first().group, add.group)
            val detail = cloneVoiceDetail(store,store.resolve(first.id))
            assertEquals(exposed.avatarPath,detail.header?.image?.path)
            val edit = detail.blocks.filterIsInstance<PageBlock.Group>().flatMap { it.children }
            assertEquals(setOf("rename", "remark", "avatar", "language", "transcript"),
                edit.filterIsInstance<PageBlock.Input>().map { it.actionId.substringBefore('/') }.toSet())
            assertTrue(detail.blocks.filterIsInstance<PageBlock.Actions>().single().actions.any { it.id == "share/${first.id}" })

            store.delete(imported.id)
            assertTrue(first.wav.isFile)
            assertEquals(1,store.voices().size)
            store.delete(first.id)
            val emptyPage=cloneVoicePage("语音克隆引擎",store,CloneOptions())
            assertEquals("add-voice",emptyPage.management?.items?.single()?.primaryAction)
        } finally { home.deleteRecursively() }
    }
}
