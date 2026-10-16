plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.qtekfun.ultimatecamera.core.codec"
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
