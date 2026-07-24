# -*- coding: utf-8 -*-
from pathlib import Path

Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\feature\sniff\SniffScreen.kt").write_text(r'''package com.localplay.app.feature.sniff

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
import com.localplay.app.LocalPlayApp
import com.localplay.app.core.sniff.SniffedVideo
import com.localplay.app.ui.theme.LpBg
import com.localplay.app.ui.theme.LpDanger
import com.localplay.app.ui.theme.LpOnPrimary
import com.localplay.app.ui.theme.LpPrimary
import com.localplay.app.ui.theme.LpSurface
import com.localplay.app.ui.theme.LpSurface2
import com.localplay.app.ui.theme.LpText
import com.localplay.app.ui.theme.LpText2
import com.localplay.app.ui.theme.LpText3
import com.localplay.app.ui.theme.LocalPlayTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SniffScreen(
    onBack: () -> Unit,
    viewModel: SniffViewModel = viewModel(factory = SniffViewModel.factory())
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busy = state.sniffing || state.downloading

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
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = LpText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("链接嗅探下载", style = LocalPlayTypography.titleLarge)
                Text(
                    "保存到：${state.downloadPathLabel}",
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
                enabled = !busy,
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
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LpPrimary,
                        contentColor = LpOnPrimary
                    )
                ) {
                    Text("开始嗅探")
                }
                Button(
                    onClick = {
                        viewModel.startDownload {
                            withContext(Dispatchers.IO) {
                                LocalPlayApp.instance.videoRepository.refresh(force = true)
                            }
                        }
                    },
                    enabled = !busy && state.selected.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LpSurface2,
                        contentColor = LpText
                    )
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("下载选中(${state.selected.size})")
                }
            }
            if (state.status.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(state.status, color = LpText2, style = LocalPlayTypography.bodyMedium)
            }
            if (state.sniffing || state.downloading) {
                Spacer(modifier = Modifier.height(8.dp))
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.downloadFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = LpPrimary,
                        trackColor = LpSurface2
                    )
                    Text(
                        state.downloadMessage,
                        color = LpText2,
                        style = LocalPlayTypography.labelSmall
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            color = LpPrimary,
                            strokeWidth = 2.dp
                        )
                        Text("嗅探中…", color = LpText2)
                    }
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

        if (state.items.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = viewModel::selectAll, enabled = !busy) { Text("全选") }
                TextButton(onClick = viewModel::clearSelection, enabled = !busy) { Text("清空") }
                Text(
                    "共 ${state.items.size} 项",
                    color = LpText3,
                    style = LocalPlayTypography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.mediaUrl }) { item ->
                    SniffItemRow(
                        item = item,
                        selected = item.mediaUrl in state.selected,
                        enabled = !busy,
                        onToggle = { viewModel.toggle(item.mediaUrl) }
                    )
                }
            }
        } else if (!state.sniffing) {
            Text(
                "支持：列表页、详情页、播放页，以及直接的 mp4/m3u8 链接。\n设置中可修改保存目录。",
                color = LpText3,
                style = LocalPlayTypography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
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
''', encoding='utf-8', newline='\n')
print('screen ok')
