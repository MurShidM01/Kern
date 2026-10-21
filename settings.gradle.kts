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

rootProject.name = "Kern"

include(":app")

// Core modules
include(":core:common")
include(":core:model")
include(":core:designsystem")
include(":core:data")
include(":core:workspace")
include(":core:providers:api")
include(":core:providers:openai")
include(":core:agent")
include(":core:tools")
include(":core:auth")
include(":core:terminal:api")
include(":core:terminal:engine")
include(":core:terminal:pty")
include(":core:terminal:binaries")
include(":core:terminal:implementation")

// Feature modules
include(":feature:home")
include(":feature:files")
include(":feature:agent")
include(":feature:settings")
include(":feature:terminal")
include(":feature:preview")
