# ProGuard rules for MAYA Assistant
# Add project specific ProGuard rules here.

# Keep all classes in the main package
-keep class com.maya.assistant.** { *; }

# Keep data classes
-keep class com.maya.assistant.models.** { *; }

# Keep service classes
-keep class com.maya.assistant.services.** { *; }
-keep class com.maya.assistant.service.** { *; }

# Keep receiver classes
-keep class com.maya.assistant.receiver.** { *; }

# Keep annotation
-keepattributes *Annotation*

# Keep WebSocket classes (OkHttp)
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Keep ML Kit
-keep class com.google.mlkit.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
