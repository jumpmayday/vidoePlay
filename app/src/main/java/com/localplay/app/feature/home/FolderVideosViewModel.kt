package com.localplay.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localplay.app.LocalPlayApp
import com.localplay.app.data.model.FolderGroup
import com.localplay.app.data.model.VideoItem
import com.localplay.app.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderVideosUiState(
    val folder: FolderGroup? = null,
    val videos: List<VideoItem> = emptyList(),
    val query: String = "",
    val selectionMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val pendingDelete: VideoItem? = null,
    val pendingBatchDelete: Boolean = false,
    val resumeTarget: VideoItem? = null,
    val contextMenuVideo: VideoItem? = null
)

class FolderVideosViewModel(
    private val folderKey: String,
    private val repository: VideoRepository = LocalPlayApp.instance.videoRepository
) : ViewModel() {

    private val extras = MutableStateFlow(Extras())

    private data class Extras(
        val query: String = "",
        val selectionMode: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
        val pendingDelete: VideoItem? = null,
        val pendingBatchDelete: Boolean = false,
        val resumeTarget: VideoItem? = null,
        val contextMenuVideo: VideoItem? = null
    )

    val uiState: StateFlow<FolderVideosUiState> = combine(
        repository.folders.map { list -> list.firstOrNull { it.key == folderKey } },
        extras
    ) { folder, extra ->
        val all = folder?.videos.orEmpty()
        val filtered = if (extra.query.isBlank()) {
            all
        } else {
            all.filter { it.displayName.contains(extra.query, ignoreCase = true) }
        }
        FolderVideosUiState(
            folder = folder,
            videos = filtered,
            query = extra.query,
            selectionMode = extra.selectionMode,
            selectedPaths = extra.selectedPaths,
            pendingDelete = extra.pendingDelete,
            pendingBatchDelete = extra.pendingBatchDelete,
            resumeTarget = extra.resumeTarget,
            contextMenuVideo = extra.contextMenuVideo
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderVideosUiState())

    fun onQueryChange(value: String) {
        extras.update { it.copy(query = value) }
    }

    fun enterSelectionMode(initialPath: String? = null) {
        extras.update {
            it.copy(
                selectionMode = true,
                selectedPaths = if (initialPath == null) emptySet() else setOf(initialPath),
                contextMenuVideo = null
            )
        }
    }

    fun exitSelectionMode() {
        extras.update {
            it.copy(
                selectionMode = false,
                selectedPaths = emptySet(),
                pendingBatchDelete = false
            )
        }
    }

    fun toggleSelected(path: String) {
        extras.update { state ->
            val next = state.selectedPaths.toMutableSet()
            if (!next.add(path)) next.remove(path)
            state.copy(selectedPaths = next)
        }
    }

    fun selectAll(videos: List<VideoItem>) {
        extras.update { it.copy(selectedPaths = videos.map { video -> video.path }.toSet()) }
    }

    fun openContextMenu(video: VideoItem) {
        extras.update { it.copy(contextMenuVideo = video) }
    }

    fun dismissContextMenu() {
        extras.update { it.copy(contextMenuVideo = null) }
    }

    fun requestDelete(video: VideoItem) {
        extras.update { it.copy(contextMenuVideo = null, pendingDelete = video) }
    }

    fun dismissDelete() {
        extras.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val target = extras.value.pendingDelete ?: return
        viewModelScope.launch {
            repository.deleteVideo(target)
            extras.update { it.copy(pendingDelete = null) }
        }
    }

    fun requestBatchDelete() {
        if (extras.value.selectedPaths.isEmpty()) return
        extras.update { it.copy(pendingBatchDelete = true) }
    }

    fun dismissBatchDelete() {
        extras.update { it.copy(pendingBatchDelete = false) }
    }

    fun confirmBatchDelete() {
        val paths = extras.value.selectedPaths
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val videos = uiState.value.videos.filter { it.path in paths }
            repository.deleteVideos(videos)
            extras.update {
                it.copy(
                    selectionMode = false,
                    selectedPaths = emptySet(),
                    pendingBatchDelete = false
                )
            }
        }
    }

    fun onVideoClick(video: VideoItem, askResume: Boolean, onPlay: (VideoItem, Boolean) -> Unit) {
        if (askResume && video.canResume) {
            extras.update { it.copy(resumeTarget = video) }
        } else {
            onPlay(video, false)
        }
    }

    fun dismissResume() {
        extras.update { it.copy(resumeTarget = null) }
    }

    fun confirmResume(fromStart: Boolean, onPlay: (VideoItem, Boolean) -> Unit) {
        val target = extras.value.resumeTarget ?: return
        extras.update { it.copy(resumeTarget = null) }
        onPlay(target, fromStart)
    }

    companion object {
        fun factory(folderKey: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return FolderVideosViewModel(folderKey) as T
                }
            }
    }
}
