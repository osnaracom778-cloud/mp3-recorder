package com.eok.mp3recorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.eok.mp3recorder.player.PlayerController
import kotlinx.coroutines.delay

private val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/** 미니 플레이어를 탭하면 열리는 전체 플레이어 화면 (바텀 시트 내용물) */
@Composable
fun PlayerScreen() {
    val nowPlaying by PlayerController.nowPlaying.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val repeatMode by PlayerController.repeatMode.collectAsState()
    val shuffle by PlayerController.shuffleEnabled.collectAsState()
    val speed by PlayerController.speed.collectAsState()
    val abLoop by PlayerController.abLoop.collectAsState()
    val sleepRemaining by PlayerController.sleepRemainingMs.collectAsState()
    val sleepAfterTrack by PlayerController.sleepAfterTrack.collectAsState()

    val track = nowPlaying ?: run {
        Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
            Text("재생 중인 곡이 없습니다")
        }
        return
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(track.mediaId) {
        while (true) {
            if (!dragging) {
                positionMs = PlayerController.currentPositionMs()
            }
            durationMs = PlayerController.currentDurationMs()
            delay(300)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 앨범아트 자리 (그라데이션 카드)
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surfaceVariant,
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
        }
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (track.artist.isNotBlank()) {
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 시크바 + A/B 마커
        Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            val progress =
                if (dragging) dragValue
                else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                else 0f
            Slider(
                value = progress,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    if (durationMs > 0) {
                        PlayerController.seekTo((dragValue * durationMs).toLong())
                        positionMs = (dragValue * durationMs).toLong()
                    }
                    dragging = false
                },
                modifier = Modifier.fillMaxWidth()
            )
            // A/B 마커 표시줄
            if (abLoop.pointAMs != null && durationMs > 0) {
                Box(Modifier.fillMaxWidth().height(16.dp)) {
                    val aFrac = (abLoop.pointAMs!!.toFloat() / durationMs).coerceIn(0f, 1f)
                    AbMarker("A", aFrac)
                    abLoop.pointBMs?.let { b ->
                        AbMarker("B", (b.toFloat() / durationMs).coerceIn(0f, 1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(if (dragging) (dragValue * durationMs).toLong() else positionMs),
                    style = MaterialTheme.typography.labelMedium)
                Text(formatDuration(durationMs), style = MaterialTheme.typography.labelMedium)
            }
        }

        // 메인 컨트롤: 이전 | -10초 | 재생/일시정지 | +10초 | 다음
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            IconButton(onClick = { PlayerController.previous() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "이전 곡",
                    modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { PlayerController.skipBack() }) {
                Icon(Icons.Filled.Replay10, contentDescription = "10초 뒤로",
                    modifier = Modifier.size(28.dp))
            }
            FilledIconButton(
                onClick = { PlayerController.togglePlayPause() },
                modifier = Modifier.size(76.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "일시정지" else "재생",
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = { PlayerController.skipForward() }) {
                Icon(Icons.Filled.Forward10, contentDescription = "10초 앞으로",
                    modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { PlayerController.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "다음 곡",
                    modifier = Modifier.size(32.dp))
            }
        }

        // 보조 컨트롤: 반복 | 셔플 | A-B | 배속 | 슬립
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)
        ) {
            // 반복: 없음 → 전체 → 한 곡
            TextButton(onClick = { PlayerController.cycleRepeatMode() }) {
                val (icon, label, active) = when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> Triple(Icons.Filled.Repeat, "전체", true)
                    Player.REPEAT_MODE_ONE -> Triple(Icons.Filled.RepeatOne, "한곡", true)
                    else -> Triple(Icons.Filled.Repeat, "반복", false)
                }
                val tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(" $label", color = tint)
            }
            // 셔플
            TextButton(onClick = { PlayerController.toggleShuffle() }) {
                val tint = if (shuffle) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(Icons.Filled.Shuffle, contentDescription = null, tint = tint,
                    modifier = Modifier.size(18.dp))
                Text(" 셔플", color = tint)
            }
            // A-B 구간 반복
            TextButton(onClick = { PlayerController.toggleAbLoop() }) {
                val (label, active) = when {
                    abLoop.isActive -> "A-B 해제" to true
                    abLoop.pointAMs != null -> "B 지점?" to true
                    else -> "A-B" to false
                }
                Text(
                    label,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 배속 (탭할 때마다 순환)
            TextButton(onClick = {
                val idx = SPEED_STEPS.indexOfFirst { it >= speed - 0.01f }
                val next = SPEED_STEPS[(if (idx < 0) 2 else idx + 1) % SPEED_STEPS.size]
                PlayerController.setSpeed(next)
            }) {
                Text(
                    "%.2f".format(speed).trimEnd('0').trimEnd('.') + "x",
                    color = if (speed != 1.0f) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 슬립 타이머
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { menuOpen = true }) {
                    val tint = if (sleepRemaining != null || sleepAfterTrack)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(Icons.Outlined.Timer, contentDescription = null, tint = tint,
                        modifier = Modifier.size(18.dp))
                    Text(
                        when {
                            sleepRemaining != null -> " ${formatDuration(sleepRemaining!!)}"
                            sleepAfterTrack -> " 곡 끝"
                            else -> " 슬립"
                        },
                        color = tint
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf(15, 30, 60).forEach { min ->
                        DropdownMenuItem(
                            text = { Text("${min}분 후 정지") },
                            onClick = {
                                PlayerController.setSleepTimerMinutes(min)
                                menuOpen = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("이 곡 끝나면 정지") },
                        onClick = {
                            PlayerController.setSleepAfterTrack()
                            menuOpen = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("끄기") },
                        onClick = {
                            PlayerController.cancelSleepTimer()
                            menuOpen = false
                        }
                    )
                }
            }
        }
    }
}

/** 시크바 아래 특정 위치(fraction)에 A/B 라벨을 놓는다 */
@Composable
private fun AbMarker(label: String, fraction: Float) {
    Row(Modifier.fillMaxWidth()) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
