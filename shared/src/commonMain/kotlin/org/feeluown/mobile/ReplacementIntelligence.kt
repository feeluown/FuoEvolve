package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

@kotlinx.serialization.Serializable
enum class ReplacementRankingMode {
    Legacy,
    OnDevice,
}

@kotlinx.serialization.Serializable
enum class ReplacementModelTier {
    Lite,
    Enhanced,
}

@kotlinx.serialization.Serializable
enum class SmartReplacementStrictness {
    Relaxed,
    Balanced,
    Strict,
}

enum class ReplacementModelState {
    Unavailable,
    NotPrepared,
    Downloading,
    Ready,
    Failed,
}

enum class ReplacementVersionKind {
    StudioOriginal,
    Live,
    Cover,
    Remix,
    Acoustic,
    InstrumentalKaraoke,
    SpedSlowed,
    Remaster,
    ClipMedley,
    Unknown,
}

enum class ReplacementRankingStrategy {
    LegacyRules,
    OnDeviceLite,
    OnDeviceEnhanced,
    LegacyFallback,
    UserSelected,
}

data class ReplacementIntelligenceCapability(
    val modelIncluded: Boolean,
    val onDeviceAvailable: Boolean,
    val reason: String? = null,
)

interface ReplacementModelManager {
    val capability: ReplacementIntelligenceCapability
    val activeTier: ReplacementModelTier?
    val state: ReplacementModelState

    suspend fun prepare(tier: ReplacementModelTier): ReplacementModelState
}

data class ReplacementRankingRequest(
    val original: MusicTrack,
    val candidates: List<ReplacementCandidate>,
    val mode: ReplacementRankingMode,
    val strictness: SmartReplacementStrictness,
    val modelTier: ReplacementModelTier,
)

interface ReplacementRanker {
    suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate>
}

object LegacyReplacementRanker : ReplacementRanker {
    override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> =
        request.candidates
            .map { candidate ->
                candidate.copy(
                    score = candidate.legacyScore,
                    modelScore = null,
                    sameSongConfidence = null,
                    rankingStrategy = ReplacementRankingStrategy.LegacyRules,
                    strategy = "legacy",
                )
            }
            .sortedByDescending { it.score }
}

internal suspend fun rankReplacementCandidatesWithFallback(
    request: ReplacementRankingRequest,
    ranker: ReplacementRanker,
    modelManager: ReplacementModelManager? = null,
    timeoutMs: Long = request.modelTier.inferenceTimeoutMs,
    prepareTimeoutMs: Long = request.modelTier.prepareTimeoutMs,
): List<ReplacementCandidate> {
    val legacy = request.candidates.sortedByDescending { it.legacyScore }
    if (request.mode != ReplacementRankingMode.OnDevice) {
        return LegacyReplacementRanker.rank(request)
    }
    if (ranker === LegacyReplacementRanker) {
        return legacy.asLegacyFallback(
            reason = "当前构建未提供端侧模型，已使用传统匹配",
            wireStrategy = "legacy_model_failure",
        )
    }
    if (
        modelManager != null &&
        (!modelManager.capability.modelIncluded || !modelManager.capability.onDeviceAvailable)
    ) {
        return legacy.asLegacyFallback(
            reason = modelManager.capability.reason ?: "端侧模型不可用，已使用传统匹配",
            wireStrategy = "legacy_model_failure",
        )
    }
    if (modelManager != null) {
        val needsPrepare = modelManager.state != ReplacementModelState.Ready ||
            modelManager.activeTier != request.modelTier
        if (needsPrepare) {
            val prepared = try {
                withTimeout(prepareTimeoutMs) {
                    modelManager.prepare(request.modelTier)
                }
            } catch (_: TimeoutCancellationException) {
                return legacy.asLegacyFallback(
                    reason = "端侧模型不可用，已使用传统匹配",
                    wireStrategy = "legacy_model_failure",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return legacy.asLegacyFallback(
                    reason = "端侧模型不可用，已使用传统匹配",
                    wireStrategy = "legacy_model_failure",
                )
            }
            if (prepared != ReplacementModelState.Ready) {
                return legacy.asLegacyFallback(
                    reason = "端侧模型不可用，已使用传统匹配",
                    wireStrategy = "legacy_model_failure",
                )
            }
        }
    }
    val ranked = try {
        withTimeout(timeoutMs) {
            ranker.rank(request)
        }
    } catch (_: TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
    if (ranked.isNullOrEmpty() || !ranked.hasCompleteValidModelOutputFor(request.candidates)) {
        return legacy.asLegacyFallback(
            reason = "端侧模型不可用，已使用传统匹配",
            wireStrategy = "legacy_model_failure",
        )
    }
    val rankedById = ranked.associateBy { it.track.id }
    val effectiveTier = modelManager?.activeTier ?: request.modelTier
    val strategy = when (effectiveTier) {
        ReplacementModelTier.Lite -> ReplacementRankingStrategy.OnDeviceLite
        ReplacementModelTier.Enhanced -> ReplacementRankingStrategy.OnDeviceEnhanced
    }
    val modelRanked = request.candidates.map { base ->
        val model = rankedById.getValue(base.track.id)
        val score = (model.modelScore ?: model.score).coerceIn(0.0, 1.0)
        model.copy(
            track = base.track,
            score = score,
            legacyScore = base.legacyScore,
            modelScore = score,
            sameSongConfidence = score,
            versionKind = base.versionKind,
            versionCompatibility = base.versionCompatibility,
            autoEligible = base.track.passesReplacementIdentityGate(request.original) &&
                score >= request.strictness.identityThreshold,
            rankingStrategy = strategy,
            strategy = strategy.wireName,
            reasons = model.reasons,
        )
    }
    return modelRanked.sortedByDescending { it.score }
}

interface ReplacementPreferenceAwareRanker {
    val preferenceLearner: ReplacementPreferenceLearner
}

internal data class ReplacementIdentitySignals(
    val titleScore: Double,
    val artistScore: Double,
    val durationScore: Double,
    val albumScore: Double,
) {
    val lexicalScore: Double
        get() = titleScore * LEGACY_TITLE_WEIGHT +
            artistScore * LEGACY_ARTIST_WEIGHT +
            durationScore * LEGACY_DURATION_WEIGHT
}

internal fun replacementIdentitySignals(
    original: MusicTrack,
    candidate: MusicTrack,
): ReplacementIdentitySignals = ReplacementIdentitySignals(
    titleScore = replacementLexicalFieldScore(original.title, candidate.title),
    artistScore = replacementLexicalFieldScore(original.artists, candidate.artists),
    durationScore = replacementDurationScore(original.durationMs, candidate.durationMs),
    albumScore = replacementLexicalFieldScore(original.album, candidate.album),
)

internal fun replacementLexicalFieldScore(original: String, candidate: String): Double {
    val originText = normalizeReplacementText(original)
    val candidateText = normalizeReplacementText(candidate)
    if (originText.isBlank() || candidateText.isBlank()) return 0.0
    return when {
        originText == candidateText -> 1.0
        originText.contains(candidateText) || candidateText.contains(originText) -> 0.8
        else -> replacementTokenSimilarity(originText, candidateText)
    }
}

internal fun replacementDurationScore(originalDurationMs: Long?, candidateDurationMs: Long?): Double {
    if (originalDurationMs == null || candidateDurationMs == null) return 0.5
    return (1.0 - kotlin.math.abs(originalDurationMs - candidateDurationMs).toDouble() / 30_000.0)
        .coerceIn(0.0, 1.0)
}

internal fun normalizeReplacementText(value: String): String =
    value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

private fun replacementTokenSimilarity(left: String, right: String): Double {
    if (left.isBlank() || right.isBlank()) return 0.0
    val common = left.toSet().intersect(right.toSet()).size.toDouble()
    return common / maxOf(left.toSet().size, right.toSet().size).toDouble()
}

internal fun MusicTrack.passesReplacementIdentityGate(original: MusicTrack): Boolean {
    if (title.isBlank()) return false
    val originalDuration = original.durationMs?.takeIf { it > 0 } ?: return true
    val candidateDuration = durationMs?.takeIf { it > 0 } ?: return false
    return kotlin.math.abs(originalDuration - candidateDuration).toDouble() / originalDuration <= MAX_IDENTITY_DURATION_DIFF_RATIO
}

internal fun classifyReplacementVersion(track: MusicTrack): ReplacementVersionKind {
    val text = "${track.title} ${track.album}".trim().lowercase()
    if (text.isBlank()) return ReplacementVersionKind.Unknown
    val tokens = Regex("[a-z0-9]+").findAll(text).map { it.value }.toSet()
    return when {
        text.containsAny(VERSION_CLIP_TEXT) || tokens.containsAny(VERSION_CLIP_TOKENS) -> ReplacementVersionKind.ClipMedley
        text.containsAny(VERSION_SPED_SLOWED_TEXT) -> ReplacementVersionKind.SpedSlowed
        text.containsAny(VERSION_INSTRUMENTAL_TEXT) || tokens.containsAny(VERSION_INSTRUMENTAL_TOKENS) ->
            ReplacementVersionKind.InstrumentalKaraoke
        text.containsAny(VERSION_COVER_TEXT) || tokens.containsAny(VERSION_COVER_TOKENS) -> ReplacementVersionKind.Cover
        text.containsAny(VERSION_REMIX_TEXT) || tokens.containsAny(VERSION_REMIX_TOKENS) -> ReplacementVersionKind.Remix
        text.containsAny(VERSION_LIVE_TEXT) || tokens.containsAny(VERSION_LIVE_TOKENS) -> ReplacementVersionKind.Live
        text.containsAny(VERSION_ACOUSTIC_TEXT) || tokens.containsAny(VERSION_ACOUSTIC_TOKENS) -> ReplacementVersionKind.Acoustic
        text.containsAny(VERSION_REMASTER_TEXT) || tokens.containsAny(VERSION_REMASTER_TOKENS) -> ReplacementVersionKind.Remaster
        else -> ReplacementVersionKind.StudioOriginal
    }
}

internal fun replacementVersionCompatibility(
    original: ReplacementVersionKind,
    candidate: ReplacementVersionKind,
): Double {
    if (original == candidate) return 1.0
    if (original == ReplacementVersionKind.Unknown) return 1.0
    if (original == ReplacementVersionKind.StudioOriginal) {
        return when (candidate) {
            ReplacementVersionKind.Remaster -> 0.90
            ReplacementVersionKind.Acoustic -> 0.55
            ReplacementVersionKind.Live -> 0.45
            ReplacementVersionKind.Remix -> 0.35
            ReplacementVersionKind.SpedSlowed -> 0.25
            ReplacementVersionKind.Cover -> 0.20
            ReplacementVersionKind.InstrumentalKaraoke -> 0.15
            ReplacementVersionKind.ClipMedley -> 0.10
            ReplacementVersionKind.Unknown -> 0.7
            ReplacementVersionKind.StudioOriginal -> 1.0
        }
    }
    return 0.0
}

private fun List<ReplacementCandidate>.hasCompleteValidModelOutputFor(
    inputs: List<ReplacementCandidate>,
): Boolean {
    if (size != inputs.size || distinctBy { it.track.id }.size != size) return false
    if (mapTo(linkedSetOf()) { it.track.id } != inputs.mapTo(linkedSetOf()) { it.track.id }) return false
    return all { candidate ->
        candidate.score.isFinite() &&
            candidate.score in 0.0..1.0 &&
            candidate.modelScore?.let { it.isFinite() && it in 0.0..1.0 } != false &&
            candidate.sameSongConfidence?.let { it.isFinite() && it in 0.0..1.0 } == true
    }
}

private fun List<ReplacementCandidate>.asLegacyFallback(
    reason: String,
    wireStrategy: String,
): List<ReplacementCandidate> =
    map { candidate ->
        candidate.copy(
            score = candidate.legacyScore,
            modelScore = null,
            sameSongConfidence = null,
            rankingStrategy = ReplacementRankingStrategy.LegacyFallback,
            strategy = wireStrategy,
            reasons = candidate.reasons + reason,
        )
    }

internal val SmartReplacementStrictness.identityThreshold: Double
    get() = when (this) {
        SmartReplacementStrictness.Relaxed -> 0.55
        SmartReplacementStrictness.Balanced -> 0.68
        SmartReplacementStrictness.Strict -> 0.80
    }

val SmartReplacementStrictness.legacyMinScore: Double
    get() = when (this) {
        SmartReplacementStrictness.Relaxed -> 0.45
        SmartReplacementStrictness.Balanced -> DEFAULT_SMART_REPLACEMENT_MIN_SCORE
        SmartReplacementStrictness.Strict -> 0.70
    }

fun smartReplacementStrictnessForLegacyScore(score: Double): SmartReplacementStrictness = when {
    score < 0.50 -> SmartReplacementStrictness.Relaxed
    score < 0.625 -> SmartReplacementStrictness.Balanced
    else -> SmartReplacementStrictness.Strict
}

internal val ReplacementRankingStrategy.isOnDevice: Boolean
    get() = this == ReplacementRankingStrategy.OnDeviceLite ||
        this == ReplacementRankingStrategy.OnDeviceEnhanced

val ReplacementVersionKind.displayLabel: String
    get() = when (this) {
        ReplacementVersionKind.StudioOriginal -> "原版"
        ReplacementVersionKind.Live -> "现场版"
        ReplacementVersionKind.Cover -> "翻唱"
        ReplacementVersionKind.Remix -> "混音版"
        ReplacementVersionKind.Acoustic -> "不插电版"
        ReplacementVersionKind.InstrumentalKaraoke -> "伴奏"
        ReplacementVersionKind.SpedSlowed -> "变速版"
        ReplacementVersionKind.Remaster -> "重制版"
        ReplacementVersionKind.ClipMedley -> "片段或串烧"
        ReplacementVersionKind.Unknown -> "版本未知"
    }

val ReplacementRankingStrategy.displayLabel: String
    get() = when (this) {
        ReplacementRankingStrategy.LegacyRules -> "传统规则"
        ReplacementRankingStrategy.OnDeviceLite -> "端侧轻量模型"
        ReplacementRankingStrategy.OnDeviceEnhanced -> "端侧增强模型"
        ReplacementRankingStrategy.LegacyFallback -> "传统规则回退"
        ReplacementRankingStrategy.UserSelected -> "用户选择"
    }

private val ReplacementRankingStrategy.wireName: String
    get() = when (this) {
        ReplacementRankingStrategy.LegacyRules -> "legacy"
        ReplacementRankingStrategy.OnDeviceLite -> "on_device_lite"
        ReplacementRankingStrategy.OnDeviceEnhanced -> "on_device_enhanced"
        ReplacementRankingStrategy.LegacyFallback -> "legacy_fallback"
        ReplacementRankingStrategy.UserSelected -> "user_selected"
    }

internal const val REPLACEMENT_CANDIDATES_PER_PROVIDER = 20
internal const val MAX_REPLACEMENT_CANDIDATES = 32
internal const val ON_DEVICE_RANKING_TIMEOUT_MS = 1_500L
internal const val ENHANCED_ON_DEVICE_RANKING_TIMEOUT_MS = 3_500L
internal const val ON_DEVICE_PREPARE_TIMEOUT_MS = 8_000L
internal const val ENHANCED_ON_DEVICE_PREPARE_TIMEOUT_MS = 12_000L
private val ReplacementModelTier.inferenceTimeoutMs: Long
    get() = when (this) {
        ReplacementModelTier.Lite -> ON_DEVICE_RANKING_TIMEOUT_MS
        ReplacementModelTier.Enhanced -> ENHANCED_ON_DEVICE_RANKING_TIMEOUT_MS
    }
private val ReplacementModelTier.prepareTimeoutMs: Long
    get() = when (this) {
        ReplacementModelTier.Lite -> ON_DEVICE_PREPARE_TIMEOUT_MS
        ReplacementModelTier.Enhanced -> ENHANCED_ON_DEVICE_PREPARE_TIMEOUT_MS
    }
private const val MAX_IDENTITY_DURATION_DIFF_RATIO = 0.35
private fun String.containsAny(values: List<String>): Boolean = values.any { it in this }
private fun Set<String>.containsAny(values: Set<String>): Boolean = values.any(::contains)
private val VERSION_LIVE_TEXT = listOf("现场", "演唱会", "音乐节")
private val VERSION_LIVE_TOKENS = setOf("live", "concert")
private val VERSION_COVER_TEXT = listOf("翻唱")
private val VERSION_COVER_TOKENS = setOf("cover")
private val VERSION_REMIX_TEXT = listOf("混音", "重混")
private val VERSION_REMIX_TOKENS = setOf("remix", "mix")
private val VERSION_ACOUSTIC_TEXT = listOf("不插电", "木吉他")
private val VERSION_ACOUSTIC_TOKENS = setOf("acoustic")
private val VERSION_INSTRUMENTAL_TEXT = listOf("伴奏", "纯音乐", "无人声", "off vocal")
private val VERSION_INSTRUMENTAL_TOKENS = setOf("instrumental", "karaoke")
private val VERSION_SPED_SLOWED_TEXT = listOf("sped up", "slowed", "slow version", "加速版", "慢速版", "变速版")
private val VERSION_REMASTER_TEXT = listOf("重制版")
private val VERSION_REMASTER_TOKENS = setOf("remaster", "remastered")
private val VERSION_CLIP_TEXT = listOf("片段", "串烧", "试听")
private val VERSION_CLIP_TOKENS = setOf("clip", "snippet", "medley", "preview")
private const val LEGACY_TITLE_WEIGHT = 0.55
private const val LEGACY_ARTIST_WEIGHT = 0.35
private const val LEGACY_DURATION_WEIGHT = 0.10
