plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
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

android {
    namespace = "org.feeluown.mobile.feature.download"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val offlineFeatureSpecs = mapOf(
    "localplaylist" to listOf("MusicTrack", "LocalPlaylistRepository", "AppNavigator", "ProviderInfo"),
    "localmusic" to listOf("MusicTrack", "LocalMusicRepository", "ProviderMusicRepository", "AppNavigator", "AppSettingsRepository", "ProviderInfo"),
    "download" to listOf("MusicTrack", "DownloadRepository", "ProviderMusicRepository", "LocalMusicRepository", "LocalMusicFeatureController", "AppSettingsRepository", "PlaybackPayload"),
)

val offlineRequiredFiles = listOf(
    "feature/localplaylist/build.gradle.kts",
    "feature/localplaylist/src/commonMain/kotlin/org/feeluown/mobile/LocalPlaylistFeature.kt",
    "feature/localplaylist/src/commonTest/kotlin/org/feeluown/mobile/feature/localplaylist/LocalPlaylistFeatureTest.kt",
    "feature/localmusic/build.gradle.kts",
    "feature/localmusic/src/commonMain/kotlin/org/feeluown/mobile/LocalMusicFeature.kt",
    "feature/localmusic/src/commonTest/kotlin/org/feeluown/mobile/LocalMusicFeatureTest.kt",
    "feature/download/build.gradle.kts",
    "feature/download/src/commonMain/kotlin/org/feeluown/mobile/DownloadFeature.kt",
    "feature/download/src/commonTest/kotlin/org/feeluown/mobile/DownloadFeatureTest.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localplaylist/LocalPlaylistFeatureFactory.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localmusic/LocalMusicFeatureFactory.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/download/DownloadFeatureFactory.kt",
)

val offlineRetiredSharedOwners = listOf(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localplaylist/LocalPlaylistController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localmusic/LocalMusicController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localmusic/LocalMusicControllerState.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/download/DownloadController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/download/DownloadControllerState.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localmusic/OfflineLibraryControllerCoordinator.kt",
)

tasks.register("checkOfflineFeatureBoundaries") {
    group = "verification"
    description = "Reject shared back-dependencies or concrete application types in offline feature modules."

    inputs.files(offlineRequiredFiles.map(rootProject::file))
    inputs.files(offlineRetiredSharedOwners.map(rootProject::file))
    offlineFeatureSpecs.keys.forEach { feature ->
        inputs.file(rootProject.file("feature/$feature/build.gradle.kts"))
        inputs.dir(rootProject.file("feature/$feature/src/commonMain/kotlin"))
    }

    doLast {
        val missing = offlineRequiredFiles.map(rootProject::file).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Offline feature physical boundary is incomplete:")
                    missing.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val reintroduced = offlineRetiredSharedOwners.map(rootProject::file).filter { it.isFile }
        if (reintroduced.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Retired shared offline feature owners were reintroduced:")
                    reintroduced.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        offlineFeatureSpecs.forEach { (feature, forbiddenDependencies) ->
            val buildFile = rootProject.file("feature/$feature/build.gradle.kts")
            if (Regex("""project\(\s*[\"']?:shared[\"']?\s*\)""").containsMatchIn(buildFile.readText())) {
                throw GradleException(":feature:$feature must not depend on :shared; bind application types in the shared composition layer.")
            }

            val sourceRoot = rootProject.file("feature/$feature/src/commonMain/kotlin")
            val violations = sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        forbiddenDependencies.firstOrNull { dependency ->
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
                        appendLine(":feature:$feature leaked application/shared dependencies:")
                        violations.forEach { appendLine(" - $it") }
                    },
                )
            }
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn("checkOfflineFeatureBoundaries")
}
