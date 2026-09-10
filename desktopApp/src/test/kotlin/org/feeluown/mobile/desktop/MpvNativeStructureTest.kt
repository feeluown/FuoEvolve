package org.feeluown.mobile.desktop

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

class MpvNativeStructureTest {
    @Test
    fun nativeStructuresAreJvmPublicForJnaReflection() {
        listOf(
            MpvNativeEvent::class.java,
            MpvNativeStartFile::class.java,
            MpvNativeEventProperty::class.java,
            MpvNativeLogMessage::class.java,
            MpvNativeEndFile::class.java,
        ).forEach { structure ->
            assertTrue(
                Modifier.isPublic(structure.modifiers),
                "${structure.simpleName} must be public for JNA reflection",
            )
        }
    }
}
