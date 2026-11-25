package com.glia.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glia.ui.components.GlassPill
import com.glia.ui.components.GlassSurface
import com.glia.ui.components.GliaIcons
import com.glia.ui.components.GlowDot
import com.glia.ui.model.GliaActions
import com.glia.ui.model.GliaSettings
import com.glia.ui.model.GliaUiState
import com.glia.ui.model.SettingKey
import com.glia.ui.theme.GliaTheme

@Composable
fun SettingsScreen(
    state: GliaUiState,
    actions: GliaActions,
    modifier: Modifier = Modifier,
) {
    val palette = GliaTheme.palette

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 14.dp,
            bottom = 110.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    text = "Ajustes",
                    style = MaterialTheme.typography.headlineLarge,
                    color = palette.textPrimary,
                )
                Text(
                    text = "Tudo acontece no seu aparelho.",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                )
            }
        }

        item { ModelCard(state, actions) }

        item {
            SectionCard("Voz") {
                ToggleRow(
                    title = "Escuta contínua da palavra “Glia”",
                    subtitle = "Serviço em primeiro plano com microfone restrito à palavra-chave.",
                    checked = state.settings.wakeWord,
                    onChange = { actions.onToggleSetting(SettingKey.WAKE_WORD, it) },
                )
                ToggleRow(
                    title = "Falar as respostas",
                    subtitle = "TTS offline em português do Brasil.",
                    checked = state.settings.speakReplies,
                    onChange = { actions.onToggleSetting(SettingKey.SPEAK_REPLIES, it) },
                )
            }
        }

        item {
            SectionCard("Aparência") {
                ToggleRow(
                    title = "Vidro líquido",
                    subtitle = "Superfícies translúcidas com brilho. Desligue se o quadro cair.",
                    checked = state.settings.glass,
                    onChange = { actions.onToggleSetting(SettingKey.GLASS, it) },
                )
                ToggleRow(
                    title = "Animações",
                    subtitle = "Molas e morphing a 90 Hz quando disponível.",
                    checked = state.settings.animations,
                    onChange = { actions.onToggleSetting(SettingKey.ANIMATIONS, it) },
                )
                ToggleRow(
                    title = "Tema escuro",
                    subtitle = "Verdes profundos em vez de preto.",
                    checked = state.settings.darkTheme,
                    onChange = { actions.onToggleSetting(SettingKey.DARK_THEME, it) },
                )
            }
        }

        item {
            SectionCard("Permissões") {
                state.permissions.forEach { permission ->
                    PermissionRow(permission.title, permission.rationale, permission.granted) {
                        actions.onPermission(permission.key)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(text = "Acessibilidade", onClick = actions.onOpenAccessibility)
                    GlassPill(text = "Notificações", onClick = actions.onOpenNotificationAccess)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(text = "Config. do app", onClick = actions.onOpenAppSettings)
                    GlassPill(text = "Bateria", onClick = actions.onOpenBattery)
                }
            }
        }

        item {
            SectionCard("HyperOS / MIUI") {
                Text(
                    text = "Para a escuta contínua sobreviver, libere a otimização de bateria e o autostart. " +
                        "Sem isso, o HyperOS encerra o serviço em segundo plano.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(text = "Otimização de bateria", onClick = actions.onOpenBattery)
                    GlassPill(text = "Autostart", onClick = actions.onOpenAutostart)
                }
            }
        }

        item {
            SectionCard("Diagnóstico") {
                Text(
                    text = state.diagnostic.ifBlank { "sem dados" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(text = "Limpar conversa", onClick = actions.onClearHistory)
                    GlassPill(text = "Recarregar modelo", onClick = actions.onReloadModel)
                }
            }
        }

        item {
            Text(
                text = "Glia 1.0 · assistente local · nenhum dado sai do aparelho",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ModelCard(state: GliaUiState, actions: GliaActions) {
    val palette = GliaTheme.palette
    GlassSurface(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    GliaIcons.Leaf,
                    contentDescription = null,
                    tint = palette.leaf,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.model.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlowDot(
                            color = if (state.model.installed) palette.moss else palette.sun,
                            size = 7.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.model.installed) "pronto" else "ausente",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.model.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
            state.model.path?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    text = "Recarregar",
                    onClick = actions.onReloadModel,
                    tint = palette.leaf.copy(alpha = 0.18f),
                )
                GlassPill(text = "Como instalar", onClick = actions.onModelHelp)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    GlassSurface(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = GliaTheme.palette.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val palette = GliaTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.cream,
                checkedTrackColor = palette.leaf,
                uncheckedThumbColor = palette.textSecondary,
                uncheckedTrackColor = palette.bubbleUser,
            ),
        )
    }
}

@Composable
private fun PermissionRow(title: String, rationale: String, granted: Boolean, onClick: () -> Unit) {
    val palette = GliaTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
            Text(rationale, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(GliaIcons.Check, contentDescription = null, tint = palette.moss)
                Spacer(Modifier.width(4.dp))
                Text("ok", style = MaterialTheme.typography.labelSmall, color = palette.moss)
            }
        } else {
            GlassPill(text = "Permitir", onClick = onClick, tint = palette.sun.copy(alpha = 0.22f))
        }
    }
}
