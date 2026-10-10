package com.necroware.terminusplayer.util

import android.content.Context
import android.net.Uri
import java.io.File

interface AudioConverter {
    fun isOpus(context: Context, uri: Uri): Boolean
    suspend fun convertOpusToAac(context: Context, inputUri: Uri, originalName: String): File
}
