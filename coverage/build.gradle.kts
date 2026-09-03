plugins {
    // Kotlin and Kover are already on the root buildscript classpath. Do not request
    // their catalog versions again here, otherwise Gradle 9 rejects the duplicate
    // plugin request as "already on the classpath with an unknown version".
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
}

val coveredProjects = listOf(
    ":core:model",
    ":feature:recognition",
    ":feature:search",
    ":feature:localplaylist",
    ":feature:localmusic",
    ":feature:download",
    ":feature:providercatalog",
    ":feature:providerauth",
    ":feature:providerdetail",
    ":feature:settings",
    ":feature:onboarding",
    ":feature:home",
    ":feature:playback",
    ":playback:api",
    ":playback:runtime",
    ":provider:api",
    ":provider:runtime",
    ":provider:bilibili",
    ":provider:netease",
    ":provider:qqmusic",
    ":provider:ytmusic",
    ":persistence:settings",
    ":persistence:listening",
    ":shared",
    ":desktopApp",
)

dependencies {
    coveredProjects.forEach { projectPath ->
        kover(project(projectPath))
    }
}

kover {
    currentProject {
        createVariant("projectJvm") {
            addWithDependencies("jvm")
        }
    }

    reports {
        variant("projectJvm") {
            xml {
                xmlFile.set(layout.buildDirectory.file("reports/kover/report.xml"))
            }
            html {
                htmlDir.set(layout.buildDirectory.dir("reports/kover/html"))
            }
        }
    }
}
