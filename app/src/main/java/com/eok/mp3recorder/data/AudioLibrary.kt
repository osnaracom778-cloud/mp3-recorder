package com.eok.mp3recorder.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class AudioTrack(
    val id: Long,
    val contentUri: Uri,
    val title: String,
    val displayName: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val relativePath: String,
    val mimeType: String,
    /** 이 앱이 만든 파일인지 (MediaStore owner_package_name 기준) */
    val isOwned: Boolean,
) {
    /** 이 앱으로 녹음한 파일인지 — 다른 폴더로 옮겨도 소유권으로 판별 */
    val isMyRecording: Boolean get() = isOwned || relativePath.contains("MP3녹음기")
}

/** 기기 전체 오디오 파일을 MediaStore에서 조회한다. */
object AudioLibrary {

    fun query(context: Context): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        val myPackage = context.packageName
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.OWNER_PACKAGE_NAME,
        )
        // 1초 미만(효과음 등) 제외
        val selection = "${MediaStore.Audio.Media.DURATION} >= 1000"

        context.contentResolver.query(
            collection, projection, selection, null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val ownerCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.OWNER_PACKAGE_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val rawTitle = cursor.getString(titleCol)
                val displayName = cursor.getString(nameCol) ?: ""
                val stem = displayName.substringBeforeLast('.')
                val isOwned = cursor.getString(ownerCol) == myPackage
                tracks += AudioTrack(
                    id = id,
                    contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    ),
                    // 내 녹음 파일은 파일명을 그대로 표시 — 이름을 바꿔도 MediaStore TITLE은
                    // 갱신되지 않는 기기가 있어서, 파일명이 항상 진실이다
                    title = if (isOwned) stem
                    else rawTitle?.takeIf { it.isNotBlank() } ?: stem,
                    displayName = displayName,
                    artist = cursor.getString(artistCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "알 수 없는 아티스트",
                    album = cursor.getString(albumCol) ?: "",
                    durationMs = cursor.getLong(durCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    dateAddedSec = cursor.getLong(dateCol),
                    relativePath = cursor.getString(pathCol) ?: "",
                    mimeType = cursor.getString(mimeCol) ?: "audio/mpeg",
                    isOwned = isOwned,
                )
            }
        }
        return tracks
    }
}
