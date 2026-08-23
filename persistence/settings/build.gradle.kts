plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "org.feeluown.mobile.persistence.settings"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.register("checkP4CloseoutBoundaries") {
    group = "verification"
    description = "Protect completed P4 persistence and app-shell boundaries."

    val retiredAppFiles = listOf(
        rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/app/P2AppRoot.kt"),
        rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/app/LegacyProviderDetailRouteBridge.kt"),
    )
    val appStateFile = rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppState.kt")
    val appRootFile = rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/app/AppRoot.kt")
    val platformRootFiles = listOf(
        rootProject.file("androidApp/src/main/kotlin/org/feeluown/mobile/MainActivity.kt"),
        rootProject.file("shared/src/iosMain/kotlin/org/feeluown/mobile/IosAppHost.kt"),
    )

    inputs.files(retiredAppFiles)
    inputs.file(appStateFile)
    inputs.file(appRootFile)
    inputs.files(platformRootFiles)

    doLast {
        val restored = retiredAppFiles.filter { it.isFile }
        if (restored.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Retired P4 app-shell compatibility files were restored:")
                    restored.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }

        val appStateText = appStateFile.readText()
        val aggregateAppState = listOf(
            "val settings: SettingsState",
            "val providerSessions: ProviderSessionState",
        ).filter(appStateText::contains)
        if (aggregateAppState.isNotEmpty()) {
            throw GradleException(
                "AppUiState must remain app-shell scoped; aggregate state fields were restored: ${aggregateAppState.joinToString()}",
            )
        }

        val appRootText = appRootFile.readText()
        if (!appRootText.contains("fun AppRoot(")) {
            throw GradleException("AppRoot must remain the canonical common app-shell entry point.")
        }
        if (appRootText.contains("LegacyProviderDetailRouteBridge")) {
            throw GradleException("Typed provider detail routes must not depend on the retired legacy route bridge.")
        }

        val legacyHostCallers = platformRootFiles.filter { file ->
            file.isFile && file.readText().contains("P2AppRoot(")
        }
        if (legacyHostCallers.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Platform hosts must call AppRoot directly instead of the retired P2 entry point:")
                    legacyHostCallers.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir).invariantSeparatorsPath}") }
                },
            )
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn("checkP4CloseoutBoundaries")
}
