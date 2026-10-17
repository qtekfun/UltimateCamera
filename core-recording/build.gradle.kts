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
    // On-device checks, run by hand on a phone: the direct 8K mode (D71), the pipeline benchmark (D68) and the timecode track (D69).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
