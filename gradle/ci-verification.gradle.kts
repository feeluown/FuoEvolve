// CI-facing verification tasks live here so GitHub Actions does not need to know
// the repository's feature/provider module topology. New modules are picked up by
// their standard Gradle test task names and Kover conventions.

val ciArchitectureCheck = tasks.register("ciArchitectureCheck") {
    group = "verification"
    description = "Runs all repository architecture-boundary checks."
    dependsOn("checkArchitectureBoundaries")
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
    description = "Runs all iOS simulator tests on the Apple CI runner."
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

subprojects {
    val subprojectPath = path

    // Only real Kover-enabled modules expose a coverage variant. Container
    // projects such as :feature or :provider deliberately have no variants and
    // must not be added to the merge configuration.
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        rootProject.dependencies.add("kover", rootProject.project(subprojectPath))
    }

    // Collect the tasks that actually exist after each subproject has finished
    // evaluation. This avoids both lazy TaskCollection discovery gaps and false
    // assumptions that every KMP/Android target exposes every test task.
    afterEvaluate {
        val taskNames = tasks.names

        if ("testAndroidHostTest" in taskNames) {
            ciAndroidTest.configure { dependsOn("$subprojectPath:testAndroidHostTest") }
        }
        if ("iosSimulatorArm64Test" in taskNames) {
            ciIosTest.configure { dependsOn("$subprojectPath:iosSimulatorArm64Test") }
        }
        if ("desktopTest" in taskNames) {
            ciDesktopTest.configure { dependsOn("$subprojectPath:desktopTest") }
        }

        taskNames
            .filter { it.startsWith("check") && it.endsWith("Boundaries") }
            .forEach { taskName ->
                ciArchitectureCheck.configure { dependsOn("$subprojectPath:$taskName") }
            }
    }
}
