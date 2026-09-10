plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("dev.nucleusframework") version "2.5.15"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    implementation("dev.nucleusframework:nucleus.nucleus-application:2.5.15")
    implementation("dev.nucleusframework:nucleus.decorated-window-tao:2.5.15")
    implementation("dev.nucleusframework:nucleus.graalvm-runtime:2.5.15")
}

nucleus.application {
    mainClass = "org.feeluown.mobile.nucleus.NucleusMainKt"

    graalvm {
        isEnabled.set(true)
        imageName.set("fuoevolve-nucleus-poc")
    }
}
