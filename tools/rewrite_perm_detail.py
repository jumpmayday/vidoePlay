# -*- coding: utf-8 -*-
"""Rewrite corrupted LocalPlay UI Kotlin sources with UTF-8 Chinese."""
from pathlib import Path

ROOT = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app")
MOD = "import androidx.compose.ui." + "modifier" + "." + "M" + "odifier"
assert MOD.endswith("modifier.Modifier".replace("m", "M", 1)) or True
assert ord(MOD.split(".")[-2][0]) == 109 and ord(MOD.split(".")[-1][0]) == 77


def write(rel: str, content: str) -> None:
    path = ROOT / rel
    path.write_text(content, encoding="utf-8", newline="\n")
    text = path.read_text(encoding="utf-8")
    line = next(l for l in text.splitlines() if "compose.ui.m" in l and "odifier" in l)
    assert ord(line.split(".")[-2][0]) == 109, line
    assert ord(line.split(".")[-1][0]) == 77, line
    print("OK", rel, "chars", len(text))


write(
    "feature/permission/PermissionScreen.kt",
    f'''package com.localplay.app.feature.permission

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import com.localplay.app.ui.theme.LpBg
import com.localplay.app.ui.theme.LpDanger
import com.localplay.app.ui.theme.LpOnPrimary
import com.localplay.app.ui.theme.LpPrimary
import com.localplay.app.ui.theme.LpPrimaryDim
import com.localplay.app.ui.theme.LpSurface2
import com.localplay.app.ui.theme.LpSurface3
import com.localplay.app.ui.theme.LpText
import com.localplay.app.ui.theme.LpText2
import com.localplay.app.ui.theme.LpText3
import com.localplay.app.ui.theme.LocalPlayTypography

fun videoPermissions(): List<String> {{
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {{
        listOf(Manifest.permission.READ_MEDIA_VIDEO)
    }} else {{
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }}
}}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit
) {{
    val permissionState = rememberMultiplePermissionsState(videoPermissions())
    var requestedOnce by remember {{ mutableStateOf(false) }}

    LaunchedEffect(permissionState.allPermissionsGranted) {{
        if (permissionState.allPermissionsGranted) {{
            onGranted()
        }}
    }}

    if (permissionState.allPermissionsGranted) {{
        content()
        return
    }}

    val showRationale = permissionState.permissions.any {{ it.status.shouldShowRationale }}
    val permanentlyDenied = requestedOnce &&
        permissionState.permissions.any {{ !it.status.isGranted && !it.status.shouldShowRationale }}

    if (permanentlyDenied && !showRationale) {{
        PermissionDeniedScreen(onOpenSettings = {{}})
    }} else {{
        PermissionRequestScreen(
            onAllow = {{
                requestedOnce = true
                permissionState.launchMultiplePermissionRequest()
            }}
        )
    }}
}}

@Composable
fun PermissionRequestScreen(onAllow: () -> Unit) {{
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LpBg)
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {{
        Spacer(modifier = Modifier.height(48.dp))
        Box(
            modifier = Modifier.size(88.dp).background(LpPrimaryDim, CircleShape),
            contentAlignment = Alignment.Center
        ) {{
            Icon(Icons.Default.PlayArrow, null, tint = LpPrimary, modifier = Modifier.size(40.dp))
        }}
        Spacer(modifier = Modifier.height(16.dp))
        Text("LocalPlay", style = LocalPlayTypography.displayLarge)
        Text("纯本地视频播放器", style = LocalPlayTypography.bodyMedium, color = LpText2)
        Spacer(modifier = Modifier.height(32.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LpSurface2, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {{
            Text("需要访问视频文件", style = LocalPlayTypography.titleMedium)
            Text(
                "LocalPlay 仅在本地扫描并播放视频，不会上传任何文件，也不会申请网络权限。",
                style = LocalPlayTypography.bodyMedium,
                color = LpText2
            )
        }}
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LpPrimary, contentColor = LpOnPrimary)
        ) {{
            Text("允许访问视频", style = LocalPlayTypography.labelLarge)
        }}
        TextButton(onClick = {{}}) {{
            Text("暂不允许", color = LpText3)
        }}
    }}
}}

@Composable
fun PermissionDeniedScreen(onOpenSettings: () -> Unit) {{
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LpBg)
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {{
        Spacer(modifier = Modifier.height(64.dp))
        Box(
            modifier = Modifier.size(72.dp).background(LpSurface3, CircleShape),
            contentAlignment = Alignment.Center
        ) {{
            Icon(Icons.Default.Lock, null, tint = LpDanger, modifier = Modifier.size(36.dp))
        }}
        Spacer(modifier = Modifier.height(20.dp))
        Text("无法访问本地视频", style = LocalPlayTypography.titleLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "你已拒绝存储权限。请前往系统设置开启视频或存储权限，否则无法扫描和播放本地视频。",
            style = LocalPlayTypography.bodyMedium,
            color = LpText2,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LpSurface2, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {{
            listOf("1. 打开系统设置", "2. 找到 LocalPlay 应用", "3. 开启视频/存储权限").forEach {{ step ->
                Text(step, color = LpText, fontSize = 13.sp)
            }}
        }}
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {{
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(intent)
                onOpenSettings()
            }},
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LpPrimary, contentColor = LpOnPrimary)
        ) {{
            Text("去设置开启权限", style = LocalPlayTypography.labelLarge)
        }}
    }}
}}
''',
)

write(
    "feature/detail/DetailScreen.kt",
    f'''package com.localplay.app.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
{MOD}
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.localplay.app.LocalPlayApp
import com.localplay.app.core.common.Formatters
import com.localplay.app.ui.theme.LpBg
import com.localplay.app.ui.theme.LpSurface
import com.localplay.app.ui.theme.LpText
import com.localplay.app.ui.theme.LpText2
import com.localplay.app.ui.theme.LpThumb
import com.localplay.app.ui.theme.LocalPlayTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DetailScreen(path: String, onBack: () -> Unit) {{
    val video = remember(path) {{ LocalPlayApp.instance.videoRepository.findByPath(path) }}
    val dateFormat = remember {{ SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }}

    Column(modifier = Modifier.fillMaxSize().background(LpBg)) {{
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {{
            IconButton(onClick = onBack) {{
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = LpText)
            }}
            Text("视频详情", style = LocalPlayTypography.titleLarge)
        }}

        if (video == null) {{
            Text("未找到视频信息", color = LpText2, modifier = Modifier.padding(24.dp))
            return
        }}

        AsyncImage(
            model = video.uri,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(180.dp).background(LpThumb),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp)
        ) {{
            val rows = listOf(
                "文件名" to video.displayName,
                "路径" to video.path.ifBlank {{ video.uri }},
                "大小" to Formatters.fileSize(video.sizeBytes),
                "时长" to Formatters.duration(video.durationMs),
                "分辨率" to Formatters.resolution(video.width, video.height),
                "格式" to video.formatLabel,
                "MIME" to video.mimeType.ifBlank {{ "-" }},
                "修改时间" to dateFormat.format(Date(video.dateModified)),
                "播放进度" to if (video.progressMs > 0) {{
                    Formatters.duration(video.progressMs) + " / " + (video.progressRatio * 100).toInt() + "%"
                }} else {{
                    "未播放"
                }}
            )
            rows.forEachIndexed {{ index, pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (index % 2 == 0) LpBg else LpSurface)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {{
                    Text(pair.first, color = LpText2, style = LocalPlayTypography.bodyMedium, modifier = Modifier.weight(0.35f))
                    Text(pair.second, color = LpText, style = LocalPlayTypography.bodyMedium, modifier = Modifier.weight(0.65f))
                }}
            }}
        }}
    }}
}}
''',
)

print("done permission+detail")
