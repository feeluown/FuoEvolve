package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NucleusOAuthDeviceCodeAssistantTest {
    @Test
    fun copyUsesCurrentlyBoundClipboardWriter() {
        val copied = mutableListOf<String>()
        val assistant = NucleusOAuthDeviceCodeAssistant(notificationSender = { _, _ -> null })
        val firstWriter: (String) -> Unit = { copied += "first:$it" }
        val secondWriter: (String) -> Unit = { copied += "second:$it" }

        assistant.bindClipboardWriter(firstWriter)
        assistant.bindClipboardWriter(secondWriter)
        assistant.unbindClipboardWriter(firstWriter)
        assistant.copyUserCode("ABCD-EFGH")

        assertEquals(listOf("second:ABCD-EFGH"), copied)
    }

    @Test
    fun notificationActionCopiesCodeAndClearDismissesIt() {
        var action: (() -> Unit)? = null
        var dismissed = 0
        val assistant = NucleusOAuthDeviceCodeAssistant { _, onCopy ->
            action = onCopy
            NucleusNotificationHandle { dismissed += 1 }
        }
        val copied = mutableListOf<String>()
        assistant.bindClipboardWriter { copied += it }

        assistant.showUserCodeNotification("CODE-1234")
        assertNotNull(action).invoke()
        assistant.clearUserCodeNotification()

        assertEquals(listOf("CODE-1234"), copied)
        assertEquals(1, dismissed)
    }

    @Test
    fun replacingNotificationDismissesPreviousHandle() {
        val dismissed = mutableListOf<String>()
        val assistant = NucleusOAuthDeviceCodeAssistant { code, _ ->
            NucleusNotificationHandle { dismissed += code }
        }

        assistant.showUserCodeNotification("FIRST")
        assistant.showUserCodeNotification("SECOND")
        assistant.clearUserCodeNotification()

        assertEquals(listOf("FIRST", "SECOND"), dismissed)
    }
}
