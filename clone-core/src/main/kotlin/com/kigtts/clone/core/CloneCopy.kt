package com.kigtts.clone.core

import com.kigtts.plugin.api.PluginStrings

internal class CloneCopy(
    private val strings: PluginStrings = PluginStrings(emptyMap()),
    private val locale: String = "zh-CN",
) {
    operator fun invoke(key: String, fallback: String, vararg values: Pair<String, String>): String =
        strings.text(key, locale, values.toMap(), fallback)

    fun error(exception: Exception, key: String, fallback: String): String =
        if (exception is CloneMessageException) invoke(exception.key, requireNotNull(exception.message))
        else invoke(key, fallback)
}

internal class CloneMessageException(val key: String, fallback: String) : IllegalArgumentException(fallback)
