package com.necroware.terminusplayer.ui.screens.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.api.custom.NavidromeNativeApiService
import com.necroware.terminusplayer.data.api.custom.UploadQuotaResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.source
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: NavidromeNativeApiService
) : ViewModel() {

    private val _quota = MutableStateFlow<UploadQuotaResponse?>(null)
    val quota = _quota.asStateFlow()

    private val _uploadStatus = MutableStateFlow<String>("")
    val uploadStatus = _uploadStatus.asStateFlow()

    init {
        fetchQuota()
    }

    private fun fetchQuota() = viewModelScope.launch {
        try {
            val response = api.getUploadQuota()
            if (response.isSuccessful) {
                _quota.value = response.body()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Quota is optional UI information; a later request can retry.
        }
    }

    fun uploadFile(uri: Uri) = viewModelScope.launch {
        _uploadStatus.value = "Uploading..."
        try {
            val name = withContext(Dispatchers.IO) { displayNameFor(uri) }
            
            val canRead = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            }
            if (!canRead) {
                _uploadStatus.value = "Error: Unreadable file"
                return@launch
            }
            
            val reqFile = object : okhttp3.RequestBody() {
                override fun contentType() = "audio/*".toMediaTypeOrNull()
                override fun writeTo(sink: okio.BufferedSink) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Selected file is no longer available")
                    inputStream.use {
                        it.source().use { source ->
                            sink.writeAll(source)
                        }
                    }
                }
            }
            val part = MultipartBody.Part.createFormData("file", name, reqFile)
            val response = api.uploadFile(part)
            if (response.isSuccessful) {
                _uploadStatus.value = "Uploaded $name successfully."
                fetchQuota()
            } else if (response.code() == 413) {
                _uploadStatus.value = "Failed: Server quota exceeded"
            } else {
                _uploadStatus.value = "Failed: ${response.code()}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uploadStatus.value = "Error: ${e.message}"
        }
    }

    private fun displayNameFor(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx) ?: ""
            }
        }
        return uri.lastPathSegment.orEmpty()
    }
}
