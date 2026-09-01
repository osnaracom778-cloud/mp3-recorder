package com.eok.mp3recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eok.mp3recorder.data.AudioTrack
import com.eok.mp3recorder.player.PlayerController

private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun LibraryScreen(
    onOpenPlayer: () -> Unit = {},
    vm: LibraryViewModel = viewModel(),
) {
    val ui by vm.uiState.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val context = LocalContext.current

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) vm.refresh()
    }

    // 다른 앱 파일 삭제 시 시스템 승인 다이얼로그 결과
    val deleteConfirmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vm.refresh()
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) vm.refresh()
    }

    if (!permissionGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("기기의 음악 파일을 표시하려면\n오디오 접근 권한이 필요합니다.",
                style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = { permissionLauncher.launch(audioPermission) },
                modifier = Modifier.padding(top = 16.dp)
            ) { Text("권한 허용") }
        }
        return
    }

    // 다이얼로그 대상 상태
    var renameTarget by remember { mutableStateOf<AudioTrack?>(null) }
    var deleteTarget by remember { mutableStateOf<AudioTrack?>(null) }
    var infoTarget by remember { mutableStateOf<AudioTrack?>(null) }
    var playlistTarget by remember { mutableStateOf<AudioTrack?>(null) }
    var moveTarget by remember { mutableStateOf<AudioTrack?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 검색 + 정렬
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = { vm.setQuery(it) },
                placeholder = { Text("제목·아티스트 검색") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "지우기",
                                modifier = Modifier.size(18.dp))
                        }
                    }
                },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f)
            )
            Box {
                var sortOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { sortOpen = true }) { Text(ui.sort.label) }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortMode.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(if (s == ui.sort) "✓ ${s.label}" else s.label) },
                            onClick = { vm.setSort(s); sortOpen = false }
                        )
                    }
                }
            }
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            LibraryFilter.entries.forEachIndexed { index, f ->
                SegmentedButton(
                    selected = ui.filter == f,
                    onClick = { vm.setFilter(f) },
                    shape = SegmentedButtonDefaults.itemShape(index, LibraryFilter.entries.size)
                ) { Text(f.label) }
            }
        }

        when {
            ui.loading && ui.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    when {
                        ui.query.isNotBlank() -> "검색 결과가 없습니다"
                        ui.filter == LibraryFilter.FAVORITES -> "즐겨찾기한 곡이 없습니다"
                        else -> "아직 녹음한 파일이 없습니다"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                val nowPlaying by PlayerController.nowPlaying.collectAsState()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            isCurrent = nowPlaying?.mediaId == track.id.toString(),
                            isSelected = ui.selectedId == track.id,
                            isFavorite = track.id in ui.favoriteIds,
                            onClick = {
                                vm.play(track)
                                onOpenPlayer()
                            },
                            onToggleFavorite = { vm.toggleFavorite(track) },
                            onAddToPlaylist = { playlistTarget = track },
                            onRename = { renameTarget = track },
                            onMove = { moveTarget = track },
                            onShare = { vm.share(track) },
                            onDelete = { deleteTarget = track },
                            onInfo = { infoTarget = track },
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }

    // ---- 다이얼로그들 ----

    renameTarget?.let { track ->
        var name by remember(track.id) { mutableStateOf("") }
        val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("이름 변경") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(track.title) },   // 기존 이름은 힌트로만 표시
                    modifier = Modifier.focusRequester(focusRequester)
                )
                // 다이얼로그가 뜨면 바로 입력칸에 커서 + 키보드
                LaunchedEffect(track.id) { focusRequester.requestFocus() }
            },
            confirmButton = {
                Button(
                    onClick = { vm.rename(track, name); renameTarget = null },
                    enabled = name.isNotBlank()
                ) { Text("변경") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("취소") }
            }
        )
    }

    moveTarget?.let { track ->
        val folders by vm.folders.collectAsState()
        var newFolder by remember(track.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("폴더로 이동") },
            text = {
                Column {
                    Text("'${track.title}' 파일을 옮길 폴더를 선택하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    folders.filter { it != track.relativePath }.forEach { folder ->
                        Text(
                            text = "📁 ${folder.trimEnd('/')}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.moveToFolder(track, folder)
                                    moveTarget = null
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        singleLine = true,
                        placeholder = { Text("새 폴더 이름 (음악 폴더 아래 생성)") },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.moveToFolder(track, "Music/${newFolder.trim()}")
                        moveTarget = null
                    },
                    enabled = newFolder.isNotBlank()
                ) { Text("새 폴더로 이동") }
            },
            dismissButton = {
                TextButton(onClick = { moveTarget = null }) { Text("취소") }
            }
        )
    }

    deleteTarget?.let { track ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("삭제") },
            text = { Text("'${track.title}' 파일을 삭제할까요?\n삭제한 파일은 되돌릴 수 없습니다.") },
            confirmButton = {
                Button(onClick = {
                    vm.delete(track) { sender ->
                        deleteConfirmLauncher.launch(
                            androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                        )
                    }
                    deleteTarget = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("취소") }
            }
        )
    }

    infoTarget?.let { track ->
        AlertDialog(
            onDismissRequest = { infoTarget = null },
            title = { Text("파일 정보") },
            text = {
                Column {
                    Text("제목: ${track.title}")
                    Text("아티스트: ${track.artist}")
                    Text("길이: ${formatDuration(track.durationMs)}")
                    Text("크기: %.1f MB".format(track.sizeBytes / 1024.0 / 1024.0))
                    Text("경로: ${track.relativePath}")
                }
            },
            confirmButton = {
                TextButton(onClick = { infoTarget = null }) { Text("닫기") }
            }
        )
    }

    playlistTarget?.let { track ->
        var newName by remember(track.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { playlistTarget = null },
            title = { Text("재생목록에 추가") },
            text = {
                Column {
                    if (playlists.isEmpty()) {
                        Text("재생목록이 없습니다. 새로 만들어 추가하세요.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    playlists.forEach { p ->
                        Text(
                            text = "${p.name} (${p.trackCount}곡)",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.addToPlaylist(p.id, track)
                                    playlistTarget = null
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("새 재생목록 이름") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.createPlaylistAndAdd(newName, track)
                        playlistTarget = null
                    },
                    enabled = newName.isNotBlank()
                ) { Text("새로 만들어 추가") }
            },
            dismissButton = {
                TextButton(onClick = { playlistTarget = null }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun TrackRow(
    track: AudioTrack,
    isCurrent: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isFavorite) {
                    Icon(
                        Icons.Filled.Favorite, contentDescription = "즐겨찾기",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp).padding(end = 2.dp)
                    )
                }
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = buildString {
                    if (track.isMyRecording) append("🎙 ")
                    append(track.artist)
                    append(" · ")
                    append(formatDuration(track.durationMs))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Box {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "메뉴",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "즐겨찾기 해제" else "즐겨찾기 ♥") },
                    onClick = { onToggleFavorite(); menuOpen = false })
                DropdownMenuItem(text = { Text("재생목록에 추가") },
                    onClick = { onAddToPlaylist(); menuOpen = false })
                DropdownMenuItem(text = { Text("이름 변경") },
                    onClick = { onRename(); menuOpen = false })
                DropdownMenuItem(text = { Text("폴더로 이동") },
                    onClick = { onMove(); menuOpen = false })
                DropdownMenuItem(text = { Text("공유") },
                    onClick = { onShare(); menuOpen = false })
                DropdownMenuItem(text = { Text("삭제") },
                    onClick = { onDelete(); menuOpen = false })
                DropdownMenuItem(text = { Text("파일 정보") },
                    onClick = { onInfo(); menuOpen = false })
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
