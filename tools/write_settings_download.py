# -*- coding: utf-8 -*-
from pathlib import Path

Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\feature\settings\SettingsScreen.kt").write_text(r'''package com.localplay.app.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localplay.app.LocalPlayApp
import com.localplay.app.core.sniff.VideoDownloader
import com.localplay.app.data.repository.AppSettings
import com.localplay.app.data.repository.ResumeMode
import com.localplay.app.ui.theme.LpBg
import com.localplay.app.ui.theme.LpOnPrimary
import com.localplay.app.ui.theme.LpPrimary
import com.localplay.app.ui.theme.LpSurface
import com.localplay.app.ui.theme.LpText
import com.localplay.app.ui.theme.LpText2
import com.localplay.app.ui.theme.LocalPlayTypography
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsRepo = LocalPlayApp.instance.settingsRepository
    val videoRepo = LocalPlayApp.instance.videoRepository
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    val defaultPath = remember { VideoDownloader(context).defaultDirPath() }
    val pathLabel = settings.downloadPathLabel.ifBlank { defaultPath }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers may not support persistable permission.
        }
        val label = DocumentFile.fromTreeUri(context, uri)?.name?.let { "已选目录/$it" }
            ?: uri.toString()
        scope.launch {
            settingsRepo.setDownloadPath(uri.toString(), label)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LpBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = LpText)
            }
            Text("设置", style = LocalPlayTypography.titleLarge)
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("下载设置")
            SettingValueRow("保存路径", pathLabel) {
                folderPicker.launch(null)
            }
            if (settings.downloadTreeUri.isNotBlank()) {
                SettingValueRow("恢复默认路径", defaultPath) {
                    scope.launch { settingsRepo.clearDownloadPath() }
                }
            }

            SectionTitle("播放设置")
            SettingValueRow("默认倍速", settings.defaultSpeed.toString() + "x") {
                val next = when (settings.defaultSpeed) {
                    1f -> 1.5f
                    1.5f -> 2f
                    2f -> 0.75f
                    else -> 1f
                }
                scope.launch { settingsRepo.setDefaultSpeed(next) }
            }
            SettingValueRow("快进/快退秒数", settings.seekStepSec.toString() + "s") {
                val next = when (settings.seekStepSec) {
                    5 -> 10
                    10 -> 15
                    15 -> 30
                    else -> 5
                }
                scope.launch { settingsRepo.setSeekStepSec(next) }
            }
            SettingSwitchRow("自动旋转", settings.autoRotate) {
                scope.launch { settingsRepo.setAutoRotate(it) }
            }
            SettingSwitchRow("硬件解码", settings.hardwareDecode) {
                scope.launch { settingsRepo.setHardwareDecode(it) }
            }
            SettingValueRow(
                "续播方式",
                when (settings.resumeMode) {
                    ResumeMode.ASK -> "弹窗询问"
                    ResumeMode.ALWAYS_RESUME -> "始终续播"
                    ResumeMode.ALWAYS_START -> "始终从头"
                }
            ) {
                val next = when (settings.resumeMode) {
                    ResumeMode.ASK -> ResumeMode.ALWAYS_RESUME
                    ResumeMode.ALWAYS_RESUME -> ResumeMode.ALWAYS_START
                    ResumeMode.ALWAYS_START -> ResumeMode.ASK
                }
                scope.launch { settingsRepo.setResumeMode(next) }
            }

            SectionTitle("扫描设置")
            SettingValueRow("最小文件过滤", settings.minSizeMb.toString() + "MB") {
                val next = when (settings.minSizeMb) {
                    0 -> 5
                    5 -> 10
                    10 -> 50
                    else -> 0
                }
                scope.launch { settingsRepo.setMinSizeMb(next) }
            }
            SettingValueRow("最短时长过滤", settings.minDurationSec.toString() + "s") {
                val next = when (settings.minDurationSec) {
                    0 -> 5
                    5 -> 10
                    10 -> 30
                    else -> 0
                }
                scope.launch { settingsRepo.setMinDurationSec(next) }
            }

            SectionTitle("其他")
            SettingValueRow("清除播放记录", "") { showClearConfirm = true }
            SettingValueRow("关于 LocalPlay", "V1.0") {}
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = LpSurface,
            title = { Text("清除全部播放记录？", color = LpText) },
            text = { Text("列表中的续播进度将被清空。", color = LpText2) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        videoRepo.clearAllProgress()
                        showClearConfirm = false
                    }
                }) { Text("清除", color = LpPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消", color = LpText2)
                }
            }
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = LpPrimary,
        style = LocalPlayTypography.labelMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
    )
}

@Composable
private fun SettingValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LpSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LpText, style = LocalPlayTypography.bodyLarge, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) {
            Text(
                value + "  >",
                color = LpText2,
                style = LocalPlayTypography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.2f)
            )
        } else {
            Text(">", color = LpText2)
        }
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LpSurface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LpText, style = LocalPlayTypography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LpOnPrimary,
                checkedTrackColor = LpPrimary,
                uncheckedThumbColor = LpText2,
                uncheckedTrackColor = LpBg
            )
        )
    }
}
''', encoding='utf-8', newline='\n')
print('settings ok')
