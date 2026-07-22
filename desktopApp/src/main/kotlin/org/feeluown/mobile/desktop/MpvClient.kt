package org.feeluown.mobile.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.Structure

internal enum class MpvEventType {
    None,
    FileLoaded,
    EndFile,
    Shutdown,
    Other,
}

internal data class MpvEvent(
    val type: MpvEventType,
    val errorCode: Int = 0,
)

internal interface MpvClient : AutoCloseable {
    fun setOption(name: String, value: String)
    fun initialize()
    fun command(vararg args: String)
    fun setProperty(name: String, value: String)
    fun getProperty(name: String): String?
    fun pollEvent(): MpvEvent
    fun errorDescription(errorCode: Int): String
}

internal class JnaMpvClient(
    private val api: LibMpv = LibMpv.instance,
) : MpvClient {
    private var handle: Pointer? = createHandle()

    override fun setOption(name: String, value: String) {
        checkResult(api.mpv_set_option_string(requireHandle(), name, value), "设置 $name")
    }

    override fun initialize() {
        checkResult(api.mpv_initialize(requireHandle()), "初始化")
    }

    override fun command(vararg args: String) {
        checkResult(api.mpv_command(requireHandle(), StringArray(args)), "执行 ${args.firstOrNull().orEmpty()}")
    }

    override fun setProperty(name: String, value: String) {
        checkResult(api.mpv_set_property_string(requireHandle(), name, value), "设置 $name")
    }

    override fun getProperty(name: String): String? {
        val value = api.mpv_get_property_string(requireHandle(), name) ?: return null
        return try {
            value.getString(0)
        } finally {
            api.mpv_free(value)
        }
    }

    override fun pollEvent(): MpvEvent {
        val pointer = api.mpv_wait_event(requireHandle(), 0.0) ?: return MpvEvent(MpvEventType.None)
        val event = NativeMpvEvent(pointer)
        return when (event.eventId) {
            MPV_EVENT_NONE -> MpvEvent(MpvEventType.None)
            MPV_EVENT_SHUTDOWN -> MpvEvent(MpvEventType.Shutdown)
            MPV_EVENT_FILE_LOADED -> MpvEvent(MpvEventType.FileLoaded)
            MPV_EVENT_END_FILE -> {
                val errorCode = event.data
                    ?.let(::NativeMpvEndFile)
                    ?.takeIf { it.reason == MPV_END_FILE_REASON_ERROR }
                    ?.error
                    ?: 0
                MpvEvent(MpvEventType.EndFile, errorCode)
            }
            else -> MpvEvent(MpvEventType.Other)
        }
    }

    override fun errorDescription(errorCode: Int): String = api.mpv_error_string(errorCode)

    override fun close() {
        val currentHandle = handle ?: return
        handle = null
        api.mpv_terminate_destroy(currentHandle)
    }

    private fun requireHandle(): Pointer = checkNotNull(handle) { "libmpv 实例已关闭" }

    private fun createHandle(): Pointer {
        NativeLocale.useCNumericLocale()
        return api.mpv_create() ?: error("无法创建 libmpv 实例")
    }

    private fun checkResult(result: Int, action: String) {
        check(result >= 0) { "libmpv ${action}失败：${errorDescription(result)}" }
    }

    internal interface LibMpv : Library {
        fun mpv_create(): Pointer?
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_terminate_destroy(ctx: Pointer)
        fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
        fun mpv_command(ctx: Pointer, args: Pointer): Int
        fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
        fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
        fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer?
        fun mpv_error_string(error: Int): String
        fun mpv_free(data: Pointer)

        companion object {
            val instance: LibMpv by lazy { Native.load("mpv", LibMpv::class.java) }
        }
    }

    private object NativeLocale {
        private val libc: LibC by lazy { Native.load(Platform.C_LIBRARY_NAME, LibC::class.java) }

        @Synchronized
        fun useCNumericLocale() {
            val configuredLocale = libc.setlocale(lcNumericCategory(), "C")?.getString(0)
            check(configuredLocale == "C") { "无法将 LC_NUMERIC 设置为 C" }
        }

        private fun lcNumericCategory(): Int = if (Platform.isLinux()) {
            LINUX_LC_NUMERIC
        } else {
            BSD_AND_WINDOWS_LC_NUMERIC
        }

        private interface LibC : Library {
            fun setlocale(category: Int, locale: String): Pointer?
        }

        // locale.h uses different LC_NUMERIC values on glibc and BSD/MSVCRT platforms.
        private const val LINUX_LC_NUMERIC = 1
        private const val BSD_AND_WINDOWS_LC_NUMERIC = 4
    }

    @Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
    internal class NativeMpvEvent(pointer: Pointer) : Structure(pointer) {
        @JvmField var eventId: Int = 0
        @JvmField var error: Int = 0
        @JvmField var replyUserdata: Long = 0
        @JvmField var data: Pointer? = null

        init {
            read()
        }
    }

    @Structure.FieldOrder("reason", "error")
    internal class NativeMpvEndFile(pointer: Pointer) : Structure(pointer) {
        @JvmField var reason: Int = 0
        @JvmField var error: Int = 0

        init {
            read()
        }
    }

    private companion object {
        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_END_FILE_REASON_ERROR = 4
    }
}
