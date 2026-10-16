plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.qtekfun.ultimatecamera.core.luts"
}

// The maker LUT catalogue lives at the repository root (REQ-COL-006) and ships as the asset catalog.json.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(rootProject.file("luts").path)
    }
}

dependencies {
    // api: the library hands out CubeLut and LutFormat, and exposes Flow.
    api(project(":core-gpu"))
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
