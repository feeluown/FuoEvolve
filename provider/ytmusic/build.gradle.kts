plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "org.feeluown.mobile.provider.ytmusic"
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
            api(project(":provider:runtime"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.quickjs.kt)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// quickjs-kt's Android artifact contains native Android libraries and cannot be
// loaded by the desktop JVM used for Android host tests. The Android KMP library
// plugin names that runtime `androidHostTestRuntimeClasspath` rather than the
// legacy `*UnitTestRuntimeClasspath`, so cover both forms here.
configurations.matching {
    it.name.endsWith("UnitTestRuntimeClasspath") ||
        it.name.endsWith("AndroidHostTestRuntimeClasspath", ignoreCase = true)
}.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickJsKt.get()}"))
    }
}

val ytmusicBuildFile = layout.projectDirectory.file("build.gradle.kts")
val legacySharedYtMusicDirectory = rootProject.layout.projectDirectory.dir(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/provider/ytmusic",
)

val checkConcreteProviderBoundaries = tasks.register("checkConcreteProviderBoundaries") {
    group = "verification"
    description = "Checks the YouTube Music concrete provider module boundary."
    doLast {
        val buildText = ytmusicBuildFile.asFile.readText()
        val forbidden = listOf(
            "project(\":shared\")",
            "project(\":androidApp\")",
            "project(\":feature:",
            "project(\":persistence:",
            "project(\":provider:bilibili\")",
            "project(\":provider:netease\")",
            "project(\":provider:qqmusic\")",
        )
        forbidden.forEach { dependency ->
            check(dependency !in buildText) { "YouTube Music provider must not depend upward/across on $dependency" }
        }
        check(!legacySharedYtMusicDirectory.asFile.exists()) {
            "YouTube Music implementation must not move back into :shared"
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn(checkConcreteProviderBoundaries)
}
