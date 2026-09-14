# ---------- Compose ----------
# @Composable 函数被编译器改写，混淆会破坏重组
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
-dontwarn androidx.compose.**

# ---------- JNI ----------
# 第 2 步引入 LibRaw 后，native 方法名必须与 so 中的符号一致
-keepclasseswithmembernames class * {
    native <methods>;
}
# ARW 解析与 LibRaw JNI 封装都在 com.hifn.pixelcake.arw / core.decode 下
# （此前写的是 com.hifn.pixelcake.raw，该包并不存在，等于没生效）
-keep class com.hifn.pixelcake.arw.** { *; }
-keep class com.hifn.pixelcake.core.decode.RawNative { *; }

# ---------- LiteRT ----------
# LiteRT 通过 JNI + 反射访问自身类，官方文档明确要求「ProGuard rules are required」：
# 不 keep 时 R8 会把「只被反射/ JNI 用到、静态看不见调用点」的类删掉，
# debug 包不复现（不混淆），只在 release 包运行时崩 —— 属最难查的一类问题。
# 本项目 release 任务此前从未运行，本段是首次启用 R8 前的必要护栏。
-keep class com.google.ai.edge.litert.** { *; }
# 防御性兜底：LiteRT 2.x 已把 GPU 加速器并入主包，但仍可能引用未随行的
# 可选 delegate / 加速器类（如 NNAPI、厂商 NPU）。这类 missing-class 只是警告，
# 但 R8 full mode 下会直接判构建失败，故显式静音 —— 不影响 keep 的语义。
-dontwarn com.google.ai.edge.litert.**
-dontwarn com.google.android.nn.**

# ---------- 通用 ----------
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
