package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeImageReachabilityMetadataTest {
    @Test
    fun credentialStorageJnaProxiesAreRegistered() {
        val resourcePath =
            "META-INF/native-image/org.feeluown/fuoevolve/reachability-metadata.json"
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resourcePath))
        val metadata = stream.bufferedReader().use { it.readText() }

        val requiredProxyInterfaces = listOf(
            "com.microsoft.credentialstorage.implementation.posix.libsecret.LibSecretLibrary",
            "com.microsoft.credentialstorage.implementation.posix.internal.GLibLibrary",
            "com.microsoft.credentialstorage.implementation.windows.CredAdvapi32",
        )

        requiredProxyInterfaces.forEach { interfaceName ->
            assertTrue(
                metadata.contains("\"$interfaceName\""),
                "Missing Native Image proxy metadata for $interfaceName",
            )
        }
    }
}
