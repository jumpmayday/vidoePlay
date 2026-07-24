# -*- coding: utf-8 -*-
from pathlib import Path

path = Path(r"D:\code\stu\videoPlay\app\src\main\java\com\localplay\app\feature\player\PlayerScreen.kt")
path.write_text(r'''package com.localplay.app.feature.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.localplay.app.LocalPlayApp
import com.localplay.app.core.common.Formatters
import com.localplay.app.data.model.VideoItem
import com.localplay.app.ui.theme.LpOnPrimary
import com.localplay.app.ui.theme.LpOverlay
import com.localplay.app.ui.theme.LpPrimary
import com.localplay.app.ui.theme.LpSurface
import com.localplay.app.ui.theme.LpSurface2
import com.localplay.app.ui.theme.LpSurface3
import com.localplay.app.ui.theme.LpText
import com.localplay.app.ui.theme.LpText2
import com.localplay.app.ui.theme.LocalPlayTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f)

private enum class GestureZone {
    BRIGHTNESS,
    VOLUME,
    SEEK
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(path: String, fromStart: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val repository = LocalPlayApp.instance.videoRepository
    val scope = rememberCoroutineScope()
    val video = remember(path) { repository.findByPath(path) }
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 18.dp.toPx() }

    if (video == null) {
        MissingVideoScreen()
        return
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(video.durationMs) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var brightnessHint by remember { mutableStateOf<Int?>(null) }
    var volumeHint by remember { mutableStateOf<Int?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var currentVideo by remember { mutableStateOf(video) }

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    val siblings = remember(path) { repository.siblingsInFolder(path) }
    var currentIndex by remember {
        mutableStateOf(siblings.indexOfFirst { it.path == path }.coerceAtLeast(0))
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(video.uri))
            playWhenReady = true
            prepare()
            val start = if (fromStart) 0L else video.progressMs
            if (start > 0L) seekTo(start)
        }
    }

    val latestVideo by rememberUpdatedState(currentVideo)
    val latestSpeed by rememberUpdatedState(speed)
    val latestLocked by rememberUpdatedState(locked)

    fun persistProgress() {
        val pos = exoPlayer.currentPosition
        val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: latestVideo.durationMs
        scope.launch {
            repository.saveProgress(
                path = latestVideo.path,
                positionMs = pos,
                durationMs = dur,
                sizeBytes = latestVideo.sizeBytes,
                modifiedAt = latestVideo.dateModified,
                speed = latestSpeed
            )
        }
    }

    fun currentBrightness(): Float {
        val window = activity?.window ?: return 0.5f
        val value = window.attributes.screenBrightness
        return if (value < 0f) 0.5f else value.coerceIn(0f, 1f)
    }

    fun applyBrightness(value: Float) {
        val window = activity?.window ?: return
        val attrs = window.attributes
        attrs.screenBrightness = value.coerceIn(0.01f, 1f)
        window.attributes = attrs
    }

    fun currentVolumePercent(): Int {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((current.toFloat() / maxVolume) * 100f).roundToInt().coerceIn(0, 100)
    }

    fun applyVolumePercent(percent: Int) {
        val target = ((percent.coerceIn(0, 100) / 100f) * maxVolume).roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            // Keep screen on while playing.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val d = exoPlayer.duration
                    if (d != C.TIME_UNSET) durationMs = d
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            persistProgress()
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition
            delay(500L)
            if (positionMs > 0L && positionMs % 5_000L < 600L) persistProgress()
        }
    }

    LaunchedEffect(controlsVisible, locked) {
        if (controlsVisible && !locked) {
            delay(4_000L)
            controlsVisible = false
        }
    }

    fun switchTo(index: Int) {
        if (index !in siblings.indices) return
        persistProgress()
        val next = siblings[index]
        currentIndex = index
        currentVideo = next
        exoPlayer.setMediaItem(MediaItem.fromUri(next.uri))
        exoPlayer.prepare()
        exoPlayer.play()
        durationMs = next.durationMs
        controlsVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(locked, touchSlopPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (latestLocked) return@awaitEachGesture

                    val start = down.position
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    val zone = when {
                        start.x < width / 3f -> GestureZone.BRIGHTNESS
                        start.x > width * 2f / 3f -> GestureZone.VOLUME
                        else -> GestureZone.SEEK
                    }

                    val startBrightness = currentBrightness()
                    val startVolumePercent = currentVolumePercent()
                    val startPosition = exoPlayer.currentPosition
                    val duration = exoPlayer.duration
                        .takeIf { it != C.TIME_UNSET && it > 0L }
                        ?: durationMs.coerceAtLeast(1L)
                    val seekRangeMs = minOf(duration, 180_000L).toFloat().coerceAtLeast(30_000f)

                    var totalDx = 0f
                    var totalDy = 0f
                    var dragging = false
                    var lastTapAt = 0L
                    var lastTapPos = Offset.Zero

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!dragging) {
                                val now = System.currentTimeMillis()
                                val isDoubleTap = now - lastTapAt < 280L &&
                                    hypot(
                                        (change.position.x - lastTapPos.x).toDouble(),
                                        (change.position.y - lastTapPos.y).toDouble()
                                    ) < touchSlopPx * 2
                                if (isDoubleTap) {
                                    val seekBy = 10_000L
                                    if (change.position.x < width / 2f) {
                                        exoPlayer.seekTo((exoPlayer.currentPosition - seekBy).coerceAtLeast(0L))
                                    } else {
                                        exoPlayer.seekTo(
                                            (exoPlayer.currentPosition + seekBy).coerceAtMost(duration)
                                        )
                                    }
                                    controlsVisible = true
                                    lastTapAt = 0L
                                } else {
                                    controlsVisible = !controlsVisible
                                    lastTapAt = now
                                    lastTapPos = change.position
                                }
                            }
                            brightnessHint = null
                            volumeHint = null
                            seekHint = null
                            break
                        }

                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        val distance = hypot(totalDx.toDouble(), totalDy.toDouble()).toFloat()
                        if (!dragging && distance >= touchSlopPx) {
                            dragging = true
                        }
                        if (!dragging) continue
                        change.consume()

                        when (zone) {
                            GestureZone.BRIGHTNESS -> {
                                val next = (startBrightness - totalDy / height).coerceIn(0.01f, 1f)
                                applyBrightness(next)
                                brightnessHint = (next * 100f).roundToInt()
                                volumeHint = null
                                seekHint = null
                            }
                            GestureZone.VOLUME -> {
                                val next = (startVolumePercent - (totalDy / height * 100f))
                                    .roundToInt()
                                    .coerceIn(0, 100)
                                applyVolumePercent(next)
                                volumeHint = next
                                brightnessHint = null
                                seekHint = null
                            }
                            GestureZone.SEEK -> {
                                val deltaMs = (totalDx / width * seekRangeMs).toLong()
                                val target = (startPosition + deltaMs).coerceIn(0L, duration)
                                exoPlayer.seekTo(target)
                                positionMs = target
                                val sign = if (deltaMs >= 0) "+" else "-"
                                seekHint = sign + Formatters.duration(abs(deltaMs)) +
                                    "  →  " + Formatters.duration(target)
                                brightnessHint = null
                                volumeHint = null
                                controlsVisible = true
                            }
                        }
                    }
                }
            }
    ) {
        AndroidView(
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

        if (controlsVisible || locked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            )
        }

        if (controlsVisible && !locked) {
            TopBar(video = currentVideo, onBack = {
                persistProgress()
                onBack()
            })
            BottomControls(
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
                            if (act.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                    }
                }
            )
        }

        IconButton(
            onClick = {
                locked = !locked
                controlsVisible = true
            },
            modifier = Modifier.align(Alignment.CenterStart).padding(12.dp).background(LpOverlay, CircleShape)
        ) {
            Icon(
                if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = "锁屏",
                tint = LpText
            )
        }

        brightnessHint?.let { value ->
            HintChip(
                text = "亮度 $value%",
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
            )
        }
        volumeHint?.let { value ->
            HintChip(
                text = "音量 $value%",
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
            )
        }
        seekHint?.let { text ->
            HintChip(
                text = text,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (showSpeedSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSpeedSheet = false },
                sheetState = rememberModalBottomSheetState(),
                containerColor = LpSurface
            ) {
                SpeedSheet(current = speed, onSelect = {
                    speed = it
                    exoPlayer.setPlaybackSpeed(it)
                    showSpeedSheet = false
                })
            }
        }
    }
}

@Composable
private fun MissingVideoScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text("视频不存在或已被删除", color = LpText2)
    }
}

@Composable
private fun TopBar(video: VideoItem, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 36.dp, start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = LpText)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(video.displayName, style = LocalPlayTypography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(video.folderName, style = LocalPlayTypography.labelSmall, color = LpText2)
        }
    }
}

@Composable
private fun BottomControls(
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
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Formatters.duration(positionMs), color = LpText, style = LocalPlayTypography.labelMedium)
            Text(Formatters.duration(durationMs), color = LpText2, style = LocalPlayTypography.labelMedium)
        }
        Slider(
            value = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
            onValueChange = { ratio -> onSeek((ratio * durationMs).toLong()) },
            colors = SliderDefaults.colors(
                thumbColor = LpPrimary,
                activeTrackColor = LpPrimary,
                inactiveTrackColor = LpSurface3
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlItem(Icons.Default.ContentCut, "截取") {}
            ControlItem(Icons.Default.CameraAlt, "截图") {}
            ControlItem(Icons.Default.SkipPrevious, "上一集", onPrev)
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(48.dp).background(LpPrimary.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = LpText,
                    modifier = Modifier.size(28.dp)
                )
            }
            ControlItem(Icons.Default.SkipNext, "下一集", onNext)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = onSpeed)
            ) {
                Text(formatSpeed(speed), color = LpText, style = LocalPlayTypography.labelMedium)
                Text("倍速", color = LpText2, style = LocalPlayTypography.labelSmall)
            }
            ControlItem(Icons.Default.ScreenRotation, "旋转", onRotate)
        }
    }
}

private fun formatSpeed(speed: Float): String {
    return if (abs(speed - speed.toInt()) < 0.01f) {
        speed.toInt().toString() + "x"
    } else {
        speed.toString() + "x"
    }
}

@Composable
private fun ControlItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = LpText) }
        Text(label, color = LpText2, style = LocalPlayTypography.labelSmall)
    }
}

@Composable
private fun HintChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LpText,
        modifier = modifier
            .background(LpOverlay, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

@Composable
private fun SpeedSheet(current: Float, onSelect: (Float) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("播放倍速", style = LocalPlayTypography.titleMedium)
        Text("长按画面可临时 2x 加速", style = LocalPlayTypography.bodyMedium, color = LpText2)
        SPEED_OPTIONS.chunked(5).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val selected = option == current
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .background(if (selected) LpPrimary else LpSurface2, RoundedCornerShape(12.dp))
                            .clickable { onSelect(option) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            formatSpeed(option),
                            color = if (selected) LpOnPrimary else LpText,
                            style = LocalPlayTypography.labelMedium
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
''', encoding="utf-8", newline="\n")
print("PlayerScreen written")
