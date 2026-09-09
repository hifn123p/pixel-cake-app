#include <jni.h>
#include <string>

// P1b raw bridge —— 占位实现。
// 本提交只验证 NDK/CMake/CI 管线能否产出 librawbridge.so 并成功 loadLibrary。
// 后续提交（P1b-1/2）在此接入 LibRaw，实现 ARW 全量解码 → RGBA 缓冲。

extern "C" JNIEXPORT jstring JNICALL
Java_com_hifn_pixelcake_core_decode_RawNative_getVersion(JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF("rawbridge-stub-0.1");
}
