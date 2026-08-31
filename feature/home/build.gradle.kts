plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "org.feeluown.mobile.feature.home"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

val homeForbiddenDependencies = listOf(
    "AppSettings",
    "AppSettingsRepository",
    "SettingsState",
    "ProviderMusicRepository",
    "ProviderCatalogFeatureController",
    "ProviderCatalogUiState",
    "ProviderDetailOwners",
    "PlaybackQueueUiPort",
    "LocalPlaylistFeatureController",
    "LocalMusicFeatureController",
    "AppNavigator",
    "AppRoute",
    "MusicTrack",
    "ProviderFeature",
    "ProviderContentSection",
    "ProviderPlaylist",
    "ProviderInfo",
    "ProviderCapabilities",
    "ProviderSessionState",
    "PlaylistPlaybackStat",
)

val homeRequiredFiles = listOf(
    "feature/home/build.gradle.kts",
    "feature/home/src/commonMain/kotlin/org/feeluown/mobile/feature/home/HomeFeature.kt",
    "feature/home/src/commonTest/kotlin/org/feeluown/mobile/feature/home/HomeFeatureTest.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/home/HomeFeatureController.kt",
)

tasks.register("checkHomeFeatureBoundaries") {
    group = "verification"
    description = "Reject shared back-dependencies, concrete application types, or shared Home business ownership."

    inputs.files(homeRequiredFiles.map(rootProject::file))
    inputs.file(rootProject.file("feature/home/build.gradle.kts"))
    inputs.dir(rootProject.file("feature/home/src/commonMain/kotlin"))
    inputs.file(rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/feature/home/HomeFeatureController.kt"))

    doLast {
        val missing = homeRequiredFiles.map(rootProject::file).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Home physical boundary is incomplete:")
                    missing.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val buildFile = rootProject.file("feature/home/build.gradle.kts")
        if (Regex("""project\(\s*[\"']?:shared[\"']?\s*\)""").containsMatchIn(buildFile.readText())) {
            throw GradleException(":feature:home must not depend on :shared; bind application types in shared.")
        }

        val sourceRoot = rootProject.file("feature/home/src/commonMain/kotlin")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    homeForbiddenDependencies.firstOrNull { dependency ->
                        Regex("\\b${Regex.escape(dependency)}\\b").containsMatchIn(line)
                    }?.let { dependency ->
                        "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1} ($dependency)"
                    }
                }.asSequence()
            }
            .toList()
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(":feature:home leaked application/shared dependencies:")
                    violations.forEach { appendLine(" - $it") }
                },
            )
        }

        val sharedBinding = rootProject.file(
            "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/home/HomeFeatureController.kt",
        ).readText()
        val retiredBusinessSymbols = listOf(
            "class DefaultHomeFeatureController",
            "fun loadSectionsIncrementally",
            "fun sortSections",
            "fun loadDynamicFeatureAndPlay",
        )
        val reintroduced = retiredBusinessSymbols.filter(sharedBinding::contains)
        if (reintroduced.isNotEmpty()) {
            throw GradleException(
                "Home business ownership must remain in :feature:home; shared binding contains: ${reintroduced.joinToString()}",
            )
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn("checkHomeFeatureBoundaries")
}
