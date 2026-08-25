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
    compileSdk = 36

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
).map { name -> rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/$name") }

val checkPlaybackFeatureBoundaries = tasks.register("checkPlaybackFeatureBoundaries") {
    group = "verification"
    description = "Reject shared/app dependencies or restoration of playback business ownership in :shared."

    inputs.files(playbackRequiredFiles.map(rootProject::file))
    inputs.dir(rootProject.file("feature/playback/src/commonMain/kotlin"))
    inputs.files(retiredSharedPlaybackBusinessFiles)

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

        val sourceText = rootProject.file("feature/playback/src/commonMain/kotlin")
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
            check(!Regex("\\b${Regex.escape(symbol)}\\b").containsMatchIn(sourceText)) {
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
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn(checkPlaybackFeatureBoundaries)
}
