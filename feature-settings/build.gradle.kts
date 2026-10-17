plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qtekfun.ultimatecamera.feature.settings"
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":core-settings"))
    implementation(project(":core-luts"))
    // Recording location and write-speed test (REQ-STO-001), audio input list (REQ-AUD-001).
    implementation(project(":core-storage"))
    implementation(project(":core-audio"))
    // Lens names of the second-camera pairs (D62): characteristics only, no camera is opened.
    implementation(project(":core-camera"))

    // Same AndroidX versions as the app: otherwise Compose pulls older transitive ones that are not in
    // gradle/verification-metadata.xml (and the app would resolve the newer ones anyway).
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)

    // Compose UI tests of the settings rows (D67); run on a phone by the lead. ui-test-manifest goes in androidTest
    // (not debugImplementation) so its test activity never reaches the app's debug APK.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
