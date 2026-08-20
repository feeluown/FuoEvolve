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
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppFeaturePortAdapters.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/SearchRoute.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/RecognitionRoute.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/download/DownloadController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localmusic/LocalMusicController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localplaylist/PlaylistActionController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderTrackActionController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackComposition.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackLifecycleCoordinator.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackQueueController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackQueueCoordinator.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackReplacementController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackSleepTimerController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackStartCoordinator.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackUiPort.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackUiOwners.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlaybackUiComposition.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/RuntimeMiniPlayer.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/RuntimeFullPlayer.kt",
    "androidApp/src/main/kotlin/org/feeluown/mobile/FuoPlaybackService.kt",
    "androidApp/src/main/kotlin/org/feeluown/mobile/LyriconLyricsPublisher.kt",
)
val retiredControllerCompatibilityFiles = listOf(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/SearchRouteCompat.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/RecognitionRouteCompat.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/ControllerPlaybackUiPort.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/ControllerPlaybackCompatibilityPorts.kt",
    "androidApp/src/main/kotlin/org/feeluown/mobile/ControllerPlaybackSession.kt",
)
val playbackRuntimeAdapterFiles = listOf(
    "androidApp/src/main/kotlin/org/feeluown/mobile/AndroidPlaybackRuntime.kt",
    "shared/src/iosMain/kotlin/org/feeluown/mobile/IosPlaybackRuntime.kt",
)
val platformCompositionRootFiles = listOf(
    "androidApp/src/main/kotlin/org/feeluown/mobile/AndroidAppContainer.kt",
    "shared/src/iosMain/kotlin/org/feeluown/mobile/IosAppHost.kt",
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
    val commonMainSources = provider {
        val root = rootProject.file("shared/src/commonMain/kotlin")
        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        } else {
            emptyList()
        }
    }
    inputs.files(sourceFiles)
    inputs.files(commonMainSources)
    inputs.files(playbackRuntimeAdapterFiles.map(rootProject::file))
    inputs.files(platformCompositionRootFiles.map(rootProject::file))

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
                    append("Use app ports or dedicated feature/playback owners instead.")
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

        val platformAppPortPattern = Regex("object\\s*:\\s*(?:SearchAppPort|RecognitionAppPort)")
        val platformAppPortViolations = platformCompositionRootFiles
            .map(rootProject::file)
            .filter { it.isFile }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (platformAppPortPattern.containsMatchIn(line)) {
                        "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
        if (platformAppPortViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Platform composition roots rebuilt Search/Recognition app-port bridges:")
                    platformAppPortViolations.forEach { appendLine(" - $it") }
                    append("Use the shared controller-free app-port adapters composed from narrow owners instead.")
                },
            )
        }

        val playbackUiAggregatePattern = Regex("\\b(?:interface|class)\\s+PlaybackUiPort\\b")
        val playbackUiAggregateViolations = commonMainSources.get().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (playbackUiAggregatePattern.containsMatchIn(line)) {
                    "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1}"
                } else {
                    null
                }
            }
        }
        if (playbackUiAggregateViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Retired PlaybackUiPort aggregate was reintroduced:")
                    playbackUiAggregateViolations.forEach { appendLine(" - $it") }
                    append("Compose playback UI from the narrow feature ports through PlaybackUiGraph instead.")
                },
            )
        }

        val legacyMiniPlayerViolations = commonMainSources.get().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val normalized = line.replace(" ", "")
                if (
                    "MiniPlayer(controller" in normalized &&
                    !normalized.startsWith("funMiniPlayer(controller")
                ) {
                    "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1}"
                } else {
                    null
                }
            }
        }
        if (legacyMiniPlayerViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Legacy MiniPlayer(controller) callers remain:")
                    legacyMiniPlayerViolations.forEach { appendLine(" - $it") }
                    append("Use PlaybackMiniPlayer() so the mini player stays on the narrow playback graph.")
                },
            )
        }

        val retiredTransportCalls = listOf(
            "controller.toggle()",
            "controller.previous()",
            "controller.next()",
        )
        val transportViolations = playbackRuntimeAdapterFiles
            .map(rootProject::file)
            .filter { it.isFile }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    retiredTransportCalls.firstOrNull(line::contains)?.let { call ->
                        "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1} ($call)"
                    }
                }
            }
        if (transportViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Playback runtime adapters reintroduced legacy controller transport calls:")
                    transportViolations.forEach { appendLine(" - $it") }
                    append("Inject PlaybackTransportCoordinator instead.")
                },
            )
        }
    }
}
