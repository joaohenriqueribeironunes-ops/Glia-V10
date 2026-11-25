package com.glia.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.glia.ui.components.GlassSurface
import com.glia.ui.components.GliaIcons
import com.glia.ui.components.OrganicBackdrop
import com.glia.ui.model.GliaActions
import com.glia.ui.model.GliaUiState
import com.glia.ui.screens.ChatScreen
import com.glia.ui.screens.OnboardingScreen
import com.glia.ui.screens.SettingsScreen
import com.glia.ui.screens.VoiceScreen
import com.glia.ui.theme.GliaTheme

private data class TabItem(val label: String, val icon: ImageVector)

@Composable
fun GliaApp(
    state: GliaUiState,
    actions: GliaActions,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val design = GliaTheme.design

    Box(modifier.fillMaxSize()) {
        OrganicBackdrop()

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                Crossfade(
                    targetState = tab,
                    animationSpec = if (design.animations) {
                        spring(dampingRatio = 0.86f, stiffness = 380f)
                    } else {
                        tween(durationMillis = 0)
                    },
                    label = "tabs",
                ) { index ->
                    when (index) {
                        0 -> ChatScreen(state, actions)
                        1 -> VoiceScreen(state, actions)
                        else -> SettingsScreen(state, actions)
                    }
                }
            }
            BottomBar(selected = tab, onSelect = { tab = it })
        }

        if (state.showOnboarding) {
            OnboardingScreen(state, actions)
        }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val palette = GliaTheme.palette
    val items = listOf(
        TabItem("Conversa", GliaIcons.Chat),
        TabItem("Voz", GliaIcons.Orb),
        TabItem("Ajustes", GliaIcons.Sliders),
    )
    Column(Modifier.fillMaxWidth()) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            shape = RoundedCornerShape(30.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val active = index == selected
                    GlassSurface(
                        modifier = Modifier.height(52.dp),
                        shape = RoundedCornerShape(22.dp),
                        tint = if (active) palette.leaf.copy(alpha = 0.18f) else null,
                        onClick = { onSelect(index) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = if (active) 20.dp else 16.dp,
                        ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = if (active) palette.leaf else palette.textSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                            if (active) {
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = palette.leaf,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
