#include <jni.h>
#include <android/log.h>
#include "lame.h"

#define LOG_TAG "LameJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_eok_mp3recorder_audio_LameEncoder_nativeGetVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(get_lame_version());
}

// Returns an opaque handle to the encoder, or 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_eok_mp3recorder_audio_LameEncoder_nativeInit(JNIEnv *, jobject,
                                                      jint sampleRate, jint channels,
                                                      jint bitrateKbps) {
    lame_t gf = lame_init();
    if (gf == nullptr) return 0;
    lame_set_in_samplerate(gf, sampleRate);
    lame_set_num_channels(gf, channels);
    lame_set_out_samplerate(gf, sampleRate);
    lame_set_mode(gf, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_brate(gf, bitrateKbps);
    lame_set_VBR(gf, vbr_off);  // CBR: A-B 반복 시크 정밀도 확보
    lame_set_quality(gf, 5);
    if (lame_init_params(gf) < 0) {
        LOGE("lame_init_params failed");
        lame_close(gf);
        return 0;
    }
    return reinterpret_cast<jlong>(gf);
}

// Encodes PCM 16-bit samples. Mono: samples in left buffer. Returns bytes written to mp3Buf, or negative on error.
JNIEXPORT jint JNICALL
Java_com_eok_mp3recorder_audio_LameEncoder_nativeEncode(JNIEnv *env, jobject,
                                                        jlong handle, jshortArray pcm,
                                                        jint sampleCount, jbyteArray mp3Buf) {
    auto gf = reinterpret_cast<lame_t>(handle);
    if (gf == nullptr) return -1;

    jshort *pcmBuf = env->GetShortArrayElements(pcm, nullptr);
    jbyte *out = env->GetByteArrayElements(mp3Buf, nullptr);
    jsize outSize = env->GetArrayLength(mp3Buf);

    int channels = lame_get_num_channels(gf);
    int written;
    if (channels == 1) {
        written = lame_encode_buffer(gf, pcmBuf, nullptr, sampleCount,
                                     reinterpret_cast<unsigned char *>(out), outSize);
    } else {
        written = lame_encode_buffer_interleaved(gf, pcmBuf, sampleCount / 2,
                                                 reinterpret_cast<unsigned char *>(out), outSize);
    }

    env->ReleaseShortArrayElements(pcm, pcmBuf, JNI_ABORT);
    env->ReleaseByteArrayElements(mp3Buf, out, 0);
    return written;
}

// Flushes remaining frames. Returns bytes written.
JNIEXPORT jint JNICALL
Java_com_eok_mp3recorder_audio_LameEncoder_nativeFlush(JNIEnv *env, jobject,
                                                       jlong handle, jbyteArray mp3Buf) {
    auto gf = reinterpret_cast<lame_t>(handle);
    if (gf == nullptr) return -1;
    jbyte *out = env->GetByteArrayElements(mp3Buf, nullptr);
    jsize outSize = env->GetArrayLength(mp3Buf);
    int written = lame_encode_flush(gf, reinterpret_cast<unsigned char *>(out), outSize);
    env->ReleaseByteArrayElements(mp3Buf, out, 0);
    return written;
}

JNIEXPORT void JNICALL
Java_com_eok_mp3recorder_audio_LameEncoder_nativeClose(JNIEnv *, jobject, jlong handle) {
    auto gf = reinterpret_cast<lame_t>(handle);
    if (gf != nullptr) lame_close(gf);
}

} // extern "C"
