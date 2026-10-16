plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.qtekfun.ultimatecamera.feature.spike"
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":core-camera"))
    api(project(":core-gpu"))
    api(project(":core-codec"))
    api(project(":core-audio"))
    api(project(":core-net"))
    implementation(project(":core-storage"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
