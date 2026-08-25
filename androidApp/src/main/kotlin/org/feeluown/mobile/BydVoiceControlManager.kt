package org.feeluown.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BydVoiceControlManager(
    context: Context,
    private val scope: CoroutineScope,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onSearch: suspend (String) -> Boolean,
) : BydVoiceControlSettingsPort, AutoCloseable {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val available = isBydDiLinkEnvironment()
    private val mutableState = MutableStateFlow(currentState())
    override val state: StateFlow<BydVoiceControlSettingsState> = mutableState.asStateFlow()

    private var monitorJob: Job? = null
    private var logReaderJob: Job? = null
    @Volatile private var logcatProcess: Process? = null

    fun start() {
        if (!available || monitorJob?.isActive == true) return
        refreshRuntimeState()
        monitorJob = scope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                refreshRuntimeState()
                delay(PERMISSION_POLL_INTERVAL_MS)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (!available) return
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        refreshRuntimeState()
    }

    override fun close() {
        monitorJob?.cancel()
        monitorJob = null
        stopLogReader()
    }

    private fun refreshRuntimeState() {
        val nextState = currentState()
        if (mutableState.value != nextState) mutableState.value = nextState
        if (nextState.enabled && nextState.readLogsGranted) {
            ensureLogReader()
        } else {
            stopLogReader()
        }
    }

    private fun currentState(): BydVoiceControlSettingsState {
        if (!available) return BydVoiceControlSettingsState()
        return BydVoiceControlSettingsState(
            available = true,
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            readLogsGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_LOGS,
            ) == PackageManager.PERMISSION_GRANTED,
            grantCommand = "adb shell pm grant ${appContext.packageName} ${Manifest.permission.READ_LOGS}",
        )
    }

    private fun ensureLogReader() {
        if (logReaderJob?.isActive == true) return
        logReaderJob = scope.launch(Dispatchers.IO) {
            runVoiceLogLoop()
        }
    }

    private fun stopLogReader() {
        logReaderJob?.cancel()
        logReaderJob = null
        logcatProcess?.destroy()
        logcatProcess = null
    }

    private suspend fun runVoiceLogLoop() {
        var lastCommand: BydVoiceCommand? = null
        var lastCommandAt = 0L
        while (currentCoroutineContext().isActive) {
            val pid = findVoiceAssistantPid()
            if (pid == null) {
                delay(VOICE_PROCESS_RETRY_MS)
                continue
            }

            val process = runCatching {
                ProcessBuilder(
                    "/system/bin/logcat",
                    "--pid=$pid",
                    "-v",
                    "brief",
                    "-T",
                    "1",
                ).redirectErrorStream(true).start()
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to start DiLink voice log reader", throwable)
            }.getOrNull()

            if (process == null) {
                delay(VOICE_PROCESS_RETRY_MS)
                continue
            }

            logcatProcess = process
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        val command = BydVoiceCommandParser.parseLogLine(line) ?: continue
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (command == lastCommand && now - lastCommandAt < COMMAND_DEDUP_WINDOW_MS) continue
                        lastCommand = command
                        lastCommandAt = now
                        dispatch(command)
                    }
                }
            } catch (throwable: Throwable) {
                if (currentCoroutineContext().isActive) {
                    Log.d(TAG, "DiLink voice log reader stopped", throwable)
                }
            } finally {
                if (logcatProcess === process) logcatProcess = null
                process.destroy()
            }
            delay(VOICE_PROCESS_RETRY_MS)
        }
    }

    private fun dispatch(command: BydVoiceCommand) {
        scope.launch {
            when (command) {
                BydVoiceCommand.Play -> onPlay()
                BydVoiceCommand.Pause -> onPause()
                BydVoiceCommand.Previous -> onPrevious()
                BydVoiceCommand.Next -> onNext()
                is BydVoiceCommand.Search -> onSearch(command.query)
            }
        }
    }

    private fun findVoiceAssistantPid(): Int? = runCatching {
        val process = ProcessBuilder("/system/bin/pidof", BYD_VOICE_ASSISTANT_PACKAGE)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
        if (!process.waitFor(PIDOF_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        output.trim().split(Regex("\\s+")).firstNotNullOfOrNull(String::toIntOrNull)
    }.getOrNull()

    private companion object {
        const val TAG = "BydVoiceControl"
        const val PREFS_NAME = "byd_voice_control"
        const val KEY_ENABLED = "enabled"
        const val BYD_VOICE_ASSISTANT_PACKAGE = "com.byd.vrassistant"
        const val PIDOF_TIMEOUT_SECONDS = 1L
        const val PERMISSION_POLL_INTERVAL_MS = 2_000L
        const val VOICE_PROCESS_RETRY_MS = 1_500L
        const val COMMAND_DEDUP_WINDOW_MS = 1_200L
    }
}

internal fun isBydDiLinkEnvironment(): Boolean {
    if (isBydInstrumentLyricsAvailable()) return true
    return listOf(
        Build.MANUFACTURER,
        Build.BRAND,
        Build.PRODUCT,
        Build.DEVICE,
        Build.DISPLAY,
        Build.FINGERPRINT,
    ).any { value ->
        value.contains("byd", ignoreCase = true) || value.contains("dilink", ignoreCase = true)
    }
}

internal sealed interface BydVoiceCommand {
    data object Play : BydVoiceCommand
    data object Pause : BydVoiceCommand
    data object Previous : BydVoiceCommand
    data object Next : BydVoiceCommand
    data class Search(val query: String) : BydVoiceCommand
}

/**
 * Conservative parser for recognized-text output from the DiLink voice process.
 *
 * Different DiLink builds log different ASR payload shapes. Strong fields such as query,
 * utterance and sentence are accepted directly; generic text/result fields are considered only
 * when the same line is explicitly marked as ASR/recognition output. Keeping extraction separate
 * from command parsing lets vehicle-specific payload variants be added without touching playback.
 */
internal object BydVoiceCommandParser {
    private val strongFieldPatterns = listOf(
        Regex("(?i)[\\\"'](?:query|utterance|sentence|asrText|iatText|recognitionText)[\\\"']\\s*[:=]\\s*[\\\"']([^\\\"']{1,120})[\\\"']"),
        Regex("(?i)(?:query|utterance|sentence|asrText|iatText|recognitionText)\\s*[:=]\\s*([^,;|]{1,120})"),
    )
    private val genericFieldPatterns = listOf(
        Regex("(?i)[\\\"'](?:text|result)[\\\"']\\s*[:=]\\s*[\\\"']([^\\\"']{1,120})[\\\"']"),
        Regex("(?i)(?:text|result)\\s*[:=]\\s*([^,;|]{1,120})"),
    )
    private val recognitionMarkers = listOf("asr", "iat", "recogn", "speechresult", "onresult")

    fun parseLogLine(line: String): BydVoiceCommand? {
        val strongText = strongFieldPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(line)?.groupValues?.getOrNull(1)
        }
        if (strongText != null) return parseRecognizedText(strongText)

        val lower = line.lowercase()
        if (recognitionMarkers.none(lower::contains)) return null
        val genericText = genericFieldPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(line)?.groupValues?.getOrNull(1)
        } ?: return null
        return parseRecognizedText(genericText)
    }

    fun parseRecognizedText(rawText: String): BydVoiceCommand? {
        val text = rawText
            .trim()
            .trim('"', '\'', '，', ',', '。', '.', '！', '!', '？', '?')
            .removePrefix("小迪小迪")
            .removePrefix("小迪")
            .trim()
        if (text.isBlank()) return null

        if (listOf("下一首", "下首", "切到下一首", "下一曲").any(text::contains)) return BydVoiceCommand.Next
        if (listOf("上一首", "上首", "切到上一首", "上一曲").any(text::contains)) return BydVoiceCommand.Previous
        if (listOf("暂停", "暂停播放", "停止播放").any(text::contains)) return BydVoiceCommand.Pause
        if (listOf("继续播放", "恢复播放", "继续音乐").any(text::contains)) return BydVoiceCommand.Play

        val prefix = SEARCH_PREFIXES.firstOrNull(text::startsWith) ?: return when (text) {
            "播放", "播放音乐", "放音乐", "继续" -> BydVoiceCommand.Play
            else -> null
        }
        val query = text.removePrefix(prefix)
            .trim(' ', '，', ',', '。', '.', '：', ':')
            .removePrefix("一下")
            .trim()
        return if (query.isBlank() || query in GENERIC_MUSIC_QUERIES) {
            BydVoiceCommand.Play
        } else {
            BydVoiceCommand.Search(query)
        }
    }

    private val SEARCH_PREFIXES = listOf(
        "播放一下",
        "播放",
        "我想听",
        "我要听",
        "来一首",
        "放一首",
        "放一下",
    )
    private val GENERIC_MUSIC_QUERIES = setOf("音乐", "歌曲", "歌", "音乐吧")
}
