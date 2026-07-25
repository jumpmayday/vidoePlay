package com.localplay.app.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localplay.app.ui.components.VideoThumbnail
import com.localplay.app.core.common.Formatters
import com.localplay.app.data.model.SortOption
import com.localplay.app.data.model.VideoItem
import com.localplay.app.data.model.label
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
import com.localplay.app.ui.theme.LocalPlayTypography

@OptIn(ExperimentalMaterial3Api::class)
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
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val title = state.folder?.name ?: "文件夹"

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onSystemDeleteResult(
            confirmed = result.resultCode == android.app.Activity.RESULT_OK
        )
    }
    LaunchedEffect(state.deleteRequest) {
        state.deleteRequest?.let { sender ->
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            viewModel.onDeleteRequestConsumed()
        }
    }

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
                        "${state.folder?.videoCount ?: 0} 个视频 · ${state.sortOption.label()}",
                        style = LocalPlayTypography.labelSmall,
                        color = LpText2
                    )
                }
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "排序", tint = LpText)
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        SortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label(),
                                        color = if (option == state.sortOption) LpPrimary else LpText
                                    )
                                },
                                onClick = {
                                    viewModel.onSortChange(option)
                                    sortMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { moreMenuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = LpText)
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
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

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if (state.videos.isEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (state.refreshing) "正在刷新…" else "该目录暂无视频（下拉刷新）",
                                color = LpText2
                            )
                        }
                    }
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
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) LpPrimary.copy(alpha = 0.12f) else LpSurface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
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
                .width(128.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            VideoThumbnail(
                uri = video.uri,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            startY = 40f
                        )
                    )
            )
            Text(
                text = video.formatLabel,
                color = LpText,
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
            when {
                video.isCompleted -> Text(
                    "已看完",
                    color = LpOnPrimary,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
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
            Text(
                Formatters.duration(video.durationMs),
                color = LpText,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            )
            if (video.progressRatio > 0f) {
                LinearProgressIndicator(
                    progress = { video.progressRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                    color = if (video.isCompleted) LpSuccess else LpPrimary,
                    trackColor = Color.Transparent
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                video.displayName,
                style = LocalPlayTypography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
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
