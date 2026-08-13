package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertContentEquals

class BertWordPieceTokenizerTest {
    @Test
    fun encodesLowercaseLatinAndCjkTokensWithSpecialMarkers() {
        val tokenizer = BertWordPieceTokenizer(
            listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "世", "界"),
        )

        val encoded = tokenizer.encode("Hello世界")

        assertContentEquals(
            longArrayOf(2, 4, 5, 6, 3) + LongArray(91),
            encoded,
        )
    }
}
