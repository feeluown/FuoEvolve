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
    namespace = "org.feeluown.mobile.feature.providerdetail"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val providerDetailForbiddenDependencies = listOf(
    "ProviderMusicRepository",
    "PlaybackQueueUiPort",
    "AppSettingsRepository",
    "ProviderCatalogFeatureController",
    "AppNavigator",
    "MusicTrack",
    "ProviderFeature",
    "ProviderContentSection",
    "ProviderPlaylist",
    "ProviderFeatureCategory",
    "ProviderComment",
    "ProviderMediaItem",
    "ProviderVideo",
    "VideoPlaybackPayload",
    "AppRoute",
    "ProviderFailure",
)

val providerDetailRequiredFiles = listOf(
    "feature/providerdetail/build.gradle.kts",
    "feature/providerdetail/src/commonMain/kotlin/org/feeluown/mobile/ProviderDetailFeature.kt",
    "feature/providerdetail/src/commonTest/kotlin/org/feeluown/mobile/feature/providerdetail/ProviderDetailFeatureTest.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderDetailOwners.kt",
)

tasks.register("checkProviderDetailFeatureBoundaries") {
    group = "verification"
    description = "Reject shared back-dependencies, concrete app types, or provider-detail ownership regressions."

    inputs.files(providerDetailRequiredFiles.map(rootProject::file))
    inputs.file(rootProject.file("feature/providerdetail/build.gradle.kts"))
    inputs.dir(rootProject.file("feature/providerdetail/src/commonMain/kotlin"))
    inputs.file(rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderDetailOwners.kt"))

    doLast {
        val missing = providerDetailRequiredFiles.map(rootProject::file).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Provider Detail physical boundary is incomplete:")
                    missing.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val buildFile = rootProject.file("feature/providerdetail/build.gradle.kts")
        if (Regex("""project\(\s*[\"']?:shared[\"']?\s*\)""").containsMatchIn(buildFile.readText())) {
            throw GradleException(":feature:providerdetail must not depend on :shared; bind application types in shared.")
        }

        val sourceRoot = rootProject.file("feature/providerdetail/src/commonMain/kotlin")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    providerDetailForbiddenDependencies.firstOrNull { dependency ->
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
                    appendLine(":feature:providerdetail leaked application/shared dependencies:")
                    violations.forEach { appendLine(" - $it") }
                },
            )
        }

        val binding = rootProject.file(
            "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderDetailOwners.kt",
        ).readText()
        val retiredOwners = listOf(
            "DefaultProviderFeatureDetailController",
            "DefaultProviderPlaylistDetailController",
            "DefaultProviderTrackDetailController",
            "DefaultProviderMediaItemDetailController",
            "DefaultProviderVideoDetailController",
        ).filter(binding::contains)
        if (retiredOwners.isNotEmpty()) {
            throw GradleException("Provider Detail business owners moved back into :shared: ${retiredOwners.joinToString()}")
        }

        val stableStates = listOf(
            "ProviderFeatureDetailUiState",
            "ProviderPlaylistDetailUiState",
            "ProviderTrackDetailUiState",
            "ProviderMediaItemDetailUiState",
            "ProviderVideoDetailUiState",
        )
        val missingConcreteStates = stableStates.filter { state ->
            !Regex("data\\s+class\\s+${Regex.escape(state)}\\b").containsMatchIn(binding)
        }
        val aliasedStates = stableStates.filter { state ->
            Regex("typealias\\s+${Regex.escape(state)}\\b").containsMatchIn(binding)
        }
        if (missingConcreteStates.isNotEmpty() || aliasedStates.isNotEmpty()) {
            throw GradleException(
                "Provider Detail stable UiState classes must remain concrete in org.feeluown.mobile; " +
                    "missing=${missingConcreteStates.joinToString()} aliased=${aliasedStates.joinToString()}",
            )
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn("checkProviderDetailFeatureBoundaries")
}
