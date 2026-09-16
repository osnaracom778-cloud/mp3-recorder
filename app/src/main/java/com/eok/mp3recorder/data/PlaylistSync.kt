package com.eok.mp3recorder.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.eok.mp3recorder.data.db.AppDatabase

/**
 * 폴더와 연결된 재생목록을 기기 폴더와 동기화한다.
 * 폴더에 새로 생긴 오디오 파일을 재생목록 끝에 자동 추가 (삭제된 파일은 표시 시 걸러짐).
 */
object PlaylistSync {

    private fun hasAudioPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** 추가된 곡 수를 반환. [tracks]를 넘기면 재조회를 생략한다. */
    suspend fun syncFolders(context: Context, tracks: List<AudioTrack>? = null): Int {
        if (!hasAudioPermission(context)) return 0
        val dao = AppDatabase.get(context.applicationContext).dao()
        val folderPlaylists = dao.folderPlaylists()
        if (folderPlaylists.isEmpty()) return 0

        val all = tracks ?: AudioLibrary.query(context)
        var added = 0
        for (playlist in folderPlaylists) {
            val folder = playlist.folderPath ?: continue
            val existing = dao.itemMediaIds(playlist.id).toHashSet()
            val newTracks = all
                .filter { it.relativePath == folder && it.id !in existing }
                .sortedBy { it.dateAddedSec }   // 추가된 순서대로 끝에 붙임
            newTracks.forEach { dao.addToPlaylist(playlist.id, it.id) }
            added += newTracks.size
        }
        return added
    }
}
