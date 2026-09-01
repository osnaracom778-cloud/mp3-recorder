package com.eok.mp3recorder.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.eok.mp3recorder.data.RecordingStore
import com.eok.mp3recorder.service.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 녹음 상태의 단일 소유자. UI(ViewModel)와 RecordingService가 모두 이 객체를 통해
 * 같은 엔진을 제어한다 — 액티비티가 죽어도 서비스가 살아 있는 한 녹음은 계속된다.
 */
object RecordingController {

    private val engine = Mp3RecorderEngine(bitrateKbps = 128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val state: StateFlow<RecorderState> get() = engine.state
    val elapsedMs: StateFlow<Long> get() = engine.elapsedMs
    val amplitude: StateFlow<Float> get() = engine.amplitude

    /** null이 아니면 저장 다이얼로그를 띄워야 함 (값은 기본 파일 이름) */
    val pendingSaveName = MutableStateFlow<String?>(null)
    val busySaving = MutableStateFlow(false)

    /** 마지막으로 저장된 녹음의 MediaStore ID — 라이브러리 자동 이동·선택 표시용 */
    val lastSavedMediaId = MutableStateFlow<Long?>(null)

    /** 토스트로 보여줄 사용자 메시지 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private var tempFile: File? = null
    private var finishedDurationMs = 0L

    /** 전화 때문에 자동 일시정지된 상태인지 (통화 종료 시 자동 재개 판단용) */
    @Volatile var pausedByCall = false
        private set

    fun start(context: Context) {
        if (engine.state.value != RecorderState.IDLE) return
        try {
            val file = File(context.cacheDir, "recording_${System.currentTimeMillis()}.mp3.tmp")
            tempFile = file
            engine.start(file)
            pausedByCall = false
            val intent = Intent(context, RecordingService::class.java)
                .setAction(RecordingService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            tempFile = null
            _events.tryEmit("녹음 시작 실패: ${e.message}")
        }
    }

    fun pause() {
        pausedByCall = false
        engine.pause()
    }

    fun resume() {
        pausedByCall = false
        engine.resume()
    }

    /** 전화 수신에 의한 자동 일시정지 */
    fun pauseForCall() {
        if (engine.state.value == RecorderState.RECORDING) {
            engine.pause()
            pausedByCall = true
            _events.tryEmit("통화 중 — 녹음이 자동 일시정지되었습니다")
        }
    }

    /** 통화 종료 시 자동 재개 (자동 일시정지였던 경우에만) */
    fun resumeAfterCall() {
        if (pausedByCall && engine.state.value == RecorderState.PAUSED) {
            pausedByCall = false
            engine.resume()
            _events.tryEmit("통화 종료 — 녹음을 재개합니다")
        }
    }

    /** 정지: 인코딩을 마무리하고 저장 다이얼로그를 요청한다 */
    fun stop() {
        if (engine.state.value == RecorderState.IDLE) return
        scope.launch {
            finishedDurationMs = engine.elapsedMs.value
            engine.stop()   // flush까지 완료 대기
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            pendingSaveName.value = "녹음_$stamp"
        }
    }

    fun save(context: Context, name: String) {
        val file = tempFile ?: return
        val appContext = context.applicationContext
        val trimmed = name.trim().ifEmpty { "녹음" }
        busySaving.value = true
        scope.launch {
            try {
                val uri = RecordingStore.save(appContext, file, trimmed, finishedDurationMs)
                lastSavedMediaId.value = android.content.ContentUris.parseId(uri)
                _events.emit("저장 완료: $trimmed.mp3 (음악/MP3녹음기)")
            } catch (e: Exception) {
                _events.emit("저장 실패: ${e.message}")
            } finally {
                tempFile = null
                pendingSaveName.value = null
                busySaving.value = false
            }
        }
    }

    fun discard() {
        tempFile?.delete()
        tempFile = null
        pendingSaveName.value = null
    }
}
