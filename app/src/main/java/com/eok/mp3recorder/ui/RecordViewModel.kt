package com.eok.mp3recorder.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eok.mp3recorder.audio.RecorderState
import com.eok.mp3recorder.audio.RecordingController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordUiState(
    val recorderState: RecorderState = RecorderState.IDLE,
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
    val saveDialogDefaultName: String? = null,  // null이 아니면 저장 다이얼로그 표시
    val busySaving: Boolean = false,
)

/**
 * 얇은 래퍼 — 실제 녹음 상태는 RecordingController(서비스와 공유)가 소유한다.
 * 액티비티가 재생성되어도 녹음은 서비스에서 계속된다.
 */
class RecordViewModel(app: Application) : AndroidViewModel(app) {

    val uiState: StateFlow<RecordUiState> = combine(
        RecordingController.state,
        RecordingController.elapsedMs,
        RecordingController.amplitude,
        RecordingController.pendingSaveName,
        RecordingController.busySaving,
    ) { state, elapsed, amp, dialogName, busy ->
        RecordUiState(state, elapsed, amp, dialogName, busy)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordUiState())

    init {
        viewModelScope.launch {
            RecordingController.events.collect { msg ->
                Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** RECORD_AUDIO 권한이 승인된 뒤에만 호출할 것 */
    fun startRecording() = RecordingController.start(getApplication())

    fun pauseOrResume() {
        when (uiState.value.recorderState) {
            RecorderState.RECORDING -> RecordingController.pause()
            RecorderState.PAUSED -> RecordingController.resume()
            RecorderState.IDLE -> Unit
        }
    }

    fun stopRecording() = RecordingController.stop()

    fun saveRecording(name: String) = RecordingController.save(getApplication(), name)

    fun discardRecording() = RecordingController.discard()
}
