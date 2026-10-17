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
        gradlePluginPortal()
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
include(":feature-spike")
include(":feature-settings")
