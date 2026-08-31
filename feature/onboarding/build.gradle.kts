plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "org.feeluown.mobile.feature.onboarding"
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

val onboardingForbiddenDependencies = listOf(
    "AppSettings",
    "AppSettingsRepository",
    "ProviderMusicRepository",
    "ProviderCatalogFeatureController",
    "ProviderCatalogUiState",
    "ProviderInfo",
    "ProviderSessionState",
    "UnavailablePlaybackPolicy",
    "AppNavigator",
    "AppRoute",
)

val onboardingRequiredFiles = listOf(
    "feature/onboarding/build.gradle.kts",
    "feature/onboarding/src/commonMain/kotlin/org/feeluown/mobile/feature/onboarding/OnboardingFeature.kt",
    "feature/onboarding/src/commonTest/kotlin/org/feeluown/mobile/feature/onboarding/OnboardingFeatureTest.kt",
    "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/onboarding/OnboardingFeatureController.kt",
)

tasks.register("checkOnboardingFeatureBoundaries") {
    group = "verification"
    description = "Reject shared back-dependencies, aggregate app/provider contracts, or shared Onboarding ownership."

    inputs.files(onboardingRequiredFiles.map(rootProject::file))
    inputs.file(rootProject.file("feature/onboarding/build.gradle.kts"))
    inputs.dir(rootProject.file("feature/onboarding/src/commonMain/kotlin"))

    doLast {
        val missing = onboardingRequiredFiles.map(rootProject::file).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Onboarding physical boundary is incomplete:")
                    missing.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val buildFile = rootProject.file("feature/onboarding/build.gradle.kts")
        if (Regex("""project\(\s*[\"']?:shared[\"']?\s*\)""").containsMatchIn(buildFile.readText())) {
            throw GradleException(":feature:onboarding must not depend on :shared; bind application types in shared.")
        }

        val sourceRoot = rootProject.file("feature/onboarding/src/commonMain/kotlin")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    onboardingForbiddenDependencies.firstOrNull { dependency ->
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
                    appendLine(":feature:onboarding leaked application/shared dependencies:")
                    violations.forEach { appendLine(" - $it") }
                },
            )
        }

        val sharedBinding = rootProject.file(
            "shared/src/commonMain/kotlin/org/feeluown/mobile/feature/onboarding/OnboardingFeatureController.kt",
        ).readText()
        if ("class DefaultOnboardingFeatureController" in sharedBinding) {
            throw GradleException("Onboarding business ownership must stay in :feature:onboarding, not shared.")
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn("checkOnboardingFeatureBoundaries")
}
