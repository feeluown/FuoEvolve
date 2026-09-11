package org.feeluown.mobile

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopAudioCaptureLibraryTest {
    @Test
    fun resolvesLibraryFromComposeApplicationResourcesDirectory() {
        val resourcesDir = Files.createTempDirectory("fuoevolve-compose-audio-resources-")
        val libraryName = when {
            isDesktopWindows() -> "fuoevolve_audio_capture.dll"
            isDesktopMac() -> "libfuoevolve_audio_capture.dylib"
            else -> "libfuoevolve_audio_capture.so"
        }
        val library = resourcesDir.resolve("native/audio/$libraryName")
        library.parent.createDirectories()
        library.createFile()

        val propertyName = "compose.application.resources.dir"
        val previous = System.getProperty(propertyName)
        try {
            System.setProperty(propertyName, resourcesDir.toString())
            val resolved = assertNotNull(resolveDesktopAudioCaptureLibrary())
            assertEquals(library.toRealPath(), resolved.toPath().toRealPath())
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
