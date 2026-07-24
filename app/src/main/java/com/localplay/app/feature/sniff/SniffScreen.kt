package com.localplay.app.feature.sniff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localplay.app.core.database.DownloadStatus
import com.localplay.app.core.database.DownloadTaskEntity
import com.localplay.app.core.sniff.SniffedVideo
import com.localplay.app.ui.theme.LpBg
import com.localplay.app.ui.theme.LpDanger
import com.localplay.app.ui.theme.LpOnPrimary
import com.localplay.app.ui.theme.LpPrimary
import com.localplay.app.ui.theme.LpSuccess
import com.localplay.app.ui.theme.LpSurface
import com.localplay.app.ui.theme.LpSurface2
import com.localplay.app.ui.theme.LpText
import com.localplay.app.ui.theme.LpText2
import com.localplay.app.ui.theme.LpText3
import com.localplay.app.ui.theme.LocalPlayTypography

@Composable
fun SniffScreen(
    onBack: () -> Unit,
    onOpenPlayer: (path: String) -> Unit = {},
    viewModel: SniffViewModel = viewModel(factory = SniffViewModel.factory())
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sniffing = state.sniffing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LpBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
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
                Text("链接嗅探下载", style = LocalPlayTypography.titleLarge)
                Text(
                    buildString {
                        append("保存到：${state.downloadPathLabel}")
                        if (state.activeCount > 0) {
                            append(" · 后台下载中 ${state.activeCount}")
                        }
                    },
                    style = LocalPlayTypography.labelSmall,
                    color = LpText2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !sniffing,
                placeholder = {
                    Text("粘贴网页 / 播放页 / m3u8 / mp4 地址", color = LpText3)
                },
                minLines = 2,
                shape = RoundedCornerShape(14.dp),
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
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = viewModel::sniff,
                    enabled = !sniffing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LpPrimary,
                        contentColor = LpOnPrimary
                    )
                ) { Text("开始嗅探") }
                Button(
                    onClick = viewModel::enqueueSelected,
                    enabled = !sniffing && state.selected.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LpSurface2,
                        contentColor = LpText
                    )
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("加入队列(${state.selected.size})")
                }
            }
            if (state.status.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(state.status, color = LpText2, style = LocalPlayTypography.bodyMedium)
            }
            if (sniffing) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = LpPrimary,
                        strokeWidth = 2.dp
                    )
                    Text("嗅探中…可随时离开，不影响已加入的下载", color = LpText2)
                }
            }
            state.error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = LpDanger, style = LocalPlayTypography.bodyMedium)
            }
            state.successMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = LpPrimary, style = LocalPlayTypography.bodyMedium)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (state.tasks.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "下载任务",
                            style = LocalPlayTypography.titleMedium,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
                        TextButton(onClick = viewModel::clearCompleted) {
                            Text("清除已完成")
                        }
                    }
                }
                items(state.tasks, key = { "task-" + it.id }) { task ->
                    DownloadTaskRow(
                        task = task,
                        onPause = { viewModel.pauseTask(task.id) },
                        onResume = { viewModel.resumeTask(task.id) },
                        onRestart = { viewModel.restartTask(task.id) },
                        onCancel = { viewModel.cancelTask(task.id) },
                        onPlay = {
                            viewModel.playWhileDownload(task.id) { path ->
                                onOpenPlayer(path)
                            }
                        }
                    )
                }
            }

            if (state.items.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "嗅探结果",
                            style = LocalPlayTypography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        TextButton(onClick = viewModel::selectAll, enabled = !sniffing) { Text("全选") }
                        TextButton(onClick = viewModel::clearSelection, enabled = !sniffing) { Text("清空") }
                        Text(
                            "共 ${state.items.size} 项",
                            color = LpText3,
                            style = LocalPlayTypography.labelSmall
                        )
                    }
                }
                items(state.items, key = { it.mediaUrl }) { item ->
                    SniffItemRow(
                        item = item,
                        selected = item.mediaUrl in state.selected,
                        enabled = !sniffing,
                        onToggle = { viewModel.toggle(item.mediaUrl) }
                    )
                }
            } else if (!sniffing && state.tasks.isEmpty()) {
                item {
                    Text(
                        "支持列表页 / 详情页 / 播放页，以及 mp4、m3u8 直链。\n" +
                            "下载在后台队列执行，退出页面不会中断；失败或暂停后可断点续传。",
                        color = LpText3,
                        style = LocalPlayTypography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(LpSurface, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(task.title, style = LocalPlayTypography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(4.dp))
        Text(statusLabel(task), color = statusColor(task.status), style = LocalPlayTypography.labelSmall)
        if (task.status == DownloadStatus.RUNNING ||
            task.status == DownloadStatus.QUEUED ||
            task.status == DownloadStatus.PAUSED
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { task.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = LpPrimary,
                trackColor = LpSurface2
            )
        }
        if (task.errorMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(task.errorMessage, color = LpDanger, style = LocalPlayTypography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (task.status) {
                DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                    TextButton(onClick = onPause) { Text("暂停") }
                    TextButton(onClick = onPlay) { Text("边下边播", color = LpPrimary) }
                    TextButton(onClick = onRestart) { Text("重新开始") }
                }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                    TextButton(onClick = onResume) { Text("继续") }
                    TextButton(onClick = onPlay) { Text("边下边播", color = LpPrimary) }
                    TextButton(onClick = onRestart) { Text("重新开始") }
                }
                DownloadStatus.COMPLETED -> {
                    TextButton(onClick = onPlay) { Text("播放", color = LpPrimary) }
                    TextButton(onClick = onRestart) { Text("重新下载") }
                }
            }
            if (task.status != DownloadStatus.COMPLETED) {
                TextButton(onClick = onCancel) { Text("取消", color = LpDanger) }
            }
        }
    }
}

private fun statusLabel(task: DownloadTaskEntity): String {
    val pct = (task.progressFraction * 100).toInt()
    return when (task.status) {
        DownloadStatus.QUEUED -> "排队中"
        DownloadStatus.RUNNING -> if (task.isHls) {
            "下载中 $pct%（分片 ${task.hlsSegmentIndex}/${task.hlsSegmentTotal}）"
        } else {
            "下载中 $pct%"
        }
        DownloadStatus.PAUSED -> "已暂停 $pct%（可续传）"
        DownloadStatus.FAILED -> "失败"
        DownloadStatus.COMPLETED -> "已完成"
    }
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.COMPLETED -> LpSuccess
    DownloadStatus.FAILED -> LpDanger
    DownloadStatus.PAUSED -> LpText2
    else -> LpPrimary
}

@Composable
private fun SniffItemRow(
    item: SniffedVideo,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .background(LpSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (selected) LpPrimary else LpText3
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                item.title,
                style = LocalPlayTypography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                buildString {
                    append(if (item.isHls) "HLS" else "文件")
                    if (item.sourceLabel.isNotBlank()) append(" · ").append(item.sourceLabel)
                },
                style = LocalPlayTypography.labelSmall,
                color = LpText2
            )
            Text(
                item.mediaUrl,
                style = LocalPlayTypography.labelSmall,
                color = LpText3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
