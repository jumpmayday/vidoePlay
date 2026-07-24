package com.localplay.app.feature.permission

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Modifier
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

fun videoPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit
) {
    val permissionState = rememberMultiplePermissionsState(videoPermissions())
    var requestedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (permissionState.allPermissionsGranted) {
            onGranted()
        }
    }

    if (permissionState.allPermissionsGranted) {
        content()
        return
    }

    val showRationale = permissionState.permissions.any { it.status.shouldShowRationale }
    val permanentlyDenied = requestedOnce &&
        permissionState.permissions.any { !it.status.isGranted && !it.status.shouldShowRationale }

    if (permanentlyDenied && !showRationale) {
        PermissionDeniedScreen(onOpenSettings = {})
    } else {
        PermissionRequestScreen(
            onAllow = {
                requestedOnce = true
                permissionState.launchMultiplePermissionRequest()
            }
        )
    }
}

@Composable
fun PermissionRequestScreen(onAllow: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LpBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Box(
            modifier = Modifier.size(88.dp).background(LpPrimaryDim, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = LpPrimary, modifier = Modifier.size(40.dp))
        }
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
        ) {
            Text("需要访问视频文件", style = LocalPlayTypography.titleMedium)
            Text(
                "LocalPlay 仅在本地扫描并播放视频，不会上传任何文件，也不会申请网络权限。",
                style = LocalPlayTypography.bodyMedium,
                color = LpText2
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LpPrimary, contentColor = LpOnPrimary)
        ) {
            Text("允许访问视频", style = LocalPlayTypography.labelLarge)
        }
        TextButton(onClick = {}) {
            Text("暂不允许", color = LpText3)
        }
    }
}

@Composable
fun PermissionDeniedScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LpBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(64.dp))
        Box(
            modifier = Modifier.size(72.dp).background(LpSurface3, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Lock, null, tint = LpDanger, modifier = Modifier.size(36.dp))
        }
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
        ) {
            listOf("1. 打开系统设置", "2. 找到 LocalPlay 应用", "3. 开启视频/存储权限").forEach { step ->
                Text(step, color = LpText, fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(intent)
                onOpenSettings()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LpPrimary, contentColor = LpOnPrimary)
        ) {
            Text("去设置开启权限", style = LocalPlayTypography.labelLarge)
        }
    }
}
