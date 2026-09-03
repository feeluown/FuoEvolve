plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.kover)
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
