package com.eok.mp3recorder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import kotlinx.coroutines.flow.first

/** 재생 설정(반복/셔플/배속)과 곡별 이어듣기 위치를 저장한다. */
object PlayerPrefs {

    private val Context.store by preferencesDataStore("player_prefs")

    private val KEY_REPEAT = intPreferencesKey("repeat_mode")
    private val KEY_SHUFFLE = booleanPreferencesKey("shuffle")
    private val KEY_SPEED = floatPreferencesKey("speed")

    data class Settings(
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        val shuffle: Boolean = false,
        val speed: Float = 1.0f,
    )

    suspend fun read(context: Context): Settings {
        val p = context.store.data.first()
        return Settings(
            repeatMode = p[KEY_REPEAT] ?: Player.REPEAT_MODE_OFF,
            shuffle = p[KEY_SHUFFLE] ?: false,
            speed = (p[KEY_SPEED] ?: 1.0f).coerceIn(0.5f, 2.0f),
        )
    }

    suspend fun saveRepeatMode(context: Context, mode: Int) {
        context.store.edit { it[KEY_REPEAT] = mode }
    }

    suspend fun saveShuffle(context: Context, enabled: Boolean) {
        context.store.edit { it[KEY_SHUFFLE] = enabled }
    }

    suspend fun saveSpeed(context: Context, speed: Float) {
        context.store.edit { it[KEY_SPEED] = speed }
    }

    // ---- 이어듣기 (긴 파일의 마지막 재생 위치) ----

    private fun resumeKey(mediaId: String) = longPreferencesKey("pos_$mediaId")

    suspend fun resumePosition(context: Context, mediaId: String): Long? =
        context.store.data.first()[resumeKey(mediaId)]

    suspend fun saveResumePosition(context: Context, mediaId: String, positionMs: Long) {
        context.store.edit { it[resumeKey(mediaId)] = positionMs }
    }

    suspend fun removeResumePosition(context: Context, mediaId: String) {
        context.store.edit { it.remove(resumeKey(mediaId)) }
    }
}
