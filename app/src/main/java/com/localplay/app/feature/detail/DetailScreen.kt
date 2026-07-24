package com.localplay.app.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.localplay.app.ui.components.VideoThumbnail
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
fun DetailScreen(path: String, onBack: () -> Unit) {
    val video = remember(path) { LocalPlayApp.instance.videoRepository.findByPath(path) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().background(LpBg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = LpText)
            }
            Text("视频详情", style = LocalPlayTypography.titleLarge)
        }

        if (video == null) {
            Text("未找到视频信息", color = LpText2, modifier = Modifier.padding(24.dp))
            return
        }

        VideoThumbnail(
            uri = video.uri,
            modifier = Modifier.fillMaxWidth().height(200.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp)
        ) {
            val rows = listOf(
                "文件名" to video.displayName,
                "路径" to video.path.ifBlank { video.uri },
                "大小" to Formatters.fileSize(video.sizeBytes),
                "时长" to Formatters.duration(video.durationMs),
                "分辨率" to Formatters.resolution(video.width, video.height),
                "格式" to video.formatLabel,
                "MIME" to video.mimeType.ifBlank { "-" },
                "修改时间" to dateFormat.format(Date(video.dateModified)),
                "播放进度" to if (video.progressMs > 0) {
                    Formatters.duration(video.progressMs) + " / " + (video.progressRatio * 100).toInt() + "%"
                } else {
                    "未播放"
                }
            )
            rows.forEachIndexed { index, pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (index % 2 == 0) LpBg else LpSurface)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(pair.first, color = LpText2, style = LocalPlayTypography.bodyMedium, modifier = Modifier.weight(0.35f))
                    Text(pair.second, color = LpText, style = LocalPlayTypography.bodyMedium, modifier = Modifier.weight(0.65f))
                }
            }
        }
    }
}
