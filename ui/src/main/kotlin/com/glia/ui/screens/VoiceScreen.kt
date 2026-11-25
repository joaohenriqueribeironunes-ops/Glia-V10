package com.glia.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glia.ui.components.GlassPill
import com.glia.ui.components.GlassSurface
import com.glia.ui.components.GliaIcons
import com.glia.ui.components.GlowDot
import com.glia.ui.components.OrganicOrb
import com.glia.ui.model.AssistantPhase
import com.glia.ui.model.GliaActions
import com.glia.ui.model.GliaUiState
import com.glia.ui.theme.GliaTheme

@Composable
fun VoiceScreen(
    state: GliaUiState,
    actions: GliaActions,
    modifier: Modifier = Modifier,
) {
    val palette = GliaTheme.palette
    val haptics = LocalHapticFeedback.current
    val phaseText = when (state.phase) {
        AssistantPhase.IDLE -> "Toque para falar"
        AssistantPhase.LISTENING -> "Ouvindo…"
        AssistantPhase.THINKING -> "Pensando localmente…"
        AssistantPhase.SPEAKING -> "Falando…"
    }
    val phaseHint = when (state.phase) {
        AssistantPhase.IDLE -> "Ou diga “Glia” se a escuta contínua estiver ativa."
        AssistantPhase.LISTENING -> "Fale com calma. Encerro quando você fizer uma pausa."
        AssistantPhase.THINKING -> "O modelo está raciocinando no seu aparelho, sem rede."
        AssistantPhase.SPEAKING -> "Interrompa quando quiser."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Voz",
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Conversa por voz, 100% offline",
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            OrganicOrb(phase = state.phase, diameter = 300.dp)
        }

        Text(
            text = phaseText,
            style = MaterialTheme.typography.headlineLarge,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = phaseHint,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))
        AnimatedVisibility(
            visible = state.partialTranscript.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            GlassSurface(
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = state.partialTranscript,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textPrimary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.phase == AssistantPhase.SPEAKING) {
                GlassPill(text = "Parar", onClick = actions.onStopSpeaking)
            }
            GlassSurface(
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
                tint = when (state.phase) {
                    AssistantPhase.LISTENING -> palette.teal.copy(alpha = 0.40f)
                    AssistantPhase.THINKING -> palette.sun.copy(alpha = 0.32f)
                    else -> palette.leaf.copy(alpha = 0.26f)
                },
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    actions.onMicToggle()
                },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.phase == AssistantPhase.LISTENING) GliaIcons.Stop
                        else GliaIcons.Mic,
                        contentDescription = "Microfone",
                        tint = palette.leaf,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
            if (state.phase == AssistantPhase.SPEAKING) {
                GlassPill(
                    text = "Repetir",
                    onClick = { actions.onSendText("Repita a última resposta.") },
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlowDot(
                color = if (state.settings.wakeWord) palette.teal else palette.textSecondary,
                size = 7.dp,
            )
            Text(
                text = if (state.settings.wakeWord) "Escuta contínua ligada"
                else "Escuta contínua desligada",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary,
            )
        }
        Spacer(Modifier.height(18.dp))
    }
}
