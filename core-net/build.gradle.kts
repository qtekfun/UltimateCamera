plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.qtekfun.ultimatecamera.core.net"
    // libwebrtc calls into its Java classes from native code (JNI); R8 in the minified release must keep them.
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)
    // implementation, not api: org.webrtc types stay inside core-net (the monitor package).
    implementation(libs.webrtc.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
