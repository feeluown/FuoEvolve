package org.feeluown.mobile

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopWebViewHelperTest {
    @Test
    fun resolvesHelperFromComposeApplicationResourcesDirectory() {
        val resourcesDir = Files.createTempDirectory("fuoevolve-compose-resources-")
        val helperName = if (isDesktopWindows()) "fuoevolve-web-login.exe" else "fuoevolve-web-login"
        val helper = resourcesDir.resolve("native/helpers/$helperName")
        helper.parent.createDirectories()
        helper.createFile()
        if (!isDesktopWindows()) {
            helper.toFile().setExecutable(true, false)
        }

        val propertyName = "compose.application.resources.dir"
        val previous = System.getProperty(propertyName)
        try {
            System.setProperty(propertyName, resourcesDir.toString())
            val resolved = assertNotNull(resolveDesktopWebViewHelper())
            assertEquals(helper.toRealPath(), resolved.toPath().toRealPath())
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
