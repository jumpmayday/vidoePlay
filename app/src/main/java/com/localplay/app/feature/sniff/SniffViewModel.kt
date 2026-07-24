package com.localplay.app.feature.sniff

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localplay.app.LocalPlayApp
import com.localplay.app.core.database.DownloadStatus
import com.localplay.app.core.database.DownloadTaskEntity
import com.localplay.app.core.sniff.SniffedVideo
import com.localplay.app.core.sniff.VideoSniffer
import com.localplay.app.data.repository.DownloadRepository
import com.localplay.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SniffUiState(
    val url: String = "",
    val sniffing: Boolean = false,
    val status: String = "",
    val items: List<SniffedVideo> = emptyList(),
    val selected: Set<String> = emptySet(),
    val tasks: List<DownloadTaskEntity> = emptyList(),
    val activeCount: Int = 0,
    val error: String? = null,
    val successMessage: String? = null,
    val downloadPathLabel: String = ""
)

class SniffViewModel(
    private val settingsRepository: SettingsRepository = LocalPlayApp.instance.settingsRepository,
    private val downloadRepository: DownloadRepository = LocalPlayApp.instance.downloadRepository,
    private val sniffer: VideoSniffer = VideoSniffer()
) : ViewModel() {

    private val extras = MutableStateFlow(SniffUiState())

    val uiState: StateFlow<SniffUiState> = combine(
        extras,
        settingsRepository.settings,
        downloadRepository.tasks,
        downloadRepository.activeCount
    ) { state, settings, tasks, activeCount ->
        state.copy(
            downloadPathLabel = settings.downloadPathLabel.ifBlank {
                downloadRepository.defaultDirPath()
            },
            tasks = tasks,
            activeCount = activeCount
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SniffUiState())

    fun onUrlChange(value: String) {
        extras.update { it.copy(url = value, error = null, successMessage = null) }
    }

    fun toggle(mediaUrl: String) {
        extras.update { state ->
            val next = state.selected.toMutableSet()
            if (!next.add(mediaUrl)) next.remove(mediaUrl)
            state.copy(selected = next)
        }
    }

    fun selectAll() {
        extras.update { state ->
            state.copy(selected = state.items.map { it.mediaUrl }.toSet())
        }
    }

    fun clearSelection() {
        extras.update { it.copy(selected = emptySet()) }
    }

    fun dismissMessage() {
        extras.update { it.copy(error = null, successMessage = null) }
    }

    fun sniff() {
        val url = extras.value.url.trim()
        if (url.isBlank()) {
            extras.update { it.copy(error = "请输入网页或视频地址") }
            return
        }
        viewModelScope.launch {
            extras.update {
                it.copy(
                    sniffing = true,
                    status = "开始嗅探…",
                    items = emptyList(),
                    selected = emptySet(),
                    error = null,
                    successMessage = null
                )
            }
            try {
                val items = withContext(Dispatchers.IO) {
                    sniffer.sniff(url) { message ->
                        extras.update { state -> state.copy(status = message) }
                    }
                }
                extras.update {
                    it.copy(
                        sniffing = false,
                        items = items,
                        selected = items.map { video -> video.mediaUrl }.toSet(),
                        status = if (items.isEmpty()) "未发现可下载视频" else "发现 ${items.size} 个视频",
                        error = if (items.isEmpty()) {
                            "未发现视频，可尝试具体播放页，或直接粘贴 m3u8/mp4 链接"
                        } else {
                            null
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "sniff failed", e)
                extras.update {
                    it.copy(
                        sniffing = false,
                        status = "",
                        error = e.message ?: "嗅探失败"
                    )
                }
            }
        }
    }

    fun enqueueSelected() {
        val targets = extras.value.items.filter { it.mediaUrl in extras.value.selected }
        if (targets.isEmpty()) {
            extras.update { it.copy(error = "请先选择要下载的视频") }
            return
        }
        viewModelScope.launch {
            try {
                val tree = settingsRepository.settings.first().downloadTreeUri.ifBlank { null }
                val added = downloadRepository.enqueue(targets, tree)
                extras.update {
                    it.copy(
                        successMessage = if (added > 0) {
                            "已加入后台下载队列 $added 个，可退出本页，下载会继续（支持断点续传）"
                        } else {
                            "所选内容已在队列中或已完成"
                        },
                        error = null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "enqueue failed", e)
                extras.update { it.copy(error = e.message ?: "加入队列失败") }
            }
        }
    }

    fun pauseTask(id: Long) {
        viewModelScope.launch { downloadRepository.pause(id) }
    }

    fun resumeTask(id: Long) {
        viewModelScope.launch { downloadRepository.resume(id) }
    }

    fun cancelTask(id: Long) {
        viewModelScope.launch { downloadRepository.cancel(id) }
    }

    fun clearCompleted() {
        viewModelScope.launch { downloadRepository.clearCompleted() }
    }

    companion object {
        private const val TAG = "SniffViewModel"

        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SniffViewModel() as T
            }
        }
    }
}
