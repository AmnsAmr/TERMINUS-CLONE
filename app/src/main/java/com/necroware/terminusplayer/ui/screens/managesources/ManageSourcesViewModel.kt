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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _folderLoadError = MutableStateFlow<String?>(null)
    val folderLoadError: StateFlow<String?> = _folderLoadError.asStateFlow()

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
            val projection = arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.RELATIVE_PATH)
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

            val cursor = context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                null
            )
            if (cursor == null) {
                _folderLoadError.value = "Couldn't read audio folders from MediaStore."
                _folders.value = emptyList()
                return@launch
            }
            cursor.use {
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relativePathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                if (dataCol < 0 && relativePathCol < 0) {
                    _folderLoadError.value = "This device doesn't expose audio folder paths."
                    _folders.value = emptyList()
                    return@launch
                }
                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val folderPath = path?.let { File(it).parent }
                        ?: if (relativePathCol >= 0) cursor.getString(relativePathCol)?.trimEnd('/') else null
                    if (folderPath != null) {
                        uniqueFolders.add(folderPath)
                    }
                }
            }

            _folderLoadError.value = null
            _folders.value = uniqueFolders.sorted()
        }
    }

    private var syncJob: kotlinx.coroutines.Job? = null

    fun toggleFolder(folderPath: String, isIncluded: Boolean) {
        viewModelScope.launch {
            userPrefsRepo.setFolderExcluded(folderPath, excluded = !isIncluded)
            
            syncJob?.cancel()
            syncJob = viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(1000)
                try {
                    musicRepository.syncLibrary()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the source preferences; a later sync can retry the scan.
                }
            }
        }
    }
}
