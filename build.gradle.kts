plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlinx.kover) apply false
}

val migratedControllerBoundaryRoots = listOf(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/search",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/recognition",
    "playback/runtime/src/commonMain/kotlin",
)
val migratedControllerBoundaryFiles = listOf(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppFeaturePorts.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/SearchRoute.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/RecognitionRoute.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackComposition.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackUiPort.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackUiComposition.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/RuntimeMiniPlayer.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/RuntimeFullPlayer.kt",
    "androidApp/src/main/kotlin/org/feeluown/mobile/FuoPlaybackService.kt",
    "androidApp/src/main/kotlin/org/feeluown/mobile/LyriconLyricsPublisher.kt",
)
val retiredControllerCompatibilityFiles = listOf(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/SearchRouteCompat.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/RecognitionRouteCompat.kt",
    "androidApp/src/main/kotlin/org/feeluown/mobile/ControllerPlaybackSession.kt",
)

tasks.register("checkArchitectureBoundaries") {
    group = "verification"
    description = "Reject legacy controller dependencies or retired compatibility files in migrated boundaries."

    val sourceFiles = provider {
        buildList {
            migratedControllerBoundaryRoots.forEach { path ->
                val root = rootProject.file(path)
                if (root.isDirectory) {
                    addAll(root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList())
                }
            }
            migratedControllerBoundaryFiles.map(rootProject::file)
                .filterTo(this) { it.isFile }
        }.distinct()
    }
    inputs.files(sourceFiles)

    doLast {
        val retiredCompatViolations = retiredControllerCompatibilityFiles
            .map(rootProject::file)
            .filter { it.isFile }
            .map { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
        if (retiredCompatViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Retired controller compatibility files were reintroduced:")
                    retiredCompatViolations.forEach { appendLine(" - $it") }
                    append("Use app ports or the dedicated playback runtime boundary instead.")
                },
            )
        }

        val controllerPattern = Regex("\\bFuoPlayerController\\b")
        val violations = sourceFiles.get().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val trimmed = line.trimStart()
                val commentOnly = trimmed.startsWith("//") ||
                    trimmed.startsWith("/**") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("*/")
                if (!commentOnly && controllerPattern.containsMatchIn(line)) {
                    "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1}"
                } else {
                    null
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("FuoPlayerController leaked into migrated architecture boundaries:")
                    violations.forEach { appendLine(" - $it") }
                    append("Use feature-owned state/actions or a narrow app/playback/provider contract instead.")
                },
            )
        }
    }
}
