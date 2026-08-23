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
    namespace = "org.feeluown.mobile.provider.qqmusic"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val checkConcreteProviderBoundaries = tasks.register("checkConcreteProviderBoundaries") {
    group = "verification"
    description = "Checks the QQ Music concrete provider module boundary."
    doLast {
        val buildText = project.buildFile.readText()
        val forbidden = listOf(
            "project(\":shared\")",
            "project(\":androidApp\")",
            "project(\":feature:",
            "project(\":persistence:",
            "project(\":provider:bilibili\")",
            "project(\":provider:netease\")",
            "project(\":provider:ytmusic\")",
        )
        forbidden.forEach { dependency ->
            check(dependency !in buildText) { "QQ Music provider must not depend upward/across on $dependency" }
        }
        check(!rootProject.file("shared/src/commonMain/kotlin/org/feeluown/mobile/provider/qqmusic").exists()) {
            "QQ Music implementation must not move back into :shared"
        }
        check(!rootProject.file("shared/src/androidMain/kotlin/org/feeluown/mobile/provider/qqmusic").exists()) {
            "QQ Music Android implementation must not move back into :shared"
        }
        check(!rootProject.file("shared/src/iosMain/kotlin/org/feeluown/mobile/provider/qqmusic").exists()) {
            "QQ Music iOS implementation must not move back into :shared"
        }
    }
}

tasks.matching { it.name == "allTests" }.configureEach {
    dependsOn(checkConcreteProviderBoundaries)
}
