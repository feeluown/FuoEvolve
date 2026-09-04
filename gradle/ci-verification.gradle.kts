// CI-facing verification tasks live here so GitHub Actions does not need to know
// the repository's feature/provider module topology. New modules are picked up by
// their standard Gradle test task names and Kover plugin application.

val ciArchitectureCheck = tasks.register("ciArchitectureCheck") {
    group = "verification"
    description = "Runs all repository architecture-boundary checks."
}

val ciAndroidTest = tasks.register("ciAndroidTest") {
    group = "verification"
    description = "Runs all Android host tests plus Android application compile/unit validation."
    dependsOn(
        ":androidApp:checkReleaseAarMetadata",
        ":androidApp:compileDebugKotlin",
        ":androidApp:compileDebugJavaWithJavac",
        ":androidApp:processDebugResources",
        ":androidApp:testDebugUnitTest",
    )
}

val ciIosTest = tasks.register("ciIosTest") {
    group = "verification"
    description = "Runs all Kotlin Multiplatform test suites on the Apple CI runner."
}

val ciDesktopTest = tasks.register("ciDesktopTest") {
    group = "verification"
    description = "Runs all desktop-target tests plus desktop application unit tests."
    dependsOn(
        ":desktopApp:compileKotlin",
        ":desktopApp:test",
    )
}

tasks.register("ciCoverage") {
    group = "verification"
    description = "Generates the merged JVM/Android-host Kover XML report without enforcing a threshold."
    dependsOn("koverXmlReport")
}

// Observe every task, including tasks registered lazily after this script is
// applied. Filtering inside configureEach ensures future modules are wired into
// the stable CI entry points without requiring workflow changes.
tasks.configureEach {
    if (name.startsWith("check") && name.endsWith("Boundaries")) {
        ciArchitectureCheck.configure { dependsOn(this@configureEach) }
    }
}

subprojects {
    // Only real Kover-enabled modules expose a coverage variant. Container
    // projects such as :feature or :provider deliberately have no variants and
    // must not be added to the merge configuration.
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        rootProject.dependencies.add("kover", project(path))
    }

    tasks.configureEach {
        when {
            name.startsWith("check") && name.endsWith("Boundaries") ->
                ciArchitectureCheck.configure { dependsOn(this@configureEach) }

            name == "testAndroidHostTest" ->
                ciAndroidTest.configure { dependsOn(this@configureEach) }

            name == "allTests" ->
                ciIosTest.configure { dependsOn(this@configureEach) }

            name == "desktopTest" ->
                ciDesktopTest.configure { dependsOn(this@configureEach) }
        }
    }
}
