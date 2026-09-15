package org.feeluown.mobile.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckedFfmDesktopMpvNativeApiTest {
    @Test
    fun `resolves packaged resources beside Linux native executable`() {
        val resourcesDir = Files.createTempDirectory("fuoevolve-ffm-resources").toFile()
        try {
            File(resourcesDir, "native/lib").mkdirs()
            val executable = File(resourcesDir, "fuoevolve").apply { writeText("") }

            assertEquals(
                resourcesDir.canonicalFile,
                resolvePackagedDesktopResourcesDir(
                    osName = "Linux",
                    procSelfExe = executable,
                    executableCommand = null,
                )?.canonicalFile,
            )
        } finally {
            resourcesDir.deleteRecursively()
        }
    }

    @Test
    fun `does not treat an unrelated executable directory as packaged resources`() {
        val executableDir = Files.createTempDirectory("fuoevolve-ffm-executable").toFile()
        try {
            val executable = File(executableDir, "fuoevolve").apply { writeText("") }

            assertNull(
                resolvePackagedDesktopResourcesDir(
                    osName = "Linux",
                    procSelfExe = executable,
                    executableCommand = null,
                ),
            )
        } finally {
            executableDir.deleteRecursively()
        }
    }
}
