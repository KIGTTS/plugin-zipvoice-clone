package com.kigtts.clone.core

import com.kigtts.plugin.api.*

internal fun cloneVoicePage(title: String, store: CloneStore, options: CloneOptions, prebakeSteps: Int? = null): PluginPage {
    fun discrete(title: String, action: String, value: Int, suffix: String) =
        PageBlock.Slider(PluginControl.Slider(title, action, value.coerceIn(SYNTHESIS_SETTINGS).toFloat(),
            MIN_SYNTHESIS_SETTING.toFloat(), MAX_SYNTHESIS_SETTING.toFloat(),
            steps = MAX_SYNTHESIS_SETTING - MIN_SYNTHESIS_SETTING - 1, suffix = suffix))
    val blocks = buildList {
        add(discrete("CPU 线程", "threads-saved", options.threads, " 线程"))
        if (prebakeSteps != null) {
            add(discrete("实时合成质量", "steps-saved", options.steps, " 档"))
            add(discrete("缓存合成质量", "prebake-steps-saved", prebakeSteps, " 档"))
        }
    }
    val selected = store.selected
    val toolbar = PageBlock.Actions(listOf(PageAction("import-pack", "导入语音包", PluginIcon.ADD)))
    val voices = store.voices().sortedWith(compareByDescending<CloneVoice> { it.pinned }.thenBy { it.order }.thenBy { it.name })
    return PluginPage("settings", title, listOf(toolbar, PageBlock.Group("设置", blocks)),
        management = ManagementList("clone-voice-packs", voices.map { voice ->
            val id=voice.id
            ManagementItem(id, voice.name, voice.remark, icon = PluginIcon.SPEAKER,
                image = voice.avatarResource(), selected = id == selected, pinned = voice.pinned,
                group = "语音包", primaryAction = "edit/$id")
        } + ManagementItem("add-voice-pack", "添加语音包", icon = PluginIcon.ADD, group = "语音包", primaryAction = "add-voice")))
}

internal fun cloneVoiceDetail(store: CloneStore, voice: CloneVoice): PluginPage {
    val id = voice.id
    val selected = store.selected == id
    val toolbar = PageBlock.Actions(listOf(
        PageAction("share/$id", "分享语音包", PluginIcon.EXPORT, symbol = "share"),
        PageAction("delete/$id", "删除语音包", PluginIcon.CLOSE, symbol = "delete")))
    val identity = PageBlock.Group("语音包资料", listOf(
        PageBlock.Input("名称", voice.name, "rename/$id", icon = PluginIcon.EDIT),
        PageBlock.Input("备注", voice.remark, "remark/$id", multiline = true, icon = PluginIcon.TEXT),
        PageBlock.Input("头像", if (voice.avatar == null) "未设置" else "已设置", "avatar/$id",
            icon = PluginIcon.IMAGE, trailingAction = voice.avatar?.let { PageAction("avatar-remove/$id", "移除头像", PluginIcon.CLOSE) }),
        PageBlock.Toggle("置顶", voice.pinned, "pin/$id", icon = PluginIcon.PIN)))
    val reference = PageBlock.Group("参考声音", listOf(
        PageBlock.Input("语言", when (voice.language) { "zh-CN" -> "中文"; "en-US" -> "英文"; else -> voice.language },
            "language/$id", icon = PluginIcon.TEXT),
        PageBlock.Input("参考文本", voice.referenceText, "transcript/$id", multiline = true, icon = PluginIcon.TEXT),
        PageBlock.Actions(listOf(PageAction("reference/$id", "更换参考 WAV", PluginIcon.MIC)))))
    val use = if (selected) emptyList() else listOf(PageBlock.Group("使用", listOf(
        PageBlock.Actions(listOf(PageAction("select/$id", "设为当前语音包", PluginIcon.SPEAKER))))))
    return PluginPage("voice/$id", voice.name, listOf(toolbar, identity, reference) + use,
        header = ManagementItem("voice-header", voice.name, if (selected) "当前语音包" else "", PluginIcon.SPEAKER,
            image = voice.avatarResource()))
}

private fun CloneVoice.avatarResource() = avatar?.let { PluginResource("voices/$id/$AVATAR_FILE") }
