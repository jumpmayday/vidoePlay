package com.localplay.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localplay.app.LocalPlayApp
import com.localplay.app.data.model.FolderGroup
import com.localplay.app.data.model.SortOption
import com.localplay.app.data.model.VideoItem
import com.localplay.app.data.model.sortedByOption
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
    val sortOption: SortOption = SortOption.EPISODE,
    val refreshing: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val pendingDelete: VideoItem? = null,
    val pendingBatchDelete: Boolean = false,
    val resumeTarget: VideoItem? = null,
    val contextMenuVideo: VideoItem? = null,
    val deleteRequest: android.content.IntentSender? = null
)

class FolderVideosViewModel(
    private val folderKey: String,
    private val repository: VideoRepository = LocalPlayApp.instance.videoRepository
) : ViewModel() {

    private val extras = MutableStateFlow(Extras())

    // Videos awaiting a system delete-confirmation result (Android 11+ non-owned media).
    private var pendingSystemDelete: List<VideoItem> = emptyList()

    private data class Extras(
        val query: String = "",
        val sortOption: SortOption = SortOption.EPISODE,
        val selectionMode: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
        val pendingDelete: VideoItem? = null,
        val pendingBatchDelete: Boolean = false,
        val resumeTarget: VideoItem? = null,
        val contextMenuVideo: VideoItem? = null,
        val deleteRequest: android.content.IntentSender? = null
    )

    val uiState: StateFlow<FolderVideosUiState> = combine(
        repository.folders.map { list -> list.firstOrNull { it.key == folderKey } },
        repository.scanning,
        extras
    ) { folder, scanning, extra ->
        val all = folder?.videos.orEmpty()
        val filtered = if (extra.query.isBlank()) {
            all
        } else {
            all.filter { it.displayName.contains(extra.query, ignoreCase = true) }
        }
        FolderVideosUiState(
            folder = folder,
            videos = filtered.sortedByOption(extra.sortOption),
            query = extra.query,
            sortOption = extra.sortOption,
            refreshing = scanning,
            selectionMode = extra.selectionMode,
            selectedPaths = extra.selectedPaths,
            pendingDelete = extra.pendingDelete,
            pendingBatchDelete = extra.pendingBatchDelete,
            resumeTarget = extra.resumeTarget,
            contextMenuVideo = extra.contextMenuVideo,
            deleteRequest = extra.deleteRequest
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderVideosUiState())

    fun refresh() {
        viewModelScope.launch { repository.refresh(force = true) }
    }

    fun onQueryChange(value: String) {
        extras.update { it.copy(query = value) }
    }

    fun onSortChange(option: SortOption) {
        extras.update { it.copy(sortOption = option) }
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
            val result = repository.deleteVideosWithPrompt(listOf(target))
            extras.update { it.copy(pendingDelete = null) }
            handleDeleteResult(result)
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
            val result = repository.deleteVideosWithPrompt(videos)
            extras.update {
                it.copy(
                    selectionMode = false,
                    selectedPaths = emptySet(),
                    pendingBatchDelete = false
                )
            }
            handleDeleteResult(result)
        }
    }

    private fun handleDeleteResult(result: VideoRepository.DeleteResult) {
        when (result) {
            is VideoRepository.DeleteResult.Completed -> pendingSystemDelete = emptyList()
            is VideoRepository.DeleteResult.NeedsPermission -> {
                pendingSystemDelete = result.videos
                extras.update { it.copy(deleteRequest = result.intentSender) }
            }
        }
    }

    /** Called after the composable launches the system delete dialog. */
    fun onDeleteRequestConsumed() {
        extras.update { it.copy(deleteRequest = null) }
    }

    /** Called with the result of the system delete-confirmation dialog. */
    fun onSystemDeleteResult(confirmed: Boolean) {
        val videos = pendingSystemDelete
        pendingSystemDelete = emptyList()
        if (confirmed && videos.isNotEmpty()) {
            viewModelScope.launch { repository.onSystemDeleteConfirmed(videos) }
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
