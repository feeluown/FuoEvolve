package org.feeluown.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidAudioRecognitionRepository(
    private val context: Context,
) : AudioRecognitionRepository {
    private val cancelled = AtomicBoolean(false)
    private val matching = AtomicBoolean(false)
    private val recognitionMutex = Mutex()
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val fingerprintRuntime = AndroidFingerprintRuntime(context.applicationContext)

    @Volatile
    private var audioRecord: AudioRecord? = null

    override suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong> =
        recognitionMutex.withLock {
            coroutineScope recognition@{
                checkMicrophonePermission()
                cancelled.set(false)
                val sessionId = UUID.randomUUID().toString()
                val windows = Channel<FloatArray>(Channel.CONFLATED)
                val captureJob = launch(Dispatchers.IO) {
                    captureWindows(windows, onEvent)
                }
                var attempt = 1
                try {
                    while (isActive && !cancelled.get()) {
                        val window = windows.receive()
                        matching.set(true)
                        withContext(Dispatchers.Main.immediate) {
                            onEvent(AudioRecognitionEvent.Matching(attempt))
                        }
                        val fingerprint = fingerprintRuntime.generate(
                            downsampleRecognitionWindow(window),
                        )
                        val matches = withContext(Dispatchers.IO) {
                            requestMatches(sessionId, fingerprint)
                        }
                        if (matches.isNotEmpty()) {
                            withContext(Dispatchers.Main.immediate) {
                                onEvent(AudioRecognitionEvent.Success(matches))
                            }
                            return@recognition matches
                        }
                        matching.set(false)
                        withContext(Dispatchers.Main.immediate) {
                            onEvent(AudioRecognitionEvent.NoMatch(attempt))
                        }
                        attempt += 1
                    }
                    emptyList()
                } catch (throwable: Throwable) {
                    if (throwable !is CancellationException && !cancelled.get()) {
                        withContext(Dispatchers.Main.immediate) {
                            onEvent(
                                AudioRecognitionEvent.Error(
                                    throwable.message ?: "听歌识曲失败",
                                ),
                            )
                        }
                    }
                    throw throwable
                } finally {
                    cancelled.set(true)
                    matching.set(false)
                    releaseAudioRecord()
                    activeConnection.getAndSet(null)?.disconnect()
                    captureJob.cancel()
                    windows.close()
                }
            }
        }

    override fun cancel() {
        cancelled.set(true)
        matching.set(false)
        releaseAudioRecord()
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun checkMicrophonePermission() {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("需要麦克风权限")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureWindows(
        windows: Channel<FloatArray>,
        onEvent: (AudioRecognitionEvent) -> Unit,
    ) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_RECOGNITION_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) { "设备不支持 48 kHz 麦克风录音" }
        val recorder = createAudioRecord(MediaRecorder.AudioSource.UNPROCESSED, minBufferSize)
            ?: createAudioRecord(MediaRecorder.AudioSource.MIC, minBufferSize)
            ?: throw IllegalStateException("麦克风初始化失败")
        audioRecord = recorder
        val readBuffer = ShortArray(2_048)
        var window = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES)
        var windowOffset = 0
        var captureAttempt = 1
        var lastProgressMs = -1L
        try {
            recorder.startRecording()
            while (!cancelled.get()) {
                val read = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    if (cancelled.get()) break
                    throw IllegalStateException("麦克风读取失败：$read")
                }
                var sourceOffset = 0
                while (sourceOffset < read) {
                    val copied = minOf(read - sourceOffset, window.size - windowOffset)
                    for (index in 0 until copied) {
                        window[windowOffset + index] = readBuffer[sourceOffset + index] / 32768f
                    }
                    sourceOffset += copied
                    windowOffset += copied
                    val capturedMs = windowOffset * 1_000L / AUDIO_RECOGNITION_SAMPLE_RATE
                    if (!matching.get() && capturedMs - lastProgressMs >= 250L) {
                        lastProgressMs = capturedMs
                        withContext(Dispatchers.Main.immediate) {
                            onEvent(
                                AudioRecognitionEvent.Capturing(
                                    attempt = captureAttempt,
                                    capturedMs = capturedMs,
                                ),
                            )
                        }
                    }
                    if (windowOffset == window.size) {
                        windows.trySend(window)
                        window = FloatArray(AUDIO_RECOGNITION_WINDOW_SAMPLES)
                        windowOffset = 0
                        captureAttempt += 1
                        lastProgressMs = -1
                    }
                }
            }
        } finally {
            releaseAudioRecord(recorder)
        }
    }

    private fun requestMatches(sessionId: String, fingerprint: String): List<RecognizedSong> {
        val body = formBody(
            "sessionId" to sessionId,
            "algorithmCode" to "shazam_v2",
            "duration" to "6",
            "rawdata" to fingerprint,
            "times" to "2",
            "decrypt" to "1",
        )
        val connection = URI(RECOGNITION_ENDPOINT).toURL().openConnection() as HttpURLConnection
        activeConnection.set(connection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            connection.setRequestProperty(
                "Origin",
                "chrome-extension://pgphbbekcgpfaekhcbjamjjkegcclhhd",
            )
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            check(status in 200..299) { "识别接口请求失败：HTTP $status" }
            parseMatches(response)
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(source: Int, minBufferSize: Int): AudioRecord? {
        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_RECOGNITION_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBufferSize * 2, 8_192))
                .build()
        }.getOrNull() ?: return null
        if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
        recorder.release()
        return null
    }

    private fun parseMatches(response: String): List<RecognizedSong> {
        val root = JSONObject(response)
        check(root.optInt("code") == 200) {
            root.optString("message").ifBlank { "识别接口返回错误" }
        }
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("识别接口响应缺少 data")
        val resultValue = data.opt("result")
        if (resultValue == null || resultValue == JSONObject.NULL) return emptyList()
        val results = resultValue as? JSONArray
            ?: throw IllegalStateException("识别接口 result 格式错误")
        return buildList {
            for (index in 0 until results.length()) {
                val match = results.optJSONObject(index) ?: continue
                val song = match.optJSONObject("song") ?: continue
                val title = song.optString("name")
                if (title.isBlank()) continue
                val artists = song.optJSONArray("artists") ?: song.optJSONArray("ar")
                val album = song.optJSONObject("album") ?: song.optJSONObject("al")
                add(
                    RecognizedSong(
                        neteaseSongId = song.opt("id")?.toString()?.takeIf { it.isNotBlank() },
                        title = title,
                        artists = artists.artistNames(),
                        album = album?.optString("name").orEmpty(),
                        coverUrl = album?.optString("picUrl")?.takeIf { it.isNotBlank() },
                        matchStartTimeMs = match.optLong("startTime")
                            .takeIf { match.has("startTime") },
                    ),
                )
            }
        }
    }

    private fun JSONArray?.artistNames(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun formBody(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8.name())}=" +
                URLEncoder.encode(value, Charsets.UTF_8.name())
        }

    private fun releaseAudioRecord(expected: AudioRecord? = null) {
        val recorder = audioRecord
        if (expected != null && recorder !== expected) return
        audioRecord = null
        recorder ?: return
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
    }

    private companion object {
        const val RECOGNITION_ENDPOINT = "https://interface.music.163.com/api/music/audio/match"
    }
}

private class AndroidFingerprintRuntime(
    private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, kotlin.coroutines.Continuation<String>>()
    private var webView: WebView? = null
    private var ready = false
    private val waiting = mutableListOf<() -> Unit>()
    private val bridge = FingerprintBridge()

    suspend fun generate(samples: FloatArray): String = suspendCancellableCoroutine { continuation ->
        val requestId = UUID.randomUUID().toString()
        pending[requestId] = continuation
        continuation.invokeOnCancellation { pending.remove(requestId) }
        val encoded = Base64.encodeToString(samples.toByteArray(), Base64.NO_WRAP)
        mainHandler.post {
            ensureWebView(context)
            val action: () -> Unit = {
                webView?.evaluateJavascript(
                    "globalThis.fuoFingerprint.generate(" +
                        "${JSONObject.quote(requestId)},${JSONObject.quote(encoded)})",
                    null,
                )
                Unit
            }
            if (ready) action() else waiting.add(action)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context) {
        if (webView != null) return
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(bridge, "FuoFingerprintBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.host != RUNTIME_HOST) return null
                    val assetName = request.url.lastPathSegment
                        ?.takeIf { it == "fingerprint.js" || it == "afp.wasm" }
                        ?: return null
                    val mimeType = if (assetName.endsWith(".wasm")) {
                        "application/wasm"
                    } else {
                        "application/javascript"
                    }
                    return WebResourceResponse(
                        mimeType,
                        null,
                        context.assets.open("audio_recognition/$assetName"),
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(
                        "void globalThis.fuoFingerprint.verifyRuntime()",
                        null,
                    )
                }
            }
            val runtimeHtml = context.assets.open("audio_recognition/runtime.html")
                .bufferedReader()
                .use { it.readText() }
            loadDataWithBaseURL(
                RUNTIME_BASE_URL,
                runtimeHtml,
                "text/html",
                Charsets.UTF_8.name(),
                null,
            )
        }
    }

    private inner class FingerprintBridge {
        @JavascriptInterface
        fun onRuntimeReady(error: String) {
            mainHandler.post {
                if (error.isBlank()) {
                    ready = true
                    waiting.toList().forEach { it() }
                    waiting.clear()
                } else {
                    failWaiting(IllegalStateException("音频指纹运行时初始化失败：$error"))
                }
            }
        }

        @JavascriptInterface
        fun onFingerprint(requestId: String, fingerprint: String, error: String) {
            val continuation = pending.remove(requestId) ?: return
            mainHandler.post {
                if (error.isBlank()) {
                    continuation.resume(fingerprint)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException("音频指纹生成失败：$error"),
                    )
                }
            }
        }
    }

    private fun failWaiting(error: Throwable) {
        waiting.clear()
        pending.entries.toList().forEach { (requestId, continuation) ->
            if (pending.remove(requestId, continuation)) {
                continuation.resumeWithException(error)
            }
        }
    }

    private fun FloatArray.toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        forEach(buffer::putFloat)
        return buffer.array()
    }

    private companion object {
        const val RUNTIME_HOST = "audio-recognition.fuo.local"
        const val RUNTIME_BASE_URL = "https://$RUNTIME_HOST/"
    }
}
