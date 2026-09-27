package com.necroware.terminusplayer.ui.screens.upload

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.api.custom.UploadQuotaResponse
import com.necroware.terminusplayer.data.repository.NavidromeUploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val uploadRepository: NavidromeUploadRepository
) : ViewModel() {
    private val _quota = MutableStateFlow<UploadQuotaResponse?>(null)
    val quota = _quota.asStateFlow()

    private val _uploadStatus = MutableStateFlow("")
    val uploadStatus = _uploadStatus.asStateFlow()

    init { fetchQuota() }

    private fun fetchQuota() = viewModelScope.launch {
        _quota.value = uploadRepository.quota()
    }

    fun uploadFile(uri: Uri) = viewModelScope.launch {
        _uploadStatus.value = "Uploading..."
        try {
            val name = uploadRepository.upload(uri)
            _uploadStatus.value = "Uploaded $name successfully."
            _quota.value = uploadRepository.quota()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uploadStatus.value = "Failed: ${e.message ?: "Upload failed"}"
            if (e.message?.contains("quota", ignoreCase = true) == true) {
                _quota.value = uploadRepository.quota()
            }
        }
    }
}
