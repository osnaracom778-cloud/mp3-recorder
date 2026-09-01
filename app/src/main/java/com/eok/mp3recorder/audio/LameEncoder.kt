package com.eok.mp3recorder.audio

/**
 * LAME MP3 인코더 JNI 래퍼.
 *
 * 사용 순서: init() → encode() 반복 → flush() → close()
 * 일시정지는 단순히 encode() 호출을 멈추면 된다 — 인코더 상태가 유지되므로
 * 재개 시 이어서 encode()하면 하나의 연속된 MP3 스트림이 된다.
 */
class LameEncoder {

    private var handle: Long = 0

    val isOpen: Boolean get() = handle != 0L

    fun init(sampleRate: Int, channels: Int, bitrateKbps: Int) {
        check(handle == 0L) { "encoder already initialized" }
        handle = nativeInit(sampleRate, channels, bitrateKbps)
        check(handle != 0L) { "lame_init failed (rate=$sampleRate ch=$channels br=$bitrateKbps)" }
    }

    /** PCM 16bit 샘플을 인코딩. mp3Buf에 쓴 바이트 수를 반환. */
    fun encode(pcm: ShortArray, sampleCount: Int, mp3Buf: ByteArray): Int {
        check(handle != 0L) { "encoder not initialized" }
        val n = nativeEncode(handle, pcm, sampleCount, mp3Buf)
        check(n >= 0) { "lame_encode failed: $n" }
        return n
    }

    /** 남은 프레임을 모두 내보낸다. 녹음 정지 시 호출. */
    fun flush(mp3Buf: ByteArray): Int {
        check(handle != 0L) { "encoder not initialized" }
        val n = nativeFlush(handle, mp3Buf)
        check(n >= 0) { "lame_flush failed: $n" }
        return n
    }

    fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0
        }
    }

    private external fun nativeGetVersion(): String
    private external fun nativeInit(sampleRate: Int, channels: Int, bitrateKbps: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, sampleCount: Int, mp3Buf: ByteArray): Int
    private external fun nativeFlush(handle: Long, mp3Buf: ByteArray): Int
    private external fun nativeClose(handle: Long)

    fun version(): String = nativeGetVersion()

    companion object {
        init {
            System.loadLibrary("lamejni")
        }

        /** 인코딩 출력 버퍼 권장 크기: 1.25 * samples + 7200 (LAME 문서 기준) */
        fun recommendedOutBufSize(samplesPerChunk: Int): Int =
            (samplesPerChunk * 1.25).toInt() + 7200
    }
}
