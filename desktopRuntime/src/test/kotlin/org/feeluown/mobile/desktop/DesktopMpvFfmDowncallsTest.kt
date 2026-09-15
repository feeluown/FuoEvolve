package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopMpvFfmDowncallsTest {
    @Test
    fun `registry keeps every libmpv bridge symbol unique`() {
        val downcalls = DesktopMpvFfmDowncalls.all
        val symbols = downcalls.map(DesktopMpvFfmDowncall::symbol)

        assertTrue(downcalls.isNotEmpty(), "FFM bridge registry must not be empty")
        assertTrue(
            symbols.size == symbols.toSet().size,
            "FFM bridge symbols must be unique",
        )
    }

    @Test
    fun `Native Image automatically enables the FFM registration feature`() {
        val resourcePath =
            "META-INF/native-image/org.feeluown/desktop-runtime/native-image.properties"
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resourcePath))
        val properties = stream.bufferedReader().use { it.readText() }

        assertTrue(
            properties.contains(
                "--features=org.feeluown.mobile.desktop.DesktopMpvFfmFeature",
            ),
            "Desktop FFM Native Image feature must be enabled",
        )
    }
}
