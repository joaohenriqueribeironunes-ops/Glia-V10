package com.glia.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.glia.ui.components.GlassPill
import com.glia.ui.components.GlassSurface
import com.glia.ui.components.GliaIcons
import com.glia.ui.components.GlowDot
import com.glia.ui.components.OrganicOrb
import com.glia.ui.model.AssistantPhase
import com.glia.ui.model.ChatMessage
import com.glia.ui.model.GliaActions
import com.glia.ui.model.GliaUiState
import com.glia.ui.theme.GliaTheme

@Composable
fun ChatScreen(
    state: GliaUiState,
    actions: GliaActions,
    modifier: Modifier = Modifier,
) {
    val palette = GliaTheme.palette
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrganicOrb(phase = state.phase, diameter = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Glia",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                )
                Text(
                    text = state.statusLine.ifBlank { "offline · no aparelho" },
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                )
            }
            GlowDot(
                color = when (state.phase) {
                    AssistantPhase.IDLE -> palette.moss
                    AssistantPhase.LISTENING -> palette.teal
                    AssistantPhase.THINKING -> palette.sun
                    AssistantPhase.SPEAKING -> palette.leaf
                },
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty()) {
                item { EmptyHint(actions) }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
            if (state.busy && state.messages.none { it.streaming }) {
                item { ThinkingBubble() }
            }
        }

        state.pendingConfirmation?.let { pending ->
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(24.dp),
                tint = palette.sun.copy(alpha = 0.28f),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = pending.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill(
                            text = "Sim, pode",
                            onClick = { actions.onConfirm(true) },
                            tint = palette.leaf.copy(alpha = 0.22f),
                        )
                        GlassPill(text = "Não", onClick = { actions.onConfirm(false) })
                    }
                }
            }
        }

        Composer(state = state, actions = actions)
    }
}

@Composable
private fun EmptyHint(actions: GliaActions) {
    val palette = GliaTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
    ) {
        Text(
            text = "Bom te ver.",
            style = MaterialTheme.typography.headlineLarge,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Estou inteiramente no seu aparelho. Pergunte sobre a agenda, o estado do aparelho — ou só converse.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        val suggestions = listOf(
            "Que horas são?",
            "Como está a bateria?",
            "Meus compromissos de hoje",
            "Liga a lanterna",
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.take(2).forEach { suggestion ->
                GlassPill(text = suggestion, onClick = { actions.onSendText(suggestion) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.drop(2).forEach { suggestion ->
                GlassPill(text = suggestion, onClick = { actions.onSendText(suggestion) })
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val palette = GliaTheme.palette
    val alignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.fromUser) palette.bubbleUser else palette.bubbleGlia
    val shape = if (message.fromUser) {
        RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 26.dp, bottomEnd = 8.dp)
    } else {
        RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 8.dp, bottomEnd = 26.dp)
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        GlassSurface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = shape,
            tint = bubbleColor,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = message.text.ifBlank { " " },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.error) palette.danger else palette.textPrimary,
                )
                if (message.streaming) {
                    Spacer(Modifier.height(6.dp))
                    PulsingLine()
                }
            }
        }
    }
}

@Composable
private fun PulsingLine() {
    val palette = GliaTheme.palette
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(Modifier.alpha(alpha)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                GlowDot(color = palette.moss, size = 5.dp)
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    val palette = GliaTheme.palette
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        GlassSurface(shape = RoundedCornerShape(24.dp)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlowDot(color = palette.sun, size = 7.dp)
                Text(
                    text = "pensando localmente…",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun Composer(state: GliaUiState, actions: GliaActions) {
    val palette = GliaTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassSurface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(26.dp),
        ) {
            BasicTextField(
                value = state.draft,
                onValueChange = actions.onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                cursorBrush = SolidColor(palette.leaf),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        keyboard?.hide()
                        actions.onSend()
                    },
                ),
                decorationBox = { inner ->
                    Box {
                        if (state.draft.isBlank()) {
                            Text(
                                text = "Fale com a Glia…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.textSecondary.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
        }

        val listening = state.phase == AssistantPhase.LISTENING

        GlassSurface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            tint = if (listening) palette.teal.copy(alpha = 0.35f) else palette.glassTint,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                actions.onMicToggle()
            },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = GliaIcons.Mic,
                    contentDescription = "Falar com a Glia",
                    tint = palette.leaf,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        GlassSurface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            tint = palette.leaf.copy(alpha = 0.30f),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                keyboard?.hide()
                actions.onSend()
            },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = GliaIcons.Send,
                    contentDescription = "Enviar",
                    tint = palette.leaf,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
