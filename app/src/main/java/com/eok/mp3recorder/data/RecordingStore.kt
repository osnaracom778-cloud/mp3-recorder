package com.eok.mp3recorder.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 완성된 MP3 임시 파일을 MediaStore(Music/MP3녹음기)에 확정 저장한다.
 * API 29+ Scoped Storage 방식이라 별도 저장소 권한이 필요 없다.
 */
object RecordingStore {

    val RELATIVE_DIR = "${Environment.DIRECTORY_MUSIC}/MP3녹음기"

    /** 저장 성공 시 MediaStore Uri 반환. [displayName]은 확장자 제외한 이름. */
    fun save(context: Context, tempFile: File, displayName: String, durationMs: Long): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$displayName.mp3")
            put(MediaStore.Audio.Media.TITLE, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Audio.Media.DURATION, durationMs)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert 실패")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("출력 스트림 열기 실패")

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            tempFile.delete()
        }
        return uri
    }
}
