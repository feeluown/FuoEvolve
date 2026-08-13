package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun createAndroidReplacementIntelligence(
    context: Context,
    learner: ReplacementLearningRepository,
): AndroidReplacementIntelligence {
    return try {
        val engine = AndroidBgeEmbeddingEngine(context)
        val modelManager = AndroidReplacementModelManager(engine)
        AndroidReplacementIntelligence(
            ranker = AndroidSemanticReplacementRanker(engine, learner, modelManager),
            modelManager = modelManager,
            capability = modelManager.capability,
            closable = engine,
        )
    } catch (_: Throwable) {
        AndroidReplacementIntelligence(
            ranker = LegacyReplacementRanker,
            modelManager = null,
            capability = ReplacementIntelligenceCapability(
                modelIncluded = BuildConfig.ON_DEVICE_REPLACEMENT_MODEL_INCLUDED,
                onDeviceAvailable = false,
                reason = "端侧模型不可用，已使用传统匹配",
            ),
        )
    }
}

private class AndroidReplacementModelManager(
    private val liteEngine: AndroidBgeEmbeddingEngine,
) : ReplacementModelManager {
    private val prepareMutex = Mutex()

    @Volatile
    override var activeTier: ReplacementModelTier? = null
        private set

    @Volatile
    override var state: ReplacementModelState = ReplacementModelState.NotPrepared
        private set

    override val capability: ReplacementIntelligenceCapability
        get() = ReplacementIntelligenceCapability(
            modelIncluded = BuildConfig.ON_DEVICE_REPLACEMENT_MODEL_INCLUDED,
            onDeviceAvailable = BuildConfig.ON_DEVICE_REPLACEMENT_MODEL_INCLUDED,
            reason = null,
        )

    override suspend fun prepare(tier: ReplacementModelTier): ReplacementModelState =
        prepareMutex.withLock {
            if (state == ReplacementModelState.Ready && activeTier == ReplacementModelTier.Lite) {
                return@withLock state
            }
            state = ReplacementModelState.NotPrepared
            try {
                liteEngine.prepare()
                activeTier = ReplacementModelTier.Lite
                state = ReplacementModelState.Ready
                state
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                activeTier = null
                state = ReplacementModelState.Failed
                state
            }
        }
}

private class AndroidSemanticReplacementRanker(
    private val engine: AndroidBgeEmbeddingEngine,
    private val learner: ReplacementLearningRepository,
    private val modelManager: ReplacementModelManager,
) : ReplacementRanker, ReplacementPreferenceAwareRanker {
    override val preferenceLearner: ReplacementPreferenceLearner
        get() = learner

    override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> {
        learner.initialize()
        val tracks = request.candidates.map { it.track }
        val similarities = engine.similarities(request.original, tracks)
        val strategy = when (modelManager.activeTier ?: request.modelTier) {
            ReplacementModelTier.Lite -> ReplacementRankingStrategy.OnDeviceLite
            ReplacementModelTier.Enhanced -> ReplacementRankingStrategy.OnDeviceEnhanced
        }
        return request.candidates.mapIndexed { index, candidate ->
            val semantic = similarities[index].calibratedSimilarity()
            candidate.copy(
                score = semantic,
                modelScore = semantic,
                sameSongConfidence = semantic,
                autoEligible = candidate.autoEligible,
                rankingStrategy = strategy,
                modelVersion = modelManager.activeTier?.let { tier ->
                    when (tier) {
                        ReplacementModelTier.Lite -> LITE_MODEL_VERSION
                        ReplacementModelTier.Enhanced -> ENHANCED_MODEL_VERSION
                    }
                },
                reasons = candidate.reasons + modelReason(semantic, candidate.versionKind),
                strategy = when (strategy) {
                    ReplacementRankingStrategy.OnDeviceLite -> "on_device_lite"
                    ReplacementRankingStrategy.OnDeviceEnhanced -> "on_device_enhanced"
                    else -> "legacy"
                },
            )
        }
    }

    private fun Double.calibratedSimilarity(): Double = ((this - 0.55) / 0.40).coerceIn(0.0, 1.0)

    private fun modelReason(confidence: Double, version: ReplacementVersionKind): String {
        val identity = when {
            confidence >= 0.85 -> "标题与歌手语义高度一致"
            confidence >= 0.68 -> "标题与歌手语义相近"
            else -> "语义置信度较低"
        }
        return "$identity · ${version.displayLabel}"
    }

    private companion object {
        const val LITE_MODEL_VERSION = REPLACEMENT_LITE_MODEL_VERSION
        const val ENHANCED_MODEL_VERSION = REPLACEMENT_ENHANCED_MODEL_VERSION
    }
}
