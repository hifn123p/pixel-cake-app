#include <jni.h>
#include <android/log.h>
#include <libraw/libraw.h>

#define LOG_TAG "rawbridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// P1b raw bridge —— 基于 LibRaw 的 ARW 全量解马赛克。
// 仅面向 arm64-v8a（一加15）。decodeFull 把 RAW 解马赛克为 sRGB / 8bit / RGBA 字节流，
// 经 RawImage 对象回传 Kotlin，由其转为 Android Bitmap。失败时返回 null 并写 DebugLog。

extern "C" JNIEXPORT jstring JNICALL
Java_com_hifn_pixelcake_core_decode_RawNative_getVersion(JNIEnv* env, jclass /*clazz*/) {
    // LibRaw 版本宏（如 "0.21.0" 或 master 的 "0.22.0-快照" 串）
    return env->NewStringUTF("LibRaw " LIBRAW_VERSION_STR);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_hifn_pixelcake_core_decode_RawNative_decodeFull(
        JNIEnv* env, jclass /*clazz*/, jstring jpath, jint jmaxLongSide) {

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return nullptr;

    LibRaw rawProcessor;
    int ret = rawProcessor.open_file(path);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeFull: open_file failed ret=%d path=%s", ret, path);
        env->ReleaseStringUTFChars(jpath, path);
        return nullptr;
    }

    // 默认处理参数：相机白平衡 + sRGB 输出 + AHD 解马赛克 + 自动亮度
    rawProcessor.imgdata.params.use_camera_wb = 1;   // 相机白平衡，出图更自然
    rawProcessor.imgdata.params.output_color  = 1;   // 1 = sRGB
    rawProcessor.imgdata.params.output_bps    = 8;   // 8bit
    rawProcessor.imgdata.params.no_auto_bright = 0;  // 允许自动亮度
    rawProcessor.imgdata.params.user_qual     = 3;   // 3 = AHD 高质量解马赛克

    ret = rawProcessor.unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeFull: unpack failed ret=%d", ret);
        env->ReleaseStringUTFChars(jpath, path);
        return nullptr;
    }

    ret = rawProcessor.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("decodeFull: dcraw_process failed ret=%d", ret);
        env->ReleaseStringUTFChars(jpath, path);
        return nullptr;
    }

    int err = 0;
    libraw_processed_image_t* image = rawProcessor.dcraw_make_mem_image(&err);
    env->ReleaseStringUTFChars(jpath, path);
    if (!image || err != LIBRAW_SUCCESS) {
        LOGE("decodeFull: dcraw_make_mem_image failed err=%d", err);
        if (image) rawProcessor.free_image();
        return nullptr;
    }

    int w = (int)image->width;
    int h = (int)image->height;
    int colors = (int)image->colors;  // 标准 8bit 输出 col=3 (RGB)
    if (w <= 0 || h <= 0 || colors < 3) {
        LOGE("decodeFull: bad processed image w=%d h=%d colors=%d", w, h, colors);
        rawProcessor.free_image();
        return nullptr;
    }

    // 按 maxLongSide 计算目标尺寸（<=0 表示全分辨率）
    int ow = w, oh = h;
    if (jmaxLongSide > 0) {
        int longSide = (w > h) ? w : h;
        if (longSide > jmaxLongSide) {
            float scale = (float)jmaxLongSide / (float)longSide;
            ow = (int)((float)w * scale);
            oh = (int)((float)h * scale);
            if (ow < 1) ow = 1;
            if (oh < 1) oh = 1;
        }
    }

    size_t rgbaSize = (size_t)ow * (size_t)oh * 4;
    jbyteArray out = env->NewByteArray((jsize)rgbaSize);
    if (!out) {
        LOGE("decodeFull: NewByteArray OOM size=%zu", rgbaSize);
        rawProcessor.free_image();
        return nullptr;
    }
    jbyte* outBuf = env->GetByteArrayElements(out, nullptr);
    if (!outBuf) {
        rawProcessor.free_image();
        return nullptr;
    }

    const unsigned char* src = image->data;

    if (ow == w && oh == h) {
        // 无缩放：RGB -> RGBA 直拷
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const unsigned char* p = src + ((size_t)y * w + x) * colors;
                size_t di = ((size_t)y * w + x) * 4;
                outBuf[di + 0] = (jbyte)p[0];
                outBuf[di + 1] = (jbyte)p[1];
                outBuf[di + 2] = (jbyte)p[2];
                outBuf[di + 3] = (jbyte)255;
            }
        }
    } else {
        // 双线性下采样 RGB -> RGBA
        float fx = (ow > 1) ? (float)(w - 1) / (float)(ow - 1) : 0.0f;
        float fy = (oh > 1) ? (float)(h - 1) / (float)(oh - 1) : 0.0f;
        for (int y = 0; y < oh; ++y) {
            float sy = (float)y * fy;
            int y0 = (int)sy;
            int y1 = (y0 + 1 < h) ? y0 + 1 : y0;
            float ty = sy - (float)y0;
            for (int x = 0; x < ow; ++x) {
                float sx = (float)x * fx;
                int x0 = (int)sx;
                int x1 = (x0 + 1 < w) ? x0 + 1 : x0;
                float tx = sx - (float)x0;
                for (int c = 0; c < 3; ++c) {
                    float v00 = (float)src[((size_t)y0 * w + x0) * colors + c];
                    float v01 = (float)src[((size_t)y0 * w + x1) * colors + c];
                    float v10 = (float)src[((size_t)y1 * w + x0) * colors + c];
                    float v11 = (float)src[((size_t)y1 * w + x1) * colors + c];
                    float v = v00 * (1.0f - tx) * (1.0f - ty)
                            + v01 * tx * (1.0f - ty)
                            + v10 * (1.0f - tx) * ty
                            + v11 * tx * ty;
                    outBuf[((size_t)y * ow + x) * 4 + c] = (jbyte)(v + 0.5f);
                }
                outBuf[((size_t)y * ow + x) * 4 + 3] = (jbyte)255;
            }
        }
    }

    env->ReleaseByteArrayElements(out, outBuf, 0);
    rawProcessor.free_image();

    // 构造 RawImage(width, height, pixels)
    jclass cls = env->FindClass("com/hifn/pixelcake/core/decode/RawImage");
    if (!cls) {
        LOGE("decodeFull: RawImage class not found");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(II[B)V");
    if (!ctor) {
        LOGE("decodeFull: RawImage ctor not found");
        return nullptr;
    }
    jobject obj = env->NewObject(cls, ctor, ow, oh, out);
    LOGI("decodeFull: ok in=%dx%d out=%dx%d", w, h, ow, oh);
    return obj;
}
