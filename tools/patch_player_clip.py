# -*- coding: utf-8 -*-
"""Patch PlayerScreen for screenshot + clip."""
from pathlib import Path

path = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\feature\player\PlayerScreen.kt")
text = path.read_text(encoding="utf-8")

# --- imports ---
old_imports = """import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
"""

new_imports = """import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
"""

if old_imports not in text:
    raise SystemExit("imports block not found")
text = text.replace(old_imports, new_imports, 1)

extra_imports = """import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.sp
import com.localplay.app.core.media.ScreenshotSaver
import com.localplay.app.core.media.VideoClipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
"""

anchor = "import androidx.compose.foundation.background\n"
if "ScreenshotSaver" not in text:
    text = text.replace(anchor, anchor + extra_imports, 1)

# --- state after lastTapY ---
old_state = """    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastTapX by remember { mutableFloatStateOf(0f) }
    var lastTapY by remember { mutableFloatStateOf(0f) }
"""

new_state = """    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastTapX by remember { mutableFloatStateOf(0f) }
    var lastTapY by remember { mutableFloatStateOf(0f) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var clipMode by remember { mutableStateOf(false) }
    var clipStartMs by remember { mutableLongStateOf(0L) }
    var clipEndMs by remember { mutableLongStateOf(0L) }
    var clipExporting by remember { mutableStateOf(false) }
    var clipProgress by remember { mutableFloatStateOf(0f) }
    var screenshotPreview by remember { mutableStateOf<Bitmap?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
"""

if old_state not in text:
    raise SystemExit("state block not found")
text = text.replace(old_state, new_state, 1)

# LaunchedEffect for status message auto clear
old_ctrl = """    LaunchedEffect(controlsVisible, locked) {
        if (controlsVisible && !locked) {
            delay(4_000L)
            controlsVisible = false
        }
    }
"""

new_ctrl = """    LaunchedEffect(controlsVisible, locked, clipMode) {
        if (controlsVisible && !locked && !clipMode) {
            delay(4_000L)
            controlsVisible = false
        }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(2_400L)
            statusMessage = null
        }
    }

    LaunchedEffect(screenshotPreview) {
        if (screenshotPreview != null) {
            delay(2_600L)
            screenshotPreview = null
        }
    }

    fun vibrateLight() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(VibratorManager::class.java)
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(35)
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    fun takeScreenshot() {
        val view = playerViewRef
        if (view == null) {
            statusMessage = "播放器未就绪"
            return
        }
        scope.launch {
            try {
                statusMessage = "正在截图…"
                val result = ScreenshotSaver.captureAndSave(
                    context = context,
                    playerView = view,
                    videoUri = currentVideo.uri,
                    videoDisplayName = currentVideo.displayName,
                    positionMs = exoPlayer.currentPosition
                )
                screenshotPreview = result.bitmap
                vibrateLight()
                statusMessage = "截图已保存到 Pictures/LocalPlay/Screenshots"
            } catch (e: Exception) {
                statusMessage = "截图失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun enterClipMode() {
        clipMode = true
        controlsVisible = true
        val pos = exoPlayer.currentPosition
        clipStartMs = pos
        clipEndMs = (pos + 30_000L).coerceAtMost(durationMs.coerceAtLeast(pos + 1_000L))
        if (clipEndMs <= clipStartMs) {
            clipEndMs = (clipStartMs + 5_000L).coerceAtMost(durationMs.coerceAtLeast(clipStartMs + 1_000L))
        }
        statusMessage = "截取模式：移动进度设定 A/B 点后导出"
    }

    fun exportClip() {
        if (clipExporting) return
        if (clipEndMs - clipStartMs < 500L) {
            statusMessage = "片段至少需要 0.5 秒"
            return
        }
        scope.launch {
            clipExporting = true
            clipProgress = 0f
            try {
                val result = withContext(Dispatchers.IO) {
                    VideoClipExporter.export(
                        context = context,
                        sourceUri = currentVideo.uri,
                        startMs = clipStartMs,
                        endMs = clipEndMs,
                        videoDisplayName = currentVideo.displayName,
                        onProgress = { p -> clipProgress = p }
                    )
                }
                vibrateLight()
                clipMode = false
                statusMessage = "片段已保存：${result.displayName}"
                Toast.makeText(context, "已保存到 Movies/LocalPlay/Clips", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                statusMessage = "导出失败：${e.message ?: "未知错误"}"
            } finally {
                clipExporting = false
            }
        }
    }
"""

# Fix: Python will interpret ${e.message} - need to escape! Use a write that doesn't use f-string
# The new_ctrl above is a regular string so ${e.message} is fine for Kotlin.
# But wait - in the write file I used """ which is fine. Inside takeScreenshot I have ${e.message ?: "未知错误"} - in Python this is OK in non-f string.

if old_ctrl not in text:
    raise SystemExit("controls LaunchedEffect not found")
text = text.replace(old_ctrl, new_ctrl, 1)

# AndroidView factory - capture ref
old_av = """        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
"""

new_av = """        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    playerViewRef = this
                }
            },
            update = { view ->
                view.player = exoPlayer
                playerViewRef = view
            },
            modifier = Modifier.fillMaxSize()
        )
"""

if old_av not in text:
    raise SystemExit("AndroidView block not found")
text = text.replace(old_av, new_av, 1)

# BottomControls call
old_bc = """            BottomControls(
                modifier = Modifier.align(Alignment.BottomCenter),
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                speed = speed,
                onSeek = { exoPlayer.seekTo(it) },
                onTogglePlay = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onPrev = { switchTo(currentIndex - 1) },
                onNext = { switchTo(currentIndex + 1) },
                onSpeed = { showSpeedSheet = true },
                onRotate = {
                    activity?.let { act ->
                        act.requestedOrientation =
                            if (act.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            }
                    }
                }
            )
"""

new_bc = """            BottomControls(
                modifier = Modifier.align(Alignment.BottomCenter),
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                speed = speed,
                onSeek = { exoPlayer.seekTo(it) },
                onTogglePlay = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onPrev = { switchTo(currentIndex - 1) },
                onNext = { switchTo(currentIndex + 1) },
                onSpeed = { showSpeedSheet = true },
                onClip = { enterClipMode() },
                onScreenshot = { takeScreenshot() },
                onRotate = {
                    activity?.let { act ->
                        act.requestedOrientation =
                            if (act.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            }
                    }
                }
            )
"""

if old_bc not in text:
    raise SystemExit("BottomControls call not found")
text = text.replace(old_bc, new_bc, 1)

# Insert clip overlay + screenshot preview before speed sheet
old_sheet = """        if (showSpeedSheet) {
"""

clip_ui = """        if (clipMode) {
            ClipPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                positionMs = positionMs,
                durationMs = durationMs,
                startMs = clipStartMs,
                endMs = clipEndMs,
                exporting = clipExporting,
                progress = clipProgress,
                onSeek = {
                    exoPlayer.seekTo(it)
                    positionMs = it
                },
                onSetStart = {
                    clipStartMs = positionMs
                    if (clipEndMs <= clipStartMs) {
                        clipEndMs = (clipStartMs + 5_000L).coerceAtMost(durationMs)
                    }
                    statusMessage = "A 点：" + Formatters.duration(clipStartMs)
                },
                onSetEnd = {
                    clipEndMs = positionMs.coerceAtLeast(clipStartMs + 500L)
                    statusMessage = "B 点：" + Formatters.duration(clipEndMs)
                },
                onExport = { exportClip() },
                onCancel = {
                    if (!clipExporting) {
                        clipMode = false
                        statusMessage = null
                    }
                }
            )
        }

        screenshotPreview?.let { bmp ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 16.dp)
                    .background(LpOverlay, RoundedCornerShape(10.dp))
                    .border(1.dp, LpPrimary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(8.dp)
                    .clickable {
                        // Open gallery best-effort
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(
                                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                        "image/*"
                                    )
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Exception) {
                            // ignore
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "截图预览",
                    modifier = Modifier
                        .size(72.dp, 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("截图已保存", color = LpText, style = LocalPlayTypography.labelMedium)
            }
        }

        statusMessage?.let { msg ->
            HintChip(
                text = msg,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
        }

        if (showSpeedSheet) {
"""

# need clip import for RoundedCornerShape already there; need Modifier.clip
if "import androidx.compose.ui.draw.clip" not in text:
    text = text.replace(
        "import androidx.compose.ui.Alignment\n",
        "import androidx.compose.ui.Alignment\nimport androidx.compose.ui.draw.clip\n",
        1
    )

if old_sheet not in text:
    raise SystemExit("speed sheet marker not found")
text = text.replace(old_sheet, clip_ui, 1)

# Update BottomControls function signature and stubs
old_bc_fn = """private fun BottomControls(
    modifier: Modifier = Modifier,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    speed: Float,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onRotate: () -> Unit
) {
"""

new_bc_fn = """private fun BottomControls(
    modifier: Modifier = Modifier,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    speed: Float,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onClip: () -> Unit,
    onScreenshot: () -> Unit,
    onRotate: () -> Unit
) {
"""

if old_bc_fn not in text:
    raise SystemExit("BottomControls fn not found")
text = text.replace(old_bc_fn, new_bc_fn, 1)

text = text.replace(
    '            ControlItem(Icons.Default.ContentCut, "截取") {}\n            ControlItem(Icons.Default.CameraAlt, "截图") {}\n',
    '            ControlItem(Icons.Default.ContentCut, "截取", onClip)\n            ControlItem(Icons.Default.CameraAlt, "截图", onScreenshot)\n',
    1
)

# Append ClipPanel composable before end of file
clip_panel = '''
@Composable
private fun ClipPanel(
    modifier: Modifier = Modifier,
    positionMs: Long,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    exporting: Boolean,
    progress: Float,
    onSeek: (Long) -> Unit,
    onSetStart: () -> Unit,
    onSetEnd: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("截取片段", color = LpText, style = LocalPlayTypography.titleMedium)
            IconButton(onClick = onCancel, enabled = !exporting) {
                Icon(Icons.Default.Close, contentDescription = "取消", tint = LpText2)
            }
        }
        Text(
            "A " + Formatters.duration(startMs) + "  →  B " + Formatters.duration(endMs) +
                "（时长 " + Formatters.duration((endMs - startMs).coerceAtLeast(0L)) + "）",
            color = LpText2,
            style = LocalPlayTypography.labelMedium
        )
        Slider(
            value = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
            onValueChange = { ratio -> onSeek((ratio * durationMs).toLong()) },
            enabled = !exporting,
            colors = SliderDefaults.colors(
                thumbColor = LpPrimary,
                activeTrackColor = LpPrimary,
                inactiveTrackColor = LpSurface3
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ClipActionButton(
                text = "设为 A 点",
                modifier = Modifier.weight(1f),
                enabled = !exporting,
                onClick = onSetStart
            )
            ClipActionButton(
                text = "设为 B 点",
                modifier = Modifier.weight(1f),
                enabled = !exporting,
                onClick = onSetEnd
            )
            ClipActionButton(
                text = if (exporting) "导出中 ${(progress * 100).toInt()}%" else "导出",
                modifier = Modifier.weight(1f),
                enabled = !exporting,
                primary = true,
                onClick = onExport
            )
        }
    }
}

@Composable
private fun ClipActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .background(
                if (primary) LpPrimary.copy(alpha = if (enabled) 1f else 0.4f)
                else LpSurface2.copy(alpha = if (enabled) 1f else 0.5f),
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (primary) LpOnPrimary else LpText,
            style = LocalPlayTypography.labelMedium,
            maxLines = 1
        )
    }
}
'''

if "private fun ClipPanel(" not in text:
    text = text.rstrip() + "\n" + clip_panel + "\n"

path.write_text(text, encoding="utf-8", newline="\n")
print("PlayerScreen patched, lines:", len(text.splitlines()))
