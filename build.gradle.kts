import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlinx.kover)
}

val kotlinJvmCoverageProjects = listOf(
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
    kotlinJvmCoverageProjects.forEach { projectPath ->
        kover(project(projectPath))
    }
}

// Desktop is a product-wide KMP platform. Register it centrally so new shared/feature/provider
// modules cannot silently omit the desktop variant and break the dependency graph later.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        extensions.configure<KoverProjectExtension>("kover") {
            currentProject {
                instrumentation {
                    disabledForTestTasks.add("testAndroidHostTest")
                }
                sources {
                    excludedSourceSets.addAll("androidMain", "androidHostTest")
                }
            }
        }
        extensions.configure<KotlinMultiplatformExtension> {
            if (targets.findByName("desktop") == null) {
                jvm("desktop") {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_17)
                    }
                }
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        pluginManager.apply("org.jetbrains.kotlinx.kover")
    }
}

val migratedControllerBoundaryRoots = listOf(
    "feature/search/src/commonMain/kotlin",
    "feature/recognition/src/commonMain/kotlin",
    "playback/runtime/src/commonMain/kotlin",
)
val migratedControllerBoundaryFiles = listOf(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppFeaturePorts.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppFeaturePortAdapters.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/SearchRoute.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/app/RecognitionRoute.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/search/SearchFeatureBindings.kt",
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
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/search/SearchController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/search/SearchControllerState.kt",
    "shared/src/commonTest/kotlin/org/feeluown/mobile/SearchControllerTest.kt",
)
val requiredPhysicalFeatureFiles = listOf(
    "feature/recognition/build.gradle.kts",
    "feature/recognition/src/commonMain/kotlin/org/feeluown/mobile/AudioRecognition.kt",
    "feature/recognition/src/commonMain/kotlin/org/feeluown/mobile/AudioRecognitionController.kt",
    "feature/search/build.gradle.kts",
    "feature/search/src/commonMain/kotlin/org/feeluown/mobile/SearchFeature.kt",
    "feature/search/src/commonTest/kotlin/org/feeluown/mobile/SearchFeatureTest.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/search/SearchFeatureBindings.kt",
)
val productionSourceRoots = listOf(
    "core/model/src/commonMain/kotlin",
    "feature/recognition/src/commonMain/kotlin",
    "feature/search/src/commonMain/kotlin",
    "playback/api/src/commonMain/kotlin",
    "playback/runtime/src/commonMain/kotlin",
    "provider/api/src/commonMain/kotlin",
    "shared/src/commonMain/kotlin",
    "shared/src/androidMain/kotlin",
    "shared/src/iosMain/kotlin",
    "shared/src/desktopMain/kotlin",
    "androidApp/src/main/kotlin",
    "desktopApp/src/main/kotlin",
)
val playbackRuntimeAdapterFiles = listOf(
    "androidApp/src/main/kotlin/org/feeluown/mobile/AndroidPlaybackRuntime.kt",
    "shared/src/iosMain/kotlin/org/feeluown/mobile/IosPlaybackRuntime.kt",
)
val platformCompositionRootFiles = listOf(
    "androidApp/src/main/kotlin/org/feeluown/mobile/AndroidAppContainer.kt",
    "shared/src/iosMain/kotlin/org/feeluown/mobile/IosAppHost.kt",
    "shared/src/desktopMain/kotlin/org/feeluown/mobile/DesktopAppHost.kt",
)
val appRootFile = "shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppRoot.kt"
val searchFeatureBuildFile = "feature/search/build.gradle.kts"
val searchFeatureSourceRoot = "feature/search/src/commonMain/kotlin"

tasks.register("checkArchitectureBoundaries") {
    group = "verification"
    description = "Reject retired compatibility code, controller dependencies, or broken physical feature boundaries."

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
    inputs.file(rootProject.file(searchFeatureBuildFile))

    doLast {
        val sharedAppRoot = rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/app")
        val sharedLowerUiRoots = listOf(
            rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/feature"),
            rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/core"),
        )
        val compositionLocalDeclarationPattern = Regex(
            "\\bval\\s+(Local[A-Za-z0-9_]+)\\s*=\\s*(?:staticCompositionLocalOf|compositionLocalOf)\\b",
        )
        val appOwnedCompositionLocals = if (sharedAppRoot.isDirectory) {
            sharedAppRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    compositionLocalDeclarationPattern.findAll(file.readText())
                        .map { match -> match.groupValues[1] }
                }
                .toSet()
        } else {
            emptySet()
        }
        val appOwnedAmbientViolations = sharedLowerUiRoots
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val trimmed = line.trimStart()
                    val commentOnly = trimmed.startsWith("//") ||
                        trimmed.startsWith("/**") ||
                        trimmed.startsWith("*") ||
                        trimmed.startsWith("*/")
                    if (commentOnly) return@mapIndexedNotNull null
                    appOwnedCompositionLocals.firstOrNull { symbol ->
                        Regex("\\b${Regex.escape(symbol)}\\b").containsMatchIn(line)
                    }?.let { symbol ->
                        "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1} ($symbol)"
                    }
                }
            }
        if (appOwnedAmbientViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Feature/core UI depends upward on app-owned CompositionLocal declarations:")
                    appOwnedAmbientViolations.forEach { appendLine(" - $it") }
                    append("Move cross-feature UI ambients to shared/core/ui and let the app shell only provide their values.")
                },
            )
        }

        val retiredCompatViolations = retiredControllerCompatibilityFiles
            .map(rootProject::file)
            .filter { it.isFile }
            .map { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
        if (retiredCompatViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Retired architecture compatibility files were reintroduced:")
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
                    appendLine("Required physical feature boundary is missing:")
                    missingPhysicalFeatureFiles.forEach { appendLine(" - $it") }
                    append("Keep migrated feature ownership in its physical Gradle module instead of moving it back into :shared.")
                },
            )
        }

        val searchBuild = rootProject.file(searchFeatureBuildFile)
        if (searchBuild.readText().contains("project(\":shared\")")) {
            throw GradleException(":feature:search must not depend on :shared; adapt application repositories in the shared composition layer.")
        }

        val forbiddenSearchDependencies = listOf(
            "ProviderMusicRepository",
            "LocalMusicRepository",
            "ProviderSearchResults",
            "MusicTrack",
            "RecognizedSong",
        )
        val searchRoot = rootProject.file(searchFeatureSourceRoot)
        val searchDependencyViolations = if (searchRoot.isDirectory) {
            searchRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        forbiddenSearchDependencies.firstOrNull(line::contains)?.let { dependency ->
                            "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1} ($dependency)"
                        }
                    }.asSequence()
                }
                .toList()
        } else {
            emptyList()
        }
        if (searchDependencyViolations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(":feature:search leaked application/shared domain dependencies:")
                    searchDependencyViolations.forEach { appendLine(" - $it") }
                    append("Keep Search generic over its repository/result ports and bind concrete app models in :shared.")
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
                    append("Production code must use feature-owned state/actions or narrow app/playback/provider contracts.")
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
