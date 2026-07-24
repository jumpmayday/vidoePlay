# -*- coding: utf-8 -*-
from pathlib import Path

ROOT = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")
MOD = "import androidx.compose.ui." + "modifier" + "." + "M" + "odifier"


def write(rel: str, content: str) -> None:
    path = ROOT / rel
    path.write_text(content, encoding="utf-8", newline="\n")
    text = path.read_text(encoding="utf-8")
    line = next(l for l in text.splitlines() if "compose.ui.m" in l and "odifier" in l)
    assert ord(line.split(".")[-2][0]) == 109 and ord(line.split(".")[-1][0]) == 77
    print("OK", rel, len(text))


write(
    "feature/home/HomeScreen.kt",
    f'''package com.localplay.app.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
{MOD}
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.localplay.app.core.common.Formatters
import com.localplay.app.data.model.SortOption
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPlayer: (path: String, fromStart: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (path: String) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory())
) {{
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sortMenuExpanded by remember {{ mutableStateOf(false) }}

    LaunchedEffect(Unit) {{ viewModel.refresh() }}

    Box(modifier = Modifier.fillMaxSize().background(LpBg)) {{
        Column(modifier = Modifier.fillMaxSize()) {{
            HomeTopBar(
                totalCount = state.totalCount,
                onSortClick = {{ sortMenuExpanded = true }},
                onSettingsClick = onOpenSettings,
                sortMenuExpanded = sortMenuExpanded,
                onDismissSort = {{ sortMenuExpanded = false }},
                onSortSelected = {{
                    viewModel.onSortChange(it)
                    sortMenuExpanded = false
                }},
                onRefresh = viewModel::refresh
            )
            SearchBar(query = state.query, onQueryChange = viewModel::onQueryChange)

            if (state.scanning && state.totalCount == 0) {{
                ScanningPlaceholder(scanned = state.scanned, totalHint = state.totalHint)
            }} else {{
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {{
                    state.folders.forEach {{ folder ->
                        item(key = "folder-" + folder.name) {{
                            FolderHeader(
                                name = folder.name,
                                count = folder.videos.size,
                                expanded = folder.expanded,
                                onClick = {{ viewModel.toggleFolder(folder.name) }}
                            )
                        }}
                        if (folder.expanded) {{
                            items(folder.videos, key = {{ it.path }}) {{ video ->
                                VideoRow(
                                    video = video,
                                    onClick = {{
                                        viewModel.onVideoClick(video, askResume = true) {{ item, fromStart ->
                                            onOpenPlayer(item.path, fromStart)
                                        }}
                                    }},
                                    onLongClick = {{ viewModel.openContextMenu(video) }}
                                )
                            }}
                        }}
                    }}
                }}
            }}
        }}

        state.contextMenuVideo?.let {{ video ->
            ContextMenuDialog(
                video = video,
                onDismiss = viewModel::dismissContextMenu,
                onPlay = {{
                    viewModel.dismissContextMenu()
                    onOpenPlayer(video.path, false)
                }},
                onDetail = {{
                    viewModel.dismissContextMenu()
                    onOpenDetail(video.path)
                }},
                onDelete = {{ viewModel.requestDelete(video) }}
            )
        }}

        state.pendingDelete?.let {{ video ->
            DeleteConfirmDialog(
                video = video,
                onDismiss = viewModel::dismissDelete,
                onConfirm = viewModel::confirmDelete
            )
        }}

        state.resumeTarget?.let {{ video ->
            ResumeDialog(
                video = video,
                onContinue = {{
                    viewModel.confirmResume(fromStart = false) {{ item, fromStart ->
                        onOpenPlayer(item.path, fromStart)
                    }}
                }},
                onFromStart = {{
                    viewModel.confirmResume(fromStart = true) {{ item, fromStart ->
                        onOpenPlayer(item.path, fromStart)
                    }}
                }},
                onDismiss = viewModel::dismissResume
            )
        }}
    }}
}}

@Composable
private fun HomeTopBar(
    totalCount: Int,
    onSortClick: () -> Unit,
    onSettingsClick: () -> Unit,
    sortMenuExpanded: Boolean,
    onDismissSort: () -> Unit,
    onSortSelected: (SortOption) -> Unit,
    onRefresh: () -> Unit
) {{
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {{
        Column(modifier = Modifier.weight(1f)) {{
            Text("LocalPlay", style = LocalPlayTypography.titleLarge)
            Text(totalCount.toString() + " 个视频 · 本地", style = LocalPlayTypography.labelSmall, color = LpText2)
        }}
        Box {{
            RoundIconButton(Icons.Default.Sort, onSortClick)
            DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = onDismissSort) {{
                DropdownMenuItem(text = {{ Text("按名称") }}, onClick = {{ onSortSelected(SortOption.NAME) }})
                DropdownMenuItem(text = {{ Text("按大小") }}, onClick = {{ onSortSelected(SortOption.SIZE) }})
                DropdownMenuItem(text = {{ Text("按时长") }}, onClick = {{ onSortSelected(SortOption.DURATION) }})
                DropdownMenuItem(text = {{ Text("按修改时间") }}, onClick = {{ onSortSelected(SortOption.DATE_MODIFIED) }})
            }}
        }}
        RoundIconButton(Icons.Default.Settings, onSettingsClick)
        RoundIconButton(Icons.Default.Add, onRefresh)
    }}
}}

@Composable
private fun RoundIconButton(icon: ImageVector, onClick: () -> Unit) {{
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(2.dp).size(40.dp).background(LpSurface2, CircleShape)
    ) {{
        Icon(icon, contentDescription = null, tint = LpText)
    }}
}}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {{
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = {{ Text("搜索文件名…", color = LpText3) }},
        leadingIcon = {{ Icon(Icons.Default.Search, null, tint = LpText3) }},
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
}}

@Composable
private fun ScanningPlaceholder(scanned: Int, totalHint: Int) {{
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {{
        CircularProgressIndicator(color = LpPrimary)
        Spacer(modifier = Modifier.height(20.dp))
        Text("正在扫描本地视频…", style = LocalPlayTypography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("已扫描 " + scanned + " / 约 " + totalHint + " 个文件", style = LocalPlayTypography.bodyMedium, color = LpText2)
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = {{ if (totalHint <= 0) 0f else scanned.toFloat() / totalHint }},
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = LpPrimary,
            trackColor = LpSurface3,
            strokeCap = StrokeCap.Round
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("首次扫描会稍慢，完成后将按目录分组展示", style = LocalPlayTypography.labelSmall, color = LpText3)
    }}
}}

@Composable
private fun FolderHeader(name: String, count: Int, expanded: Boolean, onClick: () -> Unit) {{
    Row(
        modifier = Modifier.fillMaxWidth().background(LpSurface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {{
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            contentDescription = null,
            tint = LpText3,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(name, style = LocalPlayTypography.bodyLarge, modifier = Modifier.weight(1f))
        Text(count.toString() + " 个视频", style = LocalPlayTypography.labelMedium, color = LpText2)
    }}
}}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(video: VideoItem, onClick: () -> Unit, onLongClick: () -> Unit) {{
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {{
        Box(
            modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)).background(LpThumb)
        ) {{
            AsyncImage(model = video.uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
            when {{
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
            }}
            LinearProgressIndicator(
                progress = {{ video.progressRatio }},
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                color = if (video.isCompleted) LpSuccess else LpPrimary,
                trackColor = LpSurface3
            )
        }}
        Column(modifier = Modifier.weight(1f)) {{
            Text(video.displayName, style = LocalPlayTypography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                Formatters.duration(video.durationMs) + " · " +
                    Formatters.resolution(video.width, video.height) + " · " +
                    Formatters.fileSize(video.sizeBytes),
                style = LocalPlayTypography.labelSmall,
                color = LpText2
            )
        }}
    }}
}}

@Composable
private fun ContextMenuDialog(
    video: VideoItem,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    onDelete: () -> Unit
) {{
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LpSurface2,
        title = {{ Text(video.displayName, color = LpText, maxLines = 1, overflow = TextOverflow.Ellipsis) }},
        text = {{
            Column {{
                TextButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {{ Text("播放", color = LpText) }}
                TextButton(onClick = onDetail, modifier = Modifier.fillMaxWidth()) {{ Text("查看详情", color = LpText) }}
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {{ Text("删除", color = LpDanger) }}
            }}
        }},
        confirmButton = {{}},
        dismissButton = {{ TextButton(onClick = onDismiss) {{ Text("取消", color = LpText2) }} }}
    )
}}

@Composable
private fun DeleteConfirmDialog(video: VideoItem, onDismiss: () -> Unit, onConfirm: () -> Unit) {{
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LpSurface2,
        title = {{ Text("删除视频？", color = LpText) }},
        text = {{
            Text(
                "将永久删除「" + video.displayName + "」（" + Formatters.fileSize(video.sizeBytes) + "）。此操作不可恢复。",
                color = LpText2
            )
        }},
        confirmButton = {{ TextButton(onClick = onConfirm) {{ Text("删除", color = LpDanger) }} }},
        dismissButton = {{ TextButton(onClick = onDismiss) {{ Text("取消", color = LpText2) }} }}
    )
}}

@Composable
private fun ResumeDialog(
    video: VideoItem,
    onContinue: () -> Unit,
    onFromStart: () -> Unit,
    onDismiss: () -> Unit
) {{
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LpSurface2,
        title = {{ Text("从上次位置继续？", color = LpText) }},
        text = {{
            Text(
                "上次播放到 " + Formatters.duration(video.progressMs) +
                    "（已看 " + (video.progressRatio * 100).toInt() + "%）。",
                color = LpText2
            )
        }},
        confirmButton = {{ TextButton(onClick = onContinue) {{ Text("继续播放", color = LpPrimary) }} }},
        dismissButton = {{ TextButton(onClick = onFromStart) {{ Text("从头播放", color = LpText2) }} }}
    )
}}
''',
)

print("home done")
