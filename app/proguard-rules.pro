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
