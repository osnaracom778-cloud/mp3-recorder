package com.eok.mp3recorder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eok.mp3recorder.data.db.PlaylistWithCount
import com.eok.mp3recorder.player.PlayerController

@Composable
fun PlaylistScreen(
    onOpenPlayer: () -> Unit = {},
    vm: PlaylistViewModel = viewModel(),
) {
    val selected by vm.selected.collectAsState()

    LaunchedEffect(Unit) { vm.refreshTracks() }

    if (selected == null) {
        PlaylistListView(vm)
    } else {
        PlaylistDetailView(vm, onOpenPlayer)
    }
}

// ---- 재생목록 목록 화면 ----

@Composable
private fun PlaylistListView(vm: PlaylistViewModel) {
    val playlists by vm.playlists.collectAsState()
    val favoritesCount by vm.favoritesCount.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistWithCount?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistWithCount?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("재생목록", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { showFolderPicker = true },
                modifier = Modifier.padding(end = 8.dp)
            ) { Text("📁 폴더") }
            OutlinedButton(onClick = { showCreate = true }) { Text("+ 새로") }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            // 즐겨찾기 (자동 재생목록)
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.open(PlaylistViewModel.FAVORITES_ID) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Filled.Favorite, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp))
                    Text(
                        "즐겨찾기",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                    )
                    Text("${favoritesCount}곡", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(thickness = 0.5.dp)
            }

            itemsIndexed(playlists, key = { _, p -> p.id }) { _, playlist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.open(playlist.id) }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp))
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                    )
                    Text("${playlist.trackCount}곡", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "메뉴",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("이름 변경") },
                                onClick = { renameTarget = playlist; menuOpen = false })
                            DropdownMenuItem(text = { Text("삭제") },
                                onClick = { deleteTarget = playlist; menuOpen = false })
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }

    if (showFolderPicker) {
        val folders by vm.folders.collectAsState()
        AlertDialog(
            onDismissRequest = { showFolderPicker = false },
            title = { Text("폴더 불러오기") },
            text = {
                Column {
                    if (folders.isEmpty()) {
                        Text("오디오 파일이 있는 폴더가 없습니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("선택한 폴더의 모든 곡이 같은 이름의\n재생목록으로 만들어집니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        folders.forEach { folder ->
                            Text(
                                text = "📁 ${folder.displayName} (${folder.trackCount}곡)",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.importFolder(folder)
                                        showFolderPicker = false
                                    }
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFolderPicker = false }) { Text("닫기") }
            }
        )
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("새 재생목록") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    placeholder = { Text("재생목록 이름") }, singleLine = true)
            },
            confirmButton = {
                Button(
                    onClick = { vm.createPlaylist(name); showCreate = false },
                    enabled = name.isNotBlank()
                ) { Text("만들기") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("취소") } }
        )
    }

    renameTarget?.let { playlist ->
        var name by remember(playlist.id) { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("이름 변경") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = { vm.renamePlaylist(playlist.id, name); renameTarget = null },
                    enabled = name.isNotBlank()) { Text("변경") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("취소") } }
        )
    }

    deleteTarget?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("재생목록 삭제") },
            text = { Text("'${playlist.name}' 재생목록을 삭제할까요?\n(음악 파일 자체는 삭제되지 않습니다)") },
            confirmButton = {
                Button(onClick = { vm.deletePlaylist(playlist.id); deleteTarget = null }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } }
        )
    }
}

// ---- 재생목록 상세 화면 ----

@Composable
private fun PlaylistDetailView(vm: PlaylistViewModel, onOpenPlayer: () -> Unit) {
    val selected by vm.selected.collectAsState()
    val items by vm.detailItems.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val nowPlaying by PlayerController.nowPlaying.collectAsState()

    // 뒤로 가기: 상세 → 재생목록 목록으로
    androidx.activity.compose.BackHandler { vm.closeDetail() }

    val isFavorites = selected == PlaylistViewModel.FAVORITES_ID
    val title = if (isFavorites) "♥ 즐겨찾기"
    else playlists.find { it.id == selected }?.name ?: "재생목록"

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = { vm.closeDetail() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (items.isNotEmpty()) {
                TextButton(onClick = { vm.playFrom(0); onOpenPlayer() }) { Text("▶ 전체 재생") }
            }
        }
        HorizontalDivider()

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    if (isFavorites) "라이브러리에서 ♥를 눌러 곡을 추가하세요"
                    else "라이브러리에서 곡 메뉴(⋮) → '재생목록에 추가'로 곡을 담으세요",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, it -> it.itemId }) { index, item ->
                    val isCurrent = nowPlaying?.mediaId == item.track.id.toString()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.playFrom(index); onOpenPlayer() }
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Text(
                            "%d".format(index + 1),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                item.track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${item.track.artist} · ${formatDuration(item.track.durationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "메뉴",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (!isFavorites) {
                                    DropdownMenuItem(text = { Text("위로 이동") },
                                        onClick = { vm.moveItem(index, up = true); menuOpen = false })
                                    DropdownMenuItem(text = { Text("아래로 이동") },
                                        onClick = { vm.moveItem(index, up = false); menuOpen = false })
                                }
                                DropdownMenuItem(
                                    text = { Text(if (isFavorites) "즐겨찾기 해제" else "목록에서 제거") },
                                    onClick = { vm.removeItem(item); menuOpen = false })
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}
