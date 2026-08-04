package org.feeluown.mobile.provider.netease

import java.security.SecureRandom

private val neteaseSecureRandom = SecureRandom()

internal actual fun neteaseSecureRandomBytes(size: Int): ByteArray = ByteArray(size).also(neteaseSecureRandom::nextBytes)
