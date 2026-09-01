package org.feeluown.mobile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class AndroidAppUpdateController(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope,
) : AppUpdateController {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val operationMutex = Mutex()
    private val installedPackage = readInstalledPackageInfo()
    private val installedVersionCode = installedPackage.versionCodeLong()
    private val installedVersionName = installedPackage.versionName.orEmpty()
    private var latestManifest: AppUpdateManifest? = null
    private var observedPreferences: UpdatePreferences? = null

    private val mutableUiState = MutableStateFlow(
        AppUpdateUiState(
            supported = true,
            installedVersionCode = installedVersionCode,
            installedVersionName = installedVersionName,
        ),
    )
    override val uiState: StateFlow<AppUpdateUiState> = mutableUiState

    init {
        scope.launch {
            settingsRepository.state
                .filter { it.isLoaded }
                .map { state ->
                    UpdatePreferences(
                        channel = state.settings.appUpdateChannel,
                        autoCheckEnabled = state.settings.autoCheckAppUpdates,
                    )
                }
                .distinctUntilChanged()
                .collect { preferences ->
                    val previous = observedPreferences
                    observedPreferences = preferences
                    val channelChanged = previous != null && previous.channel != preferences.channel
                    if (channelChanged) latestManifest = null
                    mutableUiState.update { current ->
                        current.copy(
                            channel = preferences.channel,
                            autoCheckEnabled = preferences.autoCheckEnabled,
                            phase = if (channelChanged) AppUpdatePhase.Idle else current.phase,
                            remoteVersionCode = if (channelChanged) null else current.remoteVersionCode,
                            remoteVersionName = if (channelChanged) null else current.remoteVersionName,
                            publishedAt = if (channelChanged) null else current.publishedAt,
                            releaseNotesUrl = if (channelChanged) null else current.releaseNotesUrl,
                            isUpdateSavedToDownloads = if (channelChanged) false else current.isUpdateSavedToDownloads,
                            isSavingUpdateToDownloads = if (channelChanged) false else current.isSavingUpdateToDownloads,
                            message = if (channelChanged) null else current.message,
                        )
                    }
                    when {
                        channelChanged -> checkForUpdates(force = true)
                        preferences.autoCheckEnabled -> checkForUpdates(force = false)
                    }
                }
        }
    }

    override fun setChannel(channel: AppUpdateChannel) {
        if (settingsRepository.state.value.settings.appUpdateChannel == channel) return
        scope.launch {
            settingsRepository.update { settings -> settings.copy(appUpdateChannel = channel) }
        }
    }

    override fun setAutoCheckEnabled(enabled: Boolean) {
        if (settingsRepository.state.value.settings.autoCheckAppUpdates == enabled) return
        scope.launch {
            settingsRepository.update { settings -> settings.copy(autoCheckAppUpdates = enabled) }
        }
    }

    override fun checkNow() {
        scope.launch { checkForUpdates(force = true) }
    }

    override fun downloadAndInstall() {
        scope.launch {
            operationMutex.withLock {
                val manifest = latestManifest
                if (manifest == null || manifest.versionCode <= installedVersionCode) {
                    checkForUpdatesLocked(force = true)
                    return@withLock
                }
                runCatching { downloadAndInstallLocked(manifest) }
                    .onFailure(::showError)
            }
        }
    }

    override fun saveToDownloads() {
        scope.launch {
            operationMutex.withLock {
                val manifest = latestManifest
                if (manifest == null || manifest.versionCode <= installedVersionCode) {
                    checkForUpdatesLocked(force = true)
                    return@withLock
                }
                if (mutableUiState.value.isUpdateSavedToDownloads) return@withLock
                if (requiresLegacyDownloadsPermission()) {
                    requestLegacyDownloadsPermission()
                    return@withLock
                }
                mutableUiState.update {
                    it.copy(
                        isSavingUpdateToDownloads = true,
                        message = "正在保存 ${manifest.versionName} 到下载文件夹",
                    )
                }
                runCatching { saveToDownloadsLocked(manifest) }
                    .onSuccess {
                        mutableUiState.update {
                            it.copy(
                                phase = AppUpdatePhase.UpdateAvailable,
                                downloadProgress = null,
                                isUpdateSavedToDownloads = true,
                                isSavingUpdateToDownloads = false,
                                message = "安装包已保存到下载文件夹",
                            )
                        }
                    }
                    .onFailure(::showError)
            }
        }
    }

    private suspend fun checkForUpdates(force: Boolean) {
        operationMutex.withLock { checkForUpdatesLocked(force) }
    }

    private suspend fun checkForUpdatesLocked(force: Boolean) {
        val settings = settingsRepository.state.value.settings
        val channel = settings.appUpdateChannel
        if (!force && !shouldAutoCheck(channel)) return
        if (mutableUiState.value.phase == AppUpdatePhase.Downloading) return

        mutableUiState.update {
            it.copy(
                channel = channel,
                autoCheckEnabled = settings.autoCheckAppUpdates,
                phase = AppUpdatePhase.Checking,
                downloadProgress = null,
                isSavingUpdateToDownloads = false,
                message = null,
            )
        }

        runCatching {
            withContext(Dispatchers.IO) { fetchManifest(channel) }
        }.onSuccess { manifest ->
            markSuccessfulCheck(channel)
            latestManifest = manifest
            val decision = evaluateAppUpdate(installedVersionCode, channel, manifest.versionCode)
            val savedToDownloads = decision == AppUpdateDecision.UpdateAvailable && runCatching {
                withContext(Dispatchers.IO) { isSavedToDownloads(manifest) }
            }.getOrDefault(false)
            mutableUiState.update { current ->
                current.copy(
                    phase = when (decision) {
                        AppUpdateDecision.UpToDate -> AppUpdatePhase.UpToDate
                        AppUpdateDecision.UpdateAvailable -> AppUpdatePhase.UpdateAvailable
                        AppUpdateDecision.WaitingForStable -> AppUpdatePhase.WaitingForStable
                    },
                    remoteVersionCode = manifest.versionCode,
                    remoteVersionName = manifest.versionName,
                    publishedAt = manifest.publishedAt,
                    releaseNotesUrl = manifest.releaseNotesUrl,
                    downloadProgress = null,
                    isUpdateSavedToDownloads = savedToDownloads,
                    message = when (decision) {
                        AppUpdateDecision.UpToDate -> "当前已是最新版本"
                        AppUpdateDecision.UpdateAvailable -> "发现新版本 ${manifest.versionName}"
                        AppUpdateDecision.WaitingForStable -> WAITING_FOR_STABLE_MESSAGE
                    },
                )
            }
        }.onFailure(::showError)
    }

    private fun shouldAutoCheck(channel: AppUpdateChannel): Boolean {
        val lastCheck = preferences.getLong(lastCheckKey(channel), 0L)
        return System.currentTimeMillis() - lastCheck >= AUTO_CHECK_INTERVAL_MS
    }

    private fun markSuccessfulCheck(channel: AppUpdateChannel) {
        preferences.edit().putLong(lastCheckKey(channel), System.currentTimeMillis()).apply()
    }

    private fun fetchManifest(channel: AppUpdateChannel): AppUpdateManifest {
        val endpoint = when (channel) {
            AppUpdateChannel.Stable -> STABLE_MANIFEST_URL
            AppUpdateChannel.Canary -> CANARY_MANIFEST_URL
        }
        val separator = if ('?' in endpoint) '&' else '?'
        val connection = (URL("$endpoint${separator}_=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "FuoEvolve/$installedVersionName")
        }
        return connection.useConnection { http ->
            if (http.responseCode !in 200..299) {
                throw IOException("检查更新失败（HTTP ${http.responseCode}）")
            }
            val manifest = http.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                json.decodeFromString<AppUpdateManifest>(reader.readText())
            }
            validateManifest(channel, manifest)
            manifest
        }
    }

    private fun validateManifest(channel: AppUpdateChannel, manifest: AppUpdateManifest) {
        require(manifest.schemaVersion == 1) { "不支持的更新清单版本" }
        require(manifest.channel == channel.manifestValue()) { "更新渠道与清单不匹配" }
        require(manifest.versionCode > 0L) { "更新版本号无效" }
        require(manifest.versionName.isNotBlank()) { "更新版本名称为空" }
        require(manifest.apk.url.startsWith("https://")) { "更新下载地址无效" }
        require(SHA256_REGEX.matches(manifest.apk.sha256)) { "更新校验值无效" }
        require(manifest.apk.size > 0L) { "更新文件大小无效" }
    }

    private suspend fun downloadAndInstallLocked(manifest: AppUpdateManifest) {
        val apkFile = obtainValidatedApk(manifest)
        launchInstaller(apkFile)
    }

    private suspend fun saveToDownloadsLocked(manifest: AppUpdateManifest) {
        val apkFile = obtainValidatedApk(manifest)
        mutableUiState.update {
            it.copy(
                phase = AppUpdatePhase.UpdateAvailable,
                downloadProgress = null,
                message = "正在保存 ${manifest.versionName} 到下载文件夹",
            )
        }
        withContext(Dispatchers.IO) { saveApkToDownloads(apkFile, manifest) }
    }

    private suspend fun obtainValidatedApk(manifest: AppUpdateManifest): File {
        val updateDir = File(appContext.cacheDir, UPDATE_CACHE_DIR).apply { mkdirs() }
        val apkFile = File(updateDir, "fuo-evolve-${manifest.versionCode}.apk")
        val validCachedApk = withContext(Dispatchers.IO) {
            apkFile.isFile &&
                apkFile.length() == manifest.apk.size &&
                sha256(apkFile).equals(manifest.apk.sha256, ignoreCase = true) &&
                validateDownloadedApk(apkFile, manifest)
        }
        if (!validCachedApk) {
            withContext(Dispatchers.IO) { downloadApk(manifest, apkFile) }
        }
        withContext(Dispatchers.IO) {
            check(validateDownloadedApk(apkFile, manifest)) { "下载的安装包校验失败" }
        }
        return apkFile
    }

    private fun downloadApk(manifest: AppUpdateManifest, apkFile: File) {
        val tempFile = File(apkFile.parentFile, "${apkFile.name}.part")
        tempFile.delete()
        apkFile.delete()
        mutableUiState.update {
            it.copy(
                phase = AppUpdatePhase.Downloading,
                downloadProgress = 0f,
                message = "正在下载 ${manifest.versionName}",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = (URL(manifest.apk.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "FuoEvolve/$installedVersionName")
        }
        try {
            connection.useConnection { http ->
                if (http.responseCode !in 200..299) {
                    throw IOException("下载安装包失败（HTTP ${http.responseCode}）")
                }
                var downloaded = 0L
                http.inputStream.buffered().use { input ->
                    tempFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            if (downloaded > manifest.apk.size) {
                                throw IOException("下载安装包大小超出清单声明")
                            }
                            val progress = (downloaded.toDouble() / manifest.apk.size.toDouble()).toFloat().coerceIn(0f, 1f)
                            mutableUiState.update { current -> current.copy(downloadProgress = progress) }
                        }
                    }
                }
                if (downloaded != manifest.apk.size) {
                    throw IOException("下载安装包大小不匹配")
                }
                val actualSha256 = digest.digest().toHexString()
                if (!actualSha256.equals(manifest.apk.sha256, ignoreCase = true)) {
                    throw IOException("下载安装包 SHA-256 校验失败")
                }
            }
            if (!tempFile.renameTo(apkFile)) {
                tempFile.copyTo(apkFile, overwrite = true)
                tempFile.delete()
            }
        } catch (error: Throwable) {
            tempFile.delete()
            apkFile.delete()
            throw error
        }
    }

    private fun isSavedToDownloads(manifest: AppUpdateManifest): Boolean {
        val fileName = downloadsFileName(manifest)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.MediaColumns.SIZE)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            appContext.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(fileName),
                null,
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    if (cursor.getLong(sizeIndex) == manifest.apk.size) return true
                }
                false
            } ?: false
        } else {
            legacyDownloadsFile(fileName).let { file ->
                file.isFile && file.length() == manifest.apk.size
            }
        }
    }

    private fun saveApkToDownloads(apkFile: File, manifest: AppUpdateManifest) {
        if (isSavedToDownloads(manifest)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveApkToMediaStore(apkFile, manifest)
        } else {
            saveApkToLegacyDownloads(apkFile, manifest)
        }
    }

    private fun saveApkToMediaStore(apkFile: File, manifest: AppUpdateManifest) {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, downloadsFileName(manifest))
            put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建下载文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                apkFile.inputStream().buffered().use { input ->
                    input.copyTo(output, DOWNLOAD_BUFFER_BYTES)
                }
            } ?: throw IOException("无法写入下载文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun requiresLegacyDownloadsPermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    private fun requestLegacyDownloadsPermission() {
        mutableUiState.update {
            it.copy(
                phase = AppUpdatePhase.UpdateAvailable,
                isSavingUpdateToDownloads = false,
                message = "需要存储权限以保存安装包到下载文件夹",
            )
        }
        appContext.startActivity(
            Intent(appContext, AppUpdateSavePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Suppress("DEPRECATION")
    private fun saveApkToLegacyDownloads(apkFile: File, manifest: AppUpdateManifest) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("保存到下载文件夹需要存储权限")
        }
        val target = legacyDownloadsFile(downloadsFileName(manifest))
        target.parentFile?.let { downloadsDir ->
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                throw IOException("无法创建下载文件夹")
            }
        }
        apkFile.copyTo(target, overwrite = true)
        if (target.length() != manifest.apk.size) {
            target.delete()
            throw IOException("保存的安装包大小不匹配")
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDownloadsFile(fileName: String): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        fileName,
    )

    private fun downloadsFileName(manifest: AppUpdateManifest): String {
        val safeVersionName = manifest.versionName.replace(INVALID_FILE_NAME_CHARS, "_")
        return "FuoEvolve-$safeVersionName-${manifest.versionCode}.apk"
    }

    private fun validateDownloadedApk(apkFile: File, manifest: AppUpdateManifest): Boolean {
        val archive = readArchivePackageInfo(apkFile) ?: return false
        if (archive.packageName != appContext.packageName) return false
        if (archive.versionCodeLong() != manifest.versionCode) return false
        if (archive.versionCodeLong() <= installedVersionCode) return false
        val installedSigners = installedPackage.signerSha256Digests()
        val archiveSigners = archive.signerSha256Digests()
        return installedSigners.isNotEmpty() && archiveSigners == installedSigners
    }

    private fun launchInstaller(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            mutableUiState.update {
                it.copy(
                    phase = AppUpdatePhase.InstallPermissionRequired,
                    downloadProgress = null,
                    message = "请允许 FuoEvolve 安装未知来源应用，然后返回继续安装",
                )
            }
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            return
        }

        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile,
        )
        var lastLaunchError: Throwable? = null
        androidInstallerIntentActions(Build.VERSION.SDK_INT).forEach { action ->
            val intent = buildInstallerIntent(action, uri)
            grantInstallerUriPermission(intent, uri)
            try {
                appContext.startActivity(intent)
                mutableUiState.update {
                    it.copy(
                        phase = AppUpdatePhase.Installing,
                        downloadProgress = null,
                        message = "已打开系统安装器",
                    )
                }
                return
            } catch (error: ActivityNotFoundException) {
                lastLaunchError = error
            } catch (error: SecurityException) {
                lastLaunchError = error
            }
        }
        throw IOException("无法打开系统安装器", lastLaunchError)
    }

    @Suppress("DEPRECATION")
    private fun buildInstallerIntent(action: AndroidInstallerIntentAction, uri: Uri): Intent =
        when (action) {
            AndroidInstallerIntentAction.InstallPackage -> Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
            }
            AndroidInstallerIntentAction.ViewPackage -> Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
            }
        }.apply {
            clipData = ClipData.newRawUri("FuoEvolve update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    @Suppress("DEPRECATION")
    private fun grantInstallerUriPermission(intent: Intent, uri: Uri) {
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).forEach { resolveInfo ->
            runCatching {
                appContext.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun showError(error: Throwable) {
        mutableUiState.update {
            it.copy(
                phase = AppUpdatePhase.Error,
                downloadProgress = null,
                isSavingUpdateToDownloads = false,
                message = error.message?.takeIf(String::isNotBlank) ?: "更新失败，请稍后重试",
            )
        }
    }

    private fun readInstalledPackageInfo(): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(
            appContext.packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        )
    }

    private fun readArchivePackageInfo(apkFile: File): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        )
    }

    private fun PackageInfo.versionCodeLong(): Long = if (Build.VERSION.SDK_INT >= 28) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }

    private fun PackageInfo.signerSha256Digests(): Set<String> {
        val signerSignatures = if (Build.VERSION.SDK_INT >= 28) {
            signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            this.signatures?.toList().orEmpty()
        }
        return signerSignatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHexString()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }

    private data class UpdatePreferences(
        val channel: AppUpdateChannel,
        val autoCheckEnabled: Boolean,
    )

    private companion object {
        private const val PREFS_NAME = "fuo_app_update_state"
        private const val AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
        private const val HTTP_TIMEOUT_MS = 15_000
        private const val DOWNLOAD_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val UPDATE_CACHE_DIR = "app-updates"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val STABLE_MANIFEST_URL = "https://feeluown.github.io/FuoEvolve/update/stable.json"
        private const val CANARY_MANIFEST_URL = "https://raw.githubusercontent.com/feeluown/FuoEvolve/canary-dist/canary.json"
        private const val WAITING_FOR_STABLE_MESSAGE =
            "已切换至稳定版。当前版本比最新稳定版更新，将在后续稳定版发布后恢复接收稳定版更新。"
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val INVALID_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

        private fun lastCheckKey(channel: AppUpdateChannel): String = "last_check_${channel.name.lowercase()}"
    }
}
