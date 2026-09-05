plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "org.feeluown.mobile.provider.netease"
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

val neteaseBuildFile = layout.projectDirectory.file("build.gradle.kts")
val legacySharedNeteaseCommonDirectory = rootProject.layout.projectDirectory.dir(
    "shared/src/commonMain/kotlin/org/feeluown/mobile/provider/netease",
)
val legacySharedNeteaseAndroidDirectory = rootProject.layout.projectDirectory.dir(
    "shared/src/androidMain/kotlin/org/feeluown/mobile/provider/netease",
)
val legacySharedNeteaseIosDirectory = rootProject.layout.projectDirectory.dir(
    "shared/src/iosMain/kotlin/org/feeluown/mobile/provider/netease",
)

val checkConcreteProviderBoundaries = tasks.register("checkConcreteProviderBoundaries") {
    group = "verification"
    description = "Checks the NetEase concrete provider module boundary."
    doLast {
        val buildText = neteaseBuildFile.asFile.readText()
        val forbidden = listOf(
            "project(\":shared\")",
            "project(\":androidApp\")",
            "project(\":feature:",
            "project(\":persistence:",
            "project(\":provider:bilibili\")",
            "project(\":provider:qqmusic\")",
            "project(\":provider:ytmusic\")",
        )
        forbidden.forEach { dependency ->
            check(dependency !in buildText) { "NetEase provider must not depend upward/across on $dependency" }
        }
        check(!legacySharedNeteaseCommonDirectory.asFile.exists()) {
            "NetEase implementation must not move back into :shared"
        }
        check(!legacySharedNeteaseAndroidDirectory.asFile.exists()) {
            "NetEase Android implementation must not move back into :shared"
        }
        check(!legacySharedNeteaseIosDirectory.asFile.exists()) {
            "NetEase iOS implementation must not move back into :shared"
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn(checkConcreteProviderBoundaries)
}
