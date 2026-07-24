package com.localplay.app.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localplay.app.LocalPlayApp
import com.localplay.app.MainActivity
import com.localplay.app.R
import com.localplay.app.core.database.AppDatabase
import com.localplay.app.core.database.DownloadStatus
import com.localplay.app.core.database.DownloadTaskEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parallel download queue with a low-frequency watchdog.
 *
 * Battery notes:
 * - Relies on the existing foreground service (no extra WakeLock).
 * - Watchdog polls every [WATCHDOG_ACTIVE_MS] only while work exists;
 *   after the queue drains it idles once then stops the service.
 * - Stall detection avoids hot-looping dead connections.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val claimMutex = Mutex()
    private var worker: Job? = null
    private var watchdog: Job? = null
    private val engine by lazy { ResumableDownloadEngine(applicationContext) }
    private val pausedIds = ConcurrentHashMap.newKeySet<Long>()
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    /** Progress fingerprint (bytes/hls) per task for stall detection. */
    private val progressMark = ConcurrentHashMap<Long, Long>()
    private val progressMarkAt = ConcurrentHashMap<Long, Long>()
    private val dao by lazy { AppDatabase.get(applicationContext).downloadTaskDao() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                val at = intent.getLongExtra(EXTRA_ACTION_AT, 0L)
                if (id > 0L) scope.launch { pauseTask(id, at) }
            }
            ACTION_RESUME -> {
                val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                val at = intent.getLongExtra(EXTRA_ACTION_AT, 0L)
                if (id > 0L) scope.launch { resumeTask(id, at) }
            }
            ACTION_PROCESS_QUEUE, null -> {
                startQueueWorker()
                ensureWatchdog()
            }
        }
        return START_STICKY
    }

    private fun startQueueWorker() {
        if (worker?.isActive == true) {
            updateNotification("下载队列运行中（${activeJobs.size} 个并行）", 0, true)
            return
        }
        startAsForeground("准备下载…", 0)
        worker = scope.launch {
            try {
                while (isActive) {
                    reclaimOrphanedRunning()
                    activeJobs.entries.removeIf { (_, job) -> !job.isActive }

                    val parallelism = LocalPlayApp.instance.settingsRepository.settings
                        .first()
                        .downloadParallelism
                        .coerceIn(1, 10)
                    val slots = parallelism - activeJobs.size
                    if (slots > 0) {
                        val batch = claimMutex.withLock {
                            dao.nextQueued(slots).mapNotNull { task ->
                                if (activeJobs.containsKey(task.id)) return@mapNotNull null
                                if (pausedIds.contains(task.id)) return@mapNotNull null
                                val claimed = task.copy(
                                    status = DownloadStatus.RUNNING,
                                    updatedAt = System.currentTimeMillis()
                                )
                                dao.update(claimed)
                                claimed
                            }
                        }
                        batch.forEach { task ->
                            markProgress(task)
                            val job = scope.launch {
                                try {
                                    processTask(task)
                                } finally {
                                    activeJobs.remove(task.id)
                                    clearProgressMark(task.id)
                                }
                            }
                            activeJobs[task.id] = job
                        }
                    }

                    val queuedLeft = dao.nextQueued(1)
                    val orphanRunning = dao.getRunning().any { !activeJobs.containsKey(it.id) }
                    if (activeJobs.isEmpty() && queuedLeft.isEmpty() && !orphanRunning) {
                        break
                    }

                    val running = activeJobs.size
                    updateNotification(
                        if (running > 0) "并行下载中：$running / $parallelism"
                        else "等待下载任务…",
                        0,
                        true
                    )
                    delay(WORKER_TICK_MS)
                }
            } finally {
                worker = null
            }
        }
        ensureWatchdog()
    }

    /**
     * Low-frequency guardian: revive worker, reclaim orphans, retry stalled tasks.
     * Stops itself when the queue stays empty (saves battery).
     */
    private fun ensureWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            var emptyRounds = 0
            try {
                while (isActive) {
                    val hasWork = runWatchdogTick()
                    if (hasWork) {
                        emptyRounds = 0
                        delay(WATCHDOG_ACTIVE_MS)
                    } else {
                        emptyRounds++
                        if (emptyRounds >= WATCHDOG_EMPTY_ROUNDS_BEFORE_STOP) {
                            Log.i(TAG, "watchdog: queue idle, stopping service")
                            break
                        }
                        delay(WATCHDOG_IDLE_MS)
                    }
                }
            } finally {
                watchdog = null
                if (worker?.isActive != true && activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runWatchdogTick(): Boolean {
        reclaimOrphanedRunning()
        activeJobs.entries.removeIf { (_, job) -> !job.isActive }

        val runningTasks = dao.getRunning()
        val hasQueued = dao.nextQueued(1).isNotEmpty()
        val hasRunning = runningTasks.isNotEmpty()
        val hasLiveJobs = activeJobs.isNotEmpty()
        val hasWork = hasQueued || hasRunning || hasLiveJobs

        if (!hasWork) {
            return false
        }

        if (worker?.isActive != true) {
            Log.w(TAG, "watchdog: queue worker dead, restarting")
            startQueueWorker()
        }

        val now = System.currentTimeMillis()
        runningTasks.forEach { task ->
            if (pausedIds.contains(task.id)) return@forEach
            val mark = progressFingerprint(task)
            val prev = progressMark[task.id]
            val prevAt = progressMarkAt[task.id] ?: task.updatedAt
            if (prev == null || prev != mark) {
                progressMark[task.id] = mark
                progressMarkAt[task.id] = now
                return@forEach
            }
            if (now - prevAt < STALL_TIMEOUT_MS) return@forEach

            Log.w(TAG, "watchdog: stalled task id=${task.id}, re-queue")
            activeJobs[task.id]?.cancel(CancellationException("stalled by watchdog"))
            activeJobs.remove(task.id)
            pausedIds.remove(task.id)
            clearProgressMark(task.id)
            dao.update(
                task.copy(
                    status = DownloadStatus.QUEUED,
                    errorMessage = "进度停滞，守护线程已自动重试",
                    updatedAt = now
                )
            )
            startQueueWorker()
        }

        updateNotification(
            "守护监控中 · 活跃 ${activeJobs.size}",
            0,
            true
        )
        return true
    }

    private fun progressFingerprint(task: DownloadTaskEntity): Long {
        return task.downloadedBytes xor (task.hlsSegmentIndex.toLong() shl 32)
    }

    private fun markProgress(task: DownloadTaskEntity) {
        progressMark[task.id] = progressFingerprint(task)
        progressMarkAt[task.id] = System.currentTimeMillis()
    }

    private fun clearProgressMark(id: Long) {
        progressMark.remove(id)
        progressMarkAt.remove(id)
    }

    private suspend fun reclaimOrphanedRunning() {
        claimMutex.withLock {
            dao.getRunning().forEach { task ->
                if (activeJobs[task.id]?.isActive == true) return@forEach
                if (pausedIds.contains(task.id)) {
                    dao.update(
                        task.copy(
                            status = DownloadStatus.PAUSED,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    return@forEach
                }
                Log.w(TAG, "re-queue orphaned RUNNING task id=${task.id}")
                clearProgressMark(task.id)
                dao.update(
                    task.copy(
                        status = DownloadStatus.QUEUED,
                        updatedAt = System.currentTimeMillis(),
                        errorMessage = ""
                    )
                )
            }
        }
    }

    private suspend fun processTask(task: DownloadTaskEntity) {
        if (pausedIds.contains(task.id)) {
            dao.update(
                task.copy(
                    status = DownloadStatus.PAUSED,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return
        }

        var latest = task.copy(
            status = DownloadStatus.RUNNING,
            updatedAt = System.currentTimeMillis()
        )
        dao.update(latest)
        markProgress(latest)
        updateNotification("正在下载：${latest.title}", (latest.progressFraction * 100).toInt(), true)

        try {
            latest = engine.download(
                task = latest,
                shouldAbort = { pausedIds.contains(task.id) }
            ) { progress ->
                markProgress(progress)
                scope.launch {
                    val current = dao.getById(progress.id)
                    if (current?.status == DownloadStatus.PAUSED || pausedIds.contains(task.id)) {
                        return@launch
                    }
                    dao.update(progress.copy(status = DownloadStatus.RUNNING))
                    updateNotification(
                        "正在下载：${progress.title}",
                        (progress.progressFraction * 100).toInt(),
                        true
                    )
                }
            }
            if (pausedIds.contains(task.id)) {
                val current = dao.getById(task.id) ?: return
                dao.update(current.copy(status = DownloadStatus.PAUSED))
                return
            }
            dao.update(latest)
            updateNotification("下载完成：${latest.title}", 100, false)
            scope.launch {
                runCatching {
                    LocalPlayApp.instance.videoRepository.refreshAfterDownload()
                }
            }
        } catch (e: CancellationException) {
            val current = dao.getById(task.id)
            if (current != null &&
                current.status != DownloadStatus.COMPLETED &&
                current.status != DownloadStatus.PAUSED
            ) {
                dao.update(
                    current.copy(
                        status = DownloadStatus.QUEUED,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            throw e
        } catch (e: DownloadAbortedException) {
            val current = dao.getById(task.id) ?: return
            dao.update(
                current.copy(
                    status = DownloadStatus.PAUSED,
                    updatedAt = System.currentTimeMillis()
                )
            )
            updateNotification("已暂停：${current.title}", (current.progressFraction * 100).toInt(), false)
        } catch (e: Exception) {
            Log.e(TAG, "task failed id=${task.id}", e)
            if (pausedIds.contains(task.id)) {
                val current = dao.getById(task.id) ?: return
                dao.update(current.copy(status = DownloadStatus.PAUSED))
                return
            }
            val current = dao.getById(task.id)
            dao.update(
                (current ?: task).copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message.orEmpty().ifBlank { "下载中断，可点继续重试" },
                    updatedAt = System.currentTimeMillis()
                )
            )
            updateNotification("下载失败：${task.title}", 0, false)
        } finally {
            val current = dao.getById(task.id)
            if (current?.status != DownloadStatus.PAUSED) {
                pausedIds.remove(task.id)
            }
        }
    }

    private suspend fun pauseTask(id: Long, actionAt: Long) {
        val task = dao.getById(id) ?: return
        if (actionAt > 0L && task.updatedAt > actionAt) {
            Log.i(TAG, "ignore stale pause id=$id")
            return
        }
        if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.COMPLETED) {
            Log.i(TAG, "ignore pause for id=$id status=${task.status}")
            return
        }
        pausedIds.add(id)
        if (task.status != DownloadStatus.PAUSED) {
            dao.update(
                task.copy(
                    status = DownloadStatus.PAUSED,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        clearProgressMark(id)
        updateNotification("已暂停：${task.title}", (task.progressFraction * 100).toInt(), false)
    }

    private suspend fun resumeTask(id: Long, actionAt: Long) {
        val existing = dao.getById(id)
        if (existing != null && actionAt > 0L && existing.updatedAt > actionAt) {
            Log.i(TAG, "ignore stale resume id=$id")
            startQueueWorker()
            ensureWatchdog()
            return
        }

        pausedIds.remove(id)
        activeJobs[id]?.cancel(CancellationException("resume requested"))
        withTimeoutOrNull(3_000L) {
            activeJobs[id]?.join()
        }
        activeJobs.remove(id)
        clearProgressMark(id)

        val task = dao.getById(id)
        if (task == null) {
            startQueueWorker()
            ensureWatchdog()
            return
        }
        if (task.status == DownloadStatus.COMPLETED) return
        if (task.status != DownloadStatus.QUEUED) {
            dao.update(
                task.copy(
                    status = DownloadStatus.QUEUED,
                    errorMessage = "",
                    updatedAt = maxOf(task.updatedAt, actionAt)
                )
            )
        }
        startQueueWorker()
        ensureWatchdog()
    }

    private fun startAsForeground(text: String, progress: Int) {
        val notification = buildNotification(text, progress, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, progress: Int, ongoing: Boolean) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress, ongoing))
    }

    private fun buildNotification(text: String, progress: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalPlay 下载")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress in 0..100) {
            builder.setProgress(100, progress.coerceIn(0, 100), progress <= 0 && ongoing)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "视频下载",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.launch {
            claimMutex.withLock {
                dao.getRunning().forEach { task ->
                    if (!pausedIds.contains(task.id)) {
                        dao.update(task.copy(status = DownloadStatus.QUEUED))
                    }
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PROCESS_QUEUE = "com.localplay.app.action.PROCESS_DOWNLOAD_QUEUE"
        const val ACTION_PAUSE = "com.localplay.app.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.localplay.app.action.RESUME_DOWNLOAD"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_ACTION_AT = "action_at"

        /** Queue claim loop while actively downloading. */
        private const val WORKER_TICK_MS = 500L
        /** Watchdog interval while queue has work — keep moderate for battery. */
        private const val WATCHDOG_ACTIVE_MS = 30_000L
        /** Extra idle confirm before stopping service. */
        private const val WATCHDOG_IDLE_MS = 45_000L
        private const val WATCHDOG_EMPTY_ROUNDS_BEFORE_STOP = 2
        /** No byte/segment progress for this long → re-queue. */
        private const val STALL_TIMEOUT_MS = 90_000L

        private const val CHANNEL_ID = "localplay_downloads"
        private const val NOTIFICATION_ID = 10021
        private const val TAG = "DownloadService"
    }
}
