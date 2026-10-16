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
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
