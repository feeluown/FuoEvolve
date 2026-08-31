plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "org.feeluown.mobile.feature.providerauth"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
        withHostTest {}

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

val providerFeatureSpecs = mapOf(
    "providercatalog" to listOf(
        "ProviderMusicRepository",
        "ProviderSessionRepository",
        "AppSettingsRepository",
        "ProviderInfo",
        "ProviderFeature",
        "ProviderCapabilities",
        "ProviderDisplaySection",
        "AppSettings",
        "SettingsState",
    ),
    "providerauth" to listOf(
        "ProviderMusicRepository",
        "ProviderSessionRepository",
        "ProviderAuthRepository",
        "OAuthDeviceCodeAssistant",
        "ProviderInfo",
        "ProviderAuthState",
        "ProviderSessionState",
        "ProviderHeaderInput",
        "ProviderOAuthInput",
        "YtMusicOAuth",
        "YtMusicOAuthFlowUiState",
    ),
)

val providerRequiredFiles = listOf(
    "feature/providercatalog/build.gradle.kts",
    "feature/providercatalog/src/commonMain/kotlin/org/feeluown/mobile/ProviderCatalogFeature.kt",
    "feature/providercatalog/src/commonTest/kotlin/org/feeluown/mobile/feature/providercatalog/ProviderCatalogFeatureTest.kt",
    "feature/providerauth/build.gradle.kts",
    "feature/providerauth/src/commonMain/kotlin/org/feeluown/mobile/ProviderAuthFeature.kt",
    "feature/providerauth/src/commonTest/kotlin/org/feeluown/mobile/feature/providerauth/ProviderAuthFeatureTest.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderCatalogFeatureFactory.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderAuthFeatureFactory.kt",
)

val providerRetiredSharedOwners = listOf(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderCatalogController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderAuthFeatureController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderAuthController.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/provider/ProviderAuthControllerState.kt",
)

tasks.register("checkProviderFeatureBoundaries") {
    group = "verification"
    description = "Reject shared back-dependencies or concrete application types in provider catalog/auth feature modules."

    inputs.files(providerRequiredFiles.map(rootProject::file))
    inputs.files(providerRetiredSharedOwners.map(rootProject::file))
    providerFeatureSpecs.keys.forEach { feature ->
        inputs.file(rootProject.file("feature/$feature/build.gradle.kts"))
        inputs.dir(rootProject.file("feature/$feature/src/commonMain/kotlin"))
    }

    doLast {
        val missing = providerRequiredFiles.map(rootProject::file).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Provider feature physical boundary is incomplete:")
                    missing.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val reintroduced = providerRetiredSharedOwners.map(rootProject::file).filter { it.isFile }
        if (reintroduced.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Retired shared provider feature owners were reintroduced:")
                    reintroduced.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        providerFeatureSpecs.forEach { (feature, forbiddenDependencies) ->
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
    dependsOn("checkProviderFeatureBoundaries")
}
