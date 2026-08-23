plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.kover)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            export(project(":core:model"))
            export(project(":playback:api"))
            export(project(":provider:api"))
            export(project(":persistence:settings"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":playback:api"))
            api(project(":provider:api"))
            api(project(":provider:runtime"))
            implementation(project(":provider:bilibili"))
            implementation(project(":provider:netease"))
            implementation(project(":provider:qqmusic"))
            implementation(project(":provider:ytmusic"))
            api(project(":persistence:settings"))
            api(project(":feature:recognition"))
            api(project(":feature:search"))
            api(project(":feature:localplaylist"))
            api(project(":feature:localmusic"))
            api(project(":feature:download"))
            api(project(":feature:providercatalog"))
            api(project(":feature:providerauth"))
            api(project(":feature:providerdetail"))
            api(project(":feature:settings"))
            api(project(":feature:onboarding"))
            api(project(":feature:home"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(libs.compose.material3.expressive)
            implementation(libs.material.kolor)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization)
            implementation(libs.ktor.client.logging)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.ui)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(project(":playback:runtime"))
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "org.feeluown.mobile.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable.add("NullSafeMutableLiveData")
    }
}

val p4RequiredContractFiles = listOf(
    "core/model/src/commonMain/kotlin/org/feeluown/mobile/TrackSourceType.kt",
    "core/model/src/commonMain/kotlin/org/feeluown/mobile/MediaModels.kt",
    "provider/api/src/commonMain/kotlin/org/feeluown/mobile/ProviderContracts.kt",
    "provider/api/src/commonMain/kotlin/org/feeluown/mobile/ProviderRepositoryContracts.kt",
    "provider/api/src/commonMain/kotlin/org/feeluown/mobile/ProviderFailureContracts.kt",
    "provider/api/src/commonMain/kotlin/org/feeluown/mobile/ProviderVideoContracts.kt",
    "provider/runtime/build.gradle.kts",
    "provider/runtime/src/commonMain/kotlin/org/feeluown/mobile/provider/core/ProviderSupport.kt",
    "provider/runtime/src/commonMain/kotlin/org/feeluown/mobile/provider/core/network/ProviderNetwork.kt",
    "provider/runtime/src/androidMain/kotlin/org/feeluown/mobile/provider/core/network/ProviderNetwork.android.kt",
    "provider/runtime/src/iosMain/kotlin/org/feeluown/mobile/provider/core/network/ProviderNetwork.ios.kt",
    "provider/runtime/src/commonMain/kotlin/org/feeluown/mobile/ProviderFailureMapping.kt",
    "playback/api/src/commonMain/kotlin/org/feeluown/mobile/PlaybackContracts.kt",
    "persistence/settings/build.gradle.kts",
    "persistence/settings/src/commonMain/kotlin/org/feeluown/mobile/SettingsPersistence.kt",
    "persistence/settings/src/commonTest/kotlin/org/feeluown/mobile/SettingsPersistenceTest.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/core/model/AppSettingsContracts.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/core/model/PlaybackApplicationContracts.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/core/model/ProviderApplicationContracts.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/core/model/RepositoryContracts.kt",
)

val p4MovedContractNames = listOf(
    "TrackSourceType",
    "MediaRefType",
    "MediaRef",
    "LocalMusicScanSettings",
    "LocalMusicDirectory",
    "LocalTrackMetadata",
    "MusicTrack",
    "ProviderLoginMode",
    "ProviderHeaderInput",
    "ProviderOAuthInput",
    "ProviderAuthState",
    "ProviderCapabilities",
    "ProviderResourceState",
    "ProviderMutationResult",
    "ProviderLoginConfig",
    "ProviderInfo",
    "ProviderFeatureCategory",
    "ProviderContentType",
    "ProviderFeature",
    "ProviderPlaylist",
    "ProviderMediaItemType",
    "ProviderMediaItem",
    "ProviderVideo",
    "ProviderComment",
    "VideoPlaybackPayload",
    "ProviderSearchResults",
    "ProviderContentSection",
    "ProviderPlaylistDetail",
    "ProviderMediaItemDetail",
    "ProviderDeviceAuthorization",
    "ProviderOAuthToken",
    "ProviderFailureKind",
    "ProviderFailure",
    "ProviderOperationException",
    "ProviderVideoStat",
    "ProviderVideoMetadata",
    "AudioQualityPolicy",
    "UnavailablePlaybackPolicy",
    "RepeatMode",
    "SmartReplacementSelection",
    "PlaybackPart",
    "PlaybackPayload",
    "PlayerStatus",
    "SleepTimerMode",
    "SleepTimerState",
    "TrackChangeDirection",
    "PlayMode",
    "AudioDecoderType",
    "AudioDecoderInfo",
    "AudioFormatInfo",
)

val p4IosExportedModules = listOf(
    ":core:model",
    ":playback:api",
    ":provider:api",
    ":persistence:settings",
)

tasks.register("checkP4ContractBoundaries") {
    group = "verification"
    description = "Reject restoration of shared contracts/runtime/persistence infrastructure or invalid lower-layer dependencies."

    val retiredSharedFiles = listOf(
        rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/core/model/FuoContracts.kt"),
        rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/core/model/MediaContracts.kt"),
        rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/core/model/ProviderFailure.kt"),
        rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/provider/core/ProviderSupport.kt"),
        rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/provider/core/network/ProviderNetwork.kt"),
        rootProject.file("shared/src/androidMain/kotlin/org/feeluown/mobile/provider/core/network/ProviderNetwork.android.kt"),
        rootProject.file("shared/src/iosMain/kotlin/org/feeluown/mobile/provider/core/network/ProviderNetwork.ios.kt"),
    )
    val requiredFiles = p4RequiredContractFiles.map(rootProject::file)
    val coreBuildFile = rootProject.file("core/model/build.gradle.kts")
    val providerApiBuildFile = rootProject.file("provider/api/build.gradle.kts")
    val providerRuntimeBuildFile = rootProject.file("provider/runtime/build.gradle.kts")
    val playbackApiBuildFile = rootProject.file("playback/api/build.gradle.kts")
    val settingsPersistenceBuildFile = rootProject.file("persistence/settings/build.gradle.kts")
    val lowerBuildFiles = listOf(
        coreBuildFile,
        providerApiBuildFile,
        providerRuntimeBuildFile,
        playbackApiBuildFile,
        settingsPersistenceBuildFile,
    )
    val sharedBuildFile = rootProject.file("shared/build.gradle.kts")
    val sharedModelRoot = rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/core/model")
    val sharedCommonRoot = rootProject.file("shared/src/commonMain/kotlin")
    val sharedProviderCapabilityFile = rootProject.file(
        "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderRepositoryCapabilities.kt",
    )
    val coreMediaFile = rootProject.file("core/model/src/commonMain/kotlin/org/feeluown/mobile/MediaModels.kt")
    val providerContractsFile = rootProject.file("provider/api/src/commonMain/kotlin/org/feeluown/mobile/ProviderContracts.kt")
    val providerRepositoryContractsFile = rootProject.file(
        "provider/api/src/commonMain/kotlin/org/feeluown/mobile/ProviderRepositoryContracts.kt",
    )
    val providerLowerRoots = listOf(
        rootProject.file("provider/api/src"),
        rootProject.file("provider/runtime/src"),
    )
    val playbackApplicationContractsFile = rootProject.file(
        "shared/src/commonMain/kotlin/org/feeluown/mobile/core/model/PlaybackApplicationContracts.kt",
    )

    inputs.files(requiredFiles)
    inputs.files(lowerBuildFiles)
    inputs.file(sharedBuildFile)
    inputs.dir(sharedModelRoot)
    inputs.dir(sharedCommonRoot)
    inputs.file(sharedProviderCapabilityFile)
    providerLowerRoots.forEach(inputs::dir)

    doLast {
        val restoredRetiredFiles = retiredSharedFiles.filter { it.isFile }
        if (restoredRetiredFiles.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Retired shared contract/runtime files were restored:")
                    restoredRetiredFiles.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val missing = requiredFiles.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("P4 contract/runtime/persistence boundary files are missing:")
                    missing.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val sharedBackDependencies = lowerBuildFiles.filter { file ->
            file.readText().contains("project(\":shared\")")
        }
        if (sharedBackDependencies.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Lower modules must not depend on :shared:")
                    sharedBackDependencies.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val coreBuildText = coreBuildFile.readText()
        val forbiddenCoreDependencies = listOf(":provider:api", ":provider:runtime", ":playback:api", ":feature:", ":persistence:").filter { dependency ->
            coreBuildText.contains("project(\"$dependency")
        }
        if (forbiddenCoreDependencies.isNotEmpty()) {
            throw GradleException(
                ":core:model must remain the dependency floor; forbidden project dependencies: ${forbiddenCoreDependencies.joinToString()}",
            )
        }

        val providerApiBuildText = providerApiBuildFile.readText()
        if (!providerApiBuildText.contains("api(project(\":core:model\"))")) {
            throw GradleException(":provider:api must consume :core:model as its public media identity boundary.")
        }
        val forbiddenProviderApiDependencies = listOf(":shared", ":feature:", ":playback:", ":provider:runtime", ":persistence:").filter { dependency ->
            providerApiBuildText.contains("project(\"$dependency")
        }
        if (forbiddenProviderApiDependencies.isNotEmpty()) {
            throw GradleException(
                ":provider:api must remain provider-neutral and independent from runtime/playback/app/persistence layers: ${forbiddenProviderApiDependencies.joinToString()}",
            )
        }

        val providerRuntimeBuildText = providerRuntimeBuildFile.readText()
        if (!providerRuntimeBuildText.contains("api(project(\":provider:api\"))")) {
            throw GradleException(":provider:runtime must expose :provider:api as its contract boundary.")
        }
        if (!providerRuntimeBuildText.contains("api(project(\":playback:api\"))")) {
            throw GradleException(":provider:runtime must depend on :playback:api for resolved playback payload SPI.")
        }
        val forbiddenProviderRuntimeDependencies = listOf(":shared", ":feature:", ":persistence:").filter { dependency ->
            providerRuntimeBuildText.contains("project(\"$dependency")
        }
        if (forbiddenProviderRuntimeDependencies.isNotEmpty()) {
            throw GradleException(
                ":provider:runtime must not depend on app/feature/persistence layers: ${forbiddenProviderRuntimeDependencies.joinToString()}",
            )
        }

        val settingsPersistenceBuildText = settingsPersistenceBuildFile.readText()
        val forbiddenSettingsPersistenceDependencies = listOf(
            ":shared",
            ":feature:",
            ":provider:",
            ":playback:",
            ":core:",
        ).filter { dependency -> settingsPersistenceBuildText.contains("project(\"$dependency") }
        if (forbiddenSettingsPersistenceDependencies.isNotEmpty()) {
            throw GradleException(
                ":persistence:settings must remain an implementation-only storage boundary: ${forbiddenSettingsPersistenceDependencies.joinToString()}",
            )
        }

        val sharedCommonDataStoreLeaks = sharedCommonRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.contains("androidx.datastore")) {
                        "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1}"
                    } else {
                        null
                    }
                }.asSequence()
            }
            .toList()
        if (sharedCommonDataStoreLeaks.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("DataStore implementation leaked back into shared commonMain:")
                    sharedCommonDataStoreLeaks.forEach { appendLine(" - $it") }
                },
            )
        }

        val lowerProviderSourceText = providerLowerRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .joinToString("\n") { it.readText() }
        if (lowerProviderSourceText.contains("org.feeluown.mobile.provider.ytmusic")) {
            throw GradleException(":provider:api/:provider:runtime must not expose concrete YTMusic types.")
        }

        val coreMediaText = coreMediaFile.readText()
        if (coreMediaText.contains("ProviderMediaItem") || coreMediaText.contains("ProviderMediaItemType")) {
            throw GradleException(":core:model media contracts must not reference provider aggregate media types.")
        }
        if (!coreMediaText.contains("val artistItems: List<MediaRef>")) {
            throw GradleException("MusicTrack must store provider-neutral MediaRef values for artist navigation metadata.")
        }

        val providerContractsText = providerContractsFile.readText()
        if (
            !providerContractsText.contains("typealias ProviderMediaItemType = MediaRefType") ||
            !providerContractsText.contains("typealias ProviderMediaItem = MediaRef")
        ) {
            throw GradleException("Provider media compatibility names must remain aliases to the core MediaRef contracts during P4.")
        }

        val providerRepositoryContractsText = providerRepositoryContractsFile.readText()
        if (
            !providerRepositoryContractsText.contains("val artists: List<MediaRef>") ||
            !providerRepositoryContractsText.contains("val mediaItems: List<MediaRef>") ||
            Regex("\\bProviderMediaItem\\b").containsMatchIn(providerRepositoryContractsText)
        ) {
            throw GradleException(":provider:api repository contracts must use canonical MediaRef naming.")
        }

        val sharedCapabilityText = sharedProviderCapabilityFile.readText()
        val providerNeutralDeclarationsStillInShared = listOf(
            "interface ProviderSearchRepository",
            "interface ProviderAuthRepository",
            "data class ProviderDeviceAuthorization",
            "data class ProviderOAuthToken",
            "sealed interface ProviderDeviceAuthorizationPollResult",
        ).filter(sharedCapabilityText::contains)
        if (providerNeutralDeclarationsStillInShared.isNotEmpty()) {
            throw GradleException(
                "Provider-neutral capability contracts must live in :provider:api, not :shared: ${providerNeutralDeclarationsStillInShared.joinToString()}",
            )
        }

        val playbackApplicationText = playbackApplicationContractsFile.readText()
        if (!playbackApplicationText.contains("private const val CURRENT_VERSION = \"v2\"")) {
            throw GradleException("P4 must preserve the existing v2 playback queue persistence format.")
        }

        val sharedBuildText = sharedBuildFile.readText()
        if (!sharedBuildText.contains("api(project(\":provider:runtime\"))")) {
            throw GradleException(":shared must consume the physical :provider:runtime boundary for concrete provider integration.")
        }
        if (!sharedBuildText.contains("api(project(\":persistence:settings\"))")) {
            throw GradleException(":shared must consume the physical :persistence:settings boundary for app settings storage.")
        }
        val missingIosExports = p4IosExportedModules.filterNot { module ->
            sharedBuildText.contains("export(project(\"$module\"))")
        }
        if (missingIosExports.isNotEmpty()) {
            throw GradleException(
                "Shared.framework must re-export lower public contracts for Swift ABI compatibility: ${missingIosExports.joinToString()}",
            )
        }

        val declarationPattern = Regex(
            "\\b(?:data\\s+class|enum\\s+class|class)\\s+(?:${p4MovedContractNames.joinToString("|")})\\b",
        )
        val duplicateDeclarations = sharedModelRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (declarationPattern.containsMatchIn(line)) {
                        "${file.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${index + 1}"
                    } else {
                        null
                    }
                }.asSequence()
            }
            .toList()
        if (duplicateDeclarations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Contracts moved to lower modules were redeclared in :shared:")
                    duplicateDeclarations.forEach { appendLine(" - $it") }
                },
            )
        }
    }
}

tasks.named("allTests") {
    dependsOn("checkP4ContractBoundaries")
    dependsOn(":persistence:settings:allTests")
    dependsOn(":provider:bilibili:allTests")
    dependsOn(":provider:netease:allTests")
    dependsOn(":provider:qqmusic:allTests")
    dependsOn(":provider:ytmusic:allTests")
}
