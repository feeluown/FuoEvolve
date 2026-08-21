package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ProviderContentHomeSection(
    home: HomeFeatureController,
    section: HomeSection,
    modifier: Modifier,
) = ProviderContentHomeFeatureSection(home, section, modifier)
