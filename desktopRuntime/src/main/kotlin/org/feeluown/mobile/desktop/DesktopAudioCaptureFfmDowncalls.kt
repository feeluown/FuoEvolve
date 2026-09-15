package org.feeluown.mobile.desktop

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout

internal object DesktopAudioCaptureFfmDowncalls {
    private val registered = mutableListOf<DesktopFfmDowncall>()

    private fun downcall(symbol: String, descriptor: FunctionDescriptor): DesktopFfmDowncall =
        DesktopFfmDowncall(symbol, descriptor).also(registered::add)

    val open = downcall(
        "fuo_audio_capture_open",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG),
    )
    val read = downcall(
        "fuo_audio_capture_read",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )
    val cancel = downcall(
        "fuo_audio_capture_cancel",
        FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG),
    )
    val close = downcall(
        "fuo_audio_capture_close",
        cancel.descriptor,
    )
    val lastError = downcall(
        "fuo_audio_capture_last_error",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )

    val all: List<DesktopFfmDowncall>
        get() = registered.toList()
}
