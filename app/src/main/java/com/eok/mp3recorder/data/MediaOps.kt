package com.eok.mp3recorder.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

/** MediaStore 파일 조작. 다른 앱이 만든 파일은 시스템 승인(IntentSender)이 필요할 수 있다. */
object MediaOps {

    private const val TAG = "MediaOps"

    /**
     * 이름 변경. 바로 성공하면 null.
     * 앱이 소유하지 않은 파일(재설치로 소유권을 잃은 예전 녹음 등)이면 시스템 허용 창용
     * IntentSender를 반환 — UI가 띄우고 사용자가 허용하면 다시 호출한다.
     */
    fun rename(context: Context, track: AudioTrack, newName: String): IntentSender? {
        val ext = track.displayName.substringAfterLast('.', "mp3")
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$newName.$ext")
            put(MediaStore.Audio.Media.TITLE, newName)
        }
        return try {
            val updated = context.contentResolver.update(track.contentUri, values, null, null)
            if (updated <= 0) throw IllegalStateException("이름을 변경하지 못했습니다")
            null
        } catch (e: SecurityException) {
            writeRequestSender(context, track.contentUri, e)
        }
    }

    /** 소유하지 않은 파일 수정 권한을 사용자에게 요청하는 IntentSender */
    private fun writeRequestSender(context: Context, uri: android.net.Uri, e: SecurityException): IntentSender {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
        } else {
            (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender ?: throw e
        }
    }

    /** MediaStore의 영문 오류를 사용자용 문구로 */
    private fun friendlyMessage(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("Primary directory", ignoreCase = true) ||
                msg.contains("not allowed", ignoreCase = true) ->
                "표준 음악 폴더(Music, Recordings 등) 아래로만 이동할 수 있습니다"
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
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

    /** 오디오 파일을 넣을 수 있는 표준 최상위 폴더 (MediaStore 제약) */
    val ALLOWED_PRIMARY_DIRS = setOf(
        "Music", "Recordings", "Podcasts", "Audiobooks", "Alarms", "Notifications", "Ringtones"
    )

    fun isMovableFolder(relativePath: String): Boolean =
        relativePath.substringBefore('/') in ALLOWED_PRIMARY_DIRS

    /** 폴더 이동 결과 */
    data class MoveResult(
        /** 복사 방식으로 이동되어 MediaStore ID가 바뀐 경우 새 ID */
        val newMediaId: Long? = null,
        /** 원본 삭제에 시스템 승인이 필요한 경우 */
        val pendingDelete: IntentSender? = null,
        /** 파일 수정 권한(소유하지 않은 파일)이 필요한 경우 — 허용 후 다시 move() */
        val pendingWrite: IntentSender? = null,
    )

    /**
     * 다른 폴더로 이동. [newRelativePath] 예: "Music/회의녹음"
     *
     * 1차: RELATIVE_PATH 갱신 (MediaStore가 파일을 옮김 — 가장 빠르고 ID 유지)
     *   - 소유하지 않은 파일이면 pendingWrite 반환 → 사용자 허용 후 재시도
     *   - 표준 폴더 밖 등 잘못된 대상이면 예외
     * 2차: 그 밖의 이유로 거부되면 새 위치에 복사 후 원본 삭제로 대체
     *      (ID가 바뀌므로 호출자가 DB 참조를 갱신해야 함)
     */
    fun move(context: Context, track: AudioTrack, newRelativePath: String): MoveResult {
        val normalized = newRelativePath.trim().trim('/') + "/"
        if (normalized == track.relativePath) return MoveResult()
        if (!isMovableFolder(normalized)) {
            throw IllegalArgumentException("표준 음악 폴더(Music, Recordings 등) 아래로만 이동할 수 있습니다")
        }

        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.RELATIVE_PATH, normalized)
            }
            val updated = context.contentResolver.update(track.contentUri, values, null, null)
            if (updated > 0) return MoveResult()
            Log.w(TAG, "RELATIVE_PATH update returned 0, falling back to copy")
        } catch (e: SecurityException) {
            return MoveResult(pendingWrite = writeRequestSender(context, track.contentUri, e))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(friendlyMessage(e))
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
        val newUri = try {
            resolver.insert(collection, values)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(friendlyMessage(e))
        } ?: throw IllegalStateException("새 위치에 파일을 만들지 못했습니다")

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

    // ---- 직접 선택한 폴더(SAF 트리)로 이동 — 표준 폴더 제한을 받지 않는다 ----

    /** 트리 URI에서 표시용 폴더 이름 ("primary:1_내 라이브러리" → "1_내 라이브러리") */
    fun treeDisplayName(treeUri: android.net.Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return "폴더"
        val rel = docId.substringAfter(':', "")
        return rel.trimEnd('/').substringAfterLast('/').ifEmpty {
            if (docId.startsWith("primary")) "내장 메모리" else "외장 메모리"
        }
    }

    /** 문서 ID를 실제 경로로 (미디어 스캔용). "primary:a/b" → /storage/emulated/0/a/b */
    private fun docIdToPath(docId: String): String? {
        val volume = docId.substringBefore(':', "")
        val rel = docId.substringAfter(':', "")
        if (volume.isEmpty()) return null
        return if (volume == "primary") "/storage/emulated/0/$rel" else "/storage/$volume/$rel"
    }

    /**
     * 사용자가 시스템 폴더 선택 창에서 고른 폴더로 이동: 복사 → 미디어 스캔 → 원본 삭제.
     * 원본 삭제에 승인이 필요하면 pendingDelete 반환.
     */
    fun moveToTree(context: Context, track: AudioTrack, treeUri: android.net.Uri): MoveResult {
        val resolver = context.contentResolver
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val newDoc = DocumentsContract.createDocument(resolver, parentDoc, track.mimeType, track.displayName)
            ?: throw IllegalStateException("선택한 폴더에 파일을 만들 수 없습니다")

        try {
            val input = resolver.openInputStream(track.contentUri)
                ?: throw IllegalStateException("원본 파일을 열 수 없습니다")
            val output = resolver.openOutputStream(newDoc)
                ?: throw IllegalStateException("새 파일에 쓸 수 없습니다")
            input.use { i -> output.use { o -> i.copyTo(o) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, newDoc) }
            throw e
        }

        // 새 파일이 라이브러리에 바로 보이도록 미디어 스캔 요청 (최대 10초 대기)
        var newMediaId: Long? = null
        val path = runCatching { docIdToPath(DocumentsContract.getDocumentId(newDoc)) }.getOrNull()
        if (path != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var scanned: android.net.Uri? = null
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(path), arrayOf(track.mimeType)
            ) { _, uri -> scanned = uri; latch.countDown() }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            // MediaStore의 files/audio 테이블 ID는 동일하다
            newMediaId = scanned?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
        }

        val pendingDelete = deleteUri(context, track.contentUri)
        return MoveResult(newMediaId = newMediaId, pendingDelete = pendingDelete)
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
