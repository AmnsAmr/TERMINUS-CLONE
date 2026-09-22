package com.necroware.terminusplayer.ui.screens.managesources

import android.content.Context
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ManageSourcesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefsRepo: UserPreferencesRepository,
    private val musicRepository: MusicRepository
) : ViewModel() {

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    private val _excludedFolders = MutableStateFlow<Set<String>>(emptySet())
    val excludedFolders: StateFlow<Set<String>> = _excludedFolders.asStateFlow()

    init {
        loadFolders()
        viewModelScope.launch {
            userPrefsRepo.preferences.collect { prefs ->
                _excludedFolders.value = prefs.excludedFolders
            }
        }
    }

    private fun loadFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val uniqueFolders = mutableSetOf<String>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                null
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: ""
                    val folderPath = File(path).parent
                    if (folderPath != null) {
                        uniqueFolders.add(folderPath)
                    }
                }
            }

            _folders.value = uniqueFolders.sorted()
        }
    }

    private var syncJob: kotlinx.coroutines.Job? = null

    fun toggleFolder(folderPath: String, isIncluded: Boolean) {
        viewModelScope.launch {
            val currentExcluded = userPrefsRepo.preferences.first().excludedFolders.toMutableSet()
            if (isIncluded) {
                currentExcluded.remove(folderPath)
            } else {
                currentExcluded.add(folderPath)
            }
            userPrefsRepo.setExcludedFolders(currentExcluded)
            
            syncJob?.cancel()
            syncJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(1000)
                musicRepository.syncLibrary()
            }
        }
    }
}
