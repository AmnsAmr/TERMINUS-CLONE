package com.necroware.terminusplayer.data.model

private val lrcTimestamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")

/** Converts plain or timestamped embedded/LRC text to the player's common lyrics model. */
fun parseLyricsText(text: String?): SyncedLyrics? {
    val source = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val timed = source.lineSequence().flatMap { line ->
        val tags = lrcTimestamp.findAll(line).toList()
        val lyric = lrcTimestamp.replace(line, "").trim()
        if (tags.isEmpty() || lyric.isEmpty()) emptySequence()
        else tags.asSequence().map { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val millis = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            LyricLine((minutes * 60L + seconds) * 1_000L + millis, lyric)
        }
    }.sortedBy { it.startMs }.toList()
    val lines = timed.ifEmpty {
        source.lineSequence().map(String::trim).filter(String::isNotEmpty).map { LyricLine(0L, it) }.toList()
    }
    return lines.takeIf { it.isNotEmpty() }?.let(::SyncedLyrics)
}

fun SyncedLyrics.toLyricsCacheFields(): Pair<String?, String?> {
    val timed = lines.filter { it.startMs > 0L }
    if (timed.isNotEmpty()) {
        val lrc = timed.joinToString("\n") { line ->
            val totalHundredths = line.startMs.coerceAtLeast(0L) / 10L
            val minutes = totalHundredths / 6_000L
            val seconds = (totalHundredths / 100L) % 60L
            val hundredths = totalHundredths % 100L
            "[%02d:%02d.%02d]%s".format(minutes, seconds, hundredths, line.text)
        }
        val plain = lines.filter { it.startMs <= 0L }.joinToString("\n") { it.text }
            .takeIf(String::isNotBlank)
        return plain to lrc
    }
    return lines.joinToString("\n") { it.text }.takeIf(String::isNotBlank) to null
}
