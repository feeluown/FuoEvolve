package org.feeluown.mobile.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong

internal class DesktopPythonClient(
    private val pythonExecutable: String = desktopPythonExecutable(),
    private val bridgeScript: File = desktopResourceFile(
        propertyName = "fuo.desktop.bridgeScript",
        envName = "FUO_DESKTOP_BRIDGE_SCRIPT",
        packagedRelativePath = "desktop-python/bridge/desktop_bridge.py",
        sourceRelativePath = "desktopApp/src/main/python/desktop_bridge.py",
    ),
    private val androidPythonDir: File = desktopResourceFile(
        propertyName = "fuo.desktop.androidPythonDir",
        envName = "FUO_DESKTOP_ANDROID_PYTHON_DIR",
        packagedRelativePath = "desktop-python/android-python",
        sourceRelativePath = "androidApp/src/main/python",
    ),
) : AutoCloseable {
    private val serial = AtomicLong()
    private val stderr = StringBuilder()
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    private var enabledProvidersJson: String = ""

    suspend fun initialize(enabledProvidersJson: String) {
        withContext(Dispatchers.IO) {
            synchronized(this@DesktopPythonClient) {
                if (process != null && this@DesktopPythonClient.enabledProvidersJson == enabledProvidersJson) return@synchronized
                closeLocked()
                startLocked()
                sendLocked("create_bridge", listOf(enabledProvidersJson))
                this@DesktopPythonClient.enabledProvidersJson = enabledProvidersJson
            }
        }
    }

    suspend fun call(method: String, args: List<Any?> = emptyList()): String = withContext(Dispatchers.IO) {
        synchronized(this@DesktopPythonClient) {
            if (process == null) {
                startLocked()
            }
            sendLocked(method, args)
        }
    }

    override fun close() {
        synchronized(this) {
            closeLocked()
        }
    }

    private fun startLocked() {
        if (!bridgeScript.isFile) {
            error("找不到桌面 Python bridge：${bridgeScript.absolutePath}")
        }
        val builder = ProcessBuilder(pythonExecutable, bridgeScript.absolutePath)
            .directory(DesktopPaths.configDir)
        builder.environment().apply {
            this["FUO_ANDROID_PYTHON_DIR"] = androidPythonDir.absolutePath
            this["PYTHONPATH"] = pythonPathEntries(androidPythonDir).joinToString(File.pathSeparator)
            this["FEELUOWN_USER_HOME"] = System.getProperty("user.home")
        }
        val nextProcess = builder.start()
        process = nextProcess
        input = BufferedWriter(OutputStreamWriter(nextProcess.outputStream, Charsets.UTF_8))
        output = BufferedReader(InputStreamReader(nextProcess.inputStream, Charsets.UTF_8))
        Thread {
            nextProcess.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { appendStderr(it) }
            }
        }.apply {
            isDaemon = true
            name = "fuo-desktop-python-stderr"
            start()
        }
    }

    private fun sendLocked(method: String, args: List<Any?>): String {
        val requestId = serial.incrementAndGet()
        val request = JSONObject()
            .put("id", requestId)
            .put("method", method)
            .put("args", JSONArray(args))
        val writer = requireNotNull(input) { "Python bridge stdin is not ready" }
        val reader = requireNotNull(output) { "Python bridge stdout is not ready" }
        writer.write(request.toString())
        writer.newLine()
        writer.flush()

        while (true) {
            val line = reader.readLine() ?: error("Python bridge 已退出：${stderrSnapshot()}")
            val response = runCatching { JSONObject(line) }.getOrNull()
            if (response == null) {
                appendStderr(line)
                continue
            }
            if (response.optLong("id") != requestId) {
                appendStderr(line)
                continue
            }
            if (!response.optBoolean("ok")) {
                val message = response.optString("error").ifBlank { stderrSnapshot() }
                error(message.ifBlank { "Python bridge 调用失败：$method" })
            }
            return response.optString("result")
        }
    }

    private fun closeLocked() {
        input?.close()
        output?.close()
        process?.destroy()
        input = null
        output = null
        process = null
        enabledProvidersJson = ""
    }

    private fun appendStderr(line: String) {
        synchronized(stderr) {
            if (stderr.length > MAX_STDERR_CHARS) {
                stderr.delete(0, stderr.length - MAX_STDERR_CHARS)
            }
            stderr.appendLine(line)
        }
    }

    private fun stderrSnapshot(): String = synchronized(stderr) {
        stderr.toString().trim()
    }

    private companion object {
        private const val MAX_STDERR_CHARS = 16_384
    }
}

private fun desktopPythonExecutable(): String {
    System.getProperty("fuo.desktop.python")?.takeIf { it.isNotBlank() }?.let { return it }
    System.getenv("FUO_DESKTOP_PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
    packagedResourceRoots()
        .map { File(it, "desktop-python/venv/bin/python") }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.let { return it.absolutePath }
    return "python3"
}

private fun desktopResourceFile(
    propertyName: String,
    envName: String,
    packagedRelativePath: String,
    sourceRelativePath: String,
): File {
    System.getProperty(propertyName)?.takeIf { it.isNotBlank() }?.let { return File(it) }
    System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return File(it) }
    File(sourceRelativePath).takeIf { it.exists() }?.let { return it }
    packagedResourceRoots()
        .map { File(it, packagedRelativePath) }
        .firstOrNull { it.exists() }
        ?.let { return it }
    return File(sourceRelativePath)
}

private fun packagedResourceRoots(): List<File> {
    return buildList {
        System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { add(File(it)) }
        System.getenv("APPDIR")
            ?.takeIf { it.isNotBlank() }
            ?.let { appDir ->
                add(File(appDir, "usr/lib/FuoEvolve/resources"))
                add(File(appDir, "usr/lib/fuo-evolve/resources"))
                add(File(appDir, "resources"))
            }
        val javaHome = File(System.getProperty("java.home").orEmpty())
        listOf(
            javaHome.parentFile,
            javaHome.parentFile?.parentFile,
            File(System.getProperty("user.dir")),
        ).filterNotNull().forEach { base ->
            add(File(base, "resources"))
            add(File(base, "app/resources"))
            add(File(base, "lib/app/resources"))
        }
    }.distinctBy { it.absolutePath }
}

private fun pythonPathEntries(androidPythonDir: File): List<String> {
    return buildList {
        add(androidPythonDir.absolutePath)
        val resourceRoot = androidPythonDir.parentFile?.parentFile
        val venvLib = resourceRoot?.let { File(it, "venv/lib") }
        venvLib
            ?.walkTopDown()
            ?.maxDepth(2)
            ?.firstOrNull { it.isDirectory && it.name == "site-packages" }
            ?.let { add(it.absolutePath) }
        System.getenv("PYTHONPATH")
            ?.takeIf { it.isNotBlank() }
            ?.let { add(it) }
    }.distinct()
}
