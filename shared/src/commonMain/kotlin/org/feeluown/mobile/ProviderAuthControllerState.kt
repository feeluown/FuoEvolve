package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class ProviderAuthControllerState {
    var authStates by mutableStateOf<Map<String, ProviderAuthState>>(emptyMap())
    var authOperations by mutableStateOf<Map<String, ProviderSessionOperation>>(emptyMap())
    var authErrors by mutableStateOf<Map<String, String>>(emptyMap())
    var cookieInputs by mutableStateOf<Map<String, String>>(emptyMap())
    var headerInputs by mutableStateOf<Map<String, ProviderHeaderInput>>(emptyMap())
    var oauthInputs by mutableStateOf<Map<String, ProviderOAuthInput>>(emptyMap())
    var ytmusicOAuthFlow by mutableStateOf<YtMusicOAuthFlowUiState?>(null)
}
