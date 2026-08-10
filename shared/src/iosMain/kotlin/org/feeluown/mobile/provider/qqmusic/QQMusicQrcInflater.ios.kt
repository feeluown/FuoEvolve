package org.feeluown.mobile.provider.qqmusic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.getPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
internal actual fun qrcInflate(data: ByteArray): ByteArray? = runCatching {
    if (data.isEmpty()) return@runCatching null
    memScoped {
        val stream = alloc<z_stream>()
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        check(inflateInit_(stream.ptr, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK) {
            "QQ QRC inflateInit_ failed"
        }
        try {
            stream.next_in = data.refTo(0).getPointer(this).reinterpret()
            stream.avail_in = data.size.toUInt()
            val output = mutableListOf<Byte>()
            val bufferSize = 4 * 1024
            val buffer = ByteArray(bufferSize)
            do {
                stream.next_out = buffer.refTo(0).getPointer(this).reinterpret()
                stream.avail_out = bufferSize.toUInt()
                val result = inflate(stream.ptr, Z_NO_FLUSH)
                check(result == Z_OK || result == Z_STREAM_END) { "QQ QRC inflate error: $result" }
                val produced = bufferSize - stream.avail_out.toInt()
                output.addAll(buffer.take(produced))
                if (result == Z_STREAM_END) break
            } while (stream.avail_out == 0u)
            output.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}.getOrNull()
