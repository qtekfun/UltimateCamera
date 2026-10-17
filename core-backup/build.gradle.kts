plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.qtekfun.ultimatecamera.core.backup"
}

dependencies {
    // api: the backup reads and writes these modules' stores and hands out their types.
    api(project(":core-settings"))
    api(project(":core-luts"))
    api(project(":core-capabilities"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
