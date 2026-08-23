package org.feeluown.mobile.provider.qqmusic

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

internal actual fun qrcInflate(data: ByteArray): ByteArray? = runCatching {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count > 0) {
                output.write(buffer, 0, count)
            } else if (inflater.needsInput() || inflater.needsDictionary()) {
                break
            } else {
                error("QQ QRC zlib stream made no progress")
            }
        }
        output.toByteArray().takeIf { it.isNotEmpty() }
    } finally {
        inflater.end()
    }
}.getOrNull()
