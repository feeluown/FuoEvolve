package org.feeluown.mobile.provider.netease

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun neteaseSecureRandomBytes(size: Int): ByteArray {
    require(size >= 0) { "random byte count must not be negative" }
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
    }
    check(status == 0) { "system secure random generator failed: $status" }
    return bytes
}
