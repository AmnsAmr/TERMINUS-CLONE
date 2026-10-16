package com.necroware.terminusplayer.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistConflictPolicyTest {
    @Test
    fun localOnlyPendingEditsDoNotConflictWithUnchangedRemoteBaseline() {
        assertFalse(conflict(remote = listOf("a", "b"), local = listOf("a", "b", "c")))
    }

    @Test
    fun divergentRemoteEditsConflictAndRemainForExplicitResolution() {
        assertTrue(conflict(remote = listOf("a", "x"), local = listOf("a", "b", "c")))
    }

    @Test
    fun alreadyConvergedRemoteAndLocalSequencesDoNotConflict() {
        assertFalse(conflict(remote = listOf("a", "b", "c"), local = listOf("a", "b", "c")))
    }

    @Test
    fun simultaneousDifferentRenamesConflict() {
        assertTrue(
            requiresPlaylistSyncConflict(
                baselineKnown = true,
                baselineSignature = "same-tracks",
                baselineName = "Old",
                remoteSignature = "same-tracks",
                remoteName = "Remote rename",
                localSignature = "same-tracks",
                localName = "Device rename"
            )
        )
    }

    @Test
    fun unknownLegacyBaselineConflictsUnlessBothSidesAlreadyConverged() {
        assertTrue(
            requiresPlaylistSyncConflict(
                baselineKnown = false,
                baselineSignature = null,
                baselineName = null,
                remoteSignature = "remote",
                remoteName = "Remote",
                localSignature = "local",
                localName = "Local"
            )
        )
        assertFalse(
            requiresPlaylistSyncConflict(
                baselineKnown = false,
                baselineSignature = null,
                baselineName = null,
                remoteSignature = "same",
                remoteName = "Same",
                localSignature = "same",
                localName = "Same"
            )
        )
    }

    private fun conflict(remote: List<String>, local: List<String>) = requiresPlaylistSyncConflict(
        baselineKnown = true,
        baselineSignature = listOf("a", "b").joinToString("|"),
        baselineName = "Mix",
        remoteSignature = remote.joinToString("|"),
        remoteName = "Mix",
        localSignature = local.joinToString("|"),
        localName = "Mix"
    )
}
