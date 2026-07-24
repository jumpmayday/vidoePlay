package com.localplay.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("localplay_settings")

enum class ResumeMode {
    ASK, ALWAYS_RESUME, ALWAYS_START
}

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            defaultSpeed = (prefs[KEY_SPEED] ?: 1f).coerceIn(0.25f, 5f),
            seekStepSec = prefs[KEY_SEEK] ?: 10,
            minSizeMb = prefs[KEY_MIN_SIZE] ?: 10,
            minDurationSec = prefs[KEY_MIN_DURATION] ?: 5,
            resumeMode = ResumeMode.valueOf(prefs[KEY_RESUME] ?: ResumeMode.ASK.name),
            hardwareDecode = (prefs[KEY_HW] ?: 1) == 1,
            autoRotate = (prefs[KEY_ROTATE] ?: 1) == 1,
            downloadTreeUri = prefs[KEY_DOWNLOAD_TREE].orEmpty(),
            downloadPathLabel = prefs[KEY_DOWNLOAD_LABEL].orEmpty(),
            downloadParallelism = (prefs[KEY_DOWNLOAD_PARALLEL] ?: 5).coerceIn(1, 10),
            lastScanDay = prefs[KEY_LAST_SCAN_DAY].orEmpty()
        )
    }

    suspend fun setDefaultSpeed(value: Float) {
        dataStore.edit { it[KEY_SPEED] = value.coerceIn(0.25f, 5f) }
    }

    suspend fun setSeekStepSec(value: Int) {
        dataStore.edit { it[KEY_SEEK] = value }
    }

    suspend fun setMinSizeMb(value: Int) {
        dataStore.edit { it[KEY_MIN_SIZE] = value }
    }

    suspend fun setMinDurationSec(value: Int) {
        dataStore.edit { it[KEY_MIN_DURATION] = value }
    }

    suspend fun setResumeMode(mode: ResumeMode) {
        dataStore.edit { it[KEY_RESUME] = mode.name }
    }

    suspend fun setHardwareDecode(enabled: Boolean) {
        dataStore.edit { it[KEY_HW] = if (enabled) 1 else 0 }
    }

    suspend fun setAutoRotate(enabled: Boolean) {
        dataStore.edit { it[KEY_ROTATE] = if (enabled) 1 else 0 }
    }

    suspend fun setDownloadPath(treeUri: String, label: String) {
        dataStore.edit {
            it[KEY_DOWNLOAD_TREE] = treeUri
            it[KEY_DOWNLOAD_LABEL] = label
        }
    }

    suspend fun clearDownloadPath() {
        dataStore.edit {
            it.remove(KEY_DOWNLOAD_TREE)
            it.remove(KEY_DOWNLOAD_LABEL)
        }
    }

    suspend fun setDownloadParallelism(value: Int) {
        dataStore.edit { it[KEY_DOWNLOAD_PARALLEL] = value.coerceIn(1, 10) }
    }

    suspend fun setLastScanDay(day: String) {
        dataStore.edit { it[KEY_LAST_SCAN_DAY] = day }
    }

    companion object {
        private val KEY_SPEED = floatPreferencesKey("default_speed")
        private val KEY_SEEK = intPreferencesKey("seek_step_sec")
        private val KEY_MIN_SIZE = intPreferencesKey("min_size_mb")
        private val KEY_MIN_DURATION = intPreferencesKey("min_duration_sec")
        private val KEY_RESUME = stringPreferencesKey("resume_mode")
        private val KEY_HW = intPreferencesKey("hardware_decode")
        private val KEY_ROTATE = intPreferencesKey("auto_rotate")
        private val KEY_DOWNLOAD_TREE = stringPreferencesKey("download_tree_uri")
        private val KEY_DOWNLOAD_LABEL = stringPreferencesKey("download_path_label")
        private val KEY_DOWNLOAD_PARALLEL = intPreferencesKey("download_parallelism")
        private val KEY_LAST_SCAN_DAY = stringPreferencesKey("last_scan_day")

        val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f, 5f)
    }
}

data class AppSettings(
    val defaultSpeed: Float = 1f,
    val seekStepSec: Int = 10,
    val minSizeMb: Int = 10,
    val minDurationSec: Int = 5,
    val resumeMode: ResumeMode = ResumeMode.ASK,
    val hardwareDecode: Boolean = true,
    val autoRotate: Boolean = true,
    val downloadTreeUri: String = "",
    val downloadPathLabel: String = "",
    val downloadParallelism: Int = 5,
    val lastScanDay: String = ""
)
