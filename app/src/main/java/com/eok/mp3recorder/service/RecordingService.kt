package com.eok.mp3recorder.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.eok.mp3recorder.MainActivity
import com.eok.mp3recorder.audio.RecorderState
import com.eok.mp3recorder.audio.RecordingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 녹음 Foreground Service (microphone 타입).
 * - 화면 꺼짐/앱 전환 중에도 녹음 유지 (부분 웨이크락 + 삼성 Deep Sleep 대비)
 * - 알림에서 일시정지/재개/정지 제어
 * - 전화 수신/발신 시 자동 일시정지, 통화 종료 시 자동 재개
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null            // API 31+
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null          // API 29~30

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecordingForeground()
            ACTION_PAUSE -> RecordingController.pause()
            ACTION_RESUME -> RecordingController.resume()
            ACTION_STOP -> RecordingController.stop()
        }
        return START_NOT_STICKY
    }

    private fun startRecordingForeground() {
        val notification = buildNotification(RecorderState.RECORDING, 0L)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        acquireWakeLock()
        registerCallListener()
        observeRecorderState()
    }

    /** 상태·경과시간을 지켜보며 알림 갱신, 녹음이 끝나면 서비스 종료 */
    private fun observeRecorderState() {
        scope.launch {
            combine(
                RecordingController.state,
                RecordingController.elapsedMs.map { it / 1000 }.distinctUntilChanged()
            ) { state, elapsedSec -> state to elapsedSec }
                .collect { (state, elapsedSec) ->
                    if (state == RecorderState.IDLE) {
                        ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, buildNotification(state, elapsedSec * 1000))
                    }
                }
        }
    }

    private fun buildNotification(state: RecorderState, elapsedMs: Long): Notification {
        val paused = state == RecorderState.PAUSED
        val totalSec = elapsedMs / 1000
        val timeText = "%02d:%02d".format(totalSec / 60, totalSec % 60)

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun action(actionName: String, label: String, requestCode: Int): NotificationCompat.Action {
            val pi = PendingIntent.getService(
                this, requestCode,
                Intent(this, RecordingService::class.java).setAction(actionName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action(0, label, pi)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "녹음 일시정지" else "녹음 중")
            .setContentText(timeText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (paused) action(ACTION_RESUME, "재개", 1)
                else action(ACTION_PAUSE, "일시정지", 2)
            )
            .addAction(action(ACTION_STOP, "정지", 3))
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "녹음", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "녹음 진행 상태 표시 및 제어"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mp3Recorder:recording").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)  // 최대 6시간 (안전 상한)
        }
    }

    /** 전화 상태 감지 — READ_PHONE_STATE 권한이 있을 때만 동작 (없으면 기능만 비활성) */
    private fun registerCallListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager = tm

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(callState: Int) = handleCallState(callState)
            }
            telephonyCallback = callback
            tm.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(callState: Int, phoneNumber: String?) =
                    handleCallState(callState)
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun handleCallState(callState: Int) {
        when (callState) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> RecordingController.pauseForCall()
            TelephonyManager.CALL_STATE_IDLE -> RecordingController.resumeAfterCall()
        }
    }

    private fun unregisterCallListener() {
        val tm = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        telephonyCallback = null
        phoneStateListener = null
        telephonyManager = null
    }

    override fun onDestroy() {
        unregisterCallListener()
        wakeLock?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.eok.mp3recorder.action.START"
        const val ACTION_PAUSE = "com.eok.mp3recorder.action.PAUSE"
        const val ACTION_RESUME = "com.eok.mp3recorder.action.RESUME"
        const val ACTION_STOP = "com.eok.mp3recorder.action.STOP"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
    }
}
