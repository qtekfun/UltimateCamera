plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qtekfun.ultimatecamera.feature.camera"
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":core-recording"))
    api(project(":core-settings"))
    // Health snapshot for the remote monitor (D52): only the control protocol types; WebRTC stays inside core-net.
    implementation(project(":core-net"))
    implementation(project(":core-luts"))

    // Same AndroidX versions as the app (see feature-settings): keeps gradle/verification-metadata.xml unchanged.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // @Preview annotations only (same artifact and version the app already ships; no verification-metadata change).
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)

    // Compose UI tests of the semantics and font-scale layouts (D67); run on a phone by the lead. ui-test-manifest goes
    // in androidTest (not debugImplementation) so its test activity never reaches the app's debug APK.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
