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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // Pleos Playground SDKs (public, no token). exclusiveContent = this repo serves only
        // ai.pleos.* and ai.pleos.* is never looked up anywhere else.
        exclusiveContent {
            forRepository {
                maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
                    name = "PleosPlayground"
                }
            }
            filter {
                includeGroupByRegex("ai\\.pleos.*")
            }
        }
    }
}

rootProject.name = "baro-drive"

include(":app")
include(":core:model")
include(":core:engine")
include(":core:sim")
