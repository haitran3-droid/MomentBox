package com.ntq.training.momentbox.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.data.local.mediastore.*
import com.ntq.training.momentbox.feature.create.SelectedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GalleryState(
    val items: List<SelectedMedia> = emptyList(),
    val albums: List<MediaAlbum> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val hasMore: Boolean = true,
)

/** Một job tải tại một thời điểm, đổi quyền/album hủy trang cũ trước khi publish. */
class GalleryViewModel(
    private val repository: MediaStoreRepository,
    private val saved: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
) : ViewModel() {
    private val _state = MutableStateFlow(GalleryState())
    val state = _state.asStateFlow()
    var filter =
        runCatching { GalleryFilter.valueOf(saved["filter"] ?: "ALL") }
            .getOrDefault(GalleryFilter.ALL)
        private set

    var album: String? = saved["album"]
        private set

    private var imagesOnly = false
    private var job: Job? = null

    fun refresh(
        imageOnly: Boolean,
        filter: GalleryFilter = this.filter,
        album: String? = this.album,
    ) {
        this.filter = filter
        this.album = album
        saved["filter"] = filter.name
        saved["album"] = album
        imagesOnly = imageOnly
        job?.cancel()
        _state.value = GalleryState(loading = true)
        job =
            viewModelScope.launch {
                try {
                    val albums = repository.albums(imageOnly)

                    val page = repository.page(filter, album, imageOnly, 0)
                    _state.value = GalleryState(page, albums, hasMore = page.size == 60)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _state.value = GalleryState(error = true)
                }
            }
    }

    fun revoke() {
        job?.cancel()
        _state.value = GalleryState(hasMore = false)
    }

    fun next() {
        val old = _state.value
        if (old.loading || !old.hasMore || old.error) return
        _state.value = old.copy(loading = true)
        job =
            viewModelScope.launch {
                try {
                    val page = repository.page(filter, album, imagesOnly, old.items.size)
                    _state.value =
                        old.copy(
                            items = (old.items + page).distinctBy { it.id },
                            hasMore = page.size == 60,
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _state.value = old.copy(error = true)
                }
            }
    }
}
