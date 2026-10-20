# libwebrtc (im.conversations.webrtc:webrtc-android) looks up its Java classes, fields and methods from native code by
# name, and the AAR ships no consumer rules of its own. Keep the whole package.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# Since m149 its JNI goes through jni_zero: JNI_OnLoad looks up org.jni_zero.JniZero by name. Without it every release
# build aborted on the first WebRTC use (monitor link) with "JNI DETECTED ERROR: java_class == null" (D95).
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
