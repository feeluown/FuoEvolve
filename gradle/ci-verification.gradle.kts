// CI-facing verification tasks live here so GitHub Actions does not need to know
// the repository's feature/provider module topology. New modules are picked up by
// their standard Gradle test task names and Kover conventions.

val appLoggerSourceRoots = listOf(
    "androidApp/src/main/kotlin",
    "desktopApp/src/main/kotlin",
    "shared/src/androidMain/kotlin",
    "shared/src/desktopMain/kotlin",
    "shared/src/iosMain/kotlin",
)
val appLoggerPlatformSinkFiles = setOf(
    "androidApp/src/main/kotlin/org/feeluown/mobile/AndroidAppLogger.kt",
    "shared/src/desktopMain/kotlin/org/feeluown/mobile/DesktopAppLogger.kt",
    "shared/src/iosMain/kotlin/org/feeluown/mobile/IosAppLogger.kt",
)

val checkAppLoggerUsage = tasks.register("checkAppLoggerUsage") {
    group = "verification"
    description = "Rejects direct platform logging outside AppLogger platform sinks."
    val sources = provider {
        appLoggerSourceRoots.flatMap { path ->
            val root = rootProject.file(path)
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            } else {
                emptyList()
            }
        }
    }
    inputs.files(sources)

    doLast {
        val forbiddenPatterns = listOf(
            "android.util.Log" to Regex("\\bandroid\\.util\\.Log\\b|^\\s*import\\s+android\\.util\\.Log\\b"),
            "System.out/System.err" to Regex("\\bSystem\\.(?:out|err)\\.(?:print|println)\\s*\\("),
            "NSLog" to Regex("\\bNSLog\\s*\\("),
        )
        val violations = sources.get().flatMap { file ->
            val relative = file.relativeTo(rootProject.projectDir).invariantSeparatorsPath
            if (relative in appLoggerPlatformSinkFiles) return@flatMap emptyList()
            file.readLines().mapIndexedNotNull { index, line ->
                val trimmed = line.trimStart()
                val commentOnly = trimmed.startsWith("//") ||
                    trimmed.startsWith("/**") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("*/")
                if (commentOnly) return@mapIndexedNotNull null
                forbiddenPatterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(line) }
                    ?.let { (api, _) -> "$relative:${index + 1} ($api)" }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Direct platform logging bypasses AppLogger:")
                    violations.forEach { appendLine(" - $it") }
                    append("Route application logs through AppLogger; platform APIs are reserved for sink implementations.")
                },
            )
        }
    }
}

val ciArchitectureCheck = tasks.register("ciArchitectureCheck") {
    group = "verification"
    description = "Runs all repository architecture-boundary checks."
    dependsOn("checkArchitectureBoundaries", checkAppLoggerUsage)
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
    description = "Runs shared desktop-target tests and the Nucleus-ready desktop runtime tests."
    dependsOn(":desktopRuntime:test")
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
