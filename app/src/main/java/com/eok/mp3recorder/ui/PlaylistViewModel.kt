package com.eok.mp3recorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eok.mp3recorder.data.AudioLibrary
import com.eok.mp3recorder.data.AudioTrack
import com.eok.mp3recorder.data.db.AppDatabase
import com.eok.mp3recorder.data.db.PlaylistEntity
import com.eok.mp3recorder.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 오디오 파일이 있는 기기 폴더 */
data class FolderInfo(
    val path: String,
    val trackCount: Int,
) {
    /** "Music/MP3녹음기/" → "MP3녹음기" */
    val displayName: String
        get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { "(루트)" }
}

/** 재생목록 상세의 한 줄 (Room 항목 + 라이브러리 트랙 정보) */
data class PlaylistDetailItem(
    val itemId: Long,      // playlist_items PK (즐겨찾기 뷰에서는 mediaId)
    val position: Int,
    val track: AudioTrack,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** 가상 재생목록: 즐겨찾기 */
        const val FAVORITES_ID = -1L
    }

    private val dao = AppDatabase.get(app).dao()
    private val allTracks = MutableStateFlow<List<AudioTrack>>(emptyList())

    val playlists = dao.playlistsWithCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoritesCount: StateFlow<Int> = dao.favorites()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 기기 내 오디오가 있는 폴더 목록 (폴더 불러오기용) */
    val folders: StateFlow<List<FolderInfo>> = allTracks
        .map { tracks ->
            tracks.groupBy { it.relativePath }
                .map { (path, list) -> FolderInfo(path, list.size) }
                .sortedBy { it.path.lowercase() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** null = 목록 화면, FAVORITES_ID = 즐겨찾기, 그 외 = 해당 재생목록 상세 */
    val selected = MutableStateFlow<Long?>(null)

    val detailItems: StateFlow<List<PlaylistDetailItem>> =
        combine(selected, allTracks) { id, tracks -> id to tracks }
            .flatMapLatest { (id, tracks) ->
                when (id) {
                    null -> flowOf(emptyList())
                    FAVORITES_ID -> dao.favorites().map { favs ->
                        favs.mapIndexedNotNull { index, f ->
                            tracks.find { it.id == f.mediaId }
                                ?.let { PlaylistDetailItem(f.mediaId, index, it) }
                        }
                    }
                    else -> dao.playlistItems(id).map { items ->
                        items.mapNotNull { item ->
                            tracks.find { it.id == item.mediaId }
                                ?.let { PlaylistDetailItem(item.id, item.position, it) }
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun refreshTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            allTracks.value = AudioLibrary.query(getApplication())
        }
    }

    fun open(playlistId: Long) { selected.value = playlistId }
    fun closeDetail() { selected.value = null }

    /** 폴더의 모든 곡을 새 재생목록으로 가져온다 (재생목록 이름 = 폴더 이름) */
    fun importFolder(folder: FolderInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = allTracks.value
                .filter { it.relativePath == folder.path }
                .sortedBy { it.title.lowercase() }
            if (tracks.isEmpty()) return@launch
            val id = dao.createPlaylist(
                PlaylistEntity(name = folder.displayName, createdAt = System.currentTimeMillis())
            )
            tracks.forEach { dao.addToPlaylist(id, it.id) }
        }
    }

    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.createPlaylist(PlaylistEntity(name = trimmed, createdAt = System.currentTimeMillis()))
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { dao.renamePlaylist(id, trimmed) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deletePlaylist(id)
            if (selected.value == id) selected.value = null
        }
    }

    /** 재생목록에서 제거 (즐겨찾기 뷰에서는 즐겨찾기 해제) */
    fun removeItem(item: PlaylistDetailItem) {
        viewModelScope.launch(Dispatchers.IO) {
            if (selected.value == FAVORITES_ID) dao.removeFavorite(item.track.id)
            else dao.removeItem(item.itemId)
        }
    }

    /** 위/아래로 이동 (인접 항목과 순서 교환) — 사용자 재생목록에서만 */
    fun moveItem(index: Int, up: Boolean) {
        val items = detailItems.value
        val other = if (up) index - 1 else index + 1
        if (selected.value == FAVORITES_ID) return
        if (index !in items.indices || other !in items.indices) return
        val a = items[index]
        val b = items[other]
        viewModelScope.launch(Dispatchers.IO) {
            dao.swapItems(a.itemId, a.position, b.itemId, b.position)
        }
    }

    fun playFrom(index: Int) {
        val tracks = detailItems.value.map { it.track }
        if (index in tracks.indices) {
            viewModelScope.launch(Dispatchers.Main) {
                PlayerController.playQueue(tracks, index)
            }
        }
    }
}
