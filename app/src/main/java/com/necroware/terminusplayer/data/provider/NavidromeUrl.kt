package com.necroware.terminusplayer.data.provider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun parseNavidromeBaseUrl(rawUrl: String): HttpUrl? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null
    val hasHttpScheme = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    if (!hasHttpScheme && "://" in trimmed) return null
    val withScheme = if (hasHttpScheme) trimmed else "http://$trimmed"
    val parsed = withScheme.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) return null
    return parsed.newBuilder().encodedPath(parsed.encodedPath.trimEnd('/') + "/").build()
}

internal fun buildSubsonicToken(password: String, salt: String): String {
    val digest = java.security.MessageDigest.getInstance("MD5")
    return digest.digest((password + salt).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun newSubsonicSalt(): String {
    val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..16).map { allowedChars.random() }.joinToString("")
}
