package com.eok.mp3recorder.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.eok.mp3recorder.data.AudioTrack
import com.eok.mp3recorder.data.PlayerPrefs
import com.eok.mp3recorder.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val mediaId: String,
    val title: String,
    val artist: String,
)

/** A-B 구간 반복 상태: A만 찍힌 상태 → A·B 모두 찍히면 활성 */
data class AbLoopState(
    val pointAMs: Long? = null,
    val pointBMs: Long? = null,
) {
    val isActive: Boolean get() = pointAMs != null && pointBMs != null
}

/**
 * PlaybackService의 MediaSession에 연결되는 앱쪽 재생 컨트롤러(싱글톤).
 * 반복/셔플/배속/A-B 구간 반복/슬립 타이머/이어듣기를 관장한다.
 */
object PlayerController {

    private var controller: MediaController? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _abLoop = MutableStateFlow(AbLoopState())
    val abLoop: StateFlow<AbLoopState> = _abLoop.asStateFlow()

    /** 남은 슬립 타이머(ms). null이면 꺼짐 */
    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs.asStateFlow()

    /** "이 곡 끝나면 정지" 모드 */
    private val _sleepAfterTrack = MutableStateFlow(false)
    val sleepAfterTrack: StateFlow<Boolean> = _sleepAfterTrack.asStateFlow()

    private var abJob: Job? = null
    private var sleepJob: Job? = null
    private var resumeSaverJob: Job? = null

    /** 이어듣기 대상: 30분 이상 파일 */
    private const val RESUME_MIN_DURATION_MS = 30 * 60 * 1000L

    /** 앱 시작 시 한 번 호출 (여러 번 호출해도 무해) */
    fun connect(context: Context) {
        if (controller != null) return
        val app = context.applicationContext
        appContext = app
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c

            // 저장된 반복/셔플/배속 복원
            scope.launch {
                val s = PlayerPrefs.read(app)
                c.repeatMode = s.repeatMode
                c.shuffleModeEnabled = s.shuffle
                c.setPlaybackSpeed(s.speed)
            }

            c.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    _nowPlaying.value = item?.toNowPlaying()
                    clearAbLoop()   // 곡이 바뀌면 A-B는 의미가 없어짐
                    if (_sleepAfterTrack.value &&
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    ) {
                        _sleepAfterTrack.value = false
                        c.pause()
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }
                override fun onRepeatModeChanged(mode: Int) {
                    _repeatMode.value = mode
                    scope.launch { PlayerPrefs.saveRepeatMode(app, mode) }
                }
                override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                    _shuffleEnabled.value = enabled
                    scope.launch { PlayerPrefs.saveShuffle(app, enabled) }
                }
                override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) {
                    _speed.value = params.speed
                    scope.launch { PlayerPrefs.saveSpeed(app, params.speed) }
                }
            })
            _nowPlaying.value = c.currentMediaItem?.toNowPlaying()
            _isPlaying.value = c.isPlaying
            _repeatMode.value = c.repeatMode
            _shuffleEnabled.value = c.shuffleModeEnabled
            _speed.value = c.playbackParameters.speed
            startResumeSaver()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun MediaItem.toNowPlaying() = NowPlaying(
        mediaId = mediaId,
        title = mediaMetadata.title?.toString() ?: "제목 없음",
        artist = mediaMetadata.artist?.toString() ?: "",
    )

    /** [tracks] 전체를 재생 큐로 걸고 [startIndex]부터 재생. 긴 파일은 이어듣기 적용 */
    fun playQueue(tracks: List<AudioTrack>, startIndex: Int) {
        val c = controller ?: return
        val app = appContext
        val items = tracks.map { t ->
            MediaItem.Builder()
                .setMediaId(t.id.toString())
                .setUri(t.contentUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .build()
                )
                .build()
        }
        clearAbLoop()
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()

        val startTrack = tracks.getOrNull(startIndex)
        if (app != null && startTrack != null && startTrack.durationMs >= RESUME_MIN_DURATION_MS) {
            scope.launch {
                val saved = PlayerPrefs.resumePosition(app, startTrack.id.toString())
                if (saved != null && saved > 5_000 && saved < startTrack.durationMs - 10_000) {
                    c.seekTo(startIndex, saved)
                }
                c.play()
            }
        } else {
            c.play()
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun skipForward(ms: Long = 10_000) {
        val c = controller ?: return
        val dur = c.duration
        val target = c.currentPosition + ms
        c.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
    }

    fun skipBack(ms: Long = 10_000) {
        val c = controller ?: return
        c.seekTo((c.currentPosition - ms).coerceAtLeast(0))
    }

    /** 반복 모드 순환: 없음 → 전체 반복 → 한 곡 반복 → 없음 */
    fun cycleRepeatMode() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun setSpeed(value: Float) {
        controller?.setPlaybackSpeed(value.coerceIn(0.5f, 2.0f))
    }

    // ---- A-B 구간 반복 ----

    /** 1번 탭: A 지점 / 2번 탭: B 지점 설정 + 반복 시작 / 3번 탭: 해제 */
    fun toggleAbLoop() {
        val c = controller ?: return
        val cur = _abLoop.value
        when {
            cur.pointAMs == null -> _abLoop.value = AbLoopState(pointAMs = c.currentPosition)
            cur.pointBMs == null -> {
                val b = c.currentPosition
                if (b > cur.pointAMs + 500) {
                    _abLoop.value = cur.copy(pointBMs = b)
                    startAbJob()
                } else {
                    _abLoop.value = AbLoopState()   // 너무 짧으면 무효
                }
            }
            else -> clearAbLoop()
        }
    }

    fun clearAbLoop() {
        abJob?.cancel()
        abJob = null
        if (_abLoop.value != AbLoopState()) _abLoop.value = AbLoopState()
    }

    private fun startAbJob() {
        abJob?.cancel()
        abJob = scope.launch {
            while (isActive) {
                val st = _abLoop.value
                val c = controller
                if (c == null || !st.isActive) break
                if (c.currentPosition >= st.pointBMs!!) {
                    c.seekTo(st.pointAMs!!)
                }
                delay(150)
            }
        }
    }

    // ---- 슬립 타이머 ----

    fun setSleepTimerMinutes(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        _sleepAfterTrack.value = false
        if (minutes == null) {
            _sleepRemainingMs.value = null
            return
        }
        sleepJob = scope.launch {
            var remain = minutes * 60_000L
            while (remain > 0 && isActive) {
                _sleepRemainingMs.value = remain
                delay(1_000)
                remain -= 1_000
            }
            _sleepRemainingMs.value = null
            controller?.pause()
        }
    }

    fun setSleepAfterTrack() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = null
        _sleepAfterTrack.value = true
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = null
        _sleepAfterTrack.value = false
    }

    // ---- 이어듣기 저장 (30분 이상 파일, 5초 주기) ----

    private fun startResumeSaver() {
        if (resumeSaverJob != null) return
        resumeSaverJob = scope.launch {
            while (isActive) {
                delay(5_000)
                val c = controller ?: continue
                val app = appContext ?: continue
                if (!c.isPlaying) continue
                val dur = c.duration
                val id = c.currentMediaItem?.mediaId ?: continue
                if (dur >= RESUME_MIN_DURATION_MS) {
                    val pos = c.currentPosition
                    if (pos > dur - 10_000) PlayerPrefs.removeResumePosition(app, id)
                    else PlayerPrefs.saveResumePosition(app, id, pos)
                }
            }
        }
    }

    /** UI 폴링용 — 메인 스레드에서만 호출 */
    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L
    fun currentDurationMs(): Long = controller?.duration?.coerceAtLeast(0L) ?: 0L

    /** 재생 완전 종료: 큐 비우기 + 미니 플레이어 숨김 (뒤로 가기/앱 종료용) */
    fun stopAndClear() {
        clearAbLoop()
        cancelSleepTimer()
        controller?.run {
            stop()
            clearMediaItems()
        }
        _nowPlaying.value = null
        _isPlaying.value = false
    }
}
