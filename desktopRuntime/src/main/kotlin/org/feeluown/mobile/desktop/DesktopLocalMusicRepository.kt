package org.feeluown.mobile.desktop

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.feeluown.mobile.LocalMusicDirectory
import org.feeluown.mobile.LocalMusicRepository
import org.feeluown.mobile.LocalMusicScanSettings
import org.feeluown.mobile.LocalTrackMetadata
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.canonicalLocalMusicDirectoryId
import org.feeluown.mobile.isLocalMusicDirectoryExcluded
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

internal data class DesktopAudioMetadata(
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long?,
)

internal class DesktopLocalMusicRepository(
    private val roots: List<Path> = defaultDesktopMusicRoots(),
    private val storageRoot: Path = desktopLocalMusicStorageRoot(),
    private val metadataReader: (Path) -> DesktopAudioMetadata = ::readDesktopAudioMetadata,
) : LocalMusicRepository {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var scanSettings = LocalMusicScanSettings()
    private var cachedTracks: List<MusicTrack>? = null

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) {
        scanSettings = settings
    }

    override suspend fun isDatabaseReady(): Boolean = withContext(Dispatchers.IO) {
        readIndex() != null
    }

    override suspend fun isDatabaseStale(): Boolean = withContext(Dispatchers.IO) {
        val stored = readIndex() ?: return@withContext true
        stored.fingerprint != computeFingerprint()
    }

    override suspend fun directories(): List<LocalMusicDirectory> = withContext(Dispatchers.IO) {
        val all = loadTracks()
        all.groupBy { it.localDirectoryId.orEmpty() }
            .filterKeys { it.isNotBlank() }
            .map { (directoryId, tracks) ->
                LocalMusicDirectory(
                    id = directoryId,
                    name = directoryId.trim('/').substringAfterLast('/').ifBlank { "Music" },
                    trackCount = tracks.size,
                    coverUrl = tracks.firstNotNullOfOrNull(MusicTrack::coverUrl),
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    override suspend fun tracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
        filterTracks(loadTracks())
    }

    override suspend fun refreshDatabase(): List<MusicTrack> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val overrides = readMetadataOverrides()
            val scanned = scanAudioFiles().mapNotNull { file ->
                val metadata = runCatching { metadataReader(file.path) }.getOrNull() ?: return@mapNotNull null
                val uri = file.path.toUri().toString()
                val override = overrides[uri]
                val lyrics = readLyrics(file.path)
                MusicTrack(
                    id = "local:${stableHash(uri)}",
                    title = override?.title?.ifBlank { metadata.title } ?: metadata.title,
                    artists = override?.artists ?: metadata.artists,
                    album = override?.album ?: metadata.album,
                    source = "local",
                    sourceType = TrackSourceType.LocalMediaStore,
                    durationMs = metadata.durationMs,
                    localUri = uri,
                    localDirectoryId = file.directoryId,
                    lyrics = lyrics,
                )
            }.sortedWith(
                compareBy<MusicTrack> { it.artists.lowercase(Locale.ROOT) }
                    .thenBy { it.album.lowercase(Locale.ROOT) }
                    .thenBy { it.title.lowercase(Locale.ROOT) },
            )
            val fingerprint = computeFingerprint()
            writeIndex(DesktopLocalMusicIndex(fingerprint, scanned))
            cachedTracks = scanned
            filterTracks(scanned)
        }
    }

    override suspend fun search(keyword: String): List<MusicTrack> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()
        return tracks().filter { track ->
            track.title.contains(query, ignoreCase = true) ||
                track.artists.contains(query, ignoreCase = true) ||
                track.album.contains(query, ignoreCase = true)
        }
    }

    override suspend fun updateMetadata(track: MusicTrack, metadata: LocalTrackMetadata) = withContext(Dispatchers.IO) {
        val uri = track.localUri ?: return@withContext
        mutex.withLock {
            val next = metadata.copy(title = metadata.title.ifBlank { track.title })
            val overrides = readMetadataOverrides().toMutableMap()
            overrides[uri] = next
            writeMetadataOverrides(overrides)
            val updated = loadTracksLocked().map { current ->
                if (current.localUri == uri) {
                    current.copy(title = next.title, artists = next.artists, album = next.album)
                } else {
                    current
                }
            }
            val fingerprint = readIndex()?.fingerprint ?: computeFingerprint()
            writeIndex(DesktopLocalMusicIndex(fingerprint, updated))
            cachedTracks = updated
            bestEffortWriteTags(uri, next)
        }
    }

    override suspend fun saveLyrics(track: MusicTrack, lyrics: String) = withContext(Dispatchers.IO) {
        val text = lyrics.takeIf { it.isNotBlank() } ?: return@withContext
        val uri = track.localUri ?: return@withContext
        val path = runCatching { Path.of(URI(uri)) }.getOrNull() ?: return@withContext
        mutex.withLock {
            val target = sidecarLyricsPath(path)
            val savedToSidecar = runCatching {
                Files.writeString(target, text)
                true
            }.getOrDefault(false)
            if (!savedToSidecar) {
                Files.createDirectories(lyricsDirectory())
                Files.writeString(lyricsDirectory().resolve("${stableHash(uri)}.lrc"), text)
            }
            val updated = loadTracksLocked().map { current ->
                if (current.localUri == uri) current.copy(lyrics = text) else current
            }
            val fingerprint = readIndex()?.fingerprint ?: computeFingerprint()
            writeIndex(DesktopLocalMusicIndex(fingerprint, updated))
            cachedTracks = updated
        }
    }

    private suspend fun loadTracks(): List<MusicTrack> = mutex.withLock { loadTracksLocked() }

    private fun loadTracksLocked(): List<MusicTrack> {
        cachedTracks?.let { return it }
        val loaded = readIndex()?.tracks.orEmpty()
        cachedTracks = loaded
        return loaded
    }

    private fun filterTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val minDurationMs = scanSettings.minDurationSeconds.coerceAtLeast(0) * 1_000L
        return tracks.filter { track ->
            !isLocalMusicDirectoryExcluded(track.localDirectoryId.orEmpty(), scanSettings.excludedDirectoryIds) &&
                (track.durationMs ?: Long.MAX_VALUE) >= minDurationMs
        }
    }

    private fun scanAudioFiles(): List<DesktopAudioFile> = buildList {
        roots.filter(Files::isDirectory).forEach { root ->
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter(::isSupportedAudioFile)
                    .forEach { path ->
                        val relativeParent = path.parent?.let { root.relativize(it).toString() }.orEmpty()
                        val rootName = root.fileName?.toString()?.ifBlank { "Music" } ?: "Music"
                        val rawDirectory = listOf(rootName, relativeParent)
                            .filter(String::isNotBlank)
                            .joinToString("/")
                            .replace('\\', '/')
                        val directoryId = canonicalLocalMusicDirectoryId(rawDirectory) ?: "$rootName/"
                        add(DesktopAudioFile(path, directoryId))
                    }
            }
        }
    }

    private fun computeFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        scanAudioFiles()
            .sortedBy { it.path.toAbsolutePath().normalize().toString() }
            .forEach { file ->
                val path = file.path
                digest.update(path.toAbsolutePath().normalize().toString().encodeToByteArray())
                digest.update(Files.size(path).toString().encodeToByteArray())
                digest.update(Files.getLastModifiedTime(path).toMillis().toString().encodeToByteArray())
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun readLyrics(path: Path): String? {
        val sidecar = sidecarLyricsPath(path)
        if (Files.isRegularFile(sidecar)) {
            runCatching { Files.readString(sidecar) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val appCopy = lyricsDirectory().resolve("${stableHash(path.toUri().toString())}.lrc")
        return if (Files.isRegularFile(appCopy)) {
            runCatching { Files.readString(appCopy) }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    private fun readIndex(): DesktopLocalMusicIndex? {
        val file = indexFile()
        if (!Files.isRegularFile(file)) return null
        return runCatching {
            val root = json.parseToJsonElement(Files.readString(file)).jsonObject
            if (root["version"]?.jsonPrimitive?.intOrNull != INDEX_VERSION) return@runCatching null
            DesktopLocalMusicIndex(
                fingerprint = root["fingerprint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                tracks = root["tracks"]?.jsonArray?.mapNotNull(::trackFromJson).orEmpty(),
            )
        }.getOrNull()
    }

    private fun writeIndex(index: DesktopLocalMusicIndex) {
        Files.createDirectories(storageRoot)
        val root = buildJsonObject {
            put("version", JsonPrimitive(INDEX_VERSION))
            put("fingerprint", JsonPrimitive(index.fingerprint))
            put("tracks", JsonArray(index.tracks.map(::trackToJson)))
        }
        atomicWrite(indexFile(), json.encodeToString(JsonElement.serializer(), root))
    }

    private fun readMetadataOverrides(): Map<String, LocalTrackMetadata> {
        val file = metadataOverrideFile()
        if (!Files.isRegularFile(file)) return emptyMap()
        return runCatching {
            val root = json.parseToJsonElement(Files.readString(file)).jsonObject
            root.mapNotNull { (uri, element) ->
                val value = element.jsonObject
                uri to LocalTrackMetadata(
                    title = value.string("title"),
                    artists = value.string("artists"),
                    album = value.string("album"),
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeMetadataOverrides(overrides: Map<String, LocalTrackMetadata>) {
        Files.createDirectories(storageRoot)
        val root = buildJsonObject {
            overrides.forEach { (uri, metadata) ->
                put(uri, buildJsonObject {
                    put("title", JsonPrimitive(metadata.title))
                    put("artists", JsonPrimitive(metadata.artists))
                    put("album", JsonPrimitive(metadata.album))
                })
            }
        }
        atomicWrite(metadataOverrideFile(), json.encodeToString(JsonElement.serializer(), root))
    }

    private fun indexFile(): Path = storageRoot.resolve("index.json")
    private fun metadataOverrideFile(): Path = storageRoot.resolve("metadata-overrides.json")
    private fun lyricsDirectory(): Path = storageRoot.resolve("lyrics")
}

private data class DesktopAudioFile(val path: Path, val directoryId: String)
private data class DesktopLocalMusicIndex(val fingerprint: String, val tracks: List<MusicTrack>)

private fun readDesktopAudioMetadata(path: Path): DesktopAudioMetadata {
    val audio = AudioFileIO.read(path.toFile())
    val tag = audio.tag
    val title = tag?.getFirst(FieldKey.TITLE).orEmpty().ifBlank { path.nameWithoutExtension }
    val artists = tag?.getFirst(FieldKey.ARTIST).orEmpty()
    val album = tag?.getFirst(FieldKey.ALBUM).orEmpty()
    val durationMs = audio.audioHeader.trackLength.toLong().takeIf { it > 0L }?.times(1_000L)
    return DesktopAudioMetadata(title, artists, album, durationMs)
}

private fun bestEffortWriteTags(uri: String, metadata: LocalTrackMetadata) {
    val path = runCatching { Path.of(URI(uri)) }.getOrNull() ?: return
    runCatching {
        val audio = AudioFileIO.read(path.toFile())
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, metadata.title)
        tag.setField(FieldKey.ARTIST, metadata.artists)
        tag.setField(FieldKey.ALBUM, metadata.album)
        audio.commit()
    }
}

private fun trackToJson(track: MusicTrack): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(track.id))
    put("title", JsonPrimitive(track.title))
    put("artists", JsonPrimitive(track.artists))
    put("album", JsonPrimitive(track.album))
    put("source", JsonPrimitive(track.source))
    put("sourceType", JsonPrimitive(track.sourceType.name))
    track.coverUrl?.let { put("coverUrl", JsonPrimitive(it)) }
    track.durationMs?.let { put("durationMs", JsonPrimitive(it)) }
    track.localUri?.let { put("localUri", JsonPrimitive(it)) }
    track.localDirectoryId?.let { put("localDirectoryId", JsonPrimitive(it)) }
    track.lyrics?.let { put("lyrics", JsonPrimitive(it)) }
}

private fun trackFromJson(element: JsonElement): MusicTrack? = runCatching {
    val value = element.jsonObject
    MusicTrack(
        id = value.string("id"),
        title = value.string("title"),
        artists = value.string("artists"),
        album = value.string("album"),
        source = value.string("source").ifBlank { "local" },
        sourceType = runCatching { TrackSourceType.valueOf(value.string("sourceType")) }
            .getOrDefault(TrackSourceType.LocalMediaStore),
        coverUrl = value.nullableString("coverUrl"),
        durationMs = value["durationMs"]?.jsonPrimitive?.longOrNull,
        localUri = value.nullableString("localUri"),
        localDirectoryId = value.nullableString("localDirectoryId"),
        lyrics = value.nullableString("lyrics"),
    )
}.getOrNull()

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.nullableString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun isSupportedAudioFile(path: Path): Boolean = path.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS

private fun sidecarLyricsPath(audio: Path): Path = audio.resolveSibling("${audio.nameWithoutExtension}.lrc")

private fun defaultDesktopMusicRoots(): List<Path> {
    val home = Path.of(System.getProperty("user.home").orEmpty())
    val candidates = linkedSetOf<Path>()
    linuxXdgMusicDirectory(home)?.let(candidates::add)
    candidates.add(home.resolve("Music"))
    return candidates.map { it.toAbsolutePath().normalize() }.distinct()
}

private fun linuxXdgMusicDirectory(home: Path): Path? {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    if (!os.contains("linux")) return null
    val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
        ?: home.resolve(".config")
    val file = configHome.resolve("user-dirs.dirs")
    if (!Files.isRegularFile(file)) return null
    val raw = runCatching { Files.readAllLines(file) }.getOrNull()
        ?.firstOrNull { it.trimStart().startsWith("XDG_MUSIC_DIR=") }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"')
        ?: return null
    val expanded = raw.replace("${'$'}HOME", home.toString())
    return runCatching { Path.of(expanded) }.getOrNull()
}

private fun desktopLocalMusicStorageRoot(): Path {
    val home = System.getProperty("user.home").orEmpty()
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val base = when {
        os.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            ?: "$home/AppData/Local"
        os.contains("mac") -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
            ?: "$home/.local/share"
    }
    return Path.of(base, "FuoEvolve", "local-music")
}

private fun stableHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
    return digest.take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun atomicWrite(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temporary = target.resolveSibling("${target.fileName}.tmp")
    Files.writeString(temporary, content)
    runCatching {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.getOrElse {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus", "wav", "aif", "aiff", "wma",
)
private const val INDEX_VERSION = 1
