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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AndroidAudioRecognitionRepository(
    context: Context,
) : AudioRecognitionRepository {
    private val delegate = DefaultAudioRecognitionRepository(
        captureDevice = AndroidAudioRecognitionCaptureDevice(context.applicationContext),
        fingerprintRuntime = AndroidFingerprintRuntime(context.applicationContext),
        matcher = NeteaseAudioRecognitionMatcher(),
    )

    override suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong> =
        delegate.recognize(onEvent)

    override fun cancel() = delegate.cancel()
}

private class AndroidAudioRecognitionCaptureDevice(
    private val context: Context,
) : AudioRecognitionCaptureDevice {
    private val activeRecorder = AtomicReference<AudioRecord?>()

    override suspend fun capture(onSamples: (FloatArray) -> Unit) = withContext(Dispatchers.IO) {
        checkMicrophonePermission()
        val minBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_RECOGNITION_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) { "设备不支持 48 kHz 麦克风录音" }
        val recorder = createAudioRecord(MediaRecorder.AudioSource.UNPROCESSED, minBufferSize)
            ?: createAudioRecord(MediaRecorder.AudioSource.MIC, minBufferSize)
            ?: throw IllegalStateException("麦克风初始化失败")
        check(activeRecorder.compareAndSet(null, recorder)) { "麦克风录音已经在进行中" }
        val readBuffer = ShortArray(2_048)
        try {
            recorder.startRecording()
            while (activeRecorder.get() === recorder) {
                val read = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    if (activeRecorder.get() !== recorder) break
                    throw IllegalStateException("麦克风读取失败：$read")
                }
                if (read > 0) {
                    onSamples(FloatArray(read) { index -> readBuffer[index] / 32768f })
                }
            }
        } finally {
            releaseRecorder(recorder)
        }
    }

    override fun cancel() {
        activeRecorder.getAndSet(null)?.let(::stopAndRelease)
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

    private fun releaseRecorder(recorder: AudioRecord) {
        activeRecorder.compareAndSet(recorder, null)
        stopAndRelease(recorder)
    }

    private fun stopAndRelease(recorder: AudioRecord) {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
    }
}

private class AndroidFingerprintRuntime(
    private val context: Context,
) : AudioFingerprintRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, Continuation<String>>()
    private var webView: WebView? = null
    private var ready = false
    private val waiting = mutableListOf<() -> Unit>()
    private val bridge = FingerprintBridge()

    override suspend fun generate(samples: FloatArray): String = suspendCancellableCoroutine { continuation ->
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
            }
            if (ready) action() else waiting.add(action)
        }
    }

    override fun cancel() {
        mainHandler.post {
            waiting.clear()
            val cancellation = CancellationException("听歌识曲已取消")
            pending.entries.toList().forEach { (requestId, continuation) ->
                if (pending.remove(requestId, continuation)) {
                    continuation.resumeWithException(cancellation)
                }
            }
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
                    view.evaluateJavascript("void globalThis.fuoFingerprint.verifyRuntime()", null)
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
                    failPending(IllegalStateException("音频指纹运行时初始化失败：$error"))
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

    private fun failPending(error: Throwable) {
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
