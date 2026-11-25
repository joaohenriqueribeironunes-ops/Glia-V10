package com.glia.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glia.ui.components.OrganicOrb
import com.glia.ui.model.AssistantPhase
import com.glia.ui.model.GliaUiState
import com.glia.ui.theme.GliaTheme

/**
 * Painel de voz sobre a tela bloqueada.
 *
 * Aberto pelo tile da central de controle: mostra a orbe, escuta um comando,
 * responde em texto e fala — sem exigir desbloqueio e sem abrir o app inteiro.
 * O microfone roda no WakeWordService (foreground service de microfone),
 * iniciado a partir desta activity visível, o que é permitido no Android 14+.
 */
class VoiceActivity : ComponentActivity() {

    private val viewModel: GliaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val settings = state.settings
            GliaTheme(
                darkTheme = true,
                glass = settings.glass,
                animations = settings.animations,
            ) {
                VoicePanel(
                    state = state,
                    micGranted = micGranted,
                    onListen = { viewModel.actions.onMicToggle() },
                    onStopSpeaking = { viewModel.actions.onStopSpeaking() },
                    onOpenApp = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }

        if (micGranted) {
            window.decorView.post { viewModel.actions.onMicToggle() }
        } else {
            android.util.Log.w("VoiceActivity", "microfone sem permissão; pedindo para abrir o app")
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setForeground(true)
    }

    override fun onPause() {
        viewModel.setForeground(false)
        super.onPause()
    }
}

@Composable
private fun VoicePanel(
    state: GliaUiState,
    micGranted: Boolean,
    onListen: () -> Unit,
    onStopSpeaking: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = GliaTheme.palette
    val lastReply = state.messages.lastOrNull { !it.fromUser }?.text.orEmpty()
    val listening = state.phase == AssistantPhase.LISTENING
    val thinking = state.busy || state.phase == AssistantPhase.THINKING
    val speaking = state.phase == AssistantPhase.SPEAKING

    val status = when {
        !micGranted -> "Falta liberar o microfone"
        listening -> state.partialTranscript.ifBlank { "Pode falar…" }
        thinking -> state.statusLine.ifBlank { "pensando localmente…" }
        speaking -> "falando…"
        lastReply.isNotBlank() -> "resposta"
        else -> "toque em Falar"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xE60B1A13)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(20.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = palette.backgroundTop.copy(alpha = 0.96f),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Glia",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (micGranted) "tela bloqueada · fale normalmente" else "abra o app uma vez",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(18.dp))

                OrganicOrb(phase = state.phase, diameter = 132.dp)

                Spacer(Modifier.height(18.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (listening) palette.leaf else palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (lastReply.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = lastReply.take(600),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                palette.glassTint.copy(alpha = 0.55f),
                                RoundedCornerShape(16.dp),
                            )
                            .padding(14.dp),
                    )
                }

                Spacer(Modifier.height(18.dp))
                if (micGranted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = if (speaking || thinking) onStopSpeaking else onListen,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (speaking || thinking) "Parar" else "Falar")
                        }
                        TextButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                            Text("Fechar", color = palette.textSecondary)
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Autorize o microfone na Glia (uma vez) para falar " +
                                "com ela pela central de controle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onOpenApp) { Text("Abrir a Glia") }
                            TextButton(onClick = onClose) {
                                Text("Fechar", color = palette.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

