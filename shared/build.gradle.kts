plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.kover)
}

kotlin {
    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi")
    }

    android {
        namespace = "org.feeluown.mobile.shared"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }

        lint {
            disable.add("NullSafeMutableLiveData")
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
            implementation(project(":playback:runtime"))
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
            api(project(":feature:playback"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.animation)
            implementation(libs.compose.material3.expressive)
            implementation(libs.material.kolor)
            implementation(libs.compose.material.icons.extended)
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
            implementation(libs.androidx.media3.database)
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
        desktopMain.dependencies {
            implementation(libs.jna)
        }
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
    "provider/runtime/src/desktopMain/kotlin/org/feeluown/mobile/provider/core/network/ProviderNetwork.desktop.kt",
    "provider/runtime/src/commonMain/kotlin/org/feeluown/mobile/ProviderFailureMapping.kt",
    "playback/api/src/commonMain/kotlin/org/feeluown/mobile/PlaybackContracts.kt",
    "persistence/settings/build.gradle.kts",
    "persistence/settings/src/commonMain/kotlin/org/feeluown/mobile/SettingsPersistence.kt",
    "persistence/settings/src/commonTest/kotlin/org/feeluown/mobile/SettingsPersistenceTest.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/core/model/AppSettingsContracts.kt",
    "feature/playback/src/commonMain/kotlin/org/feeluown/mobile/PlaybackApplicationContracts.kt",
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

val checkP4ContractBoundaries by tasks.registering {
    group = "verification"
    description = "Verifies P4 contract files are owned by the expected modules."

    doLast {
        val missing = p4RequiredContractFiles.filterNot { rootProject.file(it).isFile }
        check(missing.isEmpty()) {
            "Missing P4 contract files: ${missing.joinToString()}"
        }

        val forbiddenRoots = listOf(
            rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/core/model"),
        )
        val violations = buildList {
            forbiddenRoots.filter { it.exists() }.forEach { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        val content = file.readText()
                        p4MovedContractNames.forEach { contract ->
                            val declarationPatterns = listOf(
                                Regex("\\b(?:data\\s+)?class\\s+$contract\\b"),
                                Regex("\\benum\\s+class\\s+$contract\\b"),
                                Regex("\\binterface\\s+$contract\\b"),
                                Regex("\\bobject\\s+$contract\\b"),
                            )
                            if (declarationPatterns.any { it.containsMatchIn(content) }) {
                                add("${file.relativeTo(rootProject.projectDir).path}: $contract")
                            }
                        }
                    }
            }
        }
        check(violations.isEmpty()) {
            "P4 moved contracts must not be re-declared in shared core model:\n${violations.joinToString("\n")}"
        }
    }
}