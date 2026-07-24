package com.localplay.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey val path: String,
    val positionMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val speed: Float = 1f,
    val lastPlayedAt: Long = System.currentTimeMillis()
)
