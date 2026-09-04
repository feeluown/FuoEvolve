// CI-facing verification tasks live here so GitHub Actions does not need to know
// the repository's feature/provider module topology. New modules are picked up by
// their standard Gradle test task names and Kover plugin application.

// The root project is the Kover merge project. Every production subproject is a
// coverage dependency; Kover will collect JVM/Android-host coverage from modules
// where the plugin is applied by the root build.
dependencies {
    subprojects.forEach { subproject ->
        add("kover", project(subproject.path))
    }
}

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

// Resolve task topology after all projects have been configured. CI entry points
// therefore follow module ownership automatically instead of duplicating a module
// list in workflow YAML.
gradle.projectsEvaluated {
    val architectureTasks = allprojects
        .flatMap { project -> project.tasks.toList() }
        .filter { task ->
            task.name != ciArchitectureCheck.name &&
                task.name.startsWith("check") &&
                task.name.endsWith("Boundaries")
        }
    ciArchitectureCheck.configure { dependsOn(architectureTasks) }

    val androidHostTests = subprojects.mapNotNull { project ->
        project.tasks.findByName("testAndroidHostTest")
    }
    ciAndroidTest.configure { dependsOn(androidHostTests) }

    val multiplatformTests = subprojects
        .filter { project -> project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") }
        .mapNotNull { project -> project.tasks.findByName("allTests") }
    ciIosTest.configure { dependsOn(multiplatformTests) }

    val desktopTargetTests = subprojects.mapNotNull { project ->
        project.tasks.findByName("desktopTest")
    }
    ciDesktopTest.configure { dependsOn(desktopTargetTests) }
}
