plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.qtekfun.ultimatecamera.core.capabilities"
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The unit tests read the real catalogue seeds (catalog/devices/*.json) as fixtures.
val catalogSeeds = rootProject.layout.projectDirectory.dir("catalog/devices")
tasks.withType<Test>().configureEach {
    inputs.dir(catalogSeeds).withPropertyName("catalogSeeds").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("catalog.devices.dir", catalogSeeds.asFile.absolutePath)
}
