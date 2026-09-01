package com.eok.mp3recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eok.mp3recorder.audio.RecorderState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(vm: RecordViewModel = viewModel()) {
    val ui by vm.uiState.collectAsState()
    val context = LocalContext.current

    // 마이크(필수) + 알림(API 33+, 백그라운드 녹음 알림용) + 전화 상태(통화 시 자동 일시정지용)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) vm.startRecording()
    }
    val permissionsToRequest = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "MP3 녹음기",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp)
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatElapsed(ui.elapsedMs),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light
                )
            )
            Text(
                text = when (ui.recorderState) {
                    RecorderState.IDLE -> "대기 중"
                    RecorderState.RECORDING -> "녹음 중"
                    RecorderState.PAUSED -> "일시정지"
                },
                style = MaterialTheme.typography.titleMedium,
                color = when (ui.recorderState) {
                    RecorderState.RECORDING -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 8.dp)
            )
            LevelMeter(
                amplitude = ui.amplitude,
                modifier = Modifier.padding(top = 24.dp).fillMaxWidth(0.8f)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(bottom = 48.dp)
        ) {
            // 좌우 균형용 빈 공간 (일시정지 버튼은 오른쪽에)
            if (ui.recorderState != RecorderState.IDLE) {
                Spacer(Modifier.size(72.dp))
            }

            // 메인 녹음/정지 버튼
            val recording = ui.recorderState != RecorderState.IDLE
            val buttonColor by animateColorAsState(
                if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                label = "recButtonColor"
            )
            FilledIconButton(
                onClick = {
                    if (recording) {
                        vm.stopRecording()
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) vm.startRecording()
                        else permissionLauncher.launch(permissionsToRequest)
                    }
                },
                modifier = Modifier.size(96.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = buttonColor)
            ) {
                Icon(
                    if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (recording) "정지" else "녹음 시작",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }

            // 일시정지/재개 버튼 (녹음 중에만, 오른쪽에 표시)
            if (ui.recorderState != RecorderState.IDLE) {
                FilledIconButton(
                    onClick = { vm.pauseOrResume() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(
                        if (ui.recorderState == RecorderState.PAUSED) Icons.Filled.PlayArrow
                        else Icons.Filled.Pause,
                        contentDescription = if (ui.recorderState == RecorderState.PAUSED) "재개" else "일시정지",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }

    // 저장 다이얼로그
    ui.saveDialogDefaultName?.let { defaultName ->
        var name by remember(defaultName) { mutableStateOf(defaultName) }
        AlertDialog(
            onDismissRequest = { /* 실수 방지: 버튼으로만 닫기 */ },
            title = { Text("녹음 저장") },
            text = {
                Column {
                    Text("파일 이름을 입력하세요. (음악/MP3녹음기 폴더에 저장)")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    if (ui.busySaving) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Text("저장 중…", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.saveRecording(name) }, enabled = !ui.busySaving) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.discardRecording() }, enabled = !ui.busySaving) {
                    Text("버리기")
                }
            }
        )
    }
}

@Composable
private fun LevelMeter(amplitude: Float, modifier: Modifier = Modifier) {
    val smooth by animateFloatAsState(amplitude, label = "level")
    Box(
        modifier = modifier
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(smooth.coerceIn(0f, 1f))
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (smooth > 0.85f) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    val d = (ms % 1000) / 100
    return "%02d:%02d.%d".format(m, s, d)
}
