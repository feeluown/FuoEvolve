plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":persistence:listening"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.credential.secure.storage)
    implementation(libs.jaudiotagger)
    implementation("io.github.vinceglb:filekit-dialogs:0.15.0")

    compileOnly("org.graalvm.sdk:nativeimage:25.0.3")

    testImplementation(kotlin("test"))
}
