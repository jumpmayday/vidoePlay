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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicInteger
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
import com.localplay.app.core.database.DownloadStatus
import com.localplay.app.core.database.DownloadTaskEntity
import com.localplay.app.core.download.DownloadPlayback
import com.localplay.app.core.common.Formatters
import com.localplay.app.data.model.VideoItem
import com.localplay.app.ui.theme.LpDanger
import com.localplay.app.ui.theme.LpOnPrimary
import com.localplay.app.ui.theme.LpOverlay
import com.localplay.app.ui.theme.LpPrimary
import com.localplay.app.ui.theme.LpSuccess
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
fun PlayerScreen(
    path: String,
    fromStart: Boolean,
    onBack: () -> Unit,
    onOpenSniff: () -> Unit = {},
    onOpenPlayer: (String) -> Unit = {}
) {
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
        onOpenSniff = onOpenSniff,
        onOpenPlayer = onOpenPlayer,
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
    onOpenSniff: () -> Unit,
    onOpenPlayer: (String) -> Unit,
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
    var showPlaylist by remember { mutableStateOf(false) }
    var playlistQuery by remember { mutableStateOf("") }
    var showDownloads by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    val downloadRepo = LocalPlayApp.instance.downloadRepository
    val downloadTasks by downloadRepo.tasks.collectAsState(initial = emptyList())
    val activeDownloadCount = remember(downloadTasks) {
        downloadTasks.count {
            it.status == DownloadStatus.RUNNING ||
                it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.PAUSED
        }
    }

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
    val currentIndexRef = remember { AtomicInteger(currentIndex) }
    SideEffect { currentIndexRef.set(currentIndex) }

    val downloadFinished = remember(path) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val expectedTotalBytes = remember(path) { java.util.concurrent.atomic.AtomicLong(-1L) }
    val lastKnownPositionMs = remember(path) { java.util.concurrent.atomic.AtomicLong(0L) }
    val growingFile = remember(path, initialVideo.uri) {
        com.localplay.app.core.download.DownloadPlayback.growingFileFor(path)
    }
    // Once the download completes we re-point playback at the fully-written final file
    // (a proper seek map exists there), so the progress bar / seek gestures work reliably.
    var finalPlayUri by remember(path) { mutableStateOf<String?>(null) }

    val exoPlayer = remember(initialVideo.uri, growingFile?.absolutePath, finalPlayUri) {
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
        val useGrowing = finalPlayUri == null && growingFile != null && growingFile.exists()
        val dataSourceFactory = if (useGrowing) {
            com.localplay.app.core.download.GrowingFileDataSource.Factory(
                growingFile!!,
                downloadFinished,
                expectedTotalBytes
            )
        } else {
            httpDataSourceFactory
        }
        val mediaSourceFactory =
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
        val playUri = when {
            finalPlayUri != null -> android.net.Uri.parse(finalPlayUri)
            useGrowing -> android.net.Uri.fromFile(growingFile!!)
            else -> android.net.Uri.parse(initialVideo.uri)
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(playUri))
                playWhenReady = true
                prepare()
                val startPos = when {
                    finalPlayUri != null -> lastKnownPositionMs.get()
                    fromStart -> 0L
                    else -> initialVideo.progressMs
                }
                if (startPos > 0L) seekTo(startPos)
            }
    }

    LaunchedEffect(path) {
        val taskId = com.localplay.app.core.download.DownloadPlayback.taskIdFromPath(path)
        if (taskId != null) {
            val downloadRepo = LocalPlayApp.instance.downloadRepository
            val wasGrowing = growingFile != null
            while (true) {
                val task = downloadRepo.getTask(taskId)
                if (task != null && task.totalBytes > 0L) {
                    expectedTotalBytes.set(task.totalBytes)
                }
                val done = task == null ||
                    task.status == com.localplay.app.core.database.DownloadStatus.COMPLETED ||
                    task.status == com.localplay.app.core.database.DownloadStatus.FAILED
                downloadFinished.set(done)
                if (done) {
                    if (wasGrowing &&
                        task?.status == com.localplay.app.core.database.DownloadStatus.COMPLETED &&
                        task.outputUri.isNotBlank()
                    ) {
                        finalPlayUri = task.outputUri
                    }
                    break
                }
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
    DisposableEffect(lifecycleOwner, exoPlayer, path) {
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

    fun switchTo(index: Int) {
        if (index !in siblings.indices) return
        persistProgress()
        val next = siblings[index]
        currentIndex = index
        currentIndexRef.set(index)
        currentVideo = next
        exoPlayer.setMediaItem(MediaItem.fromUri(next.uri))
        exoPlayer.prepare()
        exoPlayer.play()
        durationMs = next.durationMs
        controlsVisible = true
    }

    val switchToLatest by rememberUpdatedState<(Int) -> Unit> { index -> switchTo(index) }

    DisposableEffect(exoPlayer) {
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
                if (playbackState == Player.STATE_ENDED) {
                    val next = currentIndexRef.get() + 1
                    if (next in siblings.indices) {
                        switchToLatest(next)
                    }
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
            if (positionMs > 0L) lastKnownPositionMs.set(positionMs)
            delay(500L)
            if (positionMs > 0L && positionMs % 5_000L < 600L) persistProgress()
        }
    }

    LaunchedEffect(controlsVisible, locked, clipMode, showPlaylist, showDownloads, showTools) {
        if (controlsVisible && !locked && !clipMode && !showPlaylist && !showDownloads && !showTools) {
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
                // Hide chrome so PixelCopy / overlay capture only sees the video frame.
                controlsVisible = false
                showTools = false
                showPlaylist = false
                showDownloads = false
                brightnessHint = null
                volumeHint = null
                seekHint = null
                statusMessage = null
                screenshotPreview = null
                delay(80L)
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

    fun launchCast() {
        controlsVisible = true
        val intents = listOf(
            Intent("android.settings.CAST_SETTINGS"),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
        )
        val opened = intents.any { intent ->
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
        statusMessage = if (opened) {
            "已打开投屏/无线显示，请选择设备"
        } else {
            "当前设备不支持系统投屏"
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
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
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
            TopBar(
                video = currentVideo,
                playlistCount = siblings.size,
                activeDownloadCount = activeDownloadCount,
                onBack = {
                    persistProgress()
                    onBack()
                },
                onPlaylist = {
                    showPlaylist = true
                    controlsVisible = true
                },
                onDownloads = {
                    showDownloads = true
                    controlsVisible = true
                },
                onCast = { launchCast() },
                onMore = { showTools = true }
            )
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
                onPlaylist = {
                    showPlaylist = true
                    controlsVisible = true
                },
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

        if (controlsVisible || locked) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = {
                        locked = !locked
                        controlsVisible = true
                    },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "锁屏",
                        tint = Color.White
                    )
                }
                Text(
                    if (locked) "解锁" else "锁屏",
                    color = Color.White.copy(alpha = 0.9f),
                    style = LocalPlayTypography.labelSmall
                )
            }
        }

        if (controlsVisible && !locked && !clipMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = { takeScreenshot() },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "截图",
                        tint = Color.White
                    )
                }
                Text(
                    "截图",
                    color = Color.White.copy(alpha = 0.9f),
                    style = LocalPlayTypography.labelSmall
                )
            }
        }

        if (showTools) {
            ModalBottomSheet(
                onDismissRequest = { showTools = false },
                sheetState = rememberModalBottomSheetState(),
                containerColor = LpSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("更多操作", style = LocalPlayTypography.titleMedium, color = LpText)
                    TextButton(
                        onClick = {
                            showTools = false
                            enterClipMode()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCut, null, tint = LpText)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("截取片段", color = LpText)
                    }
                    TextButton(
                        onClick = {
                            showTools = false
                            launchCast()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Cast, null, tint = LpText)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("投屏", color = LpText)
                    }
                }
            }
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

        if (showPlaylist) {
            PlaylistOverlay(
                videos = siblings,
                currentIndex = currentIndex,
                query = playlistQuery,
                onQueryChange = { playlistQuery = it },
                onSelect = { index ->
                    switchTo(index)
                    showPlaylist = false
                    playlistQuery = ""
                },
                onDismiss = {
                    showPlaylist = false
                    playlistQuery = ""
                }
            )
        }

        if (showDownloads) {
            DownloadStatusOverlay(
                tasks = downloadTasks,
                onPause = { id -> scope.launch { downloadRepo.pause(id) } },
                onResume = { id -> scope.launch { downloadRepo.resume(id) } },
                onRestart = { id -> scope.launch { downloadRepo.restart(id) } },
                onCancel = { id -> scope.launch { downloadRepo.cancel(id) } },
                onClearCompleted = { scope.launch { downloadRepo.clearCompleted() } },
                onPlay = { task ->
                    scope.launch {
                        var latest = downloadRepo.getTask(task.id) ?: task
                        when (latest.status) {
                            DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                                downloadRepo.resume(latest.id)
                            DownloadStatus.QUEUED -> downloadRepo.startService()
                            else -> Unit
                        }
                        latest = downloadRepo.getTask(task.id) ?: latest
                        val item = DownloadPlayback.toVideoItem(context, latest)
                        repository.registerPlayable(item)
                        showDownloads = false
                        if (item.path != path && item.path != currentVideo.path) {
                            persistProgress()
                            onOpenPlayer(item.path)
                        } else {
                            statusMessage = "当前已在播放该下载"
                        }
                    }
                },
                onOpenSniff = {
                    showDownloads = false
                    persistProgress()
                    onOpenSniff()
                },
                onDismiss = { showDownloads = false }
            )
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
private fun TopBar(
    video: VideoItem,
    playlistCount: Int,
    activeDownloadCount: Int,
    onBack: () -> Unit,
    onPlaylist: () -> Unit,
    onDownloads: () -> Unit,
    onCast: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
            Text(
                video.displayName,
                style = LocalPlayTypography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White
            )
            Text(video.folderName, style = LocalPlayTypography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
        IconButton(onClick = onCast) {
            Icon(Icons.Default.Cast, contentDescription = "投屏", tint = Color.White)
        }
        IconButton(onClick = onDownloads) {
            Box {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "嗅探下载",
                    tint = if (activeDownloadCount > 0) LpPrimary else Color.White
                )
                if (activeDownloadCount > 0) {
                    Text(
                        text = if (activeDownloadCount > 9) "9+" else activeDownloadCount.toString(),
                        color = LpOnPrimary,
                        style = LocalPlayTypography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(LpPrimary, CircleShape)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
        IconButton(onClick = onPlaylist) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = "播放列表",
                tint = Color.White
            )
        }
        if (playlistCount > 1) {
            Text(
                text = "${playlistCount}集",
                color = Color.White.copy(alpha = 0.75f),
                style = LocalPlayTypography.labelSmall
            )
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color.White)
        }
    }
}

@Composable
private fun DownloadStatusOverlay(
    tasks: List<DownloadTaskEntity>,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onRestart: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onClearCompleted: () -> Unit,
    onPlay: (DownloadTaskEntity) -> Unit,
    onOpenSniff: () -> Unit,
    onDismiss: () -> Unit
) {
    val active = remember(tasks) {
        tasks.filter {
            it.status != DownloadStatus.COMPLETED
        }
    }
    val completed = remember(tasks) {
        tasks.filter { it.status == DownloadStatus.COMPLETED }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .widthIn(max = 560.dp)
                .fillMaxSize(0.82f)
                .clip(RoundedCornerShape(14.dp))
                .background(LpSurface.copy(alpha = 0.96f))
                .clickable(enabled = false) {}
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = LpPrimary)
                Text(
                    "嗅探下载",
                    style = LocalPlayTypography.titleMedium,
                    color = LpText,
                    modifier = Modifier.padding(start = 8.dp).weight(1f)
                )
                TextButton(onClick = onOpenSniff) {
                    Text("去嗅探页", color = LpPrimary)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = LpText2)
                }
            }
            HorizontalDivider(color = LpSurface3)
            if (tasks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无下载任务", color = LpText2)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onOpenSniff) {
                            Text("去添加嗅探下载", color = LpPrimary)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (active.isNotEmpty()) {
                        item {
                            Text(
                                "进行中 · ${active.size}",
                                color = LpText2,
                                style = LocalPlayTypography.labelMedium,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                        items(active, key = { it.id }) { task ->
                            PlayerDownloadRow(
                                task = task,
                                onPause = { onPause(task.id) },
                                onResume = { onResume(task.id) },
                                onRestart = { onRestart(task.id) },
                                onCancel = { onCancel(task.id) },
                                onPlay = { onPlay(task) }
                            )
                        }
                    }
                    if (completed.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "已完成 · ${completed.size}",
                                    color = LpText2,
                                    style = LocalPlayTypography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onClearCompleted) {
                                    Text("清空已完成", color = LpText2)
                                }
                            }
                        }
                        items(completed, key = { "done-${it.id}" }) { task ->
                            PlayerDownloadRow(
                                task = task,
                                onPause = { onPause(task.id) },
                                onResume = { onResume(task.id) },
                                onRestart = { onRestart(task.id) },
                                onCancel = { onCancel(task.id) },
                                onPlay = { onPlay(task) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerDownloadRow(
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
            .clip(RoundedCornerShape(10.dp))
            .background(LpSurface2)
            .padding(10.dp)
    ) {
        Text(
            task.title.ifBlank { task.fileName },
            style = LocalPlayTypography.bodyMedium,
            color = LpText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            playerDownloadStatusLabel(task),
            color = playerDownloadStatusColor(task.status),
            style = LocalPlayTypography.labelSmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (task.status == DownloadStatus.RUNNING ||
            task.status == DownloadStatus.QUEUED ||
            task.status == DownloadStatus.PAUSED
        ) {
            LinearProgressIndicator(
                progress = { task.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = LpPrimary,
                trackColor = LpSurface3
            )
        }
        if (task.errorMessage.isNotBlank()) {
            Text(
                task.errorMessage,
                color = LpDanger,
                style = LocalPlayTypography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            when (task.status) {
                DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                    TextButton(onClick = onPause) { Text("暂停") }
                    TextButton(onClick = onPlay) { Text("边下边播", color = LpPrimary) }
                    TextButton(onClick = onRestart) { Text("重下") }
                }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                    TextButton(onClick = onResume) { Text("继续") }
                    TextButton(onClick = onPlay) { Text("边下边播", color = LpPrimary) }
                    TextButton(onClick = onRestart) { Text("重下") }
                }
                DownloadStatus.COMPLETED -> {
                    TextButton(onClick = onPlay) { Text("播放", color = LpPrimary) }
                    TextButton(onClick = onRestart) { Text("重下") }
                }
            }
            if (task.status != DownloadStatus.COMPLETED) {
                TextButton(onClick = onCancel) { Text("取消", color = LpDanger) }
            }
        }
    }
}

private fun playerDownloadStatusLabel(task: DownloadTaskEntity): String {
    val pct = (task.progressFraction * 100).toInt()
    return when (task.status) {
        DownloadStatus.QUEUED -> "排队中"
        DownloadStatus.RUNNING -> if (task.isHls) {
            "下载中 $pct%（分片 ${task.hlsSegmentIndex}/${task.hlsSegmentTotal}）"
        } else {
            "下载中 $pct%"
        }
        DownloadStatus.PAUSED -> "已暂停 $pct%"
        DownloadStatus.FAILED -> "失败"
        DownloadStatus.COMPLETED -> "已完成"
    }
}

@Composable
private fun playerDownloadStatusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.COMPLETED -> LpSuccess
    DownloadStatus.FAILED -> LpDanger
    DownloadStatus.PAUSED -> LpText2
    else -> LpPrimary
}



@Composable
private fun PlaylistOverlay(
    videos: List<VideoItem>,
    currentIndex: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val filtered = remember(videos, query) {
        if (query.isBlank()) {
            videos.mapIndexed { index, item -> index to item }
        } else {
            videos.mapIndexedNotNull { index, item ->
                if (item.displayName.contains(query, ignoreCase = true)) index to item else null
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, query) {
        if (query.isBlank() && currentIndex in videos.indices) {
            listState.animateScrollToItem(currentIndex.coerceAtMost((filtered.size - 1).coerceAtLeast(0)))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .widthIn(max = 520.dp)
                .fillMaxSize(0.78f)
                .clip(RoundedCornerShape(14.dp))
                .background(LpSurface.copy(alpha = 0.94f))
                .clickable(enabled = false) {}
                .padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = LpText2, modifier = Modifier.size(20.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = LpText, fontSize = 15.sp),
                    cursorBrush = SolidColor(LpPrimary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("搜索媒体", color = LpText2, style = LocalPlayTypography.bodyMedium)
                        }
                        inner()
                    }
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = LpText2)
                }
            }
            HorizontalDivider(color = LpSurface3)
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配的视频", color = LpText2)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(filtered, key = { _, pair -> pair.second.path }) { _, pair ->
                        val (index, item) = pair
                        val playing = index == currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = null,
                                tint = LpText2,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.displayName,
                                    color = if (playing) LpPrimary else LpText,
                                    style = LocalPlayTypography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = playlistMeta(item),
                                    color = LpText2,
                                    style = LocalPlayTypography.labelSmall,
                                    maxLines = 1
                                )
                            }
                            if (playing) {
                                Icon(
                                    Icons.Default.Equalizer,
                                    contentDescription = "正在播放",
                                    tint = LpPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun playlistMeta(video: VideoItem): String {
    val duration = compactDuration(video.durationMs)
    val res = when {
        video.height >= 2160 -> "4K"
        video.height >= 1080 -> "1080P"
        video.height >= 720 -> "720P"
        video.height > 0 -> "${video.height}P"
        else -> null
    }
    return listOfNotNull(duration, res).joinToString(" · ")
}

private fun compactDuration(ms: Long): String {
    if (ms <= 0L) return "--"
    val totalMin = (ms / 60_000L).toInt()
    val hours = totalMin / 60
    val minutes = totalMin % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h${minutes}min"
        hours > 0 -> "${hours}h"
        else -> "${minutes.coerceAtLeast(1)}min"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onPlaylist: () -> Unit,
    onRotate: () -> Unit
) {
    val seekInteraction = remember { MutableInteractionSource() }
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.28f)
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Slider(
            value = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
            onValueChange = { ratio -> onSeek((ratio * durationMs).toLong()) },
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
            colors = sliderColors,
            interactionSource = seekInteraction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = seekInteraction,
                    colors = sliderColors,
                    enabled = true,
                    modifier = Modifier.size(10.dp)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    colors = sliderColors,
                    enabled = true,
                    modifier = Modifier.height(2.dp),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp
                )
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = onPrev, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "上一集", tint = Color.White)
            }
            IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "下一集", tint = Color.White)
            }
            Text(
                text = Formatters.duration(positionMs) + " / " + Formatters.duration(durationMs),
                color = Color.White.copy(alpha = 0.9f),
                style = LocalPlayTypography.labelMedium,
                modifier = Modifier.padding(start = 2.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onPlaylist) {
                Text("本地视频", color = Color.White)
            }
            TextButton(onClick = onSpeed) {
                Text(
                    if (abs(speed - 1f) < 0.01f) "倍速" else formatSpeed(speed),
                    color = Color.White
                )
            }
            IconButton(onClick = onRotate) {
                Icon(Icons.Default.ScreenRotation, contentDescription = "旋转", tint = Color.White)
            }
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

