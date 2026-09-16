package com.eok.mp3recorder.ui

import android.app.Application
import android.content.IntentSender
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eok.mp3recorder.data.AudioLibrary
import com.eok.mp3recorder.data.AudioTrack
import com.eok.mp3recorder.data.MediaOps
import com.eok.mp3recorder.data.db.AppDatabase
import com.eok.mp3recorder.data.db.FavoriteEntity
import com.eok.mp3recorder.data.db.PlaylistEntity
import com.eok.mp3recorder.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryFilter(val label: String) {
    MY_RECORDINGS("내 녹음"),
    FAVORITES("즐겨찾기"),
}

enum class SortMode(val label: String) {
    DATE_DESC("최신순"),
    TITLE("이름순"),
    DURATION_DESC("길이순"),
    SIZE_DESC("크기순"),
}

data class LibraryUiState(
    val loading: Boolean = false,
    val tracks: List<AudioTrack> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.MY_RECORDINGS,
    val sort: SortMode = SortMode.DATE_DESC,
    val query: String = "",
    val favoriteIds: Set<Long> = emptySet(),
    val selectedId: Long? = null,   // 최근 저장/선택된 곡 강조 표시
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).dao()

    private val allTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    private val loading = MutableStateFlow(false)
    val filter = MutableStateFlow(LibraryFilter.MY_RECORDINGS)
    val sort = MutableStateFlow(SortMode.DATE_DESC)
    val query = MutableStateFlow("")
    private val selectedId = MutableStateFlow<Long?>(null)

    init {
        // 녹음이 저장되면: 내 녹음 필터로 전환 + 목록 갱신 + 새 파일 선택 표시
        viewModelScope.launch {
            com.eok.mp3recorder.audio.RecordingController.lastSavedMediaId.collect { id ->
                if (id != null) {
                    filter.value = LibraryFilter.MY_RECORDINGS
                    selectedId.value = id
                    refresh()
                }
            }
        }
    }

    private val favoriteIds: StateFlow<Set<Long>> = dao.favorites()
        .map { list -> list.map { it.mediaId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val playlists = dao.playlistsWithCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 파일 이동 대상 폴더 목록 — MediaStore가 허용하는 표준 폴더(Music 등) 아래만 */
    val folders: StateFlow<List<String>> = allTracks
        .map { tracks ->
            tracks.map { it.relativePath }
                .distinct()
                .filter { MediaOps.isMovableFolder(it) }
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 사용자가 시스템 폴더 선택 창에서 직접 고른 폴더 (표준 폴더 밖도 가능) */
    data class CustomFolder(val uri: android.net.Uri, val name: String)

    val customFolders: StateFlow<List<CustomFolder>> =
        com.eok.mp3recorder.data.PlayerPrefs.customFoldersFlow(app)
            .map { set ->
                val granted = app.contentResolver.persistedUriPermissions
                    .filter { it.isWritePermission }
                    .map { it.uri.toString() }
                    .toSet()
                set.filter { it in granted }
                    .map { s ->
                        val uri = android.net.Uri.parse(s)
                        CustomFolder(uri, MediaOps.treeDisplayName(uri))
                    }
                    .sortedBy { it.name.lowercase() }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var treeMoveTrack: AudioTrack? = null

    /** "다른 폴더 직접 선택…" 을 눌렀을 때 — 폴더 선택 창 결과가 올 때까지 대상 곡을 기억 */
    fun beginTreeMove(track: AudioTrack) {
        treeMoveTrack = track
    }

    /** 폴더 선택 창 결과. 권한을 영구 보관하고 목록에 기억한 뒤 이동 실행 */
    fun onTreePicked(treeUri: android.net.Uri?, onNeedDeleteConfirm: (IntentSender) -> Unit) {
        val track = treeMoveTrack
        treeMoveTrack = null
        if (treeUri == null || track == null) return
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            com.eok.mp3recorder.data.PlayerPrefs.addCustomFolder(app, treeUri.toString())
        }
        moveToTree(track, treeUri, onNeedDeleteConfirm)
    }

    fun moveToTree(track: AudioTrack, treeUri: android.net.Uri, onNeedDeleteConfirm: (IntentSender) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = MediaOps.moveToTree(getApplication(), track, treeUri)
                result.newMediaId?.let { newId -> dao.remapMediaId(track.id, newId) }
                refresh()
                toast("이동 완료: ${MediaOps.treeDisplayName(treeUri)}")
                result.pendingDelete?.let { sender ->
                    withContext(Dispatchers.Main) { onNeedDeleteConfirm(sender) }
                }
            } catch (e: SecurityException) {
                // 폴더 권한이 사라진 경우 목록에서 제거
                com.eok.mp3recorder.data.PlayerPrefs.removeCustomFolder(getApplication(), treeUri.toString())
                toast("폴더 접근 권한이 없습니다. 다시 선택해 주세요")
            } catch (e: Exception) {
                toast("이동 실패: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun forgetCustomFolder(folder: CustomFolder) {
        viewModelScope.launch(Dispatchers.IO) {
            com.eok.mp3recorder.data.PlayerPrefs.removeCustomFolder(getApplication(), folder.uri.toString())
        }
    }

    /** 시스템 허용 창을 거친 뒤 다시 실행할 작업 */
    private var pendingRetry: (() -> Unit)? = null

    fun retryPending() {
        val action = pendingRetry
        pendingRetry = null
        action?.invoke()
    }

    fun cancelPending() {
        pendingRetry = null
        viewModelScope.launch { toast("허용하지 않아 변경하지 못했습니다") }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        combine(allTracks, loading, filter) { t, l, f -> Triple(t, l, f) },
        combine(sort, query, favoriteIds) { s, q, fav -> Triple(s, q, fav) },
        selectedId,
    ) { (tracks, isLoading, f), (s, q, fav), sel ->
        var shown = when (f) {
            LibraryFilter.MY_RECORDINGS -> tracks.filter { it.isMyRecording }
            LibraryFilter.FAVORITES -> tracks.filter { it.id in fav }
        }
        if (q.isNotBlank()) {
            shown = shown.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true)
            }
        }
        shown = when (s) {
            SortMode.DATE_DESC -> shown.sortedByDescending { it.dateAddedSec }
            SortMode.TITLE -> shown.sortedBy { it.title.lowercase() }
            SortMode.DURATION_DESC -> shown.sortedByDescending { it.durationMs }
            SortMode.SIZE_DESC -> shown.sortedByDescending { it.sizeBytes }
        }
        LibraryUiState(isLoading, shown, f, s, q, fav, sel)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    /** 오디오 권한이 승인된 뒤에 호출할 것 */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            loading.value = true
            try {
                allTracks.value = AudioLibrary.query(getApplication())
            } finally {
                loading.value = false
            }
        }
    }

    fun setFilter(f: LibraryFilter) { filter.value = f }
    fun setSort(s: SortMode) { sort.value = s }
    fun setQuery(q: String) { query.value = q }

    /** 현재 필터/정렬/검색 결과 전체를 큐로 걸고 해당 곡부터 재생 */
    fun play(track: AudioTrack) {
        val shown = uiState.value.tracks
        val index = shown.indexOfFirst { it.id == track.id }
        if (index >= 0) {
            selectedId.value = track.id
            viewModelScope.launch(Dispatchers.Main) {
                PlayerController.playQueue(shown, index)
            }
        }
    }

    fun toggleFavorite(track: AudioTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            if (track.id in favoriteIds.value) dao.removeFavorite(track.id)
            else dao.addFavorite(FavoriteEntity(track.id, System.currentTimeMillis()))
        }
    }

    /**
     * 이름 변경. 소유하지 않은 파일이면 [onNeedPermission]으로 시스템 허용 창을 띄우고,
     * 허용되면 retryPending()으로 한 번 더 시도한다.
     */
    fun rename(
        track: AudioTrack,
        newName: String,
        onNeedPermission: (IntentSender) -> Unit,
        isRetry: Boolean = false,
    ) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sender = MediaOps.rename(getApplication(), track, trimmed)
                when {
                    sender == null -> {
                        refresh()
                        toast("이름을 변경했습니다")
                    }
                    isRetry -> toast("권한이 없어 이름을 바꾸지 못했습니다")
                    else -> {
                        pendingRetry = { rename(track, newName, onNeedPermission, isRetry = true) }
                        withContext(Dispatchers.Main) { onNeedPermission(sender) }
                    }
                }
            } catch (e: Exception) {
                toast("이름 변경 실패: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 삭제. 시스템 승인이 필요하면 [onNeedConfirm]으로 IntentSender를 넘긴다 —
     * UI가 승인 다이얼로그를 띄운 뒤 결과 OK면 refresh()를 호출한다.
     */
    fun delete(track: AudioTrack, onNeedConfirm: (IntentSender) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sender = MediaOps.delete(getApplication(), track)
                if (sender == null) {
                    refresh()
                    toast("삭제했습니다")
                } else {
                    withContext(Dispatchers.Main) { onNeedConfirm(sender) }
                }
            } catch (e: Exception) {
                toast("삭제 실패: ${e.message}")
            }
        }
    }

    /**
     * 다른 폴더로 이동. [relativePath] 예: "Music/회의녹음"
     * 복사 방식으로 이동된 경우 재생목록/즐겨찾기 참조를 새 ID로 갱신한다.
     * 원본 삭제에 시스템 승인이 필요하면 [onNeedConfirm]으로 IntentSender를 넘긴다.
     */
    fun moveToFolder(
        track: AudioTrack,
        relativePath: String,
        onNeedPermission: (IntentSender) -> Unit,
        onNeedDeleteConfirm: (IntentSender) -> Unit,
        isRetry: Boolean = false,
    ) {
        val target = relativePath.trim()
        if (target.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = MediaOps.move(getApplication(), track, target)
                val write = result.pendingWrite
                if (write != null) {
                    if (isRetry) {
                        toast("권한이 없어 이동하지 못했습니다")
                    } else {
                        pendingRetry = {
                            moveToFolder(track, relativePath, onNeedPermission, onNeedDeleteConfirm, isRetry = true)
                        }
                        withContext(Dispatchers.Main) { onNeedPermission(write) }
                    }
                    return@launch
                }
                result.newMediaId?.let { newId -> dao.remapMediaId(track.id, newId) }
                refresh()
                toast("이동 완료: ${target.trimEnd('/')}")
                result.pendingDelete?.let { sender ->
                    withContext(Dispatchers.Main) { onNeedDeleteConfirm(sender) }
                }
            } catch (e: Exception) {
                toast("이동 실패: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun share(track: AudioTrack) {
        try {
            MediaOps.share(getApplication(), track)
        } catch (e: Exception) {
            viewModelScope.launch { toast("공유 실패: ${e.message}") }
        }
    }

    fun addToPlaylist(playlistId: Long, track: AudioTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.addToPlaylist(playlistId, track.id)
            toast("재생목록에 추가했습니다")
        }
    }

    fun createPlaylistAndAdd(name: String, track: AudioTrack) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = dao.createPlaylist(PlaylistEntity(name = trimmed, createdAt = System.currentTimeMillis()))
            dao.addToPlaylist(id, track.id)
            toast("'$trimmed' 재생목록에 추가했습니다")
        }
    }

    private suspend fun toast(msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
    }
}
