pluginManagement {
    repositories {
        google()
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

rootProject.name = "FuoEvolve"

include(":core:model")
include(":feature:recognition")
include(":playback:api")
include(":playback:runtime")
include(":provider:api")
include(":shared")
include(":androidApp")
