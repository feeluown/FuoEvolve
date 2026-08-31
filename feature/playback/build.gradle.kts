plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
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
            api(project(":core:model"))
            api(project(":playback:api"))
            api(project(":provider:api"))
            implementation(compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "org.feeluown.mobile.feature.playback"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val playbackRequiredFiles = listOf(
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackApplicationContracts.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackFeatureOwner.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackFeaturePorts.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackSmartReplacementPolicy.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackUiContracts.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackQueueController.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackQueueCoordinator.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackStartCoordinator.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackLifecycleCoordinator.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackSleepTimerController.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackLyricsController.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackReplacementController.kt",
)

val retiredSharedPlaybackBusinessFiles = listOf(
    "PlaybackFeatureOwner.kt",
    "PlaybackLifecycleCoordinator.kt",
    "PlaybackLyricsController.kt",
    "PlaybackQueueController.kt",
    "PlaybackQueueCoordinator.kt",
    "PlaybackQueueRestoration.kt",
    "PlaybackReplacementController.kt",
    "PlaybackSleepTimerController.kt",
    "PlaybackStartCoordinator.kt",
    "ReplacementTieBreak.kt",
).map { name -> rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/$name") }

val providerKotlinSources = rootProject.fileTree("provider") {
    include("**/src/**/*.kt")
}

val checkPlaybackFeatureBoundaries = tasks.register("checkPlaybackFeatureBoundaries") {
    group = "verification"
    description = "Reject shared/app dependencies, provider aggregation, or restoration of playback business ownership in :shared."

    inputs.file(project.buildFile)
    inputs.files(playbackRequiredFiles.map(rootProject::file))
    inputs.dir(rootProject.file("feature/playback/src/commonMain/kotlin"))
    inputs.files(retiredSharedPlaybackBusinessFiles)
    inputs.dir(rootProject.file("shared/src/commonMain/kotlin"))
    inputs.dir(rootProject.file("shared/src/iosMain/kotlin"))
    inputs.files(providerKotlinSources)
    inputs.dir(rootProject.file("androidApp/src/main/kotlin"))

    doLast {
        val missing = playbackRequiredFiles.map(rootProject::file).filterNot { it.isFile }
        check(missing.isEmpty()) {
            buildString {
                appendLine("Playback feature boundary is incomplete:")
                missing.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
            }
        }

        val buildText = project.buildFile.readText()
        listOf(
            "project(\":shared\")",
            "project(\":androidApp\")",
            "project(\":provider:runtime\")",
            "project(\":provider:netease\")",
            "project(\":provider:qqmusic\")",
            "project(\":provider:bilibili\")",
            "project(\":provider:ytmusic\")",
            "project(\":persistence:",
            "project(\":feature:",
        ).forEach { dependency ->
            check(dependency !in buildText) { ":feature:playback must not depend upward/across on $dependency" }
        }

        val featureSourceText = rootProject.file("feature/playback/src/commonMain/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        listOf(
            "AppSettingsRepository",
            "ProviderMusicRepository",
            "DownloadActionPort",
            "DefaultPlaybackNavigationPort",
            "AppNavigator",
            "ProviderDetailOwners",
        ).forEach { symbol ->
            check(!Regex("\\b${Regex.escape(symbol)}\\b").containsMatchIn(featureSourceText)) {
                ":feature:playback leaked app/shared collaborator: $symbol"
            }
        }

        val restored = retiredSharedPlaybackBusinessFiles.filter { it.isFile }
        check(restored.isEmpty()) {
            buildString {
                appendLine("Playback business ownership moved back into :shared:")
                restored.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
            }
        }

        val applicationSourceRoots = listOf(
            rootProject.file("shared/src/commonMain/kotlin"),
            rootProject.file("shared/src/iosMain/kotlin"),
            rootProject.file("androidApp/src/main/kotlin"),
        )
        val aggregateLeaks = applicationSourceRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (Regex("\\b(?:ProviderMusicRepository|ProviderSearchRepositoryView|ProviderPlaybackRepositoryView|ProviderAuthRepositoryView)\\b")
                            .containsMatchIn(line)) {
                        "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1}"
                    } else null
                }
            }
        check(aggregateLeaks.isEmpty()) {
            buildString {
                appendLine("Retired provider aggregate/compatibility adapters were restored:")
                aggregateLeaks.forEach { appendLine(" - $it") }
            }
        }

        val replacementPolicyFiles = rootProject.file("shared/src/commonMain/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList() + providerKotlinSources.files
        val replacementPolicyLeaks = replacementPolicyFiles
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (Regex("\\b(?:bilibiliReplacementScore|rankReplacementCandidates|selectRankedReplacementCandidate|replacementTieBreakConfidence|sortReplacementScoreTies)\\b")
                            .containsMatchIn(line)) {
                        "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1}"
                    } else null
                }
            }
        check(replacementPolicyLeaks.isEmpty()) {
            buildString {
                appendLine("Smart-replacement policy must remain owned by :feature:playback:")
                replacementPolicyLeaks.forEach { appendLine(" - $it") }
            }
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn(checkPlaybackFeatureBoundaries)
}
