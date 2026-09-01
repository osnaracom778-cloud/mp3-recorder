package com.eok.mp3recorder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

    // ---- 이퀄라이저 ----

    private val KEY_EQ_ENABLED = booleanPreferencesKey("eq_enabled")
    private val KEY_EQ_PRESET = intPreferencesKey("eq_preset")
    private val KEY_EQ_BANDS = stringPreferencesKey("eq_bands")
    private val KEY_EQ_BASS = intPreferencesKey("eq_bass")

    data class EqSettings(
        val enabled: Boolean = false,
        val presetIndex: Int = -1,               // -1 = 사용자 설정(밴드 직접 조절)
        val bandLevels: List<Short>? = null,     // presetIndex == -1일 때 사용
        val bassStrength: Int = 0,
    )

    suspend fun readEq(context: Context): EqSettings {
        val p = context.store.data.first()
        val bands = p[KEY_EQ_BANDS]
            ?.split(',')
            ?.mapNotNull { it.toShortOrNull() }
            ?.takeIf { it.isNotEmpty() }
        return EqSettings(
            enabled = p[KEY_EQ_ENABLED] ?: false,
            presetIndex = p[KEY_EQ_PRESET] ?: -1,
            bandLevels = bands,
            bassStrength = (p[KEY_EQ_BASS] ?: 0).coerceIn(0, 1000),
        )
    }

    suspend fun saveEq(context: Context, settings: EqSettings) {
        context.store.edit {
            it[KEY_EQ_ENABLED] = settings.enabled
            it[KEY_EQ_PRESET] = settings.presetIndex
            it[KEY_EQ_BANDS] = settings.bandLevels?.joinToString(",") ?: ""
            it[KEY_EQ_BASS] = settings.bassStrength
        }
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
