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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var worker: Job? = null
    private val engine by lazy { ResumableDownloadEngine(applicationContext) }
    private val pausedIds = ConcurrentHashMap.newKeySet<Long>()
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
                if (id > 0L) scope.launch { pauseTask(id) }
            }
            ACTION_PROCESS_QUEUE, null -> startQueueWorker()
        }
        return START_STICKY
    }

    private fun startQueueWorker() {
        if (worker?.isActive == true) {
            updateNotification("下载队列运行中", 0, false)
            return
        }
        startAsForeground("准备下载…", 0)
        worker = scope.launch {
            mutex.withLock {
                while (isActive) {
                    val task = dao.nextActive() ?: break
                    if (task.status == DownloadStatus.PAUSED) break
                    processTask(task)
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun processTask(task: DownloadTaskEntity) {
        var latest = task.copy(
            status = DownloadStatus.RUNNING,
            updatedAt = System.currentTimeMillis()
        )
        dao.update(latest)
        updateNotification("正在下载：${latest.title}", (latest.progressFraction * 100).toInt(), true)

        try {
            latest = engine.download(
                task = latest,
                shouldAbort = { pausedIds.contains(task.id) }
            ) { progress ->
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
            runCatching {
                LocalPlayApp.instance.videoRepository.refresh(force = true)
            }
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
            if (pausedIds.contains(task.id)) return
            val current = dao.getById(task.id)
            dao.update(
                (current ?: task).copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message.orEmpty(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            updateNotification("下载失败：${task.title}", 0, false)
        } finally {
            pausedIds.remove(task.id)
        }
    }

    private suspend fun pauseTask(id: Long) {
        val task = dao.getById(id) ?: return
        pausedIds.add(id)
        dao.update(
            task.copy(
                status = DownloadStatus.PAUSED,
                updatedAt = System.currentTimeMillis()
            )
        )
        updateNotification("已暂停：${task.title}", (task.progressFraction * 100).toInt(), false)
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PROCESS_QUEUE = "com.localplay.app.action.PROCESS_DOWNLOAD_QUEUE"
        const val ACTION_PAUSE = "com.localplay.app.action.PAUSE_DOWNLOAD"
        const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL_ID = "localplay_downloads"
        private const val NOTIFICATION_ID = 10021
        private const val TAG = "DownloadService"
    }
}
