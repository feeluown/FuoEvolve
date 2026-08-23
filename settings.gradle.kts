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
include(":feature:providercatalog")
include(":feature:providerauth")
include(":feature:providerdetail")
include(":feature:settings")
include(":feature:onboarding")
include(":feature:home")
include(":playback:api")
include(":playback:runtime")
include(":provider:api")
include(":provider:runtime")
include(":provider:bilibili")
include(":provider:netease")
include(":provider:qqmusic")
include(":provider:ytmusic")
include(":persistence:settings")
include(":shared")
include(":androidApp")
