package com.eok.mp3recorder.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
import com.eok.mp3recorder.data.PlayerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ExoPlayer의 오디오 세션에 붙는 이퀄라이저 + 베이스부스트.
 * PlaybackService가 attach/release를 호출하고, UI는 state를 구독해 조절한다.
 * 설정은 DataStore에 저장되어 앱 재시작 후에도 유지된다.
 */
object EqualizerManager {

    data class EqBand(
        val centerFreqHz: Int,
        val levelMb: Short,     // 밀리벨 (1/100 dB)
        val minMb: Short,
        val maxMb: Short,
    )

    data class EqState(
        val ready: Boolean = false,
        val enabled: Boolean = false,
        val presetIndex: Int = -1,               // -1 = 사용자 설정
        val presetNames: List<String> = emptyList(),
        val bands: List<EqBand> = emptyList(),
        val bassStrength: Int = 0,               // 0~1000
        val hasBassBoost: Boolean = false,
    )

    private const val TAG = "EqualizerManager"

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var appContext: Context? = null
    private var lastSessionId: Int = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    fun attach(context: Context, audioSessionId: Int) {
        if (audioSessionId == 0) return
        appContext = context.applicationContext
        lastSessionId = audioSessionId
        releaseEffects()
        try {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            bassBoost = try {
                BassBoost(0, audioSessionId).takeIf { it.strengthSupported }
            } catch (e: Exception) {
                null
            }
            // 저장된 설정 복원
            scope.launch {
                val ctx = appContext ?: return@launch
                val saved = PlayerPrefs.readEq(ctx)
                try {
                    eq.enabled = saved.enabled
                    bassBoost?.enabled = saved.enabled
                    if (saved.presetIndex in 0 until eq.numberOfPresets) {
                        eq.usePreset(saved.presetIndex.toShort())
                    } else {
                        saved.bandLevels?.forEachIndexed { i, level ->
                            if (i < eq.numberOfBands) eq.setBandLevel(i.toShort(), level)
                        }
                    }
                    bassBoost?.setStrength(saved.bassStrength.toShort())
                } catch (e: Exception) {
                    Log.w(TAG, "restore failed", e)
                }
                publish(if (saved.presetIndex >= 0) saved.presetIndex else -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Equalizer attach failed", e)
            releaseEffects()
        }
    }

    private fun publish(presetIndex: Int) {
        val eq = equalizer
        if (eq == null) {
            _state.value = EqState()
            return
        }
        try {
            val range = eq.bandLevelRange
            _state.value = EqState(
                ready = true,
                enabled = eq.enabled,
                presetIndex = presetIndex,
                presetNames = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) },
                bands = (0 until eq.numberOfBands.toInt()).map { i ->
                    EqBand(
                        centerFreqHz = eq.getCenterFreq(i.toShort()) / 1000,
                        levelMb = eq.getBandLevel(i.toShort()),
                        minMb = range[0],
                        maxMb = range[1],
                    )
                },
                bassStrength = bassBoost?.roundedStrength?.toInt() ?: 0,
                hasBassBoost = bassBoost != null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "publish failed", e)
        }
    }

    fun setEnabled(on: Boolean) {
        equalizer?.enabled = on
        bassBoost?.enabled = on
        publish(_state.value.presetIndex)
        persist()
    }

    fun applyPreset(index: Int) {
        val eq = equalizer ?: return
        if (index !in 0 until eq.numberOfPresets) return
        eq.usePreset(index.toShort())
        publish(index)
        persist()
    }

    /** 밴드 직접 조절 → 프리셋은 "사용자 설정"으로 전환. 드래그 중 실시간 호출 가능 */
    fun setBandLevel(band: Int, levelMb: Short) {
        val eq = equalizer ?: return
        try {
            eq.setBandLevel(band.toShort(), levelMb)
        } catch (e: Exception) {
            return
        }
        publish(-1)
    }

    fun setBassStrength(strength: Int) {
        bassBoost?.setStrength(strength.coerceIn(0, 1000).toShort())
        publish(_state.value.presetIndex)
    }

    /** 슬라이더 드래그가 끝났을 때 호출 — 현재 상태를 저장 */
    fun persist() {
        val ctx = appContext ?: return
        val s = _state.value
        scope.launch {
            PlayerPrefs.saveEq(
                ctx,
                PlayerPrefs.EqSettings(
                    enabled = s.enabled,
                    presetIndex = s.presetIndex,
                    bandLevels = if (s.presetIndex == -1) s.bands.map { it.levelMb } else null,
                    bassStrength = s.bassStrength,
                )
            )
        }
    }

    /** EQ 다이얼로그를 열 때 호출 — 초기화가 안 된 상태면 마지막 세션으로 재시도 */
    fun retryAttach() {
        val ctx = appContext ?: return
        if (equalizer == null && lastSessionId != 0) {
            attach(ctx, lastSessionId)
        }
    }

    fun release() {
        releaseEffects()
        _state.value = EqState()
    }

    private fun releaseEffects() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
    }
}
