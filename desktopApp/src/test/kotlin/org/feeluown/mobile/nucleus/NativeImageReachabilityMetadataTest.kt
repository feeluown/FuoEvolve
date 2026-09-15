package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeImageReachabilityMetadataTest {
    @Test
    fun nativeImageMetadataKeepsRequiredDesktopRuntimeEntries() {
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

        val requiredWindowsCredentialFields = mapOf(
            "com.microsoft.credentialstorage.implementation.windows.CredAdvapi32\$PCREDENTIAL" to
                listOf("credential"),
            "com.microsoft.credentialstorage.implementation.windows.CredAdvapi32\$CREDENTIAL" to
                listOf(
                    "Flags",
                    "Type",
                    "TargetName",
                    "Comment",
                    "LastWritten",
                    "CredentialBlobSize",
                    "CredentialBlob",
                    "Persist",
                    "AttributeCount",
                    "Attributes",
                    "TargetAlias",
                    "UserName",
                ),
            "com.microsoft.credentialstorage.implementation.windows.CredAdvapi32\$CREDENTIAL_ATTRIBUTE" to
                listOf("Keyword", "Flags", "ValueSize", "Value"),
            "com.sun.jna.platform.win32.WinBase\$FILETIME" to
                listOf("dwLowDateTime", "dwHighDateTime"),
        )
        requiredWindowsCredentialFields.forEach { (typeName, fieldNames) ->
            assertTrue(
                metadata.contains("\"$typeName\""),
                "Missing Native Image Windows credential structure metadata for $typeName",
            )
            fieldNames.forEach { fieldName ->
                assertTrue(
                    metadata.contains("\"$fieldName\""),
                    "Missing Native Image Windows credential field metadata for $typeName.$fieldName",
                )
            }
        }
        assertTrue(
            metadata.contains(
                "\"com.microsoft.credentialstorage.implementation.windows." +
                    "CredAdvapi32\$CREDENTIAL_ATTRIBUTE\$ByReference\"",
            ),
            "Missing Native Image Windows credential attribute reference metadata",
        )

        val lastErrorType = "\"type\": \"com.sun.jna.LastErrorException\""
        val lastErrorIndex = metadata.indexOf(lastErrorType)
        assertTrue(
            lastErrorIndex >= 0,
            "Missing Native Image JNI metadata for com.sun.jna.LastErrorException",
        )
        val lastErrorMetadata = metadata.substring(
            lastErrorIndex,
            minOf(metadata.length, lastErrorIndex + 320),
        )
        assertTrue(
            lastErrorMetadata.contains("\"jniAccessible\": true"),
            "JNA LastErrorException must be JNI accessible",
        )
        assertTrue(
            lastErrorMetadata.contains("\"name\": \"<init>\"") &&
                lastErrorMetadata.contains("\"java.lang.String\""),
            "JNA LastErrorException String constructor must be available to JNI",
        )

        val requiredDatastoreFields = listOf(
            "androidx.datastore.preferences.PreferencesProto\$PreferenceMap" to "preferences_",
            "androidx.datastore.preferences.PreferencesProto\$Value" to "valueCase_",
            "androidx.datastore.preferences.PreferencesProto\$Value" to "value_",
            "androidx.datastore.preferences.PreferencesProto\$StringSet" to "strings_",
        )
        requiredDatastoreFields.forEach { (typeName, fieldName) ->
            assertTrue(
                metadata.contains("\"$typeName\"") && metadata.contains("\"$fieldName\""),
                "Missing Native Image DataStore field metadata for $typeName.$fieldName",
            )
        }

        assertTrue(
            metadata.contains("\"fuoevolve-desktop-version.properties\""),
            "Missing Native Image resource metadata for the packaged desktop version",
        )

        val compactMetadata = metadata.filterNot { it.isWhitespace() }
        val requiredDowncalls = listOf(
            """{"returnType":"jlong","parameterTypes":[]}""",
            """{"returnType":"jint","parameterTypes":["jlong"]}""",
            """{"returnType":"jint","parameterTypes":["jlong","void*","void*"]}""",
            """{"returnType":"jint","parameterTypes":["jlong","void*","void*","jlong"]}""",
            """{"returnType":"jint","parameterTypes":["jlong","void*"]}""",
            """{"returnType":"jint","parameterTypes":["jlong","jlong","void*"]}""",
            """{"returnType":"jint","parameterTypes":["jlong","jdouble","void*","jlong"]}""",
            """{"returnType":"void","parameterTypes":["jlong"]}""",
            """{"returnType":"jint","parameterTypes":["jint","void*","jlong"]}""",
            """{"returnType":"jlong","parameterTypes":["jlong"]}""",
            """{"returnType":"jlong","parameterTypes":["jlong","jint","jint","jlong","jlong"]}""",
            """{"returnType":"jlong","parameterTypes":["jint","jint"]}""",
            """{"returnType":"void","parameterTypes":["jlong","jlong"]}""",
            """{"returnType":"jlong","parameterTypes":["jlong","jint","jint"]}""",
            """{"returnType":"jint","parameterTypes":["jlong","jlong"]}""",
            """{"returnType":"jint","parameterTypes":["jlong","jint","jint","jint","void*","jlong"]}""",
            """{"returnType":"jint","parameterTypes":["jlong","void*","jlong"]}""",
        )
        requiredDowncalls.forEach { downcall ->
            assertTrue(
                compactMetadata.contains(downcall),
                "Missing Native Image FFM downcall metadata for $downcall",
            )
        }
    }
}
