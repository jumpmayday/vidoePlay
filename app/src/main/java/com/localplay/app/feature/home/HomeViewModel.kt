package com.localplay.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localplay.app.LocalPlayApp
import com.localplay.app.data.model.FolderGroup
import com.localplay.app.data.model.SortOption
import com.localplay.app.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val folders: List<FolderGroup> = emptyList(),
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val totalHint: Int = 0,
    val query: String = "",
    val sortOption: SortOption = SortOption.DATE_MODIFIED,
    val totalCount: Int = 0,
    val selectionMode: Boolean = false,
    val selectedKeys: Set<String> = emptySet(),
    val pendingBatchDelete: Boolean = false
)

class HomeViewModel(
    private val repository: VideoRepository = LocalPlayApp.instance.videoRepository
) : ViewModel() {

    private val extras = MutableStateFlow(HomeExtras())

    private data class HomeExtras(
        val query: String = "",
        val sortOption: SortOption = SortOption.DATE_MODIFIED,
        val selectionMode: Boolean = false,
        val selectedKeys: Set<String> = emptySet(),
        val pendingBatchDelete: Boolean = false
    )

    val uiState: StateFlow<HomeUiState> = combine(
        repository.folders,
        repository.scanning,
        repository.scanProgress,
        extras
    ) { folders, scanning, progress, extra ->
        HomeUiState(
            folders = folders,
            scanning = scanning,
            scanned = progress.scanned,
            totalHint = progress.totalHint,
            query = extra.query,
            sortOption = extra.sortOption,
            totalCount = folders.sumOf { it.videos.size },
            selectionMode = extra.selectionMode,
            selectedKeys = extra.selectedKeys,
            pendingBatchDelete = extra.pendingBatchDelete
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refresh() {
        viewModelScope.launch { repository.refresh(force = true) }
    }

    fun onQueryChange(value: String) {
        extras.update { it.copy(query = value) }
        repository.setQuery(value)
    }

    fun onSortChange(option: SortOption) {
        extras.update { it.copy(sortOption = option) }
        repository.setSort(option)
    }

    fun enterSelectionMode(initialKey: String? = null) {
        extras.update {
            it.copy(
                selectionMode = true,
                selectedKeys = if (initialKey == null) emptySet() else setOf(initialKey)
            )
        }
    }

    fun exitSelectionMode() {
        extras.update {
            it.copy(selectionMode = false, selectedKeys = emptySet(), pendingBatchDelete = false)
        }
    }

    fun toggleSelected(key: String) {
        extras.update { state ->
            val next = state.selectedKeys.toMutableSet()
            if (!next.add(key)) next.remove(key)
            state.copy(selectedKeys = next)
        }
    }

    fun selectAll(folders: List<FolderGroup>) {
        extras.update { it.copy(selectedKeys = folders.map { folder -> folder.key }.toSet()) }
    }

    fun requestBatchDelete() {
        if (extras.value.selectedKeys.isEmpty()) return
        extras.update { it.copy(pendingBatchDelete = true) }
    }

    fun dismissBatchDelete() {
        extras.update { it.copy(pendingBatchDelete = false) }
    }

    fun confirmBatchDelete() {
        val keys = extras.value.selectedKeys
        if (keys.isEmpty()) return
        viewModelScope.launch {
            val videos = keys.flatMap { key -> repository.videosInFolder(key) }
            repository.deleteVideos(videos)
            extras.update {
                it.copy(
                    selectionMode = false,
                    selectedKeys = emptySet(),
                    pendingBatchDelete = false
                )
            }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel() as T
            }
        }
    }
}
