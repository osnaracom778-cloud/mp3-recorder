package com.eok.mp3recorder.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

enum class RecorderState { IDLE, RECORDING, PAUSED }

/**
 * 마이크(PCM) → LAME → MP3 스트리밍 녹음 엔진.
 *
 * - 일시정지/재개는 횟수 제한 없이 가능하며, 인코더를 유지한 채 PCM 공급만 멈추므로
 *   결과물은 끊김 없는 하나의 MP3 파일이 된다 (일시정지 구간은 파일에 포함되지 않음).
 * - 인코딩 결과를 즉시 파일에 기록하므로 프로세스가 죽어도 그 시점까지의 데이터는 남는다.
 * - 경과 시간은 인코딩한 샘플 수 기준으로 계산해 파일 길이와 정확히 일치한다.
 */
class Mp3RecorderEngine(
    private val bitrateKbps: Int = 128,
) {
    private val _state = MutableStateFlow(RecorderState.IDLE)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** 현재 입력 레벨 0.0 ~ 1.0 */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile private var command = Command.NONE
    private var worker: Thread? = null
    private var sampleRateUsed = 0

    private enum class Command { NONE, PAUSE, RESUME, STOP }

    /** 녹음 시작. [outFile]에 MP3 스트림을 기록한다. */
    @SuppressLint("MissingPermission")
    fun start(outFile: File) {
        check(_state.value == RecorderState.IDLE) { "already recording" }

        val (audioRecord, sampleRate) = createAudioRecord()
        sampleRateUsed = sampleRate
        command = Command.NONE
        _elapsedMs.value = 0L
        _amplitude.value = 0f

        worker = Thread({ runLoop(audioRecord, sampleRate, outFile) }, "Mp3RecorderEngine").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        _state.value = RecorderState.RECORDING
    }

    fun pause() {
        if (_state.value == RecorderState.RECORDING) command = Command.PAUSE
    }

    fun resume() {
        if (_state.value == RecorderState.PAUSED) command = Command.RESUME
    }

    /** 녹음 종료. 워커 스레드가 flush까지 마칠 때까지 대기한 뒤 반환한다. */
    fun stop() {
        if (_state.value == RecorderState.IDLE) return
        command = Command.STOP
        worker?.join(10_000)
        worker = null
        _state.value = RecorderState.IDLE
        _amplitude.value = 0f
    }

    /** 갤럭시 등 기기별 지원 편차 대비: 샘플레이트 폴백 체인 */
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): Pair<AudioRecord, Int> {
        for (rate in intArrayOf(44100, 48000, 22050)) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) continue
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, CHUNK_SAMPLES * 2 /* bytes */ * 2)
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to rate
            record.release()
            Log.w(TAG, "AudioRecord init failed at $rate Hz, trying next")
        }
        throw IllegalStateException("AudioRecord 초기화 실패 — 마이크를 사용할 수 없습니다")
    }

    private fun runLoop(audioRecord: AudioRecord, sampleRate: Int, outFile: File) {
        val encoder = LameEncoder()
        val pcmBuf = ShortArray(CHUNK_SAMPLES)
        val mp3Buf = ByteArray(LameEncoder.recommendedOutBufSize(CHUNK_SAMPLES))
        var totalSamples = 0L
        var recordingActive = false

        try {
            encoder.init(sampleRate, channels = 1, bitrateKbps = bitrateKbps)
            BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { out ->
                audioRecord.startRecording()
                recordingActive = true

                loop@ while (true) {
                    when (command) {
                        Command.STOP -> break@loop
                        Command.PAUSE -> {
                            if (recordingActive) {
                                audioRecord.stop()
                                recordingActive = false
                                _state.value = RecorderState.PAUSED
                                _amplitude.value = 0f
                                out.flush()
                            }
                            Thread.sleep(50)
                            continue@loop
                        }
                        Command.RESUME -> {
                            command = Command.NONE
                            if (!recordingActive) {
                                audioRecord.startRecording()
                                recordingActive = true
                                _state.value = RecorderState.RECORDING
                            }
                        }
                        Command.NONE -> { /* 계속 녹음 */ }
                    }

                    val read = audioRecord.read(pcmBuf, 0, pcmBuf.size)
                    if (read > 0) {
                        val encoded = encoder.encode(pcmBuf, read, mp3Buf)
                        if (encoded > 0) out.write(mp3Buf, 0, encoded)

                        totalSamples += read
                        _elapsedMs.value = totalSamples * 1000L / sampleRate

                        var peak = 0
                        for (i in 0 until read) {
                            val v = abs(pcmBuf[i].toInt())
                            if (v > peak) peak = v
                        }
                        _amplitude.value = peak / 32767f
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord.read error: $read")
                        break@loop
                    }
                }

                if (recordingActive) audioRecord.stop()
                val flushed = encoder.flush(mp3Buf)
                if (flushed > 0) out.write(mp3Buf, 0, flushed)
                out.flush()
            }
        } finally {
            encoder.close()
            audioRecord.release()
        }
    }

    companion object {
        private const val TAG = "Mp3RecorderEngine"

        /** 청크당 샘플 수 — 44.1kHz 모노 기준 약 93ms 주기로 UI 갱신 */
        private const val CHUNK_SAMPLES = 4096
    }
}
