package com.necroware.terminusplayer.data.api.subsonic.model

import com.squareup.moshi.Moshi
import org.junit.Test
import org.junit.Assert.assertNotNull

class MoshiTest {
    @Test
    fun testParse() {
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(SubsonicResponse::class.java)

        val json = """
        {
            "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "lyricsList": {
                    "error": {
                        "code": 70,
                        "message": "Lyrics not found"
                    }
                }
            }
        }
        """.trimIndent()
        
        val response = adapter.fromJson(json)
        assertNotNull(response)
    }
}
