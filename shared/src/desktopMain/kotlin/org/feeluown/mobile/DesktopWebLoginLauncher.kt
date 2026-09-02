package org.feeluown.mobile

import java.io.File
import java.io.Reader
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface DesktopWebLoginResult {
    data class Success(val cookiesJson: String) : DesktopWebLoginResult
    data object Cancelled : DesktopWebLoginResult
    data class Failure(val message: String) : DesktopWebLoginResult
}

@Serializable
private data class DesktopWebLoginRequest(
    val providerId: String,
    val providerName: String,
    val loginUrl: String,
    val cookieKeyGroups: List<List<String>>,
    val userAgent: String? = null,
)

@Serializable
private data class DesktopWebLoginResponse(
    val status: String,
    val cookies: Map<String, String> = emptyMap(),
    val message: String? = null,
)

internal class DesktopWebLoginLauncher : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()
    private val activeProviderIds = ConcurrentHashMap.newKeySet<String>()

    suspend fun open(provider: ProviderInfo): DesktopWebLoginResult = withContext(Dispatchers.IO) {
        val config = provider.loginConfig
            ?: return@withContext DesktopWebLoginResult.Failure("${provider.providerName} 未配置网页登录地址")
        if (config.cookieKeyGroups.isEmpty()) {
            return@withContext DesktopWebLoginResult.Failure("${provider.providerName} 未配置登录 Cookie 判定规则")
        }
        val helper = resolveHelper()
            ?: return@withContext DesktopWebLoginResult.Failure("桌面网页登录组件未找到，请重新安装应用")

        if (!activeProviderIds.add(provider.providerId)) {
            return@withContext DesktopWebLoginResult.Failure("${provider.providerName} 网页登录正在进行中")
        }

        try {
            val request = DesktopWebLoginRequest(
                providerId = provider.providerId,
                providerName = provider.providerName,
                loginUrl = config.loginUrl,
                cookieKeyGroups = config.cookieKeyGroups,
                userAgent = loginUserAgent(provider.providerId),
            )

            runCatching {
                val process = ProcessBuilder(helper.absolutePath).start()
                activeProcesses += process
                coroutineScope {
                    val diagnosticsDeferred = async(Dispatchers.IO) {
                        runCatching {
                            process.errorStream.bufferedReader(Charsets.UTF_8).use(::readDiagnosticTail)
                        }.getOrDefault("")
                    }
                    try {
                        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(json.encodeToString(request))
                            writer.newLine()
                        }

                        // Drain stderr concurrently so native WebView diagnostics cannot fill the
                        // process pipe and block the helper while the user is still logging in.
                        val responseLine = process.inputStream.bufferedReader(Charsets.UTF_8).readLine().orEmpty()
                        val exitCode = process.waitFor()
                        val diagnostics = diagnosticsDeferred.await().trim()

                        if (responseLine.isBlank()) {
                            val detail = diagnostics.takeLast(240).ifBlank { "退出码 $exitCode" }
                            return@coroutineScope DesktopWebLoginResult.Failure("网页登录组件启动失败：$detail")
                        }
                        val response = json.decodeFromString<DesktopWebLoginResponse>(responseLine)
                        when (response.status) {
                            "success" -> {
                                if (response.cookies.isEmpty()) {
                                    DesktopWebLoginResult.Failure("网页登录完成，但未获取到 Cookie")
                                } else {
                                    DesktopWebLoginResult.Success(json.encodeToString(response.cookies))
                                }
                            }
                            "cancelled" -> DesktopWebLoginResult.Cancelled
                            "error" -> DesktopWebLoginResult.Failure(
                                response.message?.takeIf { it.isNotBlank() } ?: "网页登录组件发生错误",
                            )
                            else -> DesktopWebLoginResult.Failure("网页登录组件返回了未知状态")
                        }
                    } finally {
                        activeProcesses -= process
                        if (process.isAlive) process.destroyForcibly()
                        diagnosticsDeferred.cancel()
                    }
                }
            }.getOrElse { error ->
                DesktopWebLoginResult.Failure(error.message ?: "无法启动桌面网页登录")
            }
        } finally {
            activeProviderIds -= provider.providerId
        }
    }

    override fun close() {
        activeProcesses.toList().forEach { process ->
            if (process.isAlive) process.destroyForcibly()
        }
        activeProcesses.clear()
        activeProviderIds.clear()
    }

    private fun resolveHelper(): File? {
        val executableName = if (isWindows()) "fuoevolve-web-login.exe" else "fuoevolve-web-login"
        val appDir = System.getProperty("fuoevolve.appdir")
            ?.takeIf { it.isNotBlank() && !it.contains("\$APPDIR") }
            ?.let(::File)
        val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })

        val directCandidates = buildList {
            if (appDir != null) {
                // Current Compose layout. Keep this fast path, but do not make runtime correctness
                // depend on the exact resource nesting used by a particular Compose plugin version.
                add(File(appDir, "resources/native/helpers/$executableName"))
            }
            // Development fallbacks for `./gradlew :desktopApp:run` from either repository root
            // or the desktopApp project directory.
            add(File(userDir, "desktopApp/native/web-login/target/release/$executableName"))
            add(File(userDir, "native/web-login/target/release/$executableName"))
        }
        directCandidates.firstOrNull(::isUsableHelper)?.let { return it }

        // Native distributions may relocate app resources while preserving their contents. Search
        // only below the installed app root and only when the direct path did not match.
        return appDir
            ?.takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.maxDepth(6)
            ?.firstOrNull { candidate ->
                candidate.name == executableName && isUsableHelper(candidate)
            }
    }

    private fun isUsableHelper(candidate: File): Boolean =
        candidate.isFile && (isWindows() || candidate.canExecute())

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

    private fun loginUserAgent(providerId: String): String =
        if (providerId == "bilibili") MOBILE_USER_AGENT else DESKTOP_USER_AGENT

    private companion object {
        const val MAX_DIAGNOSTIC_CHARS = 8 * 1024
        const val DIAGNOSTIC_BUFFER_CHARS = 1024

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun readDiagnosticTail(reader: Reader): String {
            val tail = StringBuilder()
            val buffer = CharArray(DIAGNOSTIC_BUFFER_CHARS)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                tail.append(buffer, 0, count)
                if (tail.length > MAX_DIAGNOSTIC_CHARS) {
                    tail.delete(0, tail.length - MAX_DIAGNOSTIC_CHARS)
                }
            }
            return tail.toString()
        }
    }
}
