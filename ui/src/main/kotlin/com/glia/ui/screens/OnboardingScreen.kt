package com.glia.ui.screens

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glia.ui.components.GlassPill
import com.glia.ui.components.GlassSurface
import com.glia.ui.components.GliaIcons
import com.glia.ui.components.GlowDot
import com.glia.ui.components.OrganicBackdrop
import com.glia.ui.components.OrganicOrb
import com.glia.ui.model.AssistantPhase
import com.glia.ui.model.GliaActions
import com.glia.ui.model.GliaUiState
import com.glia.ui.model.PermissionStatus
import com.glia.ui.theme.GliaTheme

@Composable
fun OnboardingScreen(
    state: GliaUiState,
    actions: GliaActions,
    modifier: Modifier = Modifier,
) {
    val palette = GliaTheme.palette
    var step by remember { mutableIntStateOf(0) }
    val total = 5

    Box(modifier.fillMaxSize()) {
        OrganicBackdrop()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(total) { index ->
                    GlowDot(
                        color = if (index <= step) palette.leaf else palette.textSecondary.copy(alpha = 0.3f),
                        size = if (index == step) 9.dp else 6.dp,
                    )
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = step, label = "onboarding") { current ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        when (current) {
                            0 -> WelcomeStep()
                            1 -> PermissionStep(
                                icon = GliaIcons.Mic,
                                title = "Sua voz fica aqui",
                                body = "A Glia ouve \"Glia\" e seus comandos sem enviar áudio para lugar nenhum. " +
                                    "O microfone só abre quando a palavra é dita ou quando você toca no botão.",
                                permission = state.permissions.find { it.key == "mic" },
                                onGrant = { actions.onPermission("mic") },
                            )
                            2 -> PermissionStep(
                                icon = GliaIcons.Clock,
                                title = "Agenda e contatos",
                                body = "Para responder o que você tem hoje e para ligar ou escrever para alguém, " +
                                    "preciso ler sua agenda e seus contatos. Nada é copiado para fora.",
                                permission = state.permissions.find { it.key == "calendar" },
                                secondPermission = state.permissions.find { it.key == "contacts" },
                                onGrant = { actions.onPermission("calendar") },
                            )
                            3 -> PermissionStep(
                                icon = GliaIcons.Shield,
                                title = "Controle de tela e notificações",
                                body = "O serviço de acessibilidade permite tocar, rolar e ler a tela quando você pedir. " +
                                    "O acesso a notificações permite resumir o que chegou. Ambos são opcionais.",
                                permission = state.permissions.find { it.key == "accessibility" },
                                secondPermission = state.permissions.find { it.key == "notification_listener" },
                                onGrant = { actions.onOpenAccessibility },
                                extraAction = { actions.onOpenNotificationAccess() },
                                extraLabel = "Acesso a notificações",
                            )
                            else -> HyperOsStep(actions, state)
                        }
                    }
                }
            }

            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (step == 0) "boas-vindas" else "${step + 1} de $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (step < total - 1) {
                            GlassPill(text = "Pular", onClick = actions.onFinishOnboarding)
                        }
                        GlassPill(
                            text = if (step == total - 1) "Começar" else "Continuar",
                            tint = palette.leaf.copy(alpha = 0.22f),
                            onClick = {
                                if (step == total - 1) actions.onFinishOnboarding() else step++
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WelcomeStep() {
    val palette = GliaTheme.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OrganicOrb(phase = AssistantPhase.IDLE, diameter = 210.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Oi, eu sou a Glia",
            style = MaterialTheme.typography.headlineLarge,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Uma assistente que vive no seu Redmi, fala português e não sai daqui. " +
                "Sem conta, sem nuvem, sem internet.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun PermissionStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    permission: PermissionStatus?,
    onGrant: () -> Unit,
    secondPermission: PermissionStatus? = null,
    extraAction: (() -> Unit)? = null,
    extraLabel: String? = null,
) {
    val palette = GliaTheme.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = palette.leaf, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (permission != null) {
                if (permission.granted) {
                    GlassPill(text = "Concedido", tint = palette.moss.copy(alpha = 0.25f))
                } else {
                    GlassPill(
                        text = permission.actionLabel,
                        onClick = onGrant,
                        tint = palette.sun.copy(alpha = 0.25f),
                    )
                }
            }
            if (secondPermission != null && !secondPermission.granted) {
                GlassPill(
                    text = extraLabel ?: secondPermission.actionLabel,
                    onClick = extraAction ?: { },
                )
            }
        }
    }
}

@Composable
private fun HyperOsStep(actions: GliaActions, state: GliaUiState) {
    val palette = GliaTheme.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(GliaIcons.Leaf, contentDescription = null, tint = palette.leaf, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Um ajuste no HyperOS",
            style = MaterialTheme.typography.headlineMedium,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "O HyperOS encerra apps em segundo plano. Libere a otimização de bateria e permita " +
                "o autostart da Glia para a escuta contínua continuar funcionando. O modelo local também " +
                "fica em memória por mais tempo.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassPill(text = "Bateria", onClick = actions.onOpenBattery)
            GlassPill(text = "Autostart", onClick = actions.onOpenAutostart)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (state.model.installed) "Modelo local pronto."
            else "Depois, instale o modelo GGUF em Ajustes → Modelo.",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
