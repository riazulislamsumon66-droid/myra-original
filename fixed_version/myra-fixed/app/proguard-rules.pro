# ProGuard rules for MAYA Assistant
# Add project specific ProGuard rules here.
# Since minifyEnabled is false for debug builds, these rules only apply to release builds.

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
