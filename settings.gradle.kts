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

// Compose 1.12 requires Android API 37. Enforce the compile SDK at the build level
// so a stale per-module value cannot make release-only AAR metadata checks fail.
val androidCompileSdk = 37
gradle.beforeProject {
    afterEvaluate {
        val androidExtension = extensions.findByName("android")
        if (androidExtension != null) {
            androidExtension.javaClass.methods
                .firstOrNull { method ->
                    method.name == "setCompileSdk" &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0].isAssignableFrom(Int::class.javaObjectType)
                }
                ?.invoke(androidExtension, androidCompileSdk)
        }
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
include(":feature:playback")
include(":playback:api")
include(":playback:runtime")
include(":provider:api")
include(":provider:runtime")
include(":provider:bilibili")
include(":provider:netease")
include(":provider:qqmusic")
include(":provider:ytmusic")
include(":persistence:settings")
include(":persistence:listening")
include(":shared")
include(":androidApp")
include(":desktopApp")
include(":coverage")
