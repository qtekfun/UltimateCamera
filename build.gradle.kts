import com.android.build.api.dsl.LibraryExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Not applied here: puts the pinned Kotlin and Compose compiler plugins on the classpath (AGP 9 has built-in Kotlin).
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// Settings shared by every module. Keep modules' own build files down to plugins and dependencies.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom("src/main/kotlin", "src/test/kotlin", "src/androidTest/kotlin")
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
    }
    extensions.configure<KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint)
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 37
            defaultConfig {
                minSdk = 33
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint {
                abortOnError = true
                warningsAsErrors = false
                error += "MissingTranslation"
            }
        }
    }
}
