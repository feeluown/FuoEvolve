package org.feeluown.mobile.desktop

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Creates the HWND used as mpv's Win32 embedding parent for one video controller.
 *
 * mpv creates and owns a child HWND under this host. Nucleus reparents and sizes the host through
 * NativeView. Keeping one host per controller prevents overlapping controller lifetimes from sharing
 * the same Win32 parent during navigation transitions.
 */
fun createDesktopWindowsVideoHostHandle(): Long {
    if (!isWindowsDesktopRuntime()) return 0L
    val hwnd = WindowsVideoHostBindings.createHostWindow()
    check(hwnd != 0L) { "CreateWindowExA failed for the Windows mpv video host" }
    return hwnd
}

/**
 * Keeps a temporarily detached host from becoming an orphaned visible top-level window. Nucleus
 * shows it again when the same controller is reattached.
 */
fun hideDesktopWindowsVideoHost(hwnd: Long) {
    if (!isWindowsDesktopRuntime() || hwnd == 0L) return
    WindowsVideoHostBindings.hideWindow(hwnd)
}

/** Destroys the controller-owned host after mpv has destroyed its child window. */
fun destroyDesktopWindowsVideoHost(hwnd: Long) {
    if (!isWindowsDesktopRuntime() || hwnd == 0L) return
    WindowsVideoHostBindings.destroyWindow(hwnd)
}

/** mpv documents Win32 --wid as the HWND cast to uint32_t. */
internal fun windowsMpvWidValue(hwnd: Long): String =
    (hwnd and 0xFFFF_FFFFL).toString()

internal fun isWindowsDesktopRuntime(
    osName: String = System.getProperty("os.name").orEmpty(),
): Boolean = osName.contains("windows", ignoreCase = true)

private object WindowsVideoHostBindings {
    private val arena = Arena.global()
    private val linker = Linker.nativeLinker()
    private val user32 = SymbolLookup.libraryLookup("user32", arena)
    private val createWindowExA: MethodHandle = linker.downcallHandle(
        user32.find("CreateWindowExA").orElseThrow {
            UnsatisfiedLinkError("CreateWindowExA is unavailable")
        },
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )
    private val showWindow: MethodHandle = linker.downcallHandle(
        user32.find("ShowWindow").orElseThrow {
            UnsatisfiedLinkError("ShowWindow is unavailable")
        },
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )
    private val destroyWindow: MethodHandle = linker.downcallHandle(
        user32.find("DestroyWindow").orElseThrow {
            UnsatisfiedLinkError("DestroyWindow is unavailable")
        },
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    fun createHostWindow(): Long = Arena.ofConfined().use { strings ->
        val className = strings.allocateFrom("STATIC")
        val windowName = strings.allocateFrom("")
        val hwnd = createWindowExA.invokeExact(
            0,
            className,
            windowName,
            WINDOWS_VIDEO_HOST_STYLE,
            0,
            0,
            1,
            1,
            MemorySegment.NULL,
            MemorySegment.NULL,
            MemorySegment.NULL,
            MemorySegment.NULL,
        ) as MemorySegment
        hwnd.address()
    }

    fun hideWindow(hwnd: Long) {
        showWindow.invokeExact(MemorySegment.ofAddress(hwnd), SW_HIDE) as Int
    }

    fun destroyWindow(hwnd: Long) {
        destroyWindow.invokeExact(MemorySegment.ofAddress(hwnd)) as Int
    }
}

private const val WS_POPUP: Int = Int.MIN_VALUE
private const val WS_CLIPCHILDREN = 0x02000000
private const val WS_CLIPSIBLINGS = 0x04000000
private const val SS_BLACKRECT = 0x00000004
private const val SW_HIDE = 0
private const val WINDOWS_VIDEO_HOST_STYLE =
    WS_POPUP or WS_CLIPCHILDREN or WS_CLIPSIBLINGS or SS_BLACKRECT
