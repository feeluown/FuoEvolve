package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsVideoHostNativeImageMetadataTest {
    @Test
    fun nativeImageRegistersAllWindowsVideoHostDowncalls() {
        val resource = "META-INF/native-image/org.feeluown/fuoevolve/reachability-metadata.json"
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resource))
        val metadata = stream.bufferedReader().use { it.readText() }
            .replace(Regex("\\s+"), "")

        assertTrue(
            metadata.contains("\"foreign\":{\"downcalls\":["),
            "Missing Native Image FFM downcall metadata",
        )

        // These match the FunctionDescriptor definitions in DesktopWindowsVideoHost.kt.
        val requiredSignatures = mapOf(
            "CreateWindowExA" to
                """{"returnType":"void*","parameterTypes":["jint","void*","void*","jint","jint","jint","jint","jint","void*","void*","void*","void*"]}""",
            "ShowWindow" to
                """{"returnType":"jint","parameterTypes":["void*","jint"]}""",
            "DestroyWindow / DeleteObject" to
                """{"returnType":"jint","parameterTypes":["void*"]}""",
            "CreateRectRgn" to
                """{"returnType":"void*","parameterTypes":["jint","jint","jint","jint"]}""",
            "SetWindowRgn" to
                """{"returnType":"jint","parameterTypes":["void*","void*","jint"]}""",
        )
        requiredSignatures.forEach { (function, signature) ->
            assertTrue(
                metadata.contains(signature),
                "Missing Native Image FFM signature for $function",
            )
        }
    }
}
