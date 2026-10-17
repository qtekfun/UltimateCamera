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
    api(project(":core-settings"))
    // Pairing payload, handshake, discovery and the paired-devices file (REQ-NET-002).
    api(project(":core-net"))
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
