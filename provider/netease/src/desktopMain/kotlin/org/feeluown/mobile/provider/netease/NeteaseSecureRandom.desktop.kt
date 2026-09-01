package org.feeluown.mobile.provider.netease

import java.security.SecureRandom

private val neteaseSecureRandom = SecureRandom()

internal actual fun neteaseSecureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "random byte count must not be negative" }
    return ByteArray(size).also(neteaseSecureRandom::nextBytes)
}
