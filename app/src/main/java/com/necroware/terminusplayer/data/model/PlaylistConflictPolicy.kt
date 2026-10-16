package com.necroware.terminusplayer.data.model

/**
 * A conflict exists when both sides moved away from the saved baseline and
 * have not converged to the same ordered contents and name.
 */
fun requiresPlaylistSyncConflict(
    baselineKnown: Boolean,
    baselineSignature: String?,
    baselineName: String?,
    remoteSignature: String,
    remoteName: String,
    localSignature: String,
    localName: String
): Boolean {
    val sidesConverged = remoteSignature == localSignature && remoteName == localName
    if (sidesConverged) return false
    if (!baselineKnown || baselineSignature == null || baselineName == null) return true
    val remoteChanged = remoteSignature != baselineSignature || remoteName != baselineName
    return remoteChanged
}
