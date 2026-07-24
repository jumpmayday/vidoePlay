# -*- coding: utf-8 -*-
from pathlib import Path

JAVA = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")

(JAVA / "feature/home/HomeScreen.kt").write_text(r'''package com.localplay.app.feature.home

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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.localplay.app.data.model.FolderGroup
import com.localplay.app.data.model.SortOption
import com.localplay.app.ui.theme.LpBg
import com.localplay.app.ui.theme.LpDanger
import com.localplay.app.ui.theme.LpPrimary
import com.localplay.app.ui.theme.LpSurface
import com.localplay.app.ui.theme.LpSurface2
import com.localplay.app.ui.theme.LpSurface3
import com.localplay.app.ui.theme.LpText
import com.localplay.app.ui.theme.LpText2
import com.localplay.app.ui.theme.LpText3
import com.localplay.app.ui.theme.LpThumb
import com.localplay.app.ui.theme.LocalPlayTypography

@Composable
fun HomeScreen(
    onOpenFolder: (folderKey: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory())
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LpBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        if (state.selectionMode) {
            SelectionTopBar(
                selectedCount = state.selectedKeys.size,
                onClose = viewModel::exitSelectionMode,
                onSelectAll = { viewModel.selectAll(state.folders) },
                onDelete = viewModel::requestBatchDelete
            )
        } else {
            HomeTopBar(
                totalCount = state.totalCount,
                folderCount = state.folders.size,
                sortMenuExpanded = sortMenuExpanded,
                moreMenuExpanded = moreMenuExpanded,
                onSortClick = { sortMenuExpanded = true },
                onDismissSort = { sortMenuExpanded = false },
                onSortSelected = {
                    viewModel.onSortChange(it)
                    sortMenuExpanded = false
                },
                onSettingsClick = onOpenSettings,
                onRefresh = viewModel::refresh,
                onMoreClick = { moreMenuExpanded = true },
                onDismissMore = { moreMenuExpanded = false },
                onBatchDelete = {
                    moreMenuExpanded = false
                    viewModel.enterSelectionMode()
                }
            )
            SearchBar(query = state.query, onQueryChange = viewModel::onQueryChange)
        }

        when {
            state.scanning && state.totalCount == 0 -> {
                ScanningPlaceholder(scanned = state.scanned, totalHint = state.totalHint)
            }
            state.folders.isEmpty() -> EmptyLibraryPlaceholder()
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.folders, key = { it.key }) { folder ->
                        FolderRow(
                            folder = folder,
                            selectionMode = state.selectionMode,
                            selected = folder.key in state.selectedKeys,
                            onClick = {
                                if (state.selectionMode) {
                                    viewModel.toggleSelected(folder.key)
                                } else {
                                    onOpenFolder(folder.key)
                                }
                            },
                            onLongClick = {
                                if (!state.selectionMode) {
                                    viewModel.enterSelectionMode(folder.key)
                                }
                            },
                            onToggleSelect = { viewModel.toggleSelected(folder.key) }
                        )
                    }
                }
            }
        }
    }

    if (state.pendingBatchDelete) {
        val count = state.selectedKeys.size
        val videoCount = state.folders
            .filter { it.key in state.selectedKeys }
            .sumOf { it.videoCount }
        AlertDialog(
            onDismissRequest = viewModel::dismissBatchDelete,
            containerColor = LpSurface2,
            title = { Text("批量删除？", color = LpText) },
            text = {
                Text(
                    "将删除选中的 $count 个目录中的 $videoCount 个视频，此操作不可恢复。",
                    color = LpText2
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmBatchDelete) {
                    Text("删除", color = LpDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissBatchDelete) {
                    Text("取消", color = LpText2)
                }
            }
        )
    }
}

@Composable
private fun HomeTopBar(
    totalCount: Int,
    folderCount: Int,
    sortMenuExpanded: Boolean,
    moreMenuExpanded: Boolean,
    onSortClick: () -> Unit,
    onDismissSort: () -> Unit,
    onSortSelected: (SortOption) -> Unit,
    onSettingsClick: () -> Unit,
    onRefresh: () -> Unit,
    onMoreClick: () -> Unit,
    onDismissMore: () -> Unit,
    onBatchDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text("视频", style = LocalPlayTypography.titleLarge)
            Text(
                "$folderCount 个目录 · $totalCount 个视频",
                style = LocalPlayTypography.labelSmall,
                color = LpText2
            )
        }
        Box {
            RoundIconButton(Icons.Default.Sort, onSortClick)
            DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = onDismissSort) {
                DropdownMenuItem(text = { Text("按名称") }, onClick = { onSortSelected(SortOption.NAME) })
                DropdownMenuItem(text = { Text("按大小") }, onClick = { onSortSelected(SortOption.SIZE) })
                DropdownMenuItem(text = { Text("按时长") }, onClick = { onSortSelected(SortOption.DURATION) })
                DropdownMenuItem(text = { Text("按修改时间") }, onClick = { onSortSelected(SortOption.DATE_MODIFIED) })
            }
        }
        RoundIconButton(Icons.Default.Refresh, onRefresh)
        RoundIconButton(Icons.Default.Settings, onSettingsClick)
        Box {
            RoundIconButton(Icons.Default.MoreVert, onMoreClick)
            DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = onDismissMore) {
                DropdownMenuItem(
                    text = { Text("批量删除") },
                    onClick = onBatchDelete,
                    leadingIcon = { Icon(Icons.Default.Delete, null) }
                )
            }
        }
    }
}

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LpSurface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "取消", tint = LpText)
        }
        Text(
            "已选 $selectedCount 项",
            style = LocalPlayTypography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Default.SelectAll, contentDescription = "全选", tint = LpText)
        }
        IconButton(onClick = onDelete, enabled = selectedCount > 0) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = if (selectedCount > 0) LpDanger else LpText3
            )
        }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(2.dp)
            .size(40.dp)
            .background(LpSurface2, CircleShape)
    ) {
        Icon(icon, contentDescription = null, tint = LpText)
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        placeholder = { Text("搜索目录或文件名…", color = LpText3) },
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

@Composable
private fun ScanningPlaceholder(scanned: Int, totalHint: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = LpPrimary)
        Spacer(modifier = Modifier.height(20.dp))
        Text("正在扫描本地视频…", style = LocalPlayTypography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "已扫描 $scanned / 约 $totalHint 个文件",
            style = LocalPlayTypography.bodyMedium,
            color = LpText2
        )
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { if (totalHint <= 0) 0f else scanned.toFloat() / totalHint },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = LpPrimary,
            trackColor = LpSurface3,
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
private fun EmptyLibraryPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("暂无本地视频", style = LocalPlayTypography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("点击右上角刷新重新扫描", style = LocalPlayTypography.bodyMedium, color = LpText2)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderGroup,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            IconButton(onClick = onToggleSelect) {
                Icon(
                    imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (selected) LpPrimary else LpText3
                )
            }
        }
        Box(
            modifier = Modifier
                .width(112.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LpThumb)
        ) {
            AsyncImage(
                model = folder.coverUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.name,
                style = LocalPlayTypography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${folder.videoCount} 个视频",
                style = LocalPlayTypography.labelMedium,
                color = LpText2
            )
        }
    }
}
''', encoding='utf-8', newline='\n')
print('HomeScreen ok')
