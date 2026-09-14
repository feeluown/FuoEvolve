package org.feeluown.mobile

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopAudioFingerprintHelperTest {
    @Test
    fun resolvesRuntimeFromComposeApplicationResourcesDirectory() {
        val resourcesDir = Files.createTempDirectory("fuoevolve-compose-resources-")
        val helperName = if (isDesktopWindows()) {
            "fuoevolve-audio-fingerprint.exe"
        } else {
            "fuoevolve-audio-fingerprint"
        }
        val runtimeDir = resourcesDir.resolve("native/fingerprint")
        val helper = runtimeDir.resolve(helperName)
        val wasm = runtimeDir.resolve("afp.wasm")
        runtimeDir.createDirectories()
        helper.createFile()
        wasm.createFile()
        if (!isDesktopWindows()) {
            helper.toFile().setExecutable(true, false)
        }

        val propertyName = "compose.application.resources.dir"
        val previous = System.getProperty(propertyName)
        try {
            System.setProperty(propertyName, resourcesDir.toString())
            val resolved = assertNotNull(resolveDesktopAudioFingerprintRuntime())
            assertEquals(helper.toRealPath(), resolved.executable.toPath().toRealPath())
            assertEquals(wasm.toRealPath(), resolved.wasm.toPath().toRealPath())
        } finally {
            if (previous == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previous)
            }
            resourcesDir.toFile().deleteRecursively()
        }
    }
}
