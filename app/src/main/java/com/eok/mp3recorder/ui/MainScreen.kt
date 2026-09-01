package com.eok.mp3recorder.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eok.mp3recorder.audio.RecorderState
import com.eok.mp3recorder.audio.RecordingController
import com.eok.mp3recorder.player.PlayerController
import com.eok.mp3recorder.service.PlaybackService
import kotlinx.coroutines.flow.filterNotNull

private enum class MainTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    RECORD("녹음", Icons.Filled.Mic),
    LIBRARY("라이브러리", Icons.Filled.LibraryMusic),
    PLAYLIST("재생목록", Icons.AutoMirrored.Filled.QueueMusic),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        PlayerController.connect(context)
    }

    var tab by rememberSaveable { mutableStateOf(MainTab.RECORD) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }

    val nowPlaying by PlayerController.nowPlaying.collectAsState()
    val recorderState by RecordingController.state.collectAsState()

    // 녹음 저장이 완료되면 라이브러리(내 녹음) 탭으로 이동해 새 파일을 선택 표시
    LaunchedEffect(Unit) {
        RecordingController.lastSavedMediaId.filterNotNull().collect {
            tab = MainTab.LIBRARY
        }
    }

    fun exitApp() {
        PlayerController.stopAndClear()
        context.stopService(Intent(context, PlaybackService::class.java))
        (context as? Activity)?.finishAndRemoveTask()
    }

    // 뒤로 가기 단계별 처리:
    // (재생 팝업이 열려 있으면 팝업이 스스로 닫힘) → 재생 중이면 재생 종료 → 앱 종료
    BackHandler(enabled = !showPlayer) {
        when {
            nowPlaying != null -> PlayerController.stopAndClear()
            recorderState != RecorderState.IDLE ->
                Toast.makeText(context, "녹음이 진행 중입니다 — 먼저 정지해 주세요", Toast.LENGTH_SHORT).show()
            else -> exitApp()
        }
    }

    Scaffold(
        topBar = {
            // 우상단 앱 종료(✕) 버튼
            Box(Modifier.fillMaxWidth().height(40.dp)) {
                IconButton(
                    onClick = { showExitDialog = true },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "앱 종료",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        bottomBar = {
            Column {
                MiniPlayer(onExpand = { showPlayer = true })
                NavigationBar {
                    MainTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                MainTab.RECORD -> RecordScreen()
                MainTab.LIBRARY -> LibraryScreen(onOpenPlayer = { showPlayer = true })
                MainTab.PLAYLIST -> PlaylistScreen(onOpenPlayer = { showPlayer = true })
            }
        }
    }

    // 재생 팝업 (전체 플레이어)
    if (showPlayer) {
        ModalBottomSheet(
            onDismissRequest = { showPlayer = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            PlayerScreen()
        }
    }

    // 앱 종료 확인
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("앱 종료") },
            text = {
                Text(
                    if (recorderState != RecorderState.IDLE)
                        "녹음이 진행 중입니다.\n먼저 녹음을 정지하고 저장한 뒤 종료해 주세요."
                    else "앱을 종료할까요?\n재생 중인 음악도 함께 종료됩니다."
                )
            },
            confirmButton = {
                if (recorderState == RecorderState.IDLE) {
                    Button(onClick = { showExitDialog = false; exitApp() }) { Text("종료") }
                } else {
                    Button(onClick = { showExitDialog = false }) { Text("확인") }
                }
            },
            dismissButton = {
                if (recorderState == RecorderState.IDLE) {
                    TextButton(onClick = { showExitDialog = false }) { Text("취소") }
                }
            }
        )
    }
}
