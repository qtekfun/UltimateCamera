plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qtekfun.ultimatecamera.feature.multicam"
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Role preference (REQ-NET-003).
    api(project(":core-settings"))
    // Control link (status, commands, reconnection), pairing payload, handshake, discovery, paired-devices file and the
    // WebRTC monitor receiver; org.webrtc stays inside core-net.
    api(project(":core-net"))
    // The camera design system (D49) and the health banner (D52), so the monitor looks like the camera it mirrors.
    implementation(project(":feature-camera"))
    // QR generation (camera) and decoding (monitor); pure Java, Apache-2.0 (D55, LICENSES.md).
    implementation(libs.zxing.core)

    // Same AndroidX versions as the app (see feature-settings): keeps gradle/verification-metadata.xml unchanged.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
