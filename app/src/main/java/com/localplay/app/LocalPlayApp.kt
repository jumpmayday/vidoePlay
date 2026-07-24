package com.localplay.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.localplay.app.data.repository.DownloadRepository
import com.localplay.app.data.repository.SettingsRepository
import com.localplay.app.data.repository.VideoRepository

class LocalPlayApp : Application(), ImageLoaderFactory {
    lateinit var videoRepository: VideoRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var downloadRepository: DownloadRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        videoRepository = VideoRepository(this, settingsRepository)
        downloadRepository = DownloadRepository(this)
        // Resume unfinished downloads after process start.
        downloadRepository.startService()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: LocalPlayApp
            private set
    }
}
