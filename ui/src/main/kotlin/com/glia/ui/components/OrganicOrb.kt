package com.glia.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glia.ui.model.AssistantPhase
import com.glia.ui.theme.GliaTheme

/**
 * O orbe da Glia: uma semente/flor de luz com ramificações que crescem
 * enquanto ela ouve e processa — homenagem às células gliais.
 * Formas puras em Canvas, animadas a 60 fps; com animações desligadas,
 * desenha um quadro estático elegante.
 */
@Composable
fun OrganicOrb(
    phase: AssistantPhase,
    modifier: Modifier = Modifier,
    diameter: Dp = 220.dp,
) {
    val design = GliaTheme.design
    val palette = design.palette

    val coreColor = when (phase) {
        AssistantPhase.IDLE -> palette.leaf
        AssistantPhase.LISTENING -> palette.teal
        AssistantPhase.THINKING -> palette.sun
        AssistantPhase.SPEAKING -> palette.moss
    }
    val haloColor = when (phase) {
        AssistantPhase.IDLE -> palette.sun
        AssistantPhase.LISTENING -> palette.sky
        AssistantPhase.THINKING -> palette.brass
        AssistantPhase.SPEAKING -> palette.teal
    }

    var breath = 1f
    var spin = 0f
    var ring = 0f
    if (design.animations) {
        val transition = rememberInfiniteTransition(label = "orb")
        val b by transition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(3400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
        val s by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (phase == AssistantPhase.THINKING) 5200 else 24_000,
                    easing = LinearEasing,
                ),
            ),
            label = "spin",
        )
        val r by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (phase == AssistantPhase.LISTENING) 1900 else 4200,
                    easing = FastOutSlowInEasing,
                ),
            ),
            label = "ring",
        )
        breath = b
        spin = s
        ring = r
    }

    Canvas(modifier = modifier.size(diameter)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.26f * breath

        // Halo externo.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    haloColor.copy(alpha = 0.45f),
                    haloColor.copy(alpha = 0.12f),
                    Color.Transparent,
                ),
                center = center,
                radius = size.minDimension * 0.5f,
            ),
            radius = size.minDimension * 0.5f,
            center = center,
        )

        // Anel de escuta (pulso).
        if (phase == AssistantPhase.LISTENING || phase == AssistantPhase.SPEAKING) {
            val pulseRadius = radius * (1.3f + ring * 1.9f)
            drawCircle(
                color = haloColor.copy(alpha = (1f - ring) * 0.35f),
                radius = pulseRadius,
                center = center,
                style = Stroke(width = 2.5f),
            )
        }

        // Arco de pensamento.
        if (phase == AssistantPhase.THINKING) {
            rotate(spin) {
                drawArc(
                    color = coreColor.copy(alpha = 0.65f),
                    startAngle = 0f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius * 1.75f, center.y - radius * 1.75f),
                    size = androidx.compose.ui.geometry.Size(radius * 3.5f, radius * 3.5f),
                    style = Stroke(width = 3f, cap = StrokeCap.Round),
                )
            }
        }

        // Ramificações (galhos/neurônios).
        val branchCount = 9
        val branchAlpha = when (phase) {
            AssistantPhase.IDLE -> 0.35f
            AssistantPhase.LISTENING -> 0.75f
            AssistantPhase.THINKING -> 0.55f
            AssistantPhase.SPEAKING -> 0.65f
        }
        val growth = when (phase) {
            AssistantPhase.IDLE -> 1.0f
            AssistantPhase.LISTENING -> 1.0f + 0.25f * ring
            else -> 1.15f
        }
        for (i in 0 until branchCount) {
            val angle = Math.toRadians((360.0 / branchCount * i) + spin * 0.35)
            val inner = Offset(
                center.x + (radius * 1.15f * kotlin.math.cos(angle)).toFloat(),
                center.y + (radius * 1.15f * kotlin.math.sin(angle)).toFloat(),
            )
            val length = radius * (1.05f + 0.45f * ((i % 3) / 3f)) * growth
            val bend = Math.toRadians((360.0 / branchCount * i) + 18.0 + spin * 0.35)
            val mid = Offset(
                center.x + (length * 0.7f * kotlin.math.cos(bend)).toFloat(),
                center.y + (length * 0.7f * kotlin.math.sin(bend)).toFloat(),
            )
            val tip = Offset(
                center.x + (length * kotlin.math.cos(angle)).toFloat(),
                center.y + (length * kotlin.math.sin(angle)).toFloat(),
            )
            val path = Path().apply {
                moveTo(inner.x, inner.y)
                quadraticBezierTo(mid.x, mid.y, tip.x, tip.y)
            }
            drawPath(
                path = path,
                color = coreColor.copy(alpha = branchAlpha * 0.55f),
                style = Stroke(width = 1.8f, cap = StrokeCap.Round),
            )
            drawCircle(
                color = haloColor.copy(alpha = branchAlpha * 0.8f),
                radius = 2.6f + (i % 3),
                center = tip,
            )
            // Folhinha na ponta, a cada dois ramos.
            if (i % 2 == 0) {
                val leaf = Path().apply {
                    moveTo(tip.x, tip.y)
                    quadraticBezierTo(tip.x + 9f, tip.y - 12f, tip.x + 18f, tip.y - 2f)
                    quadraticBezierTo(tip.x + 9f, tip.y + 6f, tip.x, tip.y)
                }
                drawPath(leaf, color = palette.leaf.copy(alpha = branchAlpha * 0.35f))
            }
        }

        // Núcleo luminoso.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.92f),
                    coreColor.copy(alpha = 0.95f),
                    coreColor.copy(alpha = 0.75f),
                ),
                center = Offset(center.x - radius * 0.32f, center.y - radius * 0.34f),
                radius = radius * 1.8f,
            ),
            radius = radius,
            center = center,
        )

        // Broto interno (semente).
        val sprout = Path().apply {
            moveTo(center.x, center.y + radius * 0.42f)
            quadraticBezierTo(
                center.x - radius * 0.05f, center.y,
                center.x, center.y - radius * 0.44f,
            )
        }
        drawPath(
            sprout,
            color = Color.White.copy(alpha = 0.55f),
            style = Stroke(width = 2.2f, cap = StrokeCap.Round),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.5f),
            radius = radius * 0.14f,
            center = Offset(center.x + radius * 0.14f, center.y - radius * 0.22f),
        )

        // Reflexo especular.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.7f), Color.Transparent),
                center = Offset(center.x - radius * 0.38f, center.y - radius * 0.42f),
                radius = radius * 0.6f,
            ),
            radius = radius * 0.55f,
            center = Offset(center.x - radius * 0.36f, center.y - radius * 0.4f),
        )
    }
}
