package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopLibSecretFallbackTest {
    @Test
    fun bundledFallbackRegistersAllJnaLibrariesInSameDirectory() {
        val registered = mutableListOf<Pair<String, String>>()

        registerBundledLinuxLibSecretSearchPaths("/app/native") { name, path ->
            registered += name to path
        }

        assertEquals(
            listOf(
                "secret-1" to "/app/native",
                "glib-2.0" to "/app/native",
                "gobject-2.0" to "/app/native",
                "gio-2.0" to "/app/native",
            ),
            registered,
        )
    }

    @Test
    fun blankFallbackDirectoryDoesNotChangeJnaSearchPath() {
        val registered = mutableListOf<Pair<String, String>>()

        registerBundledLinuxLibSecretSearchPaths(" ") { name, path ->
            registered += name to path
        }

        assertEquals(emptyList(), registered)
    }
}
