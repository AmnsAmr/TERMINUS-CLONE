package com.necroware.terminusplayer.data.model

import java.util.Locale

fun parseGenreTags(value: String?): List<String> = value
    .orEmpty()
    .split(Regex("[,;|\\r\\n]+"))
    .map { it.trim().replace(Regex("\\s+"), " ") }
    .filter(String::isNotBlank)
    .distinctBy(::normalizeGenreKey)

fun normalizeGenreKey(value: String): String = value
    .trim()
    .replace(Regex("\\s+"), " ")
    .lowercase(Locale.ROOT)
