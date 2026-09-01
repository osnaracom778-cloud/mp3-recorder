package com.eok.mp3recorder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eok.mp3recorder.player.PlayerController
import kotlinx.coroutines.delay

/** 모든 탭 하단에 표시되는 미니 플레이어 바 (재생 중인 곡이 있을 때만) */
@Composable
fun MiniPlayer(onExpand: () -> Unit = {}) {
    val nowPlaying by PlayerController.nowPlaying.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()

    val track = nowPlaying ?: return

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(track.mediaId) {
        while (true) {
            positionMs = PlayerController.currentPositionMs()
            durationMs = PlayerController.currentDurationMs()
            delay(500)
        }
    }

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            LinearProgressIndicator(
                progress = {
                    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    else 0f
                },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onExpand)
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (track.artist.isNotBlank())
                            "${track.artist} · ${formatDuration(positionMs)} / ${formatDuration(durationMs)}"
                        else "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { PlayerController.togglePlayPause() }) {
                    Text(
                        if (isPlaying) "❚❚" else "▶",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { PlayerController.next() }) {
                    Text(
                        "⏭",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
