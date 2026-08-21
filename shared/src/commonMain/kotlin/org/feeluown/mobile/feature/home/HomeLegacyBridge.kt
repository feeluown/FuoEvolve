package org.feeluown.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Temporary compile bridge for the retired AppRoot. Removed with P2-5 facade cleanup. */
@Composable
fun HomeScreen(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    hasImagePermission: Boolean,
    onRequestImagePermission: () -> Unit,
    onOpenRecognition: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        when (controller.homeSection) {
            HomeSection.Recommend -> ProviderContentHomeSection(controller, HomeSection.Recommend, Modifier.weight(1f))
            HomeSection.Music -> ProviderContentHomeSection(controller, HomeSection.Music, Modifier.weight(1f))
            HomeSection.Mine -> MineHomeSection(
                controller = controller,
                hasAudioPermission = hasAudioPermission,
                onRequestAudioPermission = onRequestAudioPermission,
                hasImagePermission = hasImagePermission,
                onRequestImagePermission = onRequestImagePermission,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
