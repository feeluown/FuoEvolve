package org.feeluown.mobile

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWebLoginProcessEnvironmentTest {
    @Test
    fun packagedRuntimeDirectoriesAreDiscoveredWithoutLauncherEnvironment() {
        val resources = Files.createTempDirectory("fuoevolve-nucleus-resources")
        try {
            val webview = resources.resolve("native/webview")
            val libs = webview.resolve("lib").createDirectories()
            val exec = webview.resolve("webkit2gtk-4.1").createDirectories()
            val injected = exec.resolve("injected-bundle").createDirectories()
            val gio = webview.resolve("gio/modules").createDirectories()
            val environment = mutableMapOf("LD_LIBRARY_PATH" to "/host/lib")

            configureDesktopWebLoginProcessEnvironment(
                environment = environment,
                sourceEnvironment = emptyMap(),
                resourcesDir = resources.toFile(),
            )

            assertEquals("${libs.toFile().absolutePath}:/host/lib", environment["LD_LIBRARY_PATH"])
            assertEquals(exec.toFile().absolutePath, environment["WEBKIT_EXEC_PATH"])
            assertEquals(injected.toFile().absolutePath, environment["WEBKIT_INJECTED_BUNDLE_PATH"])
            assertEquals(gio.toFile().absolutePath, environment["GIO_EXTRA_MODULES"])
        } finally {
            resources.toFile().deleteRecursively()
        }
    }

    @Test
    fun explicitEnvironmentOverridesPackagedRuntime() {
        val environment = mutableMapOf<String, String>()
        configureDesktopWebLoginProcessEnvironment(
            environment = environment,
            sourceEnvironment = mapOf(
                "FUOEVOLVE_WEBVIEW_LIB_DIR" to "/explicit/lib",
                "FUOEVOLVE_WEBKIT_EXEC_PATH" to "/explicit/webkit",
            ),
            resourcesDir = null,
        )

        assertEquals("/explicit/lib", environment["LD_LIBRARY_PATH"])
        assertEquals("/explicit/webkit", environment["WEBKIT_EXEC_PATH"])
    }
}
