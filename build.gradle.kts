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
    "feature/recognition/src/commonMain/kotlin",
    "playback/runtime/src/commonMain/kotlin",
)
val migratedControllerBoundaryFiles = listOf(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppFeaturePorts.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppFeaturePortAdapters.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/SearchRoute.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/RecognitionRoute.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/debug/DebugLogFeatureController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/debug/DebugLogFeatureScreen.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/download/DownloadController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/download/DownloadManagerScreen.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localmusic/LocalMusicController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localmusic/LocalMusicControllerState.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/localmusic/LocalMusicSection.kt",
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
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/FuoPlayerController.kt",
    "shared/src/commonTest/kotlin/org/feeluown/mobile/FuoPlayerControllerTest.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/playback/PlayerScreen.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/home/HomeLegacyBridge.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/home/MineHomeSection.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/home/ProviderHomeSection.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderFeatureFilters.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/settings/SettingsScreen.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/settings/SettingsScreenV2.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/onboarding/OnboardingScreen.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderDetailScreens.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/EnhancedProviderVideoScreen.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/recognition/AudioRecognition.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/recognition/AudioRecognitionController.kt",
)
val requiredPhysicalFeatureFiles = listOf(
    "feature/recognition/build.gradle.kts",
    "feature/recognition/src/commonMain/kotlin/org/feeluown/mobile/AudioRecognition.kt",
    "feature/recognition/src/commonMain/kotlin/org/feeluown/mobile/AudioRecognitionController.kt",
)
val productionSourceRoots = listOf(
    "core/model/src/commonMain/kotlin",
    "feature/recognition/src/commonMain/kotlin",
    "playback/api/src/commonMain/kotlin",
    "playback/runtime/src/commonMain/kotlin",
    "provider/api/src/commonMain/kotlin",
    "shared/src/commonMain/kotlin",
    "shared/src/androidMain/kotlin",
    "shared/src/iosMain/kotlin",
    "androidApp/src/main/kotlin",
)
val playbackRuntimeAdapterFiles = listOf(
    "androidApp/src/main/kotlin/org/feeluown/mobile/AndroidPlaybackRuntime.kt",
    "shared/src/iosMain/kotlin/org/feeluown/mobile/IosPlaybackRuntime.kt",
)
val platformCompositionRootFiles = listOf(
    "androidApp/src/main/kotlin/org/feeluown/mobile/AndroidAppContainer.kt",
    "shared/src/iosMain/kotlin/org/feeluown/mobile/IosAppHost.kt",
)
val appRootFile = "shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppRoot.kt"

tasks.register("checkArchitectureBoundaries") {
    group = "verification"
    description = "Reject retired P2 compatibility code, controller dependencies, or missing physical feature boundaries."

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
    val productionSources = provider {
        productionSourceRoots.flatMap { path ->
            val root = rootProject.file(path)
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            } else {
                emptyList()
            }
        }.distinct()
    }
    inputs.files(sourceFiles)
    inputs.files(commonMainSources)
    inputs.files(productionSources)
    inputs.files(playbackRuntimeAdapterFiles.map(rootProject::file))
    inputs.files(platformCompositionRootFiles.map(rootProject::file))
    inputs.files(requiredPhysicalFeatureFiles.map(rootProject::file))
    inputs.file(rootProject.file(appRootFile))

    doLast {
        val retiredCompatViolations = retiredControllerCompatibilityFiles
            .map(rootProject::file)
            .filter { it.isFile }
            .map { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
        if (retiredCompatViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Retired P2 compatibility files were reintroduced:")
                    retiredCompatViolations.forEach { appendLine(" - $it") }
                    append("Use app ports or dedicated feature/playback owners instead.")
                },
            )
        }

        val missingPhysicalFeatureFiles = requiredPhysicalFeatureFiles
            .map(rootProject::file)
            .filterNot { it.isFile }
            .map { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
        if (missingPhysicalFeatureFiles.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Required P2 physical feature boundary is missing:")
                    missingPhysicalFeatureFiles.forEach { appendLine(" - $it") }
                    append("Keep Recognition owned by :feature:recognition instead of moving it back into :shared.")
                },
            )
        }

        val controllerPattern = Regex("\\bFuoPlayerController\\b")
        val violations = productionSources.get().flatMap { file ->
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
                    appendLine("FuoPlayerController was reintroduced into production source:")
                    violations.forEach { appendLine(" - $it") }
                    append("P2 production code must use feature-owned state/actions or narrow app/playback/provider contracts.")
                },
            )
        }

        val appRoot = rootProject.file(appRootFile)
        val retiredAppShellReads = listOf(
            "controller.isSettingsLoaded",
            "controller.onboardingCompleted",
            "controller.playlistOperationFeedback",
            "controller.downloadQueueFeedback",
            "controller.playbackFeedback",
            "controller.localMetadataEditorTrack",
            "DebugLogScreen(controller",
            "DownloadManagerScreen(controller",
            "LocalMusicCollectionScreen(controller",
            "LocalMetadataDialog(controller",
        )
        val appRootViolations = appRoot.readLines().mapIndexedNotNull { index, line ->
            retiredAppShellReads.firstOrNull(line::contains)?.let { legacyRead ->
                "${appRoot.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1} ($legacyRead)"
            }
        }
        if (appRootViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("AppRoot reintroduced retired app-shell controller reads:")
                    appRootViolations.forEach { appendLine(" - $it") }
                    append("Use AppUiState or the owning feature port for app-shell state and feedback.")
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
