package com.localplay.app.feature.player

import android.app.Activity
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
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.asImageBitmap
import com.localplay.app.core.media.ScreenshotSaver
import com.localplay.app.core.media.VideoClipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val settingsRepository = LocalPlayApp.instance.settingsRepository
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 18.dp.toPx() }

    var video by remember(path) { mutableStateOf<VideoItem?>(null) }
    var resolveFailed by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        resolveFailed = false
        video = repository.resolvePlayable(path)
        if (video == null) resolveFailed = true
    }

    if (resolveFailed) {
        MissingVideoScreen()
        return
    }
    val resolved = video
    if (resolved == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("加载中…", color = LpText2)
        }
        return
    }

    PlayerScreenContent(
        initialVideo = resolved,
        path = path,
        fromStart = fromStart,
        onBack = onBack,
        context = context,
        activity = activity,
        repository = repository,
        settingsRepository = settingsRepository,
        scope = scope,
        touchSlopPx = touchSlopPx
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreenContent(
    initialVideo: VideoItem,
    path: String,
    fromStart: Boolean,
    onBack: () -> Unit,
    context: Context,
    activity: Activity?,
    repository: com.localplay.app.data.repository.VideoRepository,
    settingsRepository: com.localplay.app.data.repository.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    touchSlopPx: Float
) {
    val video = initialVideo
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(initialVideo.durationMs) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var brightnessHint by remember { mutableStateOf<Int?>(null) }
    var volumeHint by remember { mutableStateOf<Int?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var currentVideo by remember { mutableStateOf(initialVideo) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
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

    val downloadFinished = remember(path) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val growingFile = remember(path, initialVideo.uri) {
        com.localplay.app.core.download.DownloadPlayback.growingFileFor(path)
    }

    val exoPlayer = remember(initialVideo.uri, growingFile?.absolutePath) {
        val referer = com.localplay.app.core.download.DownloadPlayback.refererForPath(path)
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .setAllowCrossProtocolRedirects(true)
        if (!referer.isNullOrBlank()) {
            httpFactory.setDefaultRequestProperties(
                mapOf(
                    "Referer" to referer,
                    "Accept" to "*/*"
                )
            )
        }
        val httpDataSourceFactory =
            androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val dataSourceFactory = if (growingFile != null && growingFile.exists()) {
            com.localplay.app.core.download.GrowingFileDataSource.Factory(growingFile, downloadFinished)
        } else {
            httpDataSourceFactory
        }
        val mediaSourceFactory =
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
        val playUri = if (growingFile != null && growingFile.exists()) {
            android.net.Uri.fromFile(growingFile)
        } else {
            android.net.Uri.parse(initialVideo.uri)
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(playUri))
                playWhenReady = true
                prepare()
                val startPos = if (fromStart) 0L else initialVideo.progressMs
                if (startPos > 0L) seekTo(startPos)
            }
    }

    LaunchedEffect(path) {
        val taskId = com.localplay.app.core.download.DownloadPlayback.taskIdFromPath(path)
        if (taskId != null) {
            val downloadRepo = LocalPlayApp.instance.downloadRepository
            while (true) {
                val task = downloadRepo.getTask(taskId)
                val done = task == null ||
                    task.status == com.localplay.app.core.database.DownloadStatus.COMPLETED ||
                    task.status == com.localplay.app.core.database.DownloadStatus.FAILED
                downloadFinished.set(done)
                if (done) break
                delay(400L)
            }
        } else {
            downloadFinished.set(true)
        }
    }

    LaunchedEffect(Unit) {
        val defaultSpeed = settingsRepository.settings.first().defaultSpeed.coerceIn(0.25f, 5f)
        speed = defaultSpeed
        exoPlayer.setPlaybackSpeed(defaultSpeed)
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

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        var resumeWhenVisible = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumeWhenVisible = exoPlayer.playWhenReady
                    if (resumeWhenVisible) {
                        exoPlayer.pause()
                    }
                    persistProgress()
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (resumeWhenVisible) {
                        exoPlayer.play()
                        resumeWhenVisible = false
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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

    LaunchedEffect(controlsVisible, locked, clipMode) {
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
                statusMessage = "截图失败：" + (e.message ?: "未知错误")
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
                statusMessage = "导出失败：" + (e.message ?: "未知错误")
            } finally {
                clipExporting = false
            }
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

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!dragging) {
                                val now = System.currentTimeMillis()
                                val isDoubleTap = now - lastTapAt < 280L &&
                                    hypot(
                                        (change.position.x - lastTapX).toDouble(),
                                        (change.position.y - lastTapY).toDouble()
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
                                    lastTapX = change.position.x
                                    lastTapY = change.position.y
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
                    playerViewRef = this
                }
            },
            update = { view ->
                view.player = exoPlayer
                playerViewRef = view
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

        if (controlsVisible && !locked && !clipMode) {
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

        if (clipMode) {
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
            ModalBottomSheet(
                onDismissRequest = { showSpeedSheet = false },
                sheetState = rememberModalBottomSheetState(),
                containerColor = LpSurface
            ) {
                SpeedSheet(current = speed, onSelect = {
                    speed = it
                    exoPlayer.setPlaybackSpeed(it)
                    showSpeedSheet = false
                    scope.launch {
                        settingsRepository.setDefaultSpeed(it)
                    }
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
    onClip: () -> Unit,
    onScreenshot: () -> Unit,
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
            ControlItem(Icons.Default.ContentCut, "截取", onClip)
            ControlItem(Icons.Default.CameraAlt, "截图", onScreenshot)
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

