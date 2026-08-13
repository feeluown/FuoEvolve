package org.feeluown.mobile

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.LongBuffer
import java.text.Normalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

internal class AndroidBgeEmbeddingEngine(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val inferenceMutex = Mutex()
    private val tokenizer by lazy {
        applicationContext.assets.open(VOCAB_ASSET).bufferedReader().use { reader ->
            BertWordPieceTokenizer(reader.readLines())
        }
    }
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val sessionDelegate = lazy { createSession() }
    private val session by sessionDelegate

    suspend fun similarities(origin: MusicTrack, candidates: List<MusicTrack>): DoubleArray {
        if (candidates.isEmpty()) return DoubleArray(0)
        return inferenceMutex.withLock {
            withContext(dispatcher) {
                val texts = buildList(candidates.size + 1) {
                    add(origin.toReplacementModelText())
                    candidates.forEach { candidate -> add(candidate.toReplacementModelText()) }
                }
                val encodings = texts.map(tokenizer::encode)
                val embeddings = infer(encodings)
                val originEmbedding = embeddings.first()
                DoubleArray(candidates.size) { index ->
                    cosineSimilarity(originEmbedding, embeddings[index + 1])
                        .coerceIn(-1.0, 1.0)
                }
            }
        }
    }

    suspend fun prepare() {
        withContext(dispatcher) {
            inferenceMutex.withLock {
                tokenizer
                session
            }
        }
    }

    private fun infer(encodings: List<LongArray>): List<FloatArray> {
        val batchSize = encodings.size
        val flattenedIds = LongArray(batchSize * MAX_SEQUENCE_LENGTH)
        val flattenedMask = LongArray(flattenedIds.size)
        encodings.forEachIndexed { batchIndex, inputIds ->
            inputIds.copyInto(flattenedIds, destinationOffset = batchIndex * MAX_SEQUENCE_LENGTH)
            for (tokenIndex in inputIds.indices) {
                if (inputIds[tokenIndex] != PAD_TOKEN_ID) {
                    flattenedMask[batchIndex * MAX_SEQUENCE_LENGTH + tokenIndex] = 1L
                }
            }
        }
        val shape = longArrayOf(batchSize.toLong(), MAX_SEQUENCE_LENGTH.toLong())
        OnnxTensor.createTensor(environment, LongBuffer.wrap(flattenedIds), shape).use { ids ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(flattenedMask), shape).use { mask ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(flattenedIds.size)), shape).use { types ->
                    session.run(
                        mapOf(
                            "input_ids" to ids,
                            "attention_mask" to mask,
                            "token_type_ids" to types,
                        ),
                    ).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = result[0].value as Array<Array<FloatArray>>
                        return hidden.map { tokens -> tokens[0].copyOf() }
                    }
                }
            }
        }
    }

    override fun close() {
        if (sessionDelegate.isInitialized()) {
            session.close()
        }
    }

    private fun createSession(): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(MAX_INTRA_OP_THREADS)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val modelFile = File(applicationContext.filesDir, MODEL_CACHE_FILE)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            applicationContext.assets.open(MODEL_ASSET).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return environment.createSession(modelFile.absolutePath, options).also {
            options.close()
        }
    }

    private fun MusicTrack.toReplacementModelText(): String = buildString {
        append("标题：")
        append(title.trim())
        append("；歌手：")
        append(artists.trim())
        album.trim().takeIf { it.isNotEmpty() }?.let {
            append("；专辑：")
            append(it)
        }
        durationMs?.takeIf { it > 0 }?.let {
            append("；时长：")
            append(it / 1_000)
            append("秒")
        }
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Double {
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            val leftValue = left[index].toDouble()
            val rightValue = right[index].toDouble()
            dot += leftValue * rightValue
            leftNorm += leftValue * leftValue
            rightNorm += rightValue * rightValue
        }
        val denominator = sqrt(leftNorm) * sqrt(rightNorm)
        return if (denominator == 0.0) 0.0 else dot / denominator
    }

    private companion object {
        const val MODEL_ASSET = "smart_replacement/fuo_replacement_lite_v1.ort"
        const val MODEL_CACHE_FILE = "fuo_replacement_lite_v1.ort"
        const val VOCAB_ASSET = "smart_replacement/vocab.txt"
        const val MAX_SEQUENCE_LENGTH = 96
        const val MAX_INTRA_OP_THREADS = 4
        const val PAD_TOKEN_ID = 0L
    }
}

internal class BertWordPieceTokenizer(vocabulary: List<String>) {
    private val tokenIds = vocabulary.withIndex().associate { (index, token) -> token to index.toLong() }
    private val unknownTokenId = tokenIds.getValue("[UNK]")
    private val classificationTokenId = tokenIds.getValue("[CLS]")
    private val separatorTokenId = tokenIds.getValue("[SEP]")

    fun encode(text: String): LongArray {
        val pieces = basicTokens(text).flatMap(::wordPieces)
        val ids = LongArray(MAX_SEQUENCE_LENGTH)
        ids[0] = classificationTokenId
        pieces.take(MAX_SEQUENCE_LENGTH - 2).forEachIndexed { index, token ->
            ids[index + 1] = tokenIds[token] ?: unknownTokenId
        }
        ids[minOf(pieces.size, MAX_SEQUENCE_LENGTH - 2) + 1] = separatorTokenId
        return ids
    }

    private fun basicTokens(text: String): List<String> {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.clear()
            }
        }
        normalized.forEach { character ->
            when {
                Character.getType(character) == Character.NON_SPACING_MARK.toInt() -> Unit
                character.isWhitespace() || Character.isISOControl(character) -> flush()
                isCjk(character) || isPunctuation(character) -> {
                    flush()
                    result += character.toString()
                }
                else -> current.append(character)
            }
        }
        flush()
        return result
    }

    private fun wordPieces(token: String): List<String> {
        if (token.length > MAX_WORD_LENGTH) return listOf("[UNK]")
        if (tokenIds.containsKey(token)) return listOf(token)
        val result = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var match: String? = null
            while (start < end) {
                val candidate = token.substring(start, end).let { piece ->
                    if (start == 0) piece else "##$piece"
                }
                if (tokenIds.containsKey(candidate)) {
                    match = candidate
                    break
                }
                end -= 1
            }
            if (match == null) return listOf("[UNK]")
            result += match
            start = end
        }
        return result
    }

    private fun isCjk(character: Char): Boolean {
        val code = character.code
        return code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF
    }

    private fun isPunctuation(character: Char): Boolean {
        return character in '!'..'/' ||
            character in ':'..'@' ||
            character in '['..'`' ||
            character in '{'..'~' ||
            when (Character.getType(character)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                -> true
                else -> false
            }
    }

    private companion object {
        const val MAX_SEQUENCE_LENGTH = 96
        const val MAX_WORD_LENGTH = 100
    }
}
