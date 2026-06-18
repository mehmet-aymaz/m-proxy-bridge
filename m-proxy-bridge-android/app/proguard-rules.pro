# M-Proxy Bridge ProGuard Rules

# Keep libbox JNI classes (native bridge)
-keep class libbox.** { *; }
-keepclassmembers class libbox.** { *; }

# Keep VPN Service (referenced in AndroidManifest)
-keep class com.mproxy.bridge.BridgeVpnService { *; }

# Keep MainActivity
-keep class com.mproxy.bridge.MainActivity { *; }

# Keep broadcast receivers
-keep class com.mproxy.bridge.** extends android.content.BroadcastReceiver { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Strip Kotlin metadata (safe to remove, saves space)
-dontwarn kotlin.reflect.jvm.internal.**
-dontnote kotlin.reflect.jvm.internal.**

# Remove unused Kotlin intrinsics checks
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
}
