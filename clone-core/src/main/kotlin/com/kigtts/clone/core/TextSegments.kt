package com.kigtts.clone.core

/** Lossless, bounded chunks; never bisect a Unicode surrogate pair. */
fun cloneTextSegments(text: String, maximumCodePoints: Int = 80): List<String> {
    require(maximumCodePoints in 4..500)
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val remaining = text.codePointCount(start, text.length)
        var end = if (remaining <= maximumCodePoints) text.length else text.offsetByCodePoints(start, maximumCodePoints)
        if (end < text.length) {
            val lower = text.offsetByCodePoints(start, maximumCodePoints / 2)
            val punctuation = (end - 1 downTo lower).firstOrNull { text[it] in "。！？；，.!?;,\n " }
            if (punctuation != null) end = punctuation + 1
        }
        result += text.substring(start, end)
        start = end
    }
    check(result.joinToString("") == text)
    return result
}
