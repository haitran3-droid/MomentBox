package com.ntq.training.momentbox.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class PlayerLibraryState(
    val loading: Boolean = true,
    val library: PlayerLibrary? = null,
    val error: Boolean = false
)

class PlayerViewModel(private val repository: PlayerLibraryRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PlayerLibraryState());
    val state = mutable.asStateFlow()
    private var loading: Job? = null
    private var preferredTrackId: String? = null

    init {
        reload()
    }

    fun reload(preferred: String? = preferredTrackId) {
        preferredTrackId = preferred; loading?.cancel(); loading = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true); try {
            val library = repository.load(preferred); mutable.value =
                PlayerLibraryState(false, library, library.remoteError)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            mutable.value = PlayerLibraryState(false, null, true)
        }
        }
    }

    suspend fun favorite(track: Track, value: Boolean) {
        repository.favorite(track.ownerScope, track.id, value); mutable.value = mutable.value.let {
            it.copy(library = it.library?.let { library ->
                library.copy(tracks = library.tracks.map {
                    if (it.id == track.id) it.copy(favorite = value) else it
                })
            })
        }
    }
}