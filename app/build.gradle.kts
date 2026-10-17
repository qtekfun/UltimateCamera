import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The app version lives in one place, `appVersion` in gradle.properties (semantic versioning MAJOR.MINOR.PATCH, D76). The version code
 * is derived from it, so it always grows with the version and never depends on dates or the machine (reproducible builds):
 * MAJOR * 1_000_000 + MINOR * 1_000 + PATCH.
 */
val appVersion: String = providers.gradleProperty("appVersion").get()

fun versionCodeOf(version: String): Int {
    // Semantic versioning: MAJOR.MINOR.PATCH, optionally a semver pre-release such as -rc.1 (never a phase suffix, D76).
    val match = Regex("""(\d+)\.(\d+)\.(\d+)(-(?!fase)[0-9A-Za-z.]+)?""").matchEntire(version)
        ?: error("appVersion must be semantic versioning MAJOR.MINOR.PATCH[-pre.release]: $version")
    val (major, minor, patch) = match.destructured
    require(minor.toInt() < 1_000 && patch.toInt() < 1_000) { "minor and patch must be below 1000: $version" }
    return major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
}

/**
 * Release signing (docs/06-arranque.md). Sources, in order:
 * 1. `~/keys/ultimatecamera/keystore.properties` (local machine): storeFile, storePassword, keyAlias, keyPassword.
 * 2. Environment variables (CI): KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
 * Without either, every release task fails with [signingProblem]; a release is never signed with the debug key.
 * Values are never logged: messages only name the missing keys.
 */
data class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

val localSigningFile = File(System.getProperty("user.home"), "keys/ultimatecamera/keystore.properties")

fun signingFromFile(file: File): Pair<ReleaseSigning?, String?> {
    val props = Properties().apply { file.inputStream().use { load(it) } }
    val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val missing = keys.filter { props.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) return null to "$file is missing: ${missing.joinToString()}"
    val store = File(props.getProperty("storeFile").trim()).let { if (it.isAbsolute) it else File(file.parentFile, it.path) }
    if (!store.isFile) return null to "$file points to a keystore that does not exist (storeFile)"
    return ReleaseSigning(store, props.getProperty("storePassword"), props.getProperty("keyAlias"), props.getProperty("keyPassword")) to null
}

fun signingFromEnv(): Pair<ReleaseSigning?, String?> {
    val names = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    val values = names.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
    if (values.values.all { it == null }) return null to null
    val missing = values.filterValues { it == null }.keys
    if (missing.isNotEmpty()) return null to "environment variables missing: ${missing.joinToString()}"
    val store = File(values.getValue("KEYSTORE_PATH")!!)
    if (!store.isFile) return null to "KEYSTORE_PATH does not point to a file"
    return ReleaseSigning(store, values.getValue("KEYSTORE_PASSWORD")!!, values.getValue("KEY_ALIAS")!!, values.getValue("KEY_PASSWORD")!!) to null
}

val (releaseSigning, signingProblem) = run {
    if (localSigningFile.isFile) {
        signingFromFile(localSigningFile)
    } else {
        val (fromEnv, envProblem) = signingFromEnv()
        fromEnv to (
            envProblem ?: if (fromEnv == null) {
                "neither $localSigningFile nor the KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD environment variables exist"
            } else {
                null
            }
            )
    }
}

android {
    namespace = "com.qtekfun.ultimatecamera"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qtekfun.ultimatecamera"
        minSdk = 33
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones only (D20): libwebrtc ships ~9 MB per ABI; x86/x86_64 are emulator-only and would add ~20 MB.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        releaseSigning?.let { signing ->
            create("release") {
                storeFile = signing.storeFile
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
            }
        }
    }

    // Reproducible builds (F-Droid): no Google-encrypted dependency blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Reproducible builds (F-Droid): native libraries are packaged exactly as their AARs ship them (already stripped).
    // Otherwise AGP runs llvm-strip only when an NDK is installed, so the bytes would depend on the build machine's NDK.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }

    buildTypes {
        debug {
            // Lets a debug build (debug key) live next to the signed release build on the same phone.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Never falls back to the debug key: without release signing the build fails (checkReleaseSigning).
            signingConfig = signingConfigs.findByName("release")
            // The git commit is not part of the APK: a build from a source tarball must match.
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Per-app language (Android 13+ Settings > Apps > Language) from the values-xx folders; default in resources.properties.
    androidResources {
        generateLocaleConfig = true
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

val checkReleaseSigning by tasks.registering {
    description = "Fails a release build that has no release signing key."
    val problem = signingProblem
    doLast {
        if (problem != null) {
            throw GradleException(
                "Release signing is not configured: $problem. " +
                    "Create ~/keys/ultimatecamera/keystore.properties (docs/06-arranque.md) or set the CI variables. " +
                    "Release builds are never signed with the debug key."
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }

// `./gradlew assembleRelease` also leaves dist/ultimatecamera-<versionName>.apk for quick local installs.
val copyReleaseApkToDist by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(rootProject.layout.projectDirectory.dir("dist"))
    val apkName = "ultimatecamera-$appVersion.apk"
    rename { apkName }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyReleaseApkToDist) }

dependencies {
    implementation(project(":feature-spike"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-camera"))
    implementation(project(":feature-multicam"))
    // Crash recorder installed by UltimateCameraApp (D76).
    implementation(project(":core-storage"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
