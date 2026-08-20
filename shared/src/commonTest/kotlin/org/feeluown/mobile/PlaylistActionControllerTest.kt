package org.feeluown.mobile

import org.feeluown.mobile.provider.core.network.ProviderNetworkException
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistActionControllerTest {
    @Test
    fun mutationFeedbackUsesStructuredProviderFailureMessage() {
        val message = playlistMutationErrorMessage(
            throwable = ProviderNetworkException.Http(451, ""),
            providerId = "netease",
        )

        assertEquals("当前地区暂不支持此内容", message)
    }

    @Test
    fun mutationFeedbackFallsBackWhenThrowableMessageIsMissing() {
        val message = playlistMutationErrorMessage(
            throwable = IllegalStateException(),
            providerId = "netease",
        )

        assertEquals("IllegalStateException", message)
    }
}
