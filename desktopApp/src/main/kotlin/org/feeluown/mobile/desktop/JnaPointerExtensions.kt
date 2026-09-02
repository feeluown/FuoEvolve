package org.feeluown.mobile.desktop

import com.sun.jna.Pointer

/** Keep large native buffers bounds-checked before passing their length to JNA's Int-sized array API. */
internal fun Pointer.getByteArray(offset: Long, arraySize: Long): ByteArray {
    require(arraySize in 0..Int.MAX_VALUE.toLong()) { "native buffer too large: $arraySize bytes" }
    return getByteArray(offset, arraySize.toInt())
}
