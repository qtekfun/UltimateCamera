plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qtekfun.ultimatecamera.core.settings"
}

dependencies {
    // api: SettingsRepository exposes Flow, and the DataStore-backed factory takes a CoroutineScope.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
}
