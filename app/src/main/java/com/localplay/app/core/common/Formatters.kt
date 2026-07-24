package com.localplay.app.core.common

import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {
    fun duration(ms: Long): String {
        if (ms <= 0L) return "00:00:00"
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.0f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    fun resolution(width: Int, height: Int): String {
        return if (width > 0 && height > 0) {
            width.toString() + "x" + height
        } else {
            "-"
        }
    }
}
