# -*- coding: utf-8 -*-
from pathlib import Path

JAVA = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")

(JAVA / "feature/sniff/SniffViewModel.kt").write_text(r'''package com.localplay.app.feature.sniff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.localplay.app.LocalPlayApp
import com.localplay.app.core.sniff.SniffedVideo
import com.localplay.app.core.sniff.VideoDownloader
import com.localplay.app.core.sniff.VideoSniffer
import com.localplay.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val downloading: Boolean = false,
    val downloadFraction: Float = 0f,
    val downloadMessage: String = "",
    val error: String? = null,
    val successMessage: String? = null,
    val downloadPathLabel: String = ""
)

class SniffViewModel(
    private val settingsRepository: SettingsRepository = LocalPlayApp.instance.settingsRepository,
    private val sniffer: VideoSniffer = VideoSniffer(),
    private val downloader: VideoDownloader = VideoDownloader(LocalPlayApp.instance)
) : ViewModel() {

    private val extras = MutableStateFlow(SniffUiState())

    val uiState: StateFlow<SniffUiState> = combine(
        extras,
        settingsRepository.settings
    ) { state, settings ->
        state.copy(
            downloadPathLabel = settings.downloadPathLabel.ifBlank {
                downloader.defaultDirPath()
            }
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
                        error = if (items.isEmpty()) "未发现视频，可尝试打开具体播放页或粘贴 m3u8/mp4 直链" else null
                    )
                }
            } catch (e: Exception) {
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

    fun downloadSelected(onFinishedRefresh: suspend () -> Unit) {
        val state = extras.value
        val targets = state.items.filter { it.mediaUrl in state.selected }
        if (targets.isEmpty()) {
            extras.update { it.copy(error = "请先选择要下载的视频") }
            return
        }
        viewModelScope.launch {
            extras.update {
                it.copy(
                    downloading = true,
                    downloadFraction = 0f,
                    downloadMessage = "准备下载…",
                    error = null,
                    successMessage = null
                )
            }
            val treeUri = settingsRepository.settings
            // read latest settings once
            var tree: String? = null
            settingsRepository.settings.collect { settings ->
                tree = settings.downloadTreeUri.ifBlank { null }
                // break after first by completing after capture - use first() pattern instead
                throw StopCollect()
            }
        }
    }

    fun startDownload(onLibraryRefresh: suspend () -> Unit) {
        val state = extras.value
        val targets = state.items.filter { it.mediaUrl in state.selected }
        if (targets.isEmpty()) {
            extras.update { it.copy(error = "请先选择要下载的视频") }
            return
        }
        viewModelScope.launch {
            extras.update {
                it.copy(
                    downloading = true,
                    downloadFraction = 0f,
                    downloadMessage = "准备下载…",
                    error = null,
                    successMessage = null
                )
            }
            try {
                val settings = withContext(Dispatchers.IO) {
                    var latest = com.localplay.app.data.repository.AppSettings()
                    settingsRepository.settings.collect {
                        latest = it
                        throw StopCollect()
                    }
                    latest
                }
            } catch (_: StopCollect) {
            }

            val treeUri = try {
                var value = ""
                settingsRepository.settings.collect {
                    value = it.downloadTreeUri
                    throw StopCollect()
                }
                value
            } catch (_: StopCollect) {
                ""
            }.ifBlank { null }

            // cleaner: use first()
            val downloadTree = withContext(Dispatchers.IO) {
                kotlinx.coroutines.flow.first(settingsRepository.settings).downloadTreeUri.ifBlank { null }
            }

            var success = 0
            targets.forEachIndexed { index, video ->
                extras.update {
                    it.copy(
                        downloadMessage = "(${index + 1}/${targets.size}) ${video.title}",
                        downloadFraction = index.toFloat() / targets.size
                    )
                }
                try {
                    withContext(Dispatchers.IO) {
                        downloader.download(video, downloadTree) { fraction, message ->
                            val overall = (index + fraction) / targets.size
                            extras.update { state ->
                                state.copy(
                                    downloadFraction = overall.coerceIn(0f, 1f),
                                    downloadMessage = "(${index + 1}/${targets.size}) $message"
                                )
                            }
                        }
                    }
                    success++
                } catch (e: Exception) {
                    android.util.Log.e("SniffViewModel", "download failed: ${video.mediaUrl}", e)
                    extras.update {
                        it.copy(error = "「${video.title}」下载失败：${e.message}")
                    }
                }
            }
            try {
                onLibraryRefresh()
            } catch (_: Exception) {
            }
            extras.update {
                it.copy(
                    downloading = false,
                    downloadFraction = 1f,
                    downloadMessage = "完成",
                    successMessage = "成功下载 $success / ${targets.size} 个，已保存到本地"
                )
            }
        }
    }

    private class StopCollect : Throwable()

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SniffViewModel() as T
            }
        }
    }
}
''', encoding='utf-8', newline='\n')

print('vm draft')
