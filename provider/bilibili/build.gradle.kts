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

android {
    namespace = "org.feeluown.mobile.provider.bilibili"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val checkP5BilibiliBoundaries = tasks.register("checkP5BilibiliBoundaries") {
    group = "verification"
    description = "Checks the P5 Bilibili physical provider boundary."

    doLast {
        val forbiddenDependencies = listOf(
            "project(\":shared\")",
            "project(\":androidApp\")",
            "project(\":feature:",
            "project(\":persistence:",
            "project(\":provider:netease\")",
            "project(\":provider:qqmusic\")",
            "project(\":provider:ytmusic\")",
        )
        val buildText = project.buildFile.readText()
        forbiddenDependencies.forEach { dependency ->
            check(dependency !in buildText) { "Bilibili provider must not depend upward/across on $dependency" }
        }
        check(!rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/provider/bilibili").exists()) {
            "Bilibili implementation must not move back into :shared"
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn(checkP5BilibiliBoundaries)
}
