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
include(":feature:search")
include(":feature:localplaylist")
include(":feature:localmusic")
include(":feature:download")
include(":playback:api")
include(":playback:runtime")
include(":provider:api")
include(":shared")
include(":androidApp")