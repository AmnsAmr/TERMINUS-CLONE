package com.necroware.terminusplayer.data.api.subsonic

import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Query

class SubsonicApiContractTest {
    private val authenticationQueries = setOf("u", "t", "s", "v", "c", "f")

    @Test
    fun favoritesUseSubsonicRestRoutesAndSongIds() {
        assertContract("star", "rest/star", authenticationQueries + "id")
        assertContract("unstar", "rest/unstar", authenticationQueries + "id")
        assertContract("getStarred2", "rest/getStarred2", authenticationQueries)
    }

    @Test
    fun playlistOperationsUseSubsonicQueryParameters() {
        assertContract("getPlaylists", "rest/getPlaylists", authenticationQueries)
        assertContract("getPlaylist", "rest/getPlaylist", authenticationQueries + "id")
        assertContract("createPlaylist", "rest/createPlaylist", authenticationQueries + setOf("name", "playlistId", "songId"))
        assertContract(
            "updatePlaylist",
            "rest/updatePlaylist",
            authenticationQueries + setOf("playlistId", "name", "songIdToAdd", "songIndexToRemove")
        )
        assertContract("deletePlaylist", "rest/deletePlaylist", authenticationQueries + "id")
    }

    @Test
    fun mediaTransferOperationsUseSubsonicQueryParameters() {
        assertContract("download", "rest/download", authenticationQueries + "id")
        assertContract("stream", "rest/stream", authenticationQueries + setOf("id", "maxBitRate", "format"))
    }

    private fun assertContract(methodName: String, path: String, expectedQueries: Set<String>) {
        val method = SubsonicApiService::class.java.methods.single { it.name == methodName }
        assertEquals(path, requireNotNull(method.getAnnotation(GET::class.java)).value)
        val queryNames = method.parameterAnnotations.flatMap { annotations ->
            annotations.mapNotNull { it as? Query }.map { it.value }
        }.toSet()
        assertEquals(expectedQueries, queryNames)
    }
}
