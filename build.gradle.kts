import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Not applied here: puts the pinned Kotlin and Compose compiler plugins on the classpath (AGP 9 has built-in Kotlin).
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    // Applied here too: the root is the merging project of the aggregated coverage report (D72).
    alias(libs.plugins.kover)
}

/**
 * Coverage of the JVM unit tests (D72), in the report variant `unit`: the debug build variant of every module (the
 * total variant would also build release, which refuses to run unsigned). Tasks: `koverXmlReportUnit`,
 * `koverHtmlReportUnit` (root = all modules merged, `:module:` = that module). Only generated code is excluded: Android
 * resources and build config, the lambdas the Compose compiler hoists into `ComposableSingletons`, and injection
 * factories. Nothing hand-written is.
 */
fun KoverProjectExtension.unitTestCoverage(android: Boolean) {
    currentProject {
        createVariant("unit") { if (android) add("debug") }
    }
    reports {
        filters {
            excludes {
                classes("*.R", "*.R$*", "*.BuildConfig", "*.ComposableSingletons*", "*_Factory", "*_Factory$*")
            }
        }
    }
}

extensions.configure<KoverProjectExtension> { unitTestCoverage(android = false) }
dependencies {
    subprojects.forEach { kover(project(it.path)) }
}

// Coverage ratchet (D72): no module may drop more than 0.5 pt below config/coverage-baseline.json. Standard-library
// Python like the spike tooling; the per-module reports come from each module's koverXmlReportUnit.
val moduleCoverageReports = subprojects.map { "${it.path}:koverXmlReportUnit" }
fun coverageScript(vararg args: String) = listOf("python3", "-I", "scripts/coverage/coverage.py") + args
tasks.register<Exec>("checkCoverageBaseline") {
    group = "verification"
    description = "Fails if any module's unit-test coverage drops more than 0.5 pt below its committed baseline."
    dependsOn(moduleCoverageReports)
    workingDir = rootDir
    commandLine(coverageScript("check"))
}
tasks.register<Exec>("updateCoverageBaseline") {
    group = "verification"
    description = "Raises config/coverage-baseline.json to the current unit-test coverage (commit the result)."
    dependsOn(moduleCoverageReports)
    workingDir = rootDir
    commandLine(coverageScript("update"))
}

// On-device coverage (D72, docs/TESTING.md): `-PandroidTestCoverage` instruments the debug build of the modules with
// instrumented tests so createDebugAndroidTestCoverageReport works after connected tests. Off by default: the debug APK
// people install stays uninstrumented (JaCoCo slows it down).
val androidTestCoverage = providers.gradleProperty("androidTestCoverage").isPresent

// Settings shared by every module. Keep modules' own build files down to plugins and dependencies.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // Every subproject is an Android module (app or library).
    extensions.configure<KoverProjectExtension> { unitTestCoverage(android = true) }
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            buildTypes.getByName("debug").enableAndroidTestCoverage = androidTestCoverage
        }
    }

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
            buildTypes.getByName("debug").enableAndroidTestCoverage = androidTestCoverage
            lint {
                abortOnError = true
                warningsAsErrors = false
                error += "MissingTranslation"
            }
        }
    }
}
