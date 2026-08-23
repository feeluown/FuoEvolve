package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import org.feeluown.mobile.provider.netease.NeteaseWeApi

class NeteaseWeApiTest {
    @Test
    fun encryptsKnownUserLevelPayload() {
        val payload = NeteaseWeApi.encrypt("{}", secretKey = "0123456789abcdef")

        assertEquals(
            "kxBNj/l03n7aQ/q0EdABwtOsIYuwYGkESNTUk4QvWCg=",
            payload.params,
        )
        assertEquals(
            "35701388baf89fed412e11269b9c76625d095ecaf17f03fa018abe19ea2d38b949debf242ee39a71ca1f6cda71b1b86a45aa909ee27f7e78e267d34e732f0de948206c3340a788d0003372183e2f753c1f78b66ac23d134ac1fc9b993156520ea826b8aa89a962d4491b4b8d7e08738e1da9b07aa39bf4a7ef0b1c210728cd52",
            payload.encSecKey,
        )
    }
}
