-keepattributes SourceFile,LineNumberTable
# WebRTC Java names are resolved by the native JNI library.
-keep class org.webrtc.** { *; }

# Chromium JNI bootstrap is loaded by name before WebRTC initialization.
-keep class org.jni_zero.** { *; }
