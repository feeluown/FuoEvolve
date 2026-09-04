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

// Wire stable root entry points to conventionally named verification tasks as
// subprojects create them. configureEach keeps this lazy and compatible with the
// configuration cache while still allowing future modules to join automatically.
tasks.matching {
    it.name != ciArchitectureCheck.name &&
        it.name.startsWith("check") &&
        it.name.endsWith("Boundaries")
}.configureEach {
    ciArchitectureCheck.configure { dependsOn(this@configureEach) }
}

subprojects {
    tasks.matching {
        it.name.startsWith("check") && it.name.endsWith("Boundaries")
    }.configureEach {
        ciArchitectureCheck.configure { dependsOn(this@configureEach) }
    }

    tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
        ciAndroidTest.configure { dependsOn(this@configureEach) }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        tasks.matching { it.name == "allTests" }.configureEach {
            ciIosTest.configure { dependsOn(this@configureEach) }
        }
    }

    tasks.matching { it.name == "desktopTest" }.configureEach {
        ciDesktopTest.configure { dependsOn(this@configureEach) }
    }
}
