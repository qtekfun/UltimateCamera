# libwebrtc (im.conversations.webrtc:webrtc-android) looks up its Java classes, fields and methods from native code by
# name, and the AAR ships no consumer rules of its own. Keep the whole package.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
