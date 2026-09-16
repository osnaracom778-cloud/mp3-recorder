package com.eok.mp3recorder.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/** MediaStore 파일 조작. 다른 앱이 만든 파일은 시스템 승인(IntentSender)이 필요할 수 있다. */
object MediaOps {

    private const val TAG = "MediaOps"

    /**
     * 이름 변경. 이 앱이 만든 파일(내 녹음)은 바로 성공한다.
     * 다른 앱 파일이면 SecurityException — 호출자가 안내 메시지를 띄운다.
     */
    fun rename(context: Context, track: AudioTrack, newName: String) {
        val ext = track.displayName.substringAfterLast('.', "mp3")
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$newName.$ext")
            put(MediaStore.Audio.Media.TITLE, newName)
        }
        val updated = context.contentResolver.update(track.contentUri, values, null, null)
        if (updated <= 0) throw IllegalStateException("이름을 변경하지 못했습니다")
    }

    /**
     * 삭제 시도. 바로 삭제되면 null 반환.
     * 시스템 승인이 필요하면 IntentSender 반환 — UI가 런처로 실행한 뒤 다시 삭제하면 된다.
     */
    fun delete(context: Context, track: AudioTrack): IntentSender? =
        deleteUri(context, track.contentUri)

    private fun deleteUri(context: Context, uri: android.net.Uri): IntentSender? {
        return try {
            context.contentResolver.delete(uri, null, null)
            null
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
            } else {
                (e as? RecoverableSecurityException)
                    ?.userAction?.actionIntent?.intentSender
                    ?: throw e
            }
        }
    }

    /** 폴더 이동 결과 */
    data class MoveResult(
        /** 복사 방식으로 이동되어 MediaStore ID가 바뀐 경우 새 ID */
        val newMediaId: Long? = null,
        /** 원본 삭제에 시스템 승인이 필요한 경우 */
        val pendingDelete: IntentSender? = null,
    )

    /**
     * 다른 폴더로 이동. [newRelativePath] 예: "Music/회의녹음"
     *
     * 1차: RELATIVE_PATH 갱신 (MediaStore가 파일을 옮김 — 가장 빠르고 ID 유지)
     * 2차: 기기/파일에 따라 1차가 거부되면 새 위치에 복사 후 원본 삭제로 대체
     *      (어떤 기기에서도 동작하지만 ID가 바뀌므로 호출자가 DB 참조를 갱신해야 함)
     */
    fun move(context: Context, track: AudioTrack, newRelativePath: String): MoveResult {
        val normalized = newRelativePath.trim().trim('/') + "/"
        if (normalized == track.relativePath) return MoveResult()

        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.RELATIVE_PATH, normalized)
            }
            val updated = context.contentResolver.update(track.contentUri, values, null, null)
            if (updated > 0) return MoveResult()
            Log.w(TAG, "RELATIVE_PATH update returned 0, falling back to copy")
        } catch (e: Exception) {
            Log.w(TAG, "RELATIVE_PATH update failed (${e.javaClass.simpleName}: ${e.message}), falling back to copy")
        }
        return copyAndDelete(context, track, normalized)
    }

    private fun copyAndDelete(context: Context, track: AudioTrack, relativePath: String): MoveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, track.displayName)
            put(MediaStore.Audio.Media.TITLE, track.title)
            put(MediaStore.Audio.Media.MIME_TYPE, track.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val newUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("새 위치에 파일을 만들지 못했습니다")

        try {
            val input = resolver.openInputStream(track.contentUri)
                ?: throw IllegalStateException("원본 파일을 열 수 없습니다")
            val output = resolver.openOutputStream(newUri)
                ?: throw IllegalStateException("새 파일에 쓸 수 없습니다")
            input.use { i -> output.use { o -> i.copyTo(o) } }

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(newUri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(newUri, null, null)
            throw e
        }

        val pendingDelete = deleteUri(context, track.contentUri)
        return MoveResult(newMediaId = ContentUris.parseId(newUri), pendingDelete = pendingDelete)
    }

    /** 공유 시트 열기 (카톡·이메일 등) */
    fun share(context: Context, track: AudioTrack) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = track.mimeType
            putExtra(Intent.EXTRA_STREAM, track.contentUri)
            putExtra(Intent.EXTRA_SUBJECT, track.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
