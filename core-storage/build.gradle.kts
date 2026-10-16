plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qtekfun.ultimatecamera.core.storage"
}

dependencies {
    // api: RecordingLocationRepository exposes Flow and SettingsRecordingLocationStore takes a SettingsRepository.
    // The chosen folder is one more key in core-settings' file (D27), not a second settings store.
    api(project(":core-settings"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
