package org.feeluown.mobile

internal data class AndroidReplacementIntelligence(
    val ranker: ReplacementRanker,
    val modelManager: ReplacementModelManager?,
    val capability: ReplacementIntelligenceCapability,
    private val closable: AutoCloseable? = null,
) : AutoCloseable {
    override fun close() {
        closable?.close()
    }
}
