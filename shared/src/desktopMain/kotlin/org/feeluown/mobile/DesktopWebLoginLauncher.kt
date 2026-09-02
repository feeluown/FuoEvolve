package org.feeluown.mobile

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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

    suspend fun open(provider: ProviderInfo): DesktopWebLoginResult = withContext(Dispatchers.IO) {
        val config = provider.loginConfig
            ?: return@withContext DesktopWebLoginResult.Failure("${provider.providerName} 未配置网页登录地址")
        if (config.cookieKeyGroups.isEmpty()) {
            return@withContext DesktopWebLoginResult.Failure("${provider.providerName} 未配置登录 Cookie 判定规则")
        }
        val helper = resolveHelper()
            ?: return@withContext DesktopWebLoginResult.Failure("桌面网页登录组件未找到，请重新安装应用")

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
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(json.encodeToString(request))
                    writer.newLine()
                }

                // The helper emits exactly one JSON response and never logs cookie values to stderr.
                val responseLine = process.inputStream.bufferedReader(Charsets.UTF_8).readLine().orEmpty()
                val exitCode = process.waitFor()
                val diagnostics = process.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()

                if (responseLine.isBlank()) {
                    val detail = diagnostics.take(240).ifBlank { "退出码 $exitCode" }
                    return@runCatching DesktopWebLoginResult.Failure("网页登录组件启动失败：$detail")
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
            }
        }.getOrElse { error ->
            DesktopWebLoginResult.Failure(error.message ?: "无法启动桌面网页登录")
        }
    }

    override fun close() {
        activeProcesses.toList().forEach { process ->
            if (process.isAlive) process.destroyForcibly()
        }
        activeProcesses.clear()
    }

    private fun resolveHelper(): File? {
        val executableName = if (isWindows()) "fuoevolve-web-login.exe" else "fuoevolve-web-login"
        val appDir = System.getProperty("fuoevolve.appdir")
            ?.takeIf { it.isNotBlank() && !it.contains("\$APPDIR") }
        val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })
        return buildList {
            if (appDir != null) {
                add(File(appDir, "resources/native/helpers/$executableName"))
            }
            // Development fallbacks for `./gradlew :desktopApp:run` from either repository root
            // or the desktopApp project directory.
            add(File(userDir, "desktopApp/native/web-login/target/release/$executableName"))
            add(File(userDir, "native/web-login/target/release/$executableName"))
        }.firstOrNull { candidate -> candidate.isFile && (isWindows() || candidate.canExecute()) }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

    private fun loginUserAgent(providerId: String): String =
        if (providerId == "bilibili") MOBILE_USER_AGENT else DESKTOP_USER_AGENT

    private companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
