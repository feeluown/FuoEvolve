package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppRoot(
    appViewModel: FuoAppViewModel,
    uiGraph: AppUiGraph,
    platform: AppPlatformBindings,
) {
    val appUiState by appViewModel.uiState.collectAsStateWithLifecycle()

    FuoTheme(
        themeMode = appUiState.themeMode,
        themeColorScheme = appUiState.themeColorScheme,
        themePaletteStyle = appUiState.themePaletteStyle,
        themeColorSpec = appUiState.themeColorSpec,
    ) {
        when {
            !appUiState.isInitialized -> AppInitializationLoadingScreen()
            !appUiState.onboardingCompleted -> {
                val onboarding = requireNotNull(uiGraph.onboarding) {
                    "Onboarding feature owner is not installed"
                }
                OnboardingFeatureScreen(
                    onboarding = onboarding,
                    settings = uiGraph.settings,
                    providerCatalog = uiGraph.providerCatalog,
                    providerAuth = uiGraph.providerAuth,
                    onOpenProviderWebLogin = platform.onOpenProviderWebLogin,
                    onLogoutProvider = platform.onLogoutProvider,
                    onImportYtmusicHeaderFile = platform.onImportYtmusicHeaderFile,
                    onImportYtmusicOAuthFile = platform.onImportYtmusicOAuthFile,
                    onStartYtmusicOAuth = platform.onStartYtmusicOAuth,
                )
            }
            else -> AppShell(
                appViewModel = appViewModel,
                uiGraph = uiGraph,
                appUiState = appUiState,
                platform = platform,
            )
        }
    }
}
