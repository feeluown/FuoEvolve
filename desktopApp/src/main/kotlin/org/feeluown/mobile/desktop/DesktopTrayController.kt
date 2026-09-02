package org.feeluown.mobile.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Color
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import javax.swing.SwingUtilities

internal interface DesktopTrayController : AutoCloseable {
    val isAvailable: Boolean
}

internal enum class DesktopTrayBackend {
    WindowsAwt,
    MacAwt,
    LinuxStatusNotifier,
    Unsupported,
}

internal enum class DesktopCloseBehavior {
    HideToTray,
    KeepVisible,
}

internal fun desktopTrayBackend(osName: String): DesktopTrayBackend {
    val normalized = osName.lowercase(Locale.ROOT)
    return when {
        normalized.contains("windows") -> DesktopTrayBackend.WindowsAwt
        normalized.contains("mac") || normalized.contains("darwin") -> DesktopTrayBackend.MacAwt
        normalized.contains("linux") -> DesktopTrayBackend.LinuxStatusNotifier
        else -> DesktopTrayBackend.Unsupported
    }
}

internal fun desktopCloseBehavior(trayAvailable: Boolean): DesktopCloseBehavior =
    if (trayAvailable) DesktopCloseBehavior.HideToTray else DesktopCloseBehavior.KeepVisible

internal fun createDesktopTrayController(
    onShow: () -> Unit,
    onExit: () -> Unit,
): DesktopTrayController {
    val backend = desktopTrayBackend(System.getProperty("os.name").orEmpty())
    return runCatching {
        when (backend) {
            DesktopTrayBackend.WindowsAwt,
            DesktopTrayBackend.MacAwt -> AwtDesktopTrayController(onShow, onExit)

            DesktopTrayBackend.LinuxStatusNotifier -> LinuxStatusNotifierTrayController(onShow, onExit)
            DesktopTrayBackend.Unsupported -> UnavailableDesktopTrayController("unsupported desktop platform")
        }
    }.getOrElse { error ->
        UnavailableDesktopTrayController(
            "${backend.name} initialization failed: ${error.message.orEmpty()}",
        )
    }
}

private class AwtDesktopTrayController(
    onShow: () -> Unit,
    onExit: () -> Unit,
) : DesktopTrayController {
    private val systemTray: SystemTray?
    private val trayIcon: TrayIcon?

    override val isAvailable: Boolean
        get() = systemTray != null && trayIcon != null

    init {
        val initialized = runCatching {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                error("system tray is not supported")
            }
            val tray = SystemTray.getSystemTray()
            val popup = PopupMenu()
            val showItem = MenuItem("Show FuoEvolve").apply {
                addActionListener { dispatchToDesktopUi(onShow) }
            }
            val exitItem = MenuItem("Exit").apply {
                addActionListener { dispatchToDesktopUi(onExit) }
            }
            popup.add(showItem)
            popup.addSeparator()
            popup.add(exitItem)

            val icon = TrayIcon(createTrayImage(), "FuoEvolve", popup).apply {
                isImageAutoSize = true
                addActionListener { dispatchToDesktopUi(onShow) }
            }
            tray.add(icon)
            tray to icon
        }.onFailure { error ->
            System.err.println("FuoEvolve: desktop tray unavailable: ${error.message.orEmpty()}")
        }.getOrNull()

        systemTray = initialized?.first
        trayIcon = initialized?.second
    }

    override fun close() {
        trayIcon?.let { icon -> systemTray?.remove(icon) }
    }
}

private class LinuxStatusNotifierTrayController(
    onShow: () -> Unit,
    onExit: () -> Unit,
    private val native: LinuxTrayNative = loadLinuxTrayNative(),
) : DesktopTrayController {
    private val callback = object : LinuxTrayNative.EventCallback {
        override fun invoke(action: Int, value: Long) {
            when (action) {
                LinuxTrayNative.ACTION_SHOW -> dispatchToDesktopUi(onShow)
                LinuxTrayNative.ACTION_EXIT -> dispatchToDesktopUi(onExit)
            }
        }
    }
    private val handle: Pointer?

    override val isAvailable: Boolean
        get() = handle != null

    init {
        val error = Memory(ERROR_BUFFER_BYTES.toLong()).apply { clear() }
        handle = runCatching {
            native.fuo_linux_tray_create(
                callback = callback,
                errorBuffer = error,
                errorCapacity = ERROR_BUFFER_BYTES.toLong(),
            )
        }.onFailure { failure ->
            System.err.println("FuoEvolve: Linux StatusNotifier tray unavailable: ${failure.message.orEmpty()}")
        }.getOrNull()

        if (handle == null) {
            val message = error.getString(0, Charsets.UTF_8.name()).trim()
            if (message.isNotEmpty()) {
                System.err.println("FuoEvolve: Linux StatusNotifier tray unavailable: $message")
            }
        }
    }

    override fun close() {
        handle?.let(native::fuo_linux_tray_destroy)
    }
}

private class UnavailableDesktopTrayController(reason: String) : DesktopTrayController {
    override val isAvailable: Boolean = false

    init {
        System.err.println("FuoEvolve: desktop tray unavailable: $reason")
    }

    override fun close() = Unit
}

private interface LinuxTrayNative : Library {
    interface EventCallback : Callback {
        fun invoke(action: Int, value: Long)
    }

    fun fuo_linux_tray_create(
        callback: EventCallback,
        errorBuffer: Pointer,
        errorCapacity: Long,
    ): Pointer?

    fun fuo_linux_tray_destroy(bridge: Pointer)

    companion object {
        const val ACTION_SHOW = 1
        const val ACTION_EXIT = 2
    }
}

private fun loadLinuxTrayNative(): LinuxTrayNative {
    val options = mapOf<String, Any>(Library.OPTION_STRING_ENCODING to Charsets.UTF_8.name())
    val explicit = System.getenv("FUOEVOLVE_LINUX_TRAY_BRIDGE_PATH")
        ?.takeIf(String::isNotBlank)
        ?.let(Paths::get)
    val library = sequenceOf(
        explicit,
        developmentBridgePath("desktopApp/native/linux-tray", LINUX_TRAY_LIBRARY_FILE),
        developmentBridgePath("native/linux-tray", LINUX_TRAY_LIBRARY_FILE),
    ).filterNotNull().firstOrNull(Files::isRegularFile)

    return if (library != null) {
        Native.load(library.toAbsolutePath().toString(), LinuxTrayNative::class.java, options)
    } else {
        Native.load(LINUX_TRAY_LIBRARY_NAME, LinuxTrayNative::class.java, options)
    }
}

private fun developmentBridgePath(relativeRoot: String, fileName: String): Path =
    Paths.get(System.getProperty("user.dir"), relativeRoot, "target", "release", fileName)

private fun dispatchToDesktopUi(action: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        action()
    } else {
        SwingUtilities.invokeLater(action)
    }
}

private fun createTrayImage(): BufferedImage {
    val size = 32
    return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { image ->
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color(35, 35, 35)
            graphics.fillOval(2, 2, 28, 28)
            graphics.color = Color.WHITE
            graphics.fillOval(5, 5, 22, 22)
            graphics.color = Color(35, 35, 35)
            drawMusicNote(graphics)
        } finally {
            graphics.dispose()
        }
    }
}

private fun drawMusicNote(graphics: Graphics2D) {
    graphics.fillRoundRect(17, 8, 3, 13, 2, 2)
    graphics.fillRoundRect(11, 10, 8, 3, 2, 2)
    graphics.fillOval(12, 18, 7, 6)
}

private const val ERROR_BUFFER_BYTES = 2048
private const val LINUX_TRAY_LIBRARY_NAME = "fuoevolve_linux_tray_bridge"
private const val LINUX_TRAY_LIBRARY_FILE = "libfuoevolve_linux_tray_bridge.so"
