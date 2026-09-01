package com.eok.mp3recorder.data

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore

/** MediaStore 파일 조작. 다른 앱이 만든 파일은 시스템 승인(IntentSender)이 필요할 수 있다. */
object MediaOps {

    /**
     * 이름 변경. 이 앱이 만든 파일(내 녹음)은 바로 성공한다.
     * 다른 앱 파일이면 SecurityException — 호출자가 안내 메시지를 띄운다.
     */
    fun rename(context: Context, track: AudioTrack, newName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$newName.mp3")
            put(MediaStore.Audio.Media.TITLE, newName)
        }
        val updated = context.contentResolver.update(track.contentUri, values, null, null)
        if (updated <= 0) throw IllegalStateException("이름을 변경하지 못했습니다")
    }

    /**
     * 삭제 시도. 바로 삭제되면 null 반환.
     * 시스템 승인이 필요하면 IntentSender 반환 — UI가 런처로 실행한 뒤 다시 삭제하면 된다.
     */
    fun delete(context: Context, track: AudioTrack): IntentSender? {
        return try {
            context.contentResolver.delete(track.contentUri, null, null)
            null
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(
                    context.contentResolver, listOf(track.contentUri)
                ).intentSender
            } else {
                (e as? RecoverableSecurityException)
                    ?.userAction?.actionIntent?.intentSender
                    ?: throw e
            }
        }
    }

    /** 공유 시트 열기 (카톡·이메일 등) */
    fun share(context: Context, track: AudioTrack) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, track.contentUri)
            putExtra(Intent.EXTRA_SUBJECT, track.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
