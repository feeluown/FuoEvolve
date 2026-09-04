// CI-facing verification tasks live here so GitHub Actions does not need to know
// the repository's feature/provider module topology. New modules are picked up by
// their standard Gradle test task names and Kover/plugin conventions.

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

subprojects {
    val subprojectPath = path

    // Bind aggregate tasks from plugin application rather than discovering task
    // instances through a lazy TaskCollection. Task-path dependencies resolve
    // after the subproject has finished registering the plugin's standard tasks,
    // so later/lazily registered tasks cannot be silently skipped.
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        ciIosTest.configure { dependsOn("$subprojectPath:allTests") }
        ciDesktopTest.configure { dependsOn("$subprojectPath:desktopTest") }
    }

    pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
        ciAndroidTest.configure { dependsOn("$subprojectPath:testAndroidHostTest") }
    }

    // Only real Kover-enabled modules expose a coverage variant. Container
    // projects such as :feature or :provider deliberately have no variants and
    // must not be added to the merge configuration.
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        rootProject.dependencies.add("kover", rootProject.project(subprojectPath))
    }

    // Boundary checks are repository-defined tasks rather than plugin-standard
    // tasks. Collect their registered names once each subproject has finished
    // evaluation, without hard-coding the feature/provider module topology.
    afterEvaluate {
        tasks.names
            .filter { it.startsWith("check") && it.endsWith("Boundaries") }
            .forEach { taskName ->
                ciArchitectureCheck.configure { dependsOn("$subprojectPath:$taskName") }
            }
    }
}
