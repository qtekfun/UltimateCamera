plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qtekfun.ultimatecamera.core.recording"
}

dependencies {
    api(project(":core-camera"))
    api(project(":core-codec"))
    api(project(":core-gpu"))
    api(project(":core-audio"))
    api(project(":core-storage"))
    api(project(":core-capabilities"))
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // On-device check of the direct 8K mode (D71); run by hand on a phone that lists 8K.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
