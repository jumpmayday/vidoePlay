# -*- coding: utf-8 -*-
from pathlib import Path

JAVA = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")
(JAVA / "feature/home").mkdir(parents=True, exist_ok=True)

(JAVA / "feature/home/FolderVideosViewModel.kt").write_text(r'''package com.localplay.app.feature.home

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
''', encoding='utf-8', newline='\n')

(JAVA / "feature/home/FolderVideosScreen.kt").write_text(r'''package com.localplay.app.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.localplay.app.core.common.Formatters
import com.localplay.app.data.model.VideoItem
import com.localplay.app.ui.theme.LpBg
import com.localplay.app.ui.theme.LpDanger
import com.localplay.app.ui.theme.LpOnPrimary
import com.localplay.app.ui.theme.LpPrimary
import com.localplay.app.ui.theme.LpSuccess
import com.localplay.app.ui.theme.LpSurface
import com.localplay.app.ui.theme.LpSurface2
import com.localplay.app.ui.theme.LpSurface3
import com.localplay.app.ui.theme.LpText
import com.localplay.app.ui.theme.LpText2
import com.localplay.app.ui.theme.LpText3
import com.localplay.app.ui.theme.LpThumb
import com.localplay.app.ui.theme.LocalPlayTypography

@Composable
fun FolderVideosScreen(
    folderKey: String,
    onBack: () -> Unit,
    onOpenPlayer: (path: String, fromStart: Boolean) -> Unit,
    onOpenDetail: (path: String) -> Unit,
    viewModel: FolderVideosViewModel = viewModel(
        factory = FolderVideosViewModel.factory(folderKey)
    )
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val title = state.folder?.name ?: "文件夹"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LpBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        if (state.selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LpSurface)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = viewModel::exitSelectionMode) {
                    Icon(Icons.Default.Close, contentDescription = "取消", tint = LpText)
                }
                Text(
                    "已选 ${state.selectedPaths.size} 项",
                    style = LocalPlayTypography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.selectAll(state.videos) }) {
                    Icon(Icons.Default.SelectAll, contentDescription = "全选", tint = LpText)
                }
                IconButton(
                    onClick = viewModel::requestBatchDelete,
                    enabled = state.selectedPaths.isNotEmpty()
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = if (state.selectedPaths.isNotEmpty()) LpDanger else LpText3
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = LpText)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = LocalPlayTypography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${state.folder?.videoCount ?: 0} 个视频",
                        style = LocalPlayTypography.labelSmall,
                        color = LpText2
                    )
                }
                Box {
                    IconButton(onClick = { moreMenuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = LpText)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("批量删除") },
                            onClick = {
                                moreMenuExpanded = false
                                viewModel.enterSelectionMode()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, null) }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("搜索文件名…", color = LpText3) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = LpText3) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = LpSurface2,
                    unfocusedContainerColor = LpSurface2,
                    focusedBorderColor = LpPrimary,
                    unfocusedBorderColor = LpSurface2,
                    focusedTextColor = LpText,
                    unfocusedTextColor = LpText,
                    cursorColor = LpPrimary
                )
            )
        }

        if (state.videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该目录暂无视频", color = LpText2)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(state.videos, key = { it.path }) { video ->
                    VideoRow(
                        video = video,
                        selectionMode = state.selectionMode,
                        selected = video.path in state.selectedPaths,
                        onClick = {
                            if (state.selectionMode) {
                                viewModel.toggleSelected(video.path)
                            } else {
                                viewModel.onVideoClick(video, askResume = true) { item, fromStart ->
                                    onOpenPlayer(item.path, fromStart)
                                }
                            }
                        },
                        onLongClick = {
                            if (state.selectionMode) {
                                viewModel.toggleSelected(video.path)
                            } else {
                                viewModel.enterSelectionMode(video.path)
                            }
                        },
                        onMore = { viewModel.openContextMenu(video) },
                        onToggleSelect = { viewModel.toggleSelected(video.path) }
                    )
                }
            }
        }
    }

    state.contextMenuVideo?.let { video ->
        AlertDialog(
            onDismissRequest = viewModel::dismissContextMenu,
            containerColor = LpSurface2,
            title = {
                Text(video.displayName, color = LpText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            viewModel.dismissContextMenu()
                            onOpenPlayer(video.path, false)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("播放", color = LpText) }
                    TextButton(
                        onClick = {
                            viewModel.dismissContextMenu()
                            onOpenDetail(video.path)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("查看详情", color = LpText) }
                    TextButton(
                        onClick = { viewModel.requestDelete(video) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("删除", color = LpDanger) }
                    TextButton(
                        onClick = {
                            viewModel.dismissContextMenu()
                            viewModel.enterSelectionMode(video.path)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("批量选择", color = LpText) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::dismissContextMenu) {
                    Text("取消", color = LpText2)
                }
            }
        )
    }

    state.pendingDelete?.let { video ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            containerColor = LpSurface2,
            title = { Text("删除视频？", color = LpText) },
            text = {
                Text(
                    "将永久删除「${video.displayName}」（${Formatters.fileSize(video.sizeBytes)}）。此操作不可恢复。",
                    color = LpText2
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text("删除", color = LpDanger) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("取消", color = LpText2) }
            }
        )
    }

    if (state.pendingBatchDelete) {
        AlertDialog(
            onDismissRequest = viewModel::dismissBatchDelete,
            containerColor = LpSurface2,
            title = { Text("批量删除？", color = LpText) },
            text = {
                Text(
                    "将永久删除选中的 ${state.selectedPaths.size} 个视频，此操作不可恢复。",
                    color = LpText2
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmBatchDelete) { Text("删除", color = LpDanger) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissBatchDelete) { Text("取消", color = LpText2) }
            }
        )
    }

    state.resumeTarget?.let { video ->
        AlertDialog(
            onDismissRequest = viewModel::dismissResume,
            containerColor = LpSurface2,
            title = { Text("从上次位置继续？", color = LpText) },
            text = {
                Text(
                    "上次播放到 ${Formatters.duration(video.progressMs)}（已看 ${(video.progressRatio * 100).toInt()}%）。",
                    color = LpText2
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmResume(fromStart = false) { item, fromStart ->
                            onOpenPlayer(item.path, fromStart)
                        }
                    }
                ) { Text("继续播放", color = LpPrimary) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmResume(fromStart = true) { item, fromStart ->
                            onOpenPlayer(item.path, fromStart)
                        }
                    }
                ) { Text("从头播放", color = LpText2) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    video: VideoItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    onToggleSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            IconButton(onClick = onToggleSelect, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (selected) LpPrimary else LpText3
                )
            }
        }
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LpThumb)
        ) {
            AsyncImage(
                model = video.uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Text(
                text = video.formatLabel,
                color = LpText,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(LpBg.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
            when {
                video.isCompleted -> Text(
                    "已看完",
                    color = LpOnPrimary,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(LpSuccess, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
                video.canResume -> Text(
                    "续播",
                    color = LpOnPrimary,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(LpPrimary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            LinearProgressIndicator(
                progress = { video.progressRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter),
                color = if (video.isCompleted) LpSuccess else LpPrimary,
                trackColor = LpSurface3
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                video.displayName,
                style = LocalPlayTypography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                Formatters.duration(video.durationMs) + " · " +
                    Formatters.resolution(video.width, video.height) + " · " +
                    Formatters.fileSize(video.sizeBytes),
                style = LocalPlayTypography.labelSmall,
                color = LpText2
            )
        }
        if (!selectionMode) {
            IconButton(onClick = onMore) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = LpText3)
            }
        }
    }
}
''', encoding='utf-8', newline='\n')

(JAVA / "ui/navigation/NavGraph.kt").write_text(r'''package com.localplay.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localplay.app.feature.detail.DetailScreen
import com.localplay.app.feature.home.FolderVideosScreen
import com.localplay.app.feature.home.HomeScreen
import com.localplay.app.feature.permission.PermissionGate
import com.localplay.app.feature.player.PlayerScreen
import com.localplay.app.feature.settings.SettingsScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Routes {
    const val HOME = "home"
    const val FOLDER = "folder/{folderKey}"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{path}"
    const val PLAYER = "player/{path}?fromStart={fromStart}"

    fun folder(folderKey: String): String =
        "folder/${URLEncoder.encode(folderKey, StandardCharsets.UTF_8.name())}"

    fun detail(path: String): String =
        "detail/${URLEncoder.encode(path, StandardCharsets.UTF_8.name())}"

    fun player(path: String, fromStart: Boolean): String =
        "player/${URLEncoder.encode(path, StandardCharsets.UTF_8.name())}?fromStart=$fromStart"
}

@Composable
fun LocalPlayNavHost() {
    var permissionGranted by remember { mutableStateOf(false) }
    val navController = rememberNavController()

    PermissionGate(
        onGranted = { permissionGranted = true }
    ) {
        if (!permissionGranted) return@PermissionGate

        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenFolder = { key -> navController.navigate(Routes.folder(key)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }
            composable(
                route = Routes.FOLDER,
                arguments = listOf(navArgument("folderKey") { type = NavType.StringType })
            ) { entry ->
                val encoded = entry.arguments?.getString("folderKey").orEmpty()
                val folderKey = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                FolderVideosScreen(
                    folderKey = folderKey,
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = { path, fromStart ->
                        navController.navigate(Routes.player(path, fromStart))
                    },
                    onOpenDetail = { path -> navController.navigate(Routes.detail(path)) }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { entry ->
                val encoded = entry.arguments?.getString("path").orEmpty()
                val path = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                DetailScreen(path = path, onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("fromStart") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { entry ->
                val encoded = entry.arguments?.getString("path").orEmpty()
                val path = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                val fromStart = entry.arguments?.getBoolean("fromStart") ?: false
                PlayerScreen(
                    path = path,
                    fromStart = fromStart,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
''', encoding='utf-8', newline='\n')

print('folder + nav written')
