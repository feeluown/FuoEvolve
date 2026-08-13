package org.feeluown.mobile

import android.content.Context

internal fun createAndroidReplacementIntelligence(
    context: Context,
    learner: ReplacementLearningRepository,
): AndroidReplacementIntelligence {
    val capability = ReplacementIntelligenceCapability(
        modelIncluded = BuildConfig.ON_DEVICE_REPLACEMENT_MODEL_INCLUDED,
        onDeviceAvailable = false,
        reason = "当前安装的是普通版，未包含端侧模型",
    )
    return AndroidReplacementIntelligence(
        ranker = LegacyReplacementRanker,
        modelManager = null,
        capability = capability,
    )
}
