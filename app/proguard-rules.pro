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
-keep class com.hifn.pixelcake.raw.** { *; }

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
