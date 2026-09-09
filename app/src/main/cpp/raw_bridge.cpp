#include <jni.h>
#include <android/log.h>
#include <libraw/libraw.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <new>

#define LOG_TAG "rawbridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// P1b raw bridge —— 基于 LibRaw 的 ARW 全量解马赛克。
// 仅面向 arm64-v8a（一加15）。
//
// 管线约定（FIX_LIST F01）：
//   这里只负责「把 RAW 变成 16-bit 线性 sRGB」，**不做** 白平衡基线以外的任何色调决策：
//   不自动亮度、不 gamma 编码、不降到 8-bit。白平衡/曝光/曲线/滤镜全部由 Kotlin 的
//   参数栈在线性域可逆地重放，因此同一组参数在任意照片上可复现。
//
// 内存约定（FIX_LIST F05）：
//   33MP 的 16-bit 三通道处理结果约 196MB，一次性回传 JVM 必然 OOM。
//   因此结果留在 native 侧，由 Kotlin 分带（row band）取走：
//   openLinear -> readLinearRows(...) x N -> closeLinear。

namespace {

/** 一次线性解码会话。句柄以 jlong 形式交给 Kotlin，用完必须 closeLinear。 */
struct LinearSession {
    libraw_processed_image_t* image;  // 16-bit 线性 sRGB，colors == 3
    int srcWidth;                     // LibRaw 输出宽
    int srcHeight;                    // LibRaw 输出高
    int width;                        // 目标输出宽（已按 maxLongSide 缩放）
    int height;                       // 目标输出高
    int colors;
    float invX;                       // 目标 x -> 源 x 的比例
    float invY;                       // 目标 y -> 源 y 的比例
    float camMul[4];                  // 相机白平衡乘子（按绿通道归一），回传 Kotlin 作为初始 WB
};

inline LinearSession* asSession(jlong handle) {
    return reinterpret_cast<LinearSession*>(static_cast<intptr_t>(handle));
}

inline const unsigned short* rowPtr(const LinearSession* s, int y) {
    const unsigned char* base = reinterpret_cast<const unsigned char*>(s->image->data);
    return reinterpret_cast<const unsigned short*>(
        base + static_cast<size_t>(y) * static_cast<size_t>(s->srcWidth) *
               static_cast<size_t>(s->colors) * 2u);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_hifn_pixelcake_core_decode_RawNative_getVersion(JNIEnv* env, jclass /*clazz*/) {
    // LibRaw 版本宏（如 "0.21.0" 或 master 的 "0.22.0-快照" 串）
    return env->NewStringUTF("LibRaw " LIBRAW_VERSION_STR);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hifn_pixelcake_core_decode_RawNative_openLinear(
        JNIEnv* env, jclass /*clazz*/, jstring jpath, jint jmaxLongSide) {

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;

    LibRaw* rp = new (std::nothrow) LibRaw();
    if (!rp) {
        env->ReleaseStringUTFChars(jpath, path);
        return 0;
    }

    int ret = rp->open_file(path);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("openLinear: open_file failed ret=%d", ret);
        env->ReleaseStringUTFChars(jpath, path);
        delete rp;
        return 0;
    }

    // F01：16-bit 线性输出。
    //  - output_bps=16 + gamm={1,1} + no_auto_bright=1 => LibRaw 内部 curve[i]==i，
    //    输出即「线性 sRGB，白点 = 65535」，不含任何自动曝光或 gamma 编码。
    //  - use_camera_wb 只作为初始白平衡基线（否则画面会偏绿）；
    //    乘子经 linearMeta() 回传 Kotlin，温度/色调在其之上做相对调整。
    rp->imgdata.params.use_camera_wb   = 1;
    rp->imgdata.params.use_auto_wb     = 0;
    rp->imgdata.params.output_color    = 1;     // 1 = sRGB 色彩空间
    rp->imgdata.params.output_bps      = 16;    // 16-bit
    rp->imgdata.params.no_auto_bright  = 1;     // 关闭自动亮度：曝光基准不随画面内容漂移
    rp->imgdata.params.gamm[0]         = 1.0f;  // 线性 gamma（curve[i] == i）
    rp->imgdata.params.gamm[1]         = 1.0f;
    rp->imgdata.params.user_qual       = 3;     // 3 = AHD 高质量解马赛克
    rp->imgdata.params.output_tiff     = 0;
    rp->imgdata.params.half_size       = 0;

    ret = rp->unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("openLinear: unpack failed ret=%d", ret);
        env->ReleaseStringUTFChars(jpath, path);
        delete rp;
        return 0;
    }

    ret = rp->dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("openLinear: dcraw_process failed ret=%d", ret);
        env->ReleaseStringUTFChars(jpath, path);
        delete rp;
        return 0;
    }

    int err = 0;
    libraw_processed_image_t* image = rp->dcraw_make_mem_image(&err);
    env->ReleaseStringUTFChars(jpath, path);
    if (!image || err != LIBRAW_SUCCESS) {
        LOGE("openLinear: dcraw_make_mem_image failed err=%d", err);
        // dcraw_make_mem_image() 返回的缓冲区必须用 dcraw_clear_mem() 释放（F02）；
        // free_image() 只释放 LibRaw 内部的 imgdata.image，释放不了这一块。
        if (image) LibRaw::dcraw_clear_mem(image);
        delete rp;
        return 0;
    }
    if (image->bits != 16 || image->colors < 3 || image->width <= 0 || image->height <= 0) {
        LOGE("openLinear: unexpected format bits=%d colors=%d %dx%d",
             (int)image->bits, (int)image->colors, (int)image->width, (int)image->height);
        LibRaw::dcraw_clear_mem(image);
        delete rp;
        return 0;
    }

    // 先把相机白平衡乘子取出来（linearMeta 用），再释放内部缓冲。
    float camMul[4];
    for (int c = 0; c < 4; ++c) camMul[c] = (float)rp->imgdata.color.cam_mul[c];
    const float g0 = (camMul[1] != 0.f) ? camMul[1] : 1.f;
    for (int c = 0; c < 4; ++c) camMul[c] /= g0;

    // 释放 LibRaw 内部 4 通道 ushort 缓冲（33MP 约 262MB），只保留 3 通道处理结果，
    // 再释放 LibRaw 实例本身。此后会话只持有 image 这一块内存。
    rp->recycle();
    delete rp;

    int sw = (int)image->width;
    int sh = (int)image->height;
    int ow = sw, oh = sh;
    if (jmaxLongSide > 0) {
        const int longSide = (sw > sh) ? sw : sh;
        if (longSide > jmaxLongSide) {
            const float k = (float)jmaxLongSide / (float)longSide;
            ow = (int)((float)sw * k + 0.5f);
            oh = (int)((float)sh * k + 0.5f);
            if (ow < 1) ow = 1;
            if (oh < 1) oh = 1;
        }
    }

    LinearSession* s = new (std::nothrow) LinearSession();
    if (!s) {
        LibRaw::dcraw_clear_mem(image);
        return 0;
    }
    s->image = image;
    s->srcWidth = sw;
    s->srcHeight = sh;
    s->width = ow;
    s->height = oh;
    s->colors = (int)image->colors;
    s->invX = (ow > 1) ? (float)(sw - 1) / (float)(ow - 1) : 0.0f;
    s->invY = (oh > 1) ? (float)(sh - 1) / (float)(oh - 1) : 0.0f;
    for (int c = 0; c < 4; ++c) s->camMul[c] = camMul[c];

    LOGI("openLinear: ok src=%dx%d out=%dx%d colors=%d bits=%d wb=%.3f/%.3f/%.3f",
         sw, sh, ow, oh, s->colors, (int)image->bits,
         (double)camMul[0], (double)camMul[1], (double)camMul[2]);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(s));
}

/** 返回 [width, height, colors, bits]；句柄非法时返回 null。 */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_hifn_pixelcake_core_decode_RawNative_linearDims(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    LinearSession* s = asSession(handle);
    if (!s || !s->image) return nullptr;
    jint v[4] = { s->width, s->height, s->colors, (jint)s->image->bits };
    jintArray out = env->NewIntArray(4);
    if (!out) return nullptr;
    env->SetIntArrayRegion(out, 0, 4, v);
    return out;
}

/** 返回相机白平衡乘子 [r, g, b, g2]（按绿通道归一），作为 Kotlin 侧初始 WB 基线。 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hifn_pixelcake_core_decode_RawNative_linearMeta(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    LinearSession* s = asSession(handle);
    if (!s) return nullptr;
    jfloat v[4] = { s->camMul[0], s->camMul[1], s->camMul[2], s->camMul[3] };
    jfloatArray out = env->NewFloatArray(4);
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, 4, v);
    return out;
}

/**
 * 取 [y0, y0+rows) 这一段目标行，双线性重采样后写入 out（每像素 3 个 16-bit 线性分量）。
 * @return 实际写入的行数；负数表示失败（-1 参数非法，-2 缓冲区过小，-3 无法访问缓冲区）。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_hifn_pixelcake_core_decode_RawNative_readLinearRows(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jint y0, jint rows, jshortArray outArray) {
    LinearSession* s = asSession(handle);
    if (!s || !s->image || !outArray) return -1;
    if (y0 < 0 || rows <= 0 || y0 + rows > s->height) return -1;

    const jsize need = (jsize)((size_t)rows * (size_t)s->width * 3);
    const jsize have = env->GetArrayLength(outArray);
    if (have < need) {
        LOGE("readLinearRows: buffer too small need=%d have=%d", (int)need, (int)have);
        return -2;
    }

    jshort* dst = env->GetShortArrayElements(outArray, nullptr);
    if (!dst) return -3;

    const int sw = s->srcWidth;
    const int sh = s->srcHeight;
    const int sc = s->colors;
    const int w = s->width;

    for (int r = 0; r < rows; ++r) {
        const int y = y0 + r;
        const float syf = (float)y * s->invY;
        int y0i = (int)syf;
        if (y0i > sh - 1) y0i = sh - 1;
        if (y0i < 0) y0i = 0;
        int y1i = y0i + 1;
        if (y1i > sh - 1) y1i = sh - 1;
        const float ty = syf - (float)y0i;

        const unsigned short* rowA = rowPtr(s, y0i);
        const unsigned short* rowB = rowPtr(s, y1i);
        jshort* d = dst + (size_t)r * (size_t)w * 3;

        for (int x = 0; x < w; ++x) {
            const float sxf = (float)x * s->invX;
            int x0i = (int)sxf;
            if (x0i > sw - 1) x0i = sw - 1;
            if (x0i < 0) x0i = 0;
            int x1i = x0i + 1;
            if (x1i > sw - 1) x1i = sw - 1;
            const float tx = sxf - (float)x0i;

            const unsigned short* pA0 = rowA + (size_t)x0i * sc;
            const unsigned short* pA1 = rowA + (size_t)x1i * sc;
            const unsigned short* pB0 = rowB + (size_t)x0i * sc;
            const unsigned short* pB1 = rowB + (size_t)x1i * sc;

            for (int c = 0; c < 3; ++c) {
                const float v00 = (float)pA0[c];
                const float v01 = (float)pA1[c];
                const float v10 = (float)pB0[c];
                const float v11 = (float)pB1[c];
                float v = v00 * (1.0f - tx) * (1.0f - ty)
                        + v01 * tx * (1.0f - ty)
                        + v10 * (1.0f - tx) * ty
                        + v11 * tx * ty;
                if (v < 0.0f) v = 0.0f; else if (v > 65535.0f) v = 65535.0f;
                d[(size_t)x * 3 + c] = (jshort)(v + 0.5f);
            }
        }
    }

    env->ReleaseShortArrayElements(outArray, dst, 0);
    return rows;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hifn_pixelcake_core_decode_RawNative_closeLinear(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    LinearSession* s = asSession(handle);
    if (!s) return;
    // F02：dcraw_make_mem_image() 的返回值必须用 dcraw_clear_mem() 释放。
    if (s->image) LibRaw::dcraw_clear_mem(s->image);
    delete s;
}
