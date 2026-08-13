package org.feeluown.mobile

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

const val MAX_REPLACEMENT_EXACT_MAPPINGS = 500
const val REPLACEMENT_PREFERENCE_LOGIT_LIMIT = 0.75
const val REPLACEMENT_PREFERENCE_FEATURE_COUNT = 18
const val REPLACEMENT_PREFERENCE_FEATURE_VERSION = 1
const val REPLACEMENT_LITE_MODEL_VERSION = "fuo-replacement-lite-v1-bge-small-zh-int8"
const val REPLACEMENT_ENHANCED_MODEL_VERSION = "fuo-replacement-multilingual-v1-e5-small-int8"

private const val REPLACEMENT_LEARNING_STATE_VERSION = 1
private const val FTRL_ALPHA = 0.05
private const val FTRL_BETA = 1.0
private const val FTRL_L1 = 0.0
private const val FTRL_L2 = 1.0
private const val MAX_SIGMOID_LOGIT = 35.0

private const val FEATURE_PROVIDER_NETEASE = 0
private const val FEATURE_PROVIDER_QQMUSIC = 1
private const val FEATURE_PROVIDER_BILIBILI = 2
private const val FEATURE_PROVIDER_YTMUSIC = 3
private const val FEATURE_PROVIDER_OTHER = 4
private const val FEATURE_SAME_PROVIDER = 5
private const val FEATURE_DURATION_CLOSENESS = 6
private const val FEATURE_ARTIST_MATCH = 7
private const val FEATURE_ALBUM_MATCH = 8
private const val FEATURE_LIVE = 9
private const val FEATURE_COVER = 10
private const val FEATURE_REMIX = 11
private const val FEATURE_INSTRUMENTAL = 12
private const val FEATURE_SPEED_VARIANT = 13
private const val FEATURE_VIDEO = 14
private const val FEATURE_OFFICIAL = 15
private const val FEATURE_SNIPPET = 16
private const val FEATURE_HIGH_QUALITY = 17

data class ReplacementPreferenceVector(
    val values: List<Double>,
) {
    init {
        require(values.size == REPLACEMENT_PREFERENCE_FEATURE_COUNT) {
            "replacement preference vector must have $REPLACEMENT_PREFERENCE_FEATURE_COUNT values"
        }
        require(values.all { it.isFinite() }) {
            "replacement preference vector values must be finite"
        }
    }
}

data class ReplacementPreferenceChoice(
    val key: String,
    val vector: ReplacementPreferenceVector,
) {
    init {
        require(key.isNotBlank()) { "replacement preference choice key must not be blank" }
    }
}

data class ReplacementSelectionFeedback(
    val originalKey: String,
    val selected: ReplacementPreferenceChoice,
    val rejected: List<ReplacementPreferenceChoice>,
    val exactSelection: SmartReplacementSelection,
) {
    init {
        require(originalKey.isNotBlank()) { "replacement feedback original key must not be blank" }
    }
}

@Serializable
data class ReplacementExactSelection(
    val originalKey: String,
    val selection: SmartReplacementSelection,
    val selectedAtMillis: Long,
)

@Serializable
data class ReplacementLearningState(
    val version: Int = REPLACEMENT_LEARNING_STATE_VERSION,
    val modelVersion: String = REPLACEMENT_LITE_MODEL_VERSION,
    val featureVersion: Int = REPLACEMENT_PREFERENCE_FEATURE_VERSION,
    val z: List<Double> = List(REPLACEMENT_PREFERENCE_FEATURE_COUNT) { 0.0 },
    val n: List<Double> = List(REPLACEMENT_PREFERENCE_FEATURE_COUNT) { 0.0 },
    val updateCount: Long = 0,
    val exactSelections: List<ReplacementExactSelection> = emptyList(),
)

interface ReplacementLearningStore {
    suspend fun load(): String?

    suspend fun save(encodedState: String)
}

object NoOpReplacementLearningStore : ReplacementLearningStore {
    override suspend fun load(): String? = null

    override suspend fun save(encodedState: String) = Unit
}

object ReplacementLearningCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(state: ReplacementLearningState): String =
        json.encodeToString(state.normalized())

    fun decode(raw: String?): ReplacementLearningState {
        if (raw.isNullOrBlank()) return ReplacementLearningState()
        return runCatching {
            json.decodeFromString<ReplacementLearningState>(raw).normalized()
        }.getOrDefault(ReplacementLearningState())
    }
}

suspend fun ReplacementLearningStore.loadState(): ReplacementLearningState =
    ReplacementLearningCodec.decode(load())

suspend fun ReplacementLearningStore.saveState(state: ReplacementLearningState) {
    save(ReplacementLearningCodec.encode(state))
}

interface ReplacementPreferenceLearner {
    fun adjustment(vector: ReplacementPreferenceVector): Double

    fun exactReplacementKey(originalKey: String): String?

    fun exactSelection(originalKey: String): SmartReplacementSelection?

    fun recordPlaybackFeedback(
        feedback: ReplacementSelectionFeedback,
        status: PlayerStatus,
        observedAtMillis: Long,
    )

    fun snapshot(): ReplacementLearningState
}

object NoOpReplacementPreferenceLearner : ReplacementPreferenceLearner {
    override fun adjustment(vector: ReplacementPreferenceVector): Double = 0.0

    override fun exactReplacementKey(originalKey: String): String? = null

    override fun exactSelection(originalKey: String): SmartReplacementSelection? = null

    override fun recordPlaybackFeedback(
        feedback: ReplacementSelectionFeedback,
        status: PlayerStatus,
        observedAtMillis: Long,
    ) = Unit

    override fun snapshot(): ReplacementLearningState = ReplacementLearningState()
}

class ReplacementLearningRepository(
    private val store: ReplacementLearningStore = NoOpReplacementLearningStore,
) : ReplacementPreferenceLearner {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ReplacementLearningState())
    private var learner = FtrlReplacementPreferenceLearner()
    private var initialized = false

    @Volatile
    private var generalizedLearningEnabled = true

    /** Frozen-model personalization snapshot. Mutations are serialized through [record] and [reset]. */
    val state: StateFlow<ReplacementLearningState> = mutableState.asStateFlow()

    suspend fun initialize(
        legacySelections: Map<String, SmartReplacementSelection> = emptyMap(),
    ) {
        mutex.withLock {
            if (initialized && legacySelections.isEmpty()) return
            val initialState = if (initialized) {
                learner.snapshot()
            } else {
                store.loadState()
            }
            learner = FtrlReplacementPreferenceLearner(
                initialState.withLegacySelections(legacySelections),
            )
            publishAndSave()
            initialized = true
        }
    }

    override fun adjustment(vector: ReplacementPreferenceVector): Double =
        if (generalizedLearningEnabled) {
            mutableState.value.adjustment(vector)
        } else {
            0.0
        }

    fun setGeneralizedLearningEnabled(enabled: Boolean) {
        generalizedLearningEnabled = enabled
    }

    override fun exactReplacementKey(originalKey: String): String? =
        exactSelection(originalKey)?.replacementId

    override fun exactSelection(originalKey: String): SmartReplacementSelection? =
        mutableState.value.exactSelections
            .lastOrNull { selection -> selection.originalKey == originalKey }
            ?.selection

    override fun recordPlaybackFeedback(
        feedback: ReplacementSelectionFeedback,
        status: PlayerStatus,
        observedAtMillis: Long,
    ) {
        // Used by synchronous ranker integrations; Android persistence should call suspend [record].
        learner.recordPlaybackFeedback(
            feedback = feedback.forGeneralizedLearningState(),
            status = status,
            observedAtMillis = observedAtMillis,
        )
        mutableState.value = learner.snapshot()
    }

    suspend fun record(
        feedback: ReplacementSelectionFeedback,
        status: PlayerStatus,
        observedAtMillis: Long,
    ) {
        mutex.withLock {
            learner.recordPlaybackFeedback(
                feedback = feedback.forGeneralizedLearningState(),
                status = status,
                observedAtMillis = observedAtMillis,
            )
            publishAndSave()
        }
    }

    suspend fun reset() {
        mutex.withLock {
            learner = FtrlReplacementPreferenceLearner()
            publishAndSave()
        }
    }

    override fun snapshot(): ReplacementLearningState = mutableState.value

    suspend fun persist() {
        mutex.withLock { publishAndSave() }
    }

    private suspend fun publishAndSave() {
        val snapshot = learner.snapshot()
        mutableState.value = snapshot
        store.saveState(snapshot)
    }

    private fun ReplacementSelectionFeedback.forGeneralizedLearningState(): ReplacementSelectionFeedback =
        if (generalizedLearningEnabled) this else copy(rejected = emptyList())
}

class FtrlReplacementPreferenceLearner(
    initialState: ReplacementLearningState = ReplacementLearningState(),
) : ReplacementPreferenceLearner {
    private val z: DoubleArray
    private val n: DoubleArray
    private var updateCount: Long
    private val exactSelections = linkedMapOf<String, ReplacementExactSelection>()

    init {
        val state = initialState.normalized()
        z = state.z.toDoubleArray()
        n = state.n.toDoubleArray()
        updateCount = state.updateCount
        state.exactSelections.forEach { selection ->
            exactSelections[selection.originalKey] = selection
        }
    }

    override fun adjustment(vector: ReplacementPreferenceVector): Double {
        val values = vector.values
        var logit = 0.0
        for (index in values.indices) {
            logit += weightAt(index) * values[index]
        }
        return logit.coerceIn(-REPLACEMENT_PREFERENCE_LOGIT_LIMIT, REPLACEMENT_PREFERENCE_LOGIT_LIMIT)
    }

    override fun exactReplacementKey(originalKey: String): String? =
        exactSelection(originalKey)?.replacementId

    override fun exactSelection(originalKey: String): SmartReplacementSelection? =
        exactSelections[originalKey]?.selection

    override fun recordPlaybackFeedback(
        feedback: ReplacementSelectionFeedback,
        status: PlayerStatus,
        observedAtMillis: Long,
    ) {
        if (status != PlayerStatus.Playing && status != PlayerStatus.Paused) return

        rememberExactSelection(
            ReplacementExactSelection(
                originalKey = feedback.originalKey,
                selection = feedback.exactSelection,
                selectedAtMillis = observedAtMillis.coerceAtLeast(0),
            ),
        )
        feedback.rejected
            .asSequence()
            .filter { it.key != feedback.selected.key }
            .distinctBy { it.key }
            .forEach { rejected -> updatePair(feedback.selected.vector, rejected.vector) }
    }

    override fun snapshot(): ReplacementLearningState = ReplacementLearningState(
        z = z.toList(),
        n = n.toList(),
        updateCount = updateCount,
        exactSelections = exactSelections.values.toList(),
    )

    private fun updatePair(
        selected: ReplacementPreferenceVector,
        rejected: ReplacementPreferenceVector,
    ) {
        val difference = DoubleArray(REPLACEMENT_PREFERENCE_FEATURE_COUNT)
        var pairLogit = 0.0
        var hasSignal = false
        for (index in difference.indices) {
            val value = selected.values[index] - rejected.values[index]
            difference[index] = value
            if (value != 0.0) hasSignal = true
            pairLogit += weightAt(index) * value
        }
        if (!hasSignal) return

        val probability = sigmoid(pairLogit)
        for (index in difference.indices) {
            val gradient = (probability - 1.0) * difference[index]
            if (gradient == 0.0) continue
            val weight = weightAt(index)
            val oldN = n[index]
            val newN = oldN + gradient * gradient
            val sigma = (sqrt(newN) - sqrt(oldN)) / FTRL_ALPHA
            z[index] += gradient - sigma * weight
            n[index] = newN
        }
        updateCount += 1
    }

    private fun weightAt(index: Int): Double {
        return ftrlWeight(z[index], n[index])
    }

    private fun rememberExactSelection(selection: ReplacementExactSelection) {
        exactSelections.remove(selection.originalKey)
        exactSelections[selection.originalKey] = selection
        while (exactSelections.size > MAX_REPLACEMENT_EXACT_MAPPINGS) {
            exactSelections.keys.firstOrNull()?.let(exactSelections::remove)
        }
    }
}

private fun ReplacementLearningState.adjustment(vector: ReplacementPreferenceVector): Double {
    var logit = 0.0
    for (index in vector.values.indices) {
        logit += ftrlWeight(z[index], n[index]) * vector.values[index]
    }
    return logit.coerceIn(-REPLACEMENT_PREFERENCE_LOGIT_LIMIT, REPLACEMENT_PREFERENCE_LOGIT_LIMIT)
}

private fun ftrlWeight(zValue: Double, nValue: Double): Double {
    if (abs(zValue) <= FTRL_L1) return 0.0
    val sign = if (zValue < 0.0) -1.0 else 1.0
    return -(zValue - sign * FTRL_L1) /
        ((FTRL_BETA + sqrt(nValue)) / FTRL_ALPHA + FTRL_L2)
}

fun replacementPreferenceVector(
    original: MusicTrack,
    candidate: MusicTrack,
): ReplacementPreferenceVector {
    val values = MutableList(REPLACEMENT_PREFERENCE_FEATURE_COUNT) { 0.0 }
    values[candidate.providerFeatureIndex()] = 1.0
    values[FEATURE_SAME_PROVIDER] = booleanFeature(
        candidate.source.equals(original.source, ignoreCase = true),
    )
    values[FEATURE_DURATION_CLOSENESS] = durationCloseness(original.durationMs, candidate.durationMs)
    values[FEATURE_ARTIST_MATCH] = normalizedMetadata(candidate.artists)
        .takeIf { it.isNotBlank() }
        ?.let { booleanFeature(it == normalizedMetadata(original.artists)) }
        ?: 0.0
    values[FEATURE_ALBUM_MATCH] = normalizedMetadata(candidate.album)
        .takeIf { it.isNotBlank() }
        ?.let { booleanFeature(it == normalizedMetadata(original.album)) }
        ?: 0.0

    val signals = ReplacementTextSignals(candidate.title + " " + candidate.album)
    values[FEATURE_LIVE] = booleanFeature(signals.has(chinese = listOf("现场", "演唱会"), ascii = listOf("live", "concert")))
    values[FEATURE_COVER] = booleanFeature(signals.has(chinese = listOf("翻唱"), ascii = listOf("cover")))
    values[FEATURE_REMIX] = booleanFeature(signals.has(chinese = listOf("混音", "重混"), ascii = listOf("remix")))
    values[FEATURE_INSTRUMENTAL] = booleanFeature(
        signals.has(
            chinese = listOf("伴奏", "纯音乐", "无人声"),
            ascii = listOf("instrumental", "karaoke"),
            phrases = listOf("off vocal"),
        ),
    )
    values[FEATURE_SPEED_VARIANT] = booleanFeature(
        signals.has(
            chinese = listOf("加速", "慢速", "变速"),
            ascii = listOf("nightcore"),
            phrases = listOf("sped up", "slowed down"),
        ),
    )
    values[FEATURE_VIDEO] = booleanFeature(signals.has(ascii = listOf("mv", "pv", "video")))
    values[FEATURE_OFFICIAL] = booleanFeature(signals.has(chinese = listOf("官方"), ascii = listOf("official")))
    values[FEATURE_SNIPPET] = booleanFeature(
        signals.has(
            chinese = listOf("片段", "试听", "截取"),
            ascii = listOf("snippet", "preview", "short"),
        ),
    )
    values[FEATURE_HIGH_QUALITY] = booleanFeature(
        signals.has(
            chinese = listOf("无损", "母带", "高解析"),
            ascii = listOf("lossless", "hires"),
            phrases = listOf("hi res"),
        ),
    )
    return ReplacementPreferenceVector(values)
}

fun replacementSelectionFeedback(
    original: MusicTrack,
    selected: MusicTrack,
    previousAutomatic: MusicTrack?,
    exactSelection: SmartReplacementSelection,
): ReplacementSelectionFeedback {
    return ReplacementSelectionFeedback(
        originalKey = original.id,
        selected = ReplacementPreferenceChoice(
            key = selected.id,
            vector = replacementPreferenceVector(original, selected),
        ),
        rejected = previousAutomatic
            ?.takeIf { it.id != selected.id }
            ?.let { automatic ->
                listOf(
                    ReplacementPreferenceChoice(
                        key = automatic.id,
                        vector = replacementPreferenceVector(original, automatic),
                    ),
                )
            }
            .orEmpty(),
        exactSelection = exactSelection,
    )
}

internal fun <T> rememberExactReplacementMapping(
    mappings: Map<String, T>,
    originalKey: String,
    replacement: T,
): Map<String, T> {
    val updated = linkedMapOf<String, T>()
    mappings.forEach { (key, value) ->
        if (key != originalKey) updated[key] = value
    }
    updated[originalKey] = replacement
    return updated.entries
        .toList()
        .takeLast(MAX_REPLACEMENT_EXACT_MAPPINGS)
        .associateTo(linkedMapOf<String, T>()) { it.toPair() }
}

internal fun <T> cappedExactReplacementMappings(mappings: Map<String, T>): Map<String, T> =
    mappings.entries
        .toList()
        .takeLast(MAX_REPLACEMENT_EXACT_MAPPINGS)
        .associateTo(linkedMapOf<String, T>()) { it.toPair() }

private fun ReplacementLearningState.normalized(): ReplacementLearningState {
    if (version != REPLACEMENT_LEARNING_STATE_VERSION) return ReplacementLearningState()

    val validHead = featureVersion == REPLACEMENT_PREFERENCE_FEATURE_VERSION &&
        z.size == REPLACEMENT_PREFERENCE_FEATURE_COUNT &&
        n.size == REPLACEMENT_PREFERENCE_FEATURE_COUNT &&
        z.all { it.isFinite() } &&
        n.all { it.isFinite() && it >= 0.0 } &&
        updateCount >= 0
    val normalizedExactSelections = linkedMapOf<String, ReplacementExactSelection>()
    exactSelections.forEach { selection ->
        if (selection.originalKey.isBlank() || selection.selection.replacementId.isBlank()) return@forEach
        normalizedExactSelections.remove(selection.originalKey)
        normalizedExactSelections[selection.originalKey] = selection.copy(
            selectedAtMillis = selection.selectedAtMillis.coerceAtLeast(0),
        )
    }
    val cappedExactSelections = normalizedExactSelections.values
        .toList()
        .takeLast(MAX_REPLACEMENT_EXACT_MAPPINGS)
    return copy(
        modelVersion = modelVersion.ifBlank { REPLACEMENT_LITE_MODEL_VERSION },
        featureVersion = REPLACEMENT_PREFERENCE_FEATURE_VERSION,
        z = if (validHead) z else List(REPLACEMENT_PREFERENCE_FEATURE_COUNT) { 0.0 },
        n = if (validHead) n else List(REPLACEMENT_PREFERENCE_FEATURE_COUNT) { 0.0 },
        updateCount = if (validHead) updateCount else 0,
        exactSelections = cappedExactSelections,
    )
}

private fun ReplacementLearningState.withLegacySelections(
    legacySelections: Map<String, SmartReplacementSelection>,
): ReplacementLearningState {
    if (legacySelections.isEmpty()) return this
    val merged = linkedMapOf<String, ReplacementExactSelection>()
    legacySelections.forEach { (originalKey, selection) ->
        if (originalKey.isBlank() || selection.replacementId.isBlank()) return@forEach
        merged[originalKey] = ReplacementExactSelection(
            originalKey = originalKey,
            selection = selection,
            selectedAtMillis = 0,
        )
    }
    exactSelections.forEach { exact ->
        merged.remove(exact.originalKey)
        merged[exact.originalKey] = exact
    }
    return copy(exactSelections = merged.values.toList()).normalized()
}

private fun MusicTrack.providerFeatureIndex(): Int = when (source.lowercase()) {
    "netease" -> FEATURE_PROVIDER_NETEASE
    "qqmusic" -> FEATURE_PROVIDER_QQMUSIC
    "bilibili" -> FEATURE_PROVIDER_BILIBILI
    "ytmusic" -> FEATURE_PROVIDER_YTMUSIC
    else -> FEATURE_PROVIDER_OTHER
}

private fun durationCloseness(originalDurationMs: Long?, candidateDurationMs: Long?): Double {
    if (originalDurationMs == null || candidateDurationMs == null) return 0.0
    val differenceMs = abs(originalDurationMs.toDouble() - candidateDurationMs.toDouble())
    return (1.0 - differenceMs / 30_000.0).coerceIn(0.0, 1.0)
}

private fun normalizedMetadata(value: String): String = value
    .lowercase()
    .filter(Char::isLetterOrDigit)

private fun booleanFeature(value: Boolean): Double = if (value) 1.0 else 0.0

private fun sigmoid(logit: Double): Double {
    val bounded = logit.coerceIn(-MAX_SIGMOID_LOGIT, MAX_SIGMOID_LOGIT)
    return if (bounded >= 0.0) {
        1.0 / (1.0 + exp(-bounded))
    } else {
        val exponential = exp(bounded)
        exponential / (1.0 + exponential)
    }
}

private class ReplacementTextSignals(raw: String) {
    private val normalized = raw.lowercase()
    private val asciiTokens = Regex("[a-z0-9]+")
        .findAll(normalized)
        .map { it.value }
        .toSet()

    fun has(
        chinese: List<String> = emptyList(),
        ascii: List<String> = emptyList(),
        phrases: List<String> = emptyList(),
    ): Boolean {
        return chinese.any(normalized::contains) ||
            ascii.any(asciiTokens::contains) ||
            phrases.any(normalized::contains)
    }
}
