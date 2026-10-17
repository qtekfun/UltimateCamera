pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // Google's mirror of Maven Central serves the same bytes (dependency verification still applies), so a Central
        // hiccup no longer fails the build now that the portal may not serve the Kotlin plugins (D61, D75).
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeGroupByRegex("org\\.jetbrains\\.kotlin.*") }
        }
        gradlePluginPortal {
            content {
                // Kotlin plugins come from Maven Central only: the portal serves its own re-generated marker POMs with
                // other bytes, so a Central hiccup made dependency verification fail on CI (D61).
                excludeGroupByRegex("org\\.jetbrains\\.kotlin.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "UltimateCamera"
include(":app")
include(":core-gpu")
include(":core-codec")
include(":core-camera")
include(":core-audio")
include(":core-net")
include(":core-storage")
include(":core-recording")
include(":core-luts")
include(":core-settings")
include(":core-capabilities")
include(":core-backup")
include(":feature-spike")
include(":feature-settings")
include(":feature-camera")
include(":feature-multicam")
